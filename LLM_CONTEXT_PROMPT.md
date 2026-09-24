# Context prompt for an LLM technical advisor

Copy everything below the line into another LLM. Replace the final section
("My question") with the decision you want help with.

---

You are a senior software architect and reviewer. I am the maintainer of an
open-source project called Omnirec. I want your help making development
decisions. Below is a complete description of the project as it exists today:
what it is for, how it is built, what has been verified, and what is still open.
Treat it as the source of truth. If something you would recommend contradicts
it, say so explicitly rather than silently assuming otherwise. If you need
information that is not here, list what you need instead of guessing.

## 1. What the project is

Omnirec is provider-independent commerce event tracking infrastructure. A
merchant instruments their storefront once. Omnirec collects shopper
interactions, converts them into one canonical event format, resolves who each
event belongs to, and reliably delivers them to personalization providers the
merchant has configured (currently Amazon Personalize and Google Cloud Retail,
with others addable). Provider credentials are never present in the browser.

It is infrastructure only. Explicit non-goals: no dashboard, no reporting or
analytics UI, no billing, no machine learning of its own.

Status: version 0.1.0, MIT licensed, preparing to be published as an open-source
project. Nothing is committed to the release branch yet.

## 2. Technology stack

- Frontend: TypeScript monorepo (Turborepo, npm workspaces, tsup for builds,
  vitest with jsdom for tests). Packages: `@omnirec/commerce-web` (browser SDK,
  no framework dependency), `@omnirec/commerce-react` (React bindings). Package
  names keep the `@omnirec` and `io.omnirec` namespaces.
- Backend: Java 17, Spring Boot 3.3.4, Maven multi-module.
- Infrastructure: RabbitMQ 3.13 (queueing), Redis 7 (shared deduplication and
  identity links), Micrometer with Prometheus (metrics).
- Testing: JUnit 5, Mockito, Testcontainers (real RabbitMQ and Redis), plus a
  live end-to-end script.
- Example: a Next.js storefront in `examples/nextjs-demo-store`.

## 3. Architecture

```
  Storefront browser                 Merchant backend
  @omnirec/commerce-web              commerce-tracker-spring-boot
  (views, search, cart)              (purchases, refunds, reviews)
          |  HTTPS batches                   |  HTTPS batches
          +----------------+-----------------+
                           v
   EVENT API (standalone, self-hosted service, holds all provider credentials)
     Gateway filter (before body parsing): payload cap -> API key to tenant
       -> rate limit; CORS headers on rejections
     Per-event binding and validation -> normalization -> deduplication
       -> identity resolution
                           v
   RabbitMQ: topic exchange, one queue set per destination:
     omnirec.events.<dest>, retry tiers .retry.1 to .retry.5 (queue-level TTL,
     dead-letter back to main), dead-letter exchange -> .dlq
                           v
   Per-destination consumers (EventDispatcher, delivery lease)
        |                  |                    |
   Amazon Personalize   Google Retail      Recently-viewed
   adapter              adapter            (writes Redis lists read by the
        |                  |                serving API)
```

### Modules

- `omnirec-commerce-core`: canonical `CommerceEvent`, event taxonomy,
  validation, identity linking, deduplication interfaces, and the
  `EventDestination` interface. Depends only on Jackson and SLF4J. No Spring, no
  queue, no cloud SDK. The module graph prevents any provider import from
  compiling in the core, gateway, or SDKs.
- `omnirec-event-api`: the gateway (auth, limits, normalization, validation,
  dedup, identity).
- `omnirec-event-processing`: RabbitMQ topology, publisher, consumers,
  dispatcher, retry scheduling, queue-depth metrics.
- `omnirec-amazon-personalize-destination`, `omnirec-google-retail-destination`,
  `omnirec-recently-viewed-destination`: one adapter per destination, each behind
  its own auto-configuration and `enabled` flag (all off by default).
- `omnirec-redis-state`: Redis-backed deduplication and identity-link stores,
  required once more than one Event API instance runs.
- `commerce-tracker-spring-boot`: server-side SDK embedded in the merchant app.
- `omnirec-event-api-app`: the deployable Spring Boot service assembling the
  above.
- `omnirec-contract-tests`: parses the TypeScript types, the Java enum, and the
  JSON schema and fails if they diverge (event types, commerce fields, the
  sensitive-field list, the URL-scrubbing list).
- Legacy serving side (`omnirec-core`, `omnirec-web`, `omnirec-demo-app`,
  Personalize/Google/Redis starters): answers `/v1/recommendations`,
  `/v1/search`, `/v1/recently-viewed`. Deployed separately. Its old
  unauthenticated `POST /v1/events` is disabled by default. Catalog sync was
  removed and the Algolia connector was removed; search was kept.

The extension point is one interface:

```java
public interface EventDestination {
    String id();
    void send(CommerceEvent event);
    default void sendBatch(List<CommerceEvent> events) { ... }
    default boolean supports(CommerceEvent event) { ... }
}
```

Adding a provider means implementing it and registering a bean. Contract:
idempotent, throw on transient failure, `DestinationException.permanent(...)`
for non-retryable failure, never mutate the event.

## 4. The canonical event

Structure: `eventId`, `eventType`, `schemaVersion` (1.0), `timestamp`,
`tenantId` (server-set from the API key), `identity` (`anonymousId`, `userId`,
`sessionId`), `context` (url, path, referrer, platform, device, locale,
timezone, plus server-derived ip and country), `commerce` (productId,
productIds, categoryId, cartId, orderId, quantity, price, currency, total,
items, searchQuery, recommendationId, recommendationProvider, and similar),
`properties` (free map), `receivedAt` (server-set).

Taxonomy: 37 event types plus one control event, `identify` (never delivered to
providers). Categories: session, discovery, product interaction, cart,
checkout, purchase, recommendation, user. Requiredness is per event type.

Validation runs in the browser SDK, in the server SDK, and again in the Event
API. Events containing sensitive field names (card number, CVV, password,
tokens, API keys, private keys, and so on) are rejected outright at any depth,
never redacted. URLs are scrubbed of tokens, emails, credentials, and fragments
in both the browser SDK and the API, with the two lists kept identical by a
contract test.

Dwell time: `product.viewed` starts a foreground-only timer (paused when the tab
is hidden). When measurement ends (another product, a route change through
`page.viewed()`, component unmount, logout, page unload) the SDK sends an
"engagement update": a second `product_viewed` carrying `dwellTimeMs` and
`viewEventId` (the eventId of the original view). `CommerceEvent.isEngagementUpdate()`
identifies it, and all interaction-counting destinations skip it so views are
not double-counted. Minimum 1s, capped at 30 minutes, no heartbeats. Lost on
hard crash and some mobile freezes.

Deterministic event ids for business events: `evt:<eventType>:<businessKey>`
(for example `evt:purchase_completed:<orderId>`). The browser and server SDKs
produce identical ids, so a purchase reported by both is delivered once.

## 5. Identity model

- `anonymousId`: device, first-party cookie, one year, never rotated, never
  cleared on logout.
- `sessionId`: visit, stored in localStorage, new after 30 minutes idle, on
  logout, or when a different user identifies.
- `userId`: supplied by the merchant through `identify()`. Never invented.
- On identify, the server stores a link `anonymousId -> userId` per tenant
  (many devices to one user; latest link wins on a shared device).
- Design rule: historical events are never rewritten. Attribution is a join.
  Future anonymous events from a linked device are enriched with `userId` before
  queueing.
- IP addresses and fingerprints are never used as identity. IP is used only for
  coarse geo and rate limiting and is discarded by default.
- Server-side events without an `anonymousId` get a derived `server:<uuid>`
  identity.

## 6. Reliability design

- Browser SDK: batches of up to 20 events or every 5 seconds; bounded
  localStorage offline buffer (500 events, oldest dropped); exponential backoff
  with full jitter; 4xx dropped, 408/429/5xx/network retried; events older than
  12 hours dropped (server dedup window is 24 hours); unload path uses
  `sendBeacon`; `keepalive` only for bodies under 60KB.
- Server SDK: asynchronous, bounded in-memory queue (10,000), retries transient
  failures with exponential backoff from 500ms to 30s, 8 attempts (about 3
  minutes), 4xx not retried, drops are counted. It must never block the
  merchant's checkout. Known limit: queue is in memory.
- Event API answers 202 only after the RabbitMQ publisher confirm (returns and
  confirms are mandatory; the service refuses to start without them). Broker
  failure gives 503 with Retry-After.
- Deduplication uses a lease-then-complete protocol at two stages (ingestion,
  30s lease; delivery per destination, 2 minute lease). A crash mid-work leaves
  only an expiring lease, so events are retried rather than lost. The Redis
  implementation uses SET NX and a conditional Lua release. 24 hour window.
- Retry: 5 tiers with delays 1s, 2s, 4s, 8s, 16s (each a separate queue with a
  queue-level TTL, to avoid head-of-line blocking), then a dead-letter queue with
  an `x-omnirec-failure-reason` header. Permanent failures go straight to the
  DLQ. Replay by shoveling the DLQ back onto the exchange (safe because of
  delivery dedup).
- One queue set per destination, so one provider's outage does not delay
  another.

## 7. Provider adapters and their verified constraints

Amazon Personalize (`PutEvents`): at most 10 events per call (adapter chunks);
`properties` is a string map of at most 1024 characters, only operator
allow-listed schema keys, and must not contain reserved keys (`recommendationId`,
`impression`, and others); `Event.recommendationId` at most 40 characters and
set only when `recommendationProvider` is `amazon-personalize`; `impression` at
most 25 items; `userId` omitted for anonymous visitors (session id used
instead); multi-item orders split into per-line events with ids `eventId:i`;
throttling, 5xx, and network errors are retryable, validation and not-found
errors are not. Credentials come from the AWS default provider chain only.

Google Cloud Retail: only 7 user event types are supported (no page-visit or
remove-from-cart mapping), so other types are skipped; detail-page-view takes
exactly one product; `attributionToken` only when the id was issued by Google;
`visitorId` is always the anonymous device id (at most 128 characters) with the
customer in `userInfo`; events missing required fields are skipped. Credentials
come from Application Default Credentials only.

Recently-viewed destination: writes a Redis list `recently-viewed:<userId>` in
the format the serving side reads, plus a sorted-set index, updated atomically by
a Lua script so out-of-order redelivery cannot reorder the list. Signed-in users
only. Optional tenant filter.

Azure: not implemented. Azure AI Personalizer is scheduled to retire on
2026-10-01 and no replacement target has been chosen.

## 8. Security posture

- Browser holds only a publishable key that can write events for one tenant. The
  SDK throws if the key looks like a secret. A build-time script scans bundles
  for credentials and provider SDKs.
- The API key, not the request body, determines the tenant. Keys compared in
  constant time; disabled tenants are rejected.
- Auth, payload cap (413, including chunked bodies), and rate limit (300 per
  minute per tenant per client address, keyed on the connection address, not
  `X-Forwarded-For`) run in a filter before the body is parsed.
- CORS: configured origins only, POST only, credentials off.
- Logs contain event ids and field names, never values or payloads. Metrics tags
  are bounded values only.
- `allow-anonymous-ingestion` is refused outside dev/test/local profiles.

## 9. What has been verified, and how

- 160 frontend tests and 312 backend tests pass with 0 skipped (real RabbitMQ
  and Redis via Testcontainers). A 9-check live end-to-end script drives the
  built browser SDK through a two-day anonymous journey plus login against the
  real Event API, RabbitMQ, and Redis, with a local capture server standing in
  for Personalize (real AWS SDK, real SigV4 signing).
- A prior audit found about 35 defects (for example: unawaited publisher confirms,
  retry head-of-line blocking, a claim-before-work crash window, dwell events
  double-counting views, invalid provider payloads) and each was fixed with a test
  that fails against the old code.
- The Amazon and Google adapters are implemented and checked against the vendors'
  documented API rules, but have NOT been run against live provider accounts.
  Treat them as "implemented but externally unverified".

## 10. Known limitations and open decisions

1. No live-provider verification yet (no accounts or credentials available to me).
2. Azure target undecided (Personalizer retirement).
3. Rate limiting is per instance, fixed window (effective limit is N times the
   setting behind N replicas). A shared Redis limiter is a possible upgrade.
4. Server SDK queue is in memory; long outage plus restart loses queued events.
   Current guidance is a merchant-side transactional outbox.
5. Legacy serving-side ingestion still exists (off by default) and should
   eventually be removed.
6. RabbitMQ queue-argument changes require a migration on existing brokers.
7. Multi-tenant configuration is static in application properties (no tenant
   management API).
8. Recently-viewed requires standalone Redis (two keys per user), not Redis
   Cluster.
9. Open-source readiness: MIT LICENSE, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY
   (using GitHub private vulnerability reporting, which must be enabled on the
   repository), and CHANGELOG now exist, and the npm and Maven metadata declare
   the license. Still open: nothing is published to npm or Maven Central, no git
   tag or release exists, the release and versioning process is only described in
   general terms (Semantic Versioning), there are no issue or pull request
   templates, and CI does not yet publish anything. The maintainer contact for
   code-of-conduct reports relies on GitHub features rather than a dedicated
   address.

## 11. Constraints any recommendation must respect

- Never place provider credentials in frontend code or in shipped configuration
  defaults.
- Never collect card numbers, CVV, passwords, or authentication tokens.
- Do not use IP address or fingerprinting as identity. Do not rewrite historical
  anonymous events.
- Do not add dashboards, UI, billing, or ML.
- Do not delete working functionality without a reason.
- Do not claim something works merely because it compiles. Label anything not
  exercised against the real external system as unverified.
- Keep the core free of provider dependencies.

## 12. How I want you to answer

1. Restate the decision in one or two sentences so I can confirm you understood.
2. Give a clear recommendation first, then the reasoning. Do not just list options.
3. Name the two or three strongest alternatives and why you would not pick them
   given the constraints above.
4. State trade-offs and risks concretely, including operational cost and what
   could fail in production.
5. Call out anything in my description that looks wrong, inconsistent, or risky.
6. Where a claim depends on a provider's current API behaviour, say so and tell me
   what to verify in their documentation rather than asserting it.
7. If the change affects the public event schema, identity model, or delivery
   guarantees, say what tests and documentation would need to change.
8. End with a short, ordered list of next steps.

## My question

[Describe the decision here. Include what you are trying to achieve, any deadline
or resource limits, and which of the open items in section 10 it relates to.]
