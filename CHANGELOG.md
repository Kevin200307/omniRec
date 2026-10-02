# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

## 2.0.0 - 2026-10-02

Omnirec becomes a general commerce event-tracking platform: a catalog of 205
events, custom events, declarative HTML tracking, SDKs for every common stack,
webhooks in and out, derived events, privacy deletion, and two ready-made
self-hosting profiles. Existing 0.x senders keep working: see
[docs/migration-v1-to-v2.md](docs/migration-v1-to-v2.md).

### Added

#### Events and validation

- **Event catalog** (`catalog/`). Every standard event, block and controlled
  vocabulary is defined once in YAML: **205 events in 12 domains**, with 20
  blocks and 15 vocabularies. `npm run catalog:generate` produces:
  - the TypeScript types and runtime rules
  - the Java constants and registry
  - the JSON Schema
  - the CLI's copy of the catalog
  - the event reference in `docs/events/`

  CI fails if any generated file is stale. Every event has an example, which
  the TypeScript, Java and CLI validators all check. Short names such as
  `add_to_cart` are accepted as aliases of the canonical names.
- **Envelope v2.** Events carry `event`, `eventVersion`, `kind`, `source` and a
  `data` object of catalog blocks. `context` gains `campaign` and `page`. The v1
  contract is frozen at `schema/v1/commerce-event.schema.json`.
- **Event registry** (`EventRegistry`, `EventName`) and a catalog-driven
  validator with `STRICT` and `PERMISSIVE` modes.
- **Tracking plans** (`omnirec.plan.yaml`) for custom events, per tenant.
  `GET /v1/catalog` returns a tenant's merged catalog.

#### Collector

- **Auth modes** (`omnirec.events.auth-mode`):
  - `open` runs without keys, protected by origin checks
  - `keys` requires publishable keys
  - `auto` (the default) picks by whether any key is configured
- **Tenant registry** with file and JDBC implementations. The JDBC one uses the
  `omnirec_tenant` table and stores keys as hashes. Allowed origins, validation
  mode and tracking plans are set per tenant.
- **Inbound webhooks** at `POST /v1/webhooks/{source}`, verified by the
  sender's signature, with a secret per tenant. Included:
  - a Stripe adapter: disputes and refunds become `chargeback_opened`,
    `chargeback_resolved` and `refund_issued`
  - generic JSON sources, mapped in configuration
  - a `WebhookAdapter` interface for your own

  See `docs/webhooks.md`.
- **Derived events** (`omnirec-derived-events`, `omnirec.derived.enabled`):
  `cart_abandoned`, `checkout_abandoned`, `return_visit`,
  `new_customer_purchase` and `repeat_purchase`. They are computed by rules
  running as a pipeline destination, with timers and counters in Redis (no
  double fire across instances) or in memory. A `DerivedEventRule` interface
  lets you add your own. See `docs/derived-events.md`.
- **Outbound webhook destination** (`omnirec-webhook-destination`). It POSTs
  events to your URLs, signed with HMAC-SHA256 (`X-Omnirec-Signature`). Each
  endpoint has an event and tenant filter, and its own queue, retries and
  dead-letter queue.
- **Dead-letter replay.** `/actuator/deadletters` reports queue sizes, and
  `POST /actuator/deadletters/{destination}` moves events back once the cause is
  fixed. Each move is confirmed by the broker before the dead letter is
  removed. Replay is a dry run unless `"dryRun": false` is sent.

#### Storage and privacy

- **Optional historical storage** (`omnirec-event-storage`), off by default.
  - A storage worker consumes its own queue and writes through the
    `EventStore` interface, acknowledging only after commit.
  - Works with PostgreSQL (local, Neon, RDS, Supabase) or TimescaleDB.
  - Flyway migrations V1 to V6.
- **Customer history API**, `GET /v1/customers/{customerId}/events`. It has
  keyset pagination and includes linked anonymous history. It is
  authenticated with a tenant's `secret-key` or a platform key.
- **Customer deletion**, `DELETE /v1/customers/{customerId}`.
  - Removes the customer's events and their devices' anonymous events, and
    forgets their identity links in the database and in Redis.
  - Returns a receipt.
  - Leaves tombstones, stored only as SHA-256 fingerprints, so the collector
    and the storage worker drop late events for the customer.
- **Per-tenant retention**: `omnirec.storage.retention.tenants.<id>`, shorter
  or longer than the global `max-age` (postgres provider).

#### SDKs and tools

- **Browser SDK v2.** `createOmnirec({ endpoint })` provides:
  - a catalog-typed `track()` and `trackUntyped()`
  - middleware and plugins
  - no required key, and same-site endpoint paths
  - a first-party session cookie

  The core is 9 KB gzipped, against a 10 KB budget enforced in CI.
- **Browser plugins**, each a separate import:
  - `dom`: HTML attributes with scope inheritance
  - `impressions`
  - `autocapture`: page views, SPA navigation, campaigns, scroll depth, dwell
    time
  - `consent`
  - `debug`: full constraint checks and name suggestions
- **Framework packages:**
  - `@omnirec/commerce-react`: `OmnirecProvider`, `useTrack`,
    `useImpression`, `<Track>`
  - `@omnirec/commerce-next`: App Router provider, server helper
  - `@omnirec/commerce-vue`: plugin, `v-track`, `v-impression`
  - `@omnirec/commerce-node`: batching sender with retries
  - a script-tag build, `omnirec.min.js`
- **Java tracking v2.**
  - New framework-free `omnirec-tracker-java`.
  - The Spring starter adds `OmnirecTracker`, an identity filter that reads
    the browser's cookies, `@TrackEvent`, a PostgreSQL transactional outbox,
    and `@AutoConfigureOmnirecTest` with `OmnirecTestRecorder`.
- **`@omnirec/cli`:**
  - `omnirec init` detects the framework, writes a starter plan and prints the
    setup.
  - `omnirec dev` is a local collector that validates against the catalog and
    your plan, with a live event list.
  - `omnirec generate` writes `omnirec.d.ts` or `OmnirecEvents.java`.
  - `omnirec validate --against <file or git ref>` fails on breaking plan
    changes without a version bump.

#### Deployment and tests

- **Deployment profiles:**
  - `deploy/lite`: the collector alone, with no RabbitMQ, Redis or database.
    This is the new Spring profile `lite`.
  - `deploy/standard`: with RabbitMQ and Redis, plus optional PostgreSQL.

  `scripts/compose-smoke.sh` checks both.
- **Documentation:** a rewritten README and guide quick start, plus
  `catalog.md`, `custom-events.md`, `html-attributes.md`, `self-hosting.md`,
  `webhooks.md`, `derived-events.md`, `extending.md` and
  `migration-v1-to-v2.md`.
- **Test harnesses:**
  - React Testing Library
  - Playwright browser tests
  - bundle-size budgets
  - catalog parity across languages
  - destination coverage tests
  - the end-to-end script, now 11 checks including derived events and
    outbound webhooks

### Changed

- **Breaking (Java API):** the `EventType` and `EventCategory` enums are
  removed. Use `EventName` and the generated `StandardEvents` /
  `StandardEventNames`. `CommerceEvent` holds `EventData data`; `commerce()`
  remains as a deprecated v1 view.
- **Breaking (Java SPI):** `IdentityLinkStore` gains `forget(...)` for customer
  deletion.
- **Breaking (TypeScript types):** the generated per-domain event unions follow
  the new domain list, for example `CartCheckoutEventType`. `track()` takes v2
  `data`.
- The Event API still accepts v1 events and converts them to v2 at the edge.
  Validation errors name v2 paths, for example `data.product.id`.
- **Unknown events are accepted by default** (`permissive`) and flagged
  `unplanned`. Only storage receives them. Set `validation-mode: strict` to
  reject them.
- The browser core checks required fields only. Constraints are reported by the
  `debug` plugin in development and always enforced by the collector.
- Server events no longer require a userId: the visitor's anonymous id from the
  request cookies is enough.
- Money keeps its exact value and scale from the request body through the
  queue, storage and destinations.
- The customer history response adds `event`, `eventVersion`, `kind`, `source`
  and `data`. `eventType` and `commerce` remain for one release.
- Destinations declare which catalog events they map. The build fails if a new
  event is neither mapped nor ignored.
- The `identify` control event is published after its link is recorded, and
  reaches only destinations that accept control events.
- The collector's health check reports the state Redis (`REDIS_HOST`), not
  `localhost`.
- The collector's Docker build caches Maven downloads between builds.
- Relicensed from MIT to the Apache License, Version 2.0, with a `NOTICE` file,
  SPDX headers and a `license-check` CI workflow.
- Versions: npm packages and Maven modules are `2.0.0`.

### Deprecated

- `createCommerceClient`, `CommerceClient` and the per-event helpers in the
  browser SDK. They still work and convert v1 input to v2.
- `CommerceProvider` in `@omnirec/commerce-react`, replaced by
  `OmnirecProvider`.
- `CommerceTracker` in the Spring starter, replaced by `OmnirecTracker`.
- `omnirec.events.allow-anonymous-ingestion`, replaced by `auth-mode: open`.

### Fixed

- The React provider dropped every event under Strict Mode, which Next.js
  enables in development.
- New visitors never received `session_started`.

## 0.1.0

Initial development release. It has not yet been published to a package registry,
and the provider adapters have not been executed against live provider accounts.

### Added

- **Canonical event model** with 37 event types and an `identify` control event,
  defined identically in TypeScript, Java, and a JSON Schema, with a contract test
  that fails the build if they diverge.
- **Browser SDK** (`@omnirec/commerce-web`) and React bindings
  (`@omnirec/commerce-react`): automatic identity, sessions, and page context;
  client-side validation; batching with offline buffering, retry with backoff and
  jitter, and `sendBeacon` on unload; foreground-only dwell-time measurement
  reported as an engagement update linked to its view.
- **Server-side SDK** (`commerce-tracker-spring-boot`) for authoritative business
  events, with asynchronous delivery, bounded retry, and deterministic event
  identifiers shared with the browser SDK so that a purchase reported from both
  sources is delivered once.
- **Event API** (`omnirec-event-api`, `omnirec-event-api-app`): a standalone
  service with API-key authentication and tenant isolation, payload and rate
  limits enforced before the body is parsed, per-event validation, URL
  sanitization, deduplication, and identity resolution.
- **Identity stitching**: an anonymous-to-user link stored separately from events,
  many devices per user, session rotation on sign-out or user change, and no
  rewriting of captured history.
- **RabbitMQ pipeline** (`omnirec-event-processing`): one queue set per
  destination, awaited publisher confirms, tiered retry queues, dead-letter
  queues, and lease-based idempotent delivery.
- **Destinations**: Amazon Personalize, Google Cloud Retail, and a
  recently-viewed feed for the serving side, each disabled by default.
- **Shared state** (`omnirec-redis-state`): Redis-backed deduplication and
  identity links for multi-instance deployments.
- **Observability**: Micrometer counters for ingestion and delivery, retry and
  dead-letter counters, a delivery latency timer, and queue-depth gauges.
- **Verification tooling**: a bundle security scan, Testcontainers-based suites
  against a real RabbitMQ and Redis, and a live end-to-end script.
- **Documentation**: guide, architecture, event schema, identity, SDK and
  provider references, RabbitMQ, security, configuration, testing,
  troubleshooting, and an audit report.

### Changed

- The Algolia connector and catalog synchronization were removed. Search was
  retained.
- The legacy unauthenticated ingestion endpoint on the serving API is now
  disabled by default (`omnirec.web.legacy-ingestion.enabled=false`).
- The legacy serving-side provider mappers were corrected to follow the current
  identity rules.

### Fixed

The audit recorded in [docs/AUDIT.md](docs/AUDIT.md) identified and resolved
approximately 35 defects before this release, including unawaited publisher
confirms, retry head-of-line blocking, a claim-before-work window that could lose
events, dwell updates that double-counted views, requests that were invalid for
the Personalize and Retail APIs, unenforced payload limits, and a rate limit
that could be bypassed. Each fix is covered by a test that fails against the
earlier behaviour.

### Known limitations

- The Amazon Personalize and Google Retail adapters are unverified against live
  accounts.
- No Azure destination is provided. Azure AI Personalizer retires on 1 October
  2026.
- Rate limiting is per instance.
- The server-side SDK queue is held in memory.

See [docs/guide.md](docs/guide.md#10-known-limitations) for the full list.
