# Project specification prompt

Copy everything below the line into another LLM. It is a complete, self-contained
specification of the Omnirec project: what it is, its exact architecture, every
feature, and how it is used. Use the "Your task" section at the end to say what
you want the LLM to do with it (build it, explain it, review it, or extend it).

---

You are a senior software architect and engineer. Below is the complete
specification of a project called **Omnirec**. Treat it as the single source of
truth and reproduce it faithfully: same architecture, same module boundaries, same
behaviour, same rules. Do not substitute your own design where this document is
specific. Where something is not specified, choose the simplest option that
satisfies the stated constraints and list your assumption.

## 1. What Omnirec is

Omnirec is provider-independent commerce event tracking infrastructure.

A merchant instruments their online store once. Omnirec then:

1. collects shopper interactions (views, searches, cart, checkout, purchases);
2. converts them into one canonical event format;
3. resolves which customer each event belongs to (anonymous visitor to signed-in
   customer), without ever rewriting history;
4. queues them durably; and
5. delivers them reliably to personalization providers the merchant configures
   (Amazon Personalize, Google Cloud Retail, and any destination added later).

Provider credentials are never present in the browser or in the merchant's code.
They live only in a standalone service called the Event API.

**Non-goals (never build these):** dashboards or any reporting UI, billing,
machine learning of its own, cross-site tracking, fingerprinting.

## 2. Technology stack

- Frontend: TypeScript monorepo (Turborepo, npm workspaces, tsup builds, vitest
  with jsdom). Node 20.
- Backend: Java 17, Spring Boot 3.3.x, Maven multi-module.
- Infrastructure: RabbitMQ 3.13 (queueing), Redis 7 (shared state), Micrometer +
  Prometheus (metrics).
- Testing: JUnit 5, Mockito, Testcontainers (real RabbitMQ and Redis).
- Packages keep the names `@omnirec/*` (npm) and `io.omnirec.*` (Java).

## 3. Architecture

```
  Storefront browser                     Merchant backend
  @omnirec/commerce-web                  commerce-tracker-spring-boot
  views, search, cart                    purchases, refunds, reviews
        |  HTTPS batches                        |  HTTPS batches
        +------------------+--------------------+
                           v
   EVENT API  (standalone, self-hosted; the ONLY holder of provider credentials)
     1. Filter, before the body is parsed:
          payload size cap (413, including chunked bodies)
          -> API key resolves to an enabled tenant (401)
          -> rate limit (429), keyed on the connection address
        rejections carry CORS headers for allowed origins
     2. Per-event JSON binding (one bad event never fails the batch)
     3. Normalize (server owns tenantId, ip, country, receivedAt; scrub URLs)
     4. Validate (per event type; reject sensitive field names)
     5. Deduplicate (lease-then-complete claim on eventId)
     6. Resolve identity (enrich userId from a stored link; absorb `identify`)
     7. Publish to RabbitMQ, await publisher confirm, then answer 202
                           v
   RABBITMQ: topic exchange `omnirec.events`, one queue set PER destination
     omnirec.events.<dest>            main queue, DLX = omnirec.events.dlx
     omnirec.events.<dest>.retry.1..N one queue per attempt, queue-level TTL,
                                      dead-letters back to the main queue
     omnirec.events.<dest>.dlq        via omnirec.events.dlx
                           v
   DESTINATION CONSUMERS (EventDispatcher, delivery lease per destination)
        |                    |                     |
   Amazon Personalize   Google Cloud Retail   Recently-viewed
   adapter              adapter               (Redis lists for serving side)
```

A separate, older **serving side** (recommendations, search, recently-viewed
endpoints) deploys independently and is not part of the event pipeline.

### 3.1 Modules

Backend (Maven, group `io.omnirec`):

| Module | Responsibility |
| --- | --- |
| `omnirec-commerce-core` | Canonical `CommerceEvent`, event taxonomy, validation, identity linking, deduplication interface, `EventDestination` interface. Depends ONLY on Jackson (+ date module) and SLF4J. No Spring, no queue, no cloud SDK. |
| `omnirec-event-api` | The gateway: filter, controllers, ingestion service, API-key authenticator, rate limiter, normalizer, URL sanitizer, exception handler, metrics. |
| `omnirec-event-processing` | RabbitMQ topology, `ConfirmedPublisher`, consumers, `EventDispatcher`, retry schedule, queue-depth gauges. |
| `omnirec-amazon-personalize-destination` | Amazon Personalize adapter (mapper separate from client). |
| `omnirec-google-retail-destination` | Google Cloud Retail adapter (mapper separate from client). |
| `omnirec-recently-viewed-destination` | Maintains recently-viewed lists in Redis. |
| `omnirec-redis-state` | Redis-backed deduplication and identity-link stores. |
| `commerce-tracker-spring-boot` | Server-side SDK the merchant embeds. |
| `omnirec-event-api-app` | The deployable Spring Boot service assembling the above. |
| `omnirec-contract-tests` | Parses the TypeScript types, Java enum and JSON schema and fails if they diverge. |

Frontend (npm workspaces): `@omnirec/commerce-web` (browser SDK, no framework
dependency), `@omnirec/commerce-react` (thin React bindings). Also
`schema/commerce-event.schema.json`, `examples/nextjs-demo-store`,
`scripts/verify-bundle-security.mjs`, `scripts/e2e/run.sh`.

**The one architectural rule:** the core, gateway, queue layer and both SDKs must
have no compile-time dependency on any provider SDK. Provider code lives only in
destination modules behind one interface:

```java
public interface EventDestination {
    String id();                                   // used in config, queue names, metrics
    void send(CommerceEvent event);
    default void sendBatch(List<CommerceEvent> events) { for (e : events) send(e); }
    default boolean supports(CommerceEvent event) { return !event.eventType().isControlEvent(); }
}
// send() contract: idempotent; THROW DestinationException on a transient failure;
// DestinationException.permanent(id, msg) for failures a retry cannot fix; never
// mutate the event (it is shared across destinations).
```

Adding a provider means implementing this and registering a bean from its own
auto-configuration guarded by `omnirec.destinations.<id>.enabled` (default false).

## 4. The canonical event

```jsonc
{
  "eventId": "9f1c...",                 // unique; the deduplication key
  "eventType": "product_viewed",
  "schemaVersion": "1.0",               // a string
  "timestamp": "2026-01-01T12:00:00.000Z",
  "tenantId": "demo-store",             // SERVER-set from the API key; client value ignored
  "identity": { "anonymousId": "anon_A", "userId": null, "sessionId": "session_1" },
  "context": { "url": "...", "path": "/p/123", "referrer": "...", "platform": "web",
               "device": "mobile", "locale": "en-GB", "timezone": "Europe/London",
               "ip": null, "country": "GB" },        // ip/country server-derived; ip dropped by default
  "commerce": { "productId": "p123", "productIds": ["p1"], "categoryId": "laptops",
                "category": "Laptops", "listId": "gaming-laptops", "cartId": "c1",
                "orderId": "o1", "quantity": 1, "price": 1500.00, "currency": "USD",
                "total": 1500.00, "items": [ /* CommerceItem */ ], "searchQuery": "gaming laptop",
                "recommendationId": "rec_1", "recommendationProvider": "amazon-personalize" },
                // all commerce fields are optional; null fields are omitted on the wire
  "properties": { "dwellTimeMs": 42500, "viewEventId": "..." },
  "receivedAt": "2026-01-01T12:00:01.113Z"       // server-set
}
```

Server-owned fields (`tenantId`, `ip`, `country`, `receivedAt`): client values are
discarded. The API ignores unknown fields (forward compatibility). The shape is
defined three times (TypeScript types, Java record, JSON Schema) and a contract
test fails the build if they disagree.

### 4.1 Taxonomy: 37 event types plus one control event

| Category | Types |
| --- | --- |
| Session | `session_started`, `session_ended`, `page_viewed`, `home_page_viewed` |
| Discovery | `search_performed`, `search_result_clicked`, `product_list_viewed`, `category_viewed`, `product_viewed`, `product_clicked` |
| Product interaction | `product_wishlisted`, `product_shared`, `product_compared`, `product_review_viewed`, `product_review_submitted` |
| Cart | `cart_viewed`, `product_added_to_cart`, `product_removed_from_cart`, `cart_quantity_updated`, `cart_abandoned` |
| Checkout | `checkout_started`, `shipping_information_added`, `payment_information_added`, `checkout_completed`, `checkout_failed` |
| Purchase | `purchase_completed`, `purchase_failed`, `order_cancelled`, `order_refunded` |
| Recommendation | `recommendation_impression`, `recommendation_clicked`, `recommendation_added_to_cart`, `recommendation_purchased` |
| User | `user_registered`, `user_logged_in`, `user_logged_out`, `user_profile_updated` |
| Control | `identify` (establishes an identity link; NEVER delivered to a provider) |

### 4.2 Validation (identical in browser SDK, server SDK, and Event API)

Every event: `eventId`, `eventType`, `schemaVersion`, valid `timestamp`,
`identity.anonymousId`, `identity.sessionId`. Per type, additionally:

| Types | Also required |
| --- | --- |
| `product_viewed`, `product_clicked`, `product_wishlisted`, `product_shared`, `product_compared`, `product_review_*` | `productId` |
| `product_added_to_cart` | `productId`, `quantity` > 0 |
| `product_removed_from_cart`, `cart_quantity_updated` | `productId` |
| `cart_viewed`, `cart_abandoned`, all `checkout_*` | `cartId` |
| `purchase_completed` | `orderId`, non-empty `items`, ISO 4217 `currency`, non-negative `total` |
| `purchase_failed`, `order_cancelled`, `order_refunded` | `orderId` |
| `search_performed` | `searchQuery` |
| `search_result_clicked` | `searchQuery`, `productId` |
| `category_viewed` | `categoryId` |
| `product_list_viewed` | non-empty `productIds` |
| `recommendation_impression` | `recommendationId`, non-empty `productIds` |
| `recommendation_clicked/added_to_cart/purchased` | `recommendationId`, `productId` |
| `user_registered`, `user_logged_in`, `user_profile_updated`, `identify` | `userId` |

Session events and `page_viewed` require nothing more.

**Sensitive data is REJECTED, never redacted.** Walk the whole event at any depth
(objects and arrays); reject if any field name, normalized by lowercasing and
stripping separators, is one of: `cardnumber`, `cardno`, `pan`, `cvv`, `cvc`,
`cvv2`, `securitycode`, `cardsecuritycode`, `expirymonth`, `expiryyear`,
`cardexpiry`, `password`, `passwd`, `pin`, `ssn`, `socialsecuritynumber`,
`accesstoken`, `refreshtoken`, `apikey`, `apisecret`, `secretkey`, `privatekey`,
`authorization`, `creditcard`, `iban`. The TypeScript and Java lists must be
identical (contract-tested).

`context.url` and `context.referrer` are scrubbed in BOTH the browser SDK and the
API (two identical, contract-tested lists): remove URL userinfo and fragment;
drop query parameters named `token`, `code`, `email`, `session` (and similar),
any parameter whose name ends in `token`, and any parameter whose value looks
like an email address.

## 5. Identity model

| Id | Meaning | Storage / rule |
| --- | --- | --- |
| `anonymousId` | The device | First-party cookie `omnirec_anonymous_id`, 1 year, `SameSite=Lax`, `Secure` on HTTPS. Never rotated, never cleared on logout. |
| `sessionId` | The visit | `localStorage`. New session after 30 min idle (configurable), on logout, or when a DIFFERENT user identifies. An anonymous visitor logging in KEEPS the session. |
| `userId` | The customer | Supplied by the merchant via `identify()` / `user.loggedIn()`. The SDK never invents one. |

- On identify the SDK emits an `identify` event; the server stores a link
  `anonymousId -> userId` per tenant (many devices to one user; on a shared device
  the most recent link wins).
- **History is never rewritten.** Events captured anonymously keep `userId: null`.
  Later events from a linked device are enriched with `userId` before queueing.
  Attribution is a join.
- IP address and fingerprints are NEVER identity. IP is used only for coarse geo
  and rate limiting and is discarded by default.
- Repeat `identify()` calls for the same user emit nothing (safe on every page load).
- Server-side events without an `anonymousId` get a stable derived
  `server:<uuid-of-userId>` identity.
- Logout: clear `userId`, keep `anonymousId`, rotate `sessionId`, KEEP the link.

## 6. Delivery, reliability, and idempotency

**Browser SDK transport:** batches of up to 20 events or every 5 s; also flush on
`pagehide`, tab hidden, and coming back online. Bounded `localStorage` offline
buffer (500 events, oldest dropped). Retry with exponential backoff and full
jitter, capped at 5 min between flushes; 408/429/5xx/network are retried, other
4xx are dropped. Each request carries at most `maxBatchSize` events. `keepalive`
only for bodies under 60KB. Buffered events older than 12 h are dropped (the
server dedup window is 24 h). On unload, use `navigator.sendBeacon` (key as a
query parameter, `text/plain` body) and beacon any in-flight batch.

**Server SDK transport:** asynchronous, bounded in-memory queue (10,000), batches
of 50; retry transient failures (5xx, 408, 429, network) with exponential backoff
500 ms doubling to 30 s, 8 attempts (about 3 min); other 4xx not retried;
dropped events are counted (`droppedCount()`). It must never block the merchant's
transaction. Invalid events THROW at the call site (unlike the browser SDK, which
logs and drops). Known limit: the queue is in memory.

**Event API answers:** `202` with body
`{ "accepted": n, "rejected": n, "duplicates": n, "retryLater": n, "errors": [{ "eventId", "reason" }] }`
(errors name the field, never the value); `401` bad/disabled key; `413` oversize
payload or batch over `max-batch-size`; `429` rate limited; `503` with
`Retry-After` when the broker is unavailable or an event is mid-ingestion
elsewhere. JSON error bodies throughout. **202 only after the RabbitMQ publisher
confirm** (awaited; returns and confirms are mandatory and the service refuses to
start without them).

**Deduplication (two stages, same protocol).** Lease-then-complete, never
claim-then-work:

```
claim(key, lease) -> CLAIMED | ALREADY_COMPLETED | IN_PROGRESS
CLAIMED           -> do the work -> complete(key, 24h)        (release(key) on failure)
ALREADY_COMPLETED -> skip (a duplicate / redelivery)
IN_PROGRESS       -> another worker holds it, or a crashed one did: retry later
```

Ingestion key `dedup:ingest:<tenant>:<eventId>` (30 s lease). Delivery key
`dedup:deliver:<destination>:<eventId>` (2 min lease, must outlast the slowest
provider call). A crash mid-work leaves only an expiring lease, so the event is
retried instead of being marked done and lost. Release must never delete a
completed entry. Redis implementation: `SET NX` with value `pending`, then
`done`; release is a Lua script deleting only if still `pending`. Window 24 h.
Pipeline order matters: normalize -> validate -> deduplicate -> resolve identity
-> queue (validate before dedup so a malformed event does not burn a key).

**Deterministic ids for business events:** `evt:<eventType>:<businessKey>`
(`purchase_completed`/`failed`/`order_cancelled`/`order_refunded` and
`checkout_completed` key on `orderId`; `cart_abandoned` on `cartId`;
`product_review_submitted` on `reviewId`; `user_registered` on `userId`;
`recommendation_purchased` on `orderId:productId`; `identify` on
`anonymousId:userId`). The browser and server SDKs MUST produce identical ids, so
a purchase reported from both is delivered once.

**RabbitMQ:** one queue set per destination (a provider outage delays only that
provider). Retry: 5 tiers with delays 1 s, 2 s, 4 s, 8 s, 16 s (doubling from
`retry-initial-interval` to `retry-max-interval`), each a separate queue with a
queue-level TTL (per-message TTL causes head-of-line blocking). After the last
tier or on a permanent failure: DLQ with header `x-omnirec-failure-reason`.
Main queue has a DLX so rejected/unparseable messages reach the DLQ instead of
being dropped. Durable exchanges/queues, persistent messages, container-managed
acks (ack only after delivered/skipped or a CONFIRMED move to a retry tier/DLQ),
prefetch 10. Replay = shovel the DLQ back onto the exchange with routing key
`events.<dest>` (safe because of delivery dedup).

**Dwell time (browser only):** `product.viewed()` sends the normal
`product_viewed` immediately and starts a foreground-only timer (paused on
`visibilitychange` hidden). When measurement ends (another product viewed;
`page.viewed()`/`home.viewed()` route change; `product.viewEnded()` / component
unmount; logout; `pagehide`) send an ENGAGEMENT UPDATE: a second `product_viewed`
with `properties.dwellTimeMs` and `properties.viewEventId` (the eventId of the
original view). `CommerceEvent.isEngagementUpdate()` = `viewEventId` present. Every
interaction-counting destination MUST skip engagement updates so views are not
double-counted. Minimum 1 s (below is discarded), capped at 30 min (treat as
"at least"), no heartbeats. The client `flush()` does not end a measurement.

**Cart abandonment** is never produced by the browser; the merchant backend
derives it (cart has items AND no order AND untouched past a threshold) and calls
`commerce.cart.abandoned(...)`.

## 7. Destinations

All destinations are disabled by default, so the whole stack runs with no cloud
account. Credentials come only from each platform's own mechanism, never config
properties.

**Amazon Personalize** (`PutEvents`). Credentials: AWS default provider chain
only. Properties: `region`, `tracking-id`, `property-keys` (operator allow-list),
`endpoint-override` (LocalStack/capture server only).
- At most 10 events per call: chunk.
- Anonymous visitor: send `sessionId`, OMIT `userId` entirely (putting the
  anonymous id in `userId` fragments history). Authenticated: send both.
- `eventId` -> `eventId`; `eventType` verbatim; `timestamp` -> `sentAt`;
  `productId` -> `itemId`; `total` or `price` -> `eventValue`.
- `impression` = `productIds` for recommendation/list events, max 25.
- `Event.recommendationId` only when `recommendationProvider` is
  `amazon-personalize`, max 40 chars.
- `properties`: string map, max 1024 chars, ONLY keys in `property-keys`; reserved
  keys (`userId`, `sessionId`, `eventType`, `timestamp`, `recommendationId`,
  `impression`) in `property-keys` fail at startup; if over 1024 chars, omit
  with a warning and still send the interaction.
- Multi-item orders split into one event per line with ids `<eventId>:<index>`,
  weighted `quantity * price`.
- Skip: `session_started`, `session_ended`, `user_logged_out`,
  `user_profile_updated`, `identify`, and engagement updates.
- Failure: throttling/5xx/network retryable; validation and not-found permanent;
  missing `tracking-id` permanent.

**Google Cloud Retail** (`UserEvent`). Credentials: Application Default
Credentials only. Properties: `project-number`, `location` (global),
`catalog-id` (default_catalog).
- Identity: `anonymousId` -> `visitorId` ALWAYS (max 128 chars, even after login);
  `userId` -> `userInfo.userId` alongside. (Opposite shape from Personalize.)
- Exactly seven event types are supported; everything else is DROPPED, not coerced:
  `home_page_viewed`->`home-page-view`; `category_viewed` and `product_list_viewed`
  (with a category)->`category-page-view`; `product_viewed`->`detail-page-view`
  (exactly one product); `search_performed`->`search`;
  `cart_viewed`->`shopping-cart-page-view`; `product_added_to_cart`->`add-to-cart`;
  `purchase_completed`->`purchase-complete`.
- Do NOT map clicks or `recommendation_*` (they double-count the real view/add),
  `page_viewed`, or `product_removed_from_cart` (no valid Retail type), or
  engagement updates.
- Skip events missing required fields: `search` needs a query, `category-page-view`
  needs a category, `purchase-complete` needs `purchaseTransaction` (id, revenue,
  currency).
- `attributionToken` only when `recommendationProvider` is `google-retail`.
- Failure: use gRPC retryability plus UNAVAILABLE/DEADLINE_EXCEEDED/
  RESOURCE_EXHAUSTED/INTERNAL retryable; INVALID_ARGUMENT/NOT_FOUND/
  PERMISSION_DENIED permanent; missing `project-number` permanent.

**Recently-viewed** (feeds the serving side). Writes Redis list
`recently-viewed:<userId>` (newest first, each element the product id
JSON-encoded, e.g. `"p3"`) plus a sorted-set index `recently-viewed:<userId>:viewed-at`
(scores = view time), updated in ONE atomic Lua script: a view no newer than the
recorded one changes nothing (so out-of-order retries and redelivery are safe),
cap 20 items, TTL 30 days on both keys. Signed-in users only; skips engagement
updates; optional `tenant-id` filter (blank = all tenants). Its Redis connection
must NOT be a `RedisConnectionFactory` bean (it collides with Spring Boot's Redis
auto-configuration when the state Redis is also enabled): hold it in a plain
object.

**Azure:** not implemented (Azure AI Personalizer retires 2026-10-01). The
`EventDestination` interface is the seam.

## 8. Security requirements

- Browser holds only a publishable key (`pk_live_...`) that can write events for
  one tenant. The SDK THROWS at startup if `apiKey` looks like a secret (`sk_`,
  `AKIA`/`ASIA`, PEM block). `scripts/verify-bundle-security.mjs` scans built
  bundles for AWS keys, PEM, service-account JSON, `sk_` keys, Azure connection
  strings and provider SDKs, with narrow rules to avoid false positives.
- The API key, not the body, determines the tenant (constant-time compare across
  the key set; disabled tenants excluded). Dedup keys and identity links are
  namespaced per tenant.
- Auth, size and rate checks run in a servlet filter BEFORE body parsing.
  Rate limit default 300 requests/min per tenant per client address, keyed on
  the connection address (never raw `X-Forwarded-For`; behind a proxy set
  `server.forward-headers-strategy=native`). Per-instance fixed window (known limit).
- CORS: configured origins only, POST only, credentials off. `text/plain` accepted
  on the batch endpoint only so `sendBeacon` stays a CORS simple request.
- `allow-anonymous-ingestion` disables tenant isolation; startup fails unless the
  profile is dev/test/local.
- Logs carry event ids, types, tenant ids and field NAMES only, never values or
  payloads. Metric tags are bounded values only (tenant, destination, reason).
- Never collect card numbers, CVV, passwords, auth tokens or private keys.

## 9. How to use it

### Run locally (no cloud account needed)

```bash
docker-compose up -d     # RabbitMQ, Redis, Event API on 8081, serving API on 8080
npm install
cd examples/nextjs-demo-store && cp .env.local.example .env.local && cd ../..
npx turbo run dev --filter=nextjs-demo-store     # http://localhost:3000
```

### Storefront (browser)

```js
import { createCommerceClient } from "@omnirec/commerce-web";

const commerce = createCommerceClient({
  apiKey: "pk_live_xxxxx",                 // publishable
  endpoint: "https://events.my-store.com",
  // optional: tenantId, sessionTimeoutMs, autoTrackSessions, autoTrackDwellTime,
  // maxBatchSize (20), maxWaitMs (5000), maxRetries (3), maxOfflineEvents (500),
  // maxEventAgeMs (43200000), validateEvents, onError, debug
});

commerce.page.viewed();                                   // call on every SPA route change
commerce.search.performed({ query: "gaming laptop", resultCount: 24 });
commerce.product.viewed({ productId: "p123", price: 1500, currency: "USD" });
commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 1, price: 1500, currency: "USD" });
commerce.checkout.started({ cartId: "c1" });
commerce.recommendation.impression({ recommendationId: "rec_1",
  recommendationProvider: "amazon-personalize", productIds: ["p1","p2"], source: "homepage" });
commerce.user.loggedIn({ userId: "customer_123" });       // or commerce.identify({ userId })
commerce.user.loggedOut();
await commerce.flush(); commerce.pending();
```

Tracker groups: `session`, `page`, `home`, `search`, `productList`, `category`,
`product` (viewed, viewEnded, clicked, wishlisted, shared, compared, reviewViewed,
reviewSubmitted), `cart`, `checkout`, `purchase`, `recommendation`, `user`, plus
`identify`, `logout`, `track` (escape hatch). The caller NEVER passes
`anonymousId`, `sessionId`, `eventId`, `timestamp`, `url`, `referrer`, `platform`
or `device`. React: `<CommerceProvider apiKey endpoint>`, `useCommerce()`,
`useProductView({ productId, price, currency })` (records the view on mount, ends
dwell on unmount). Every browser API is guarded so SSR import is safe.

### Merchant backend (Spring Boot)

```yaml
omnirec:
  tracker:
    endpoint: https://events.my-store.com
    api-key: ${OMNIREC_API_KEY}
    tenant-id: my-store
    # async: true, queue-capacity: 10000, max-batch-size: 50, validate-events: true,
    # max-retries: 8, retry-initial-interval: 500ms, retry-max-interval: 30s
```

```java
commerce.purchase.completed(PurchaseCompleted.builder()
        .orderId(order.getId()).userId(order.getCustomerId())
        .anonymousId(order.getTrackingAnonymousId())      // from the omnirec_anonymous_id cookie
        .items(lines).total(order.getTotal()).currency("USD").build());
```

Also: `purchase.failed/orderCancelled/orderRefunded`, `product.reviewSubmitted`,
`cart.abandoned`, `checkout.*`, `user.registered/loggedIn/profileUpdated`,
`recommendation.purchased`, `identify`, and `commerce.flush()` before shutdown.
`paymentInformationAdded` takes a method string only (no way to pass card data).
Recommend a merchant-side transactional outbox for must-not-lose purchases.

### Event API configuration (excerpt)

```yaml
omnirec:
  events:
    tenants: { my-store: { api-key: ${MY_STORE_API_KEY}, enabled: true } }
    max-batch-size: 500
    max-payload-bytes: 1048576
    deduplication-window: PT24H
    retain-ip-address: false
    rate-limit: { enabled: true, requests-per-window: 300, window: PT1M }
    cors: { allowed-origins: [https://www.my-store.com] }
  state:
    redis: { enabled: true, host: ${REDIS_HOST}, port: 6379, identity-link-ttl: P365D }
  processing:
    queue-enabled: true      # false = inline dispatch, development only
    max-retries: 5
    retry-initial-interval: 1s
    retry-max-interval: 5m
    delivery-lease: 2m
    confirm-timeout: 10s
    concurrency: 2
    prefetch-count: 10
  destinations:
    amazon-personalize: { enabled: false, region: us-east-1, tracking-id: ${AWS_PERSONALIZE_TRACKING_ID:}, property-keys: [] }
    google-retail:      { enabled: false, project-number: ${GOOGLE_PROJECT_NUMBER:}, location: global, catalog-id: default_catalog }
    recently-viewed:    { enabled: false, host: ${CACHE_REDIS_HOST}, tenant-id: my-store, max-items: 20, ttl: P30D }
spring:
  rabbitmq: { publisher-confirm-type: correlated, publisher-returns: true }   # REQUIRED
```

Redis state is REQUIRED with more than one Event API instance (the in-memory
dedup and identity stores are per-process and log a warning). Endpoints:
`POST /v1/events`, `POST /v1/events/batch` (header `X-Omnirec-Key`),
`POST /v1/identify`, `/actuator/health`, `/actuator/prometheus`.

### Add a destination

Implement `EventDestination`, register it from an auto-configuration behind
`omnirec.destinations.<id>.enabled`, keep the mapper separate from the network
call, and follow the send() contract in section 3.1.

### Metrics

`omnirec.events.received|validated|rejected|duplicates|queued|processed|failed`
(tagged by tenant), `omnirec.provider.delivery.success|failure|latency`,
`omnirec.provider.retries`, `omnirec.provider.dead_lettered`,
`omnirec.queue.depth{queue=main|retry-n|dlq}`.

## 10. Required tests (a change is not done without them)

- Every behaviour has a test that FAILS without the change. Do not claim
  something works because it compiles.
- Frontend: identity (persistence, session policy, login/logout/user switch),
  per-type validation, security (sensitive fields, secret-key guard), every
  tracker, transport (retry classification, backoff, offline buffer, chunking,
  keepalive limit, max age, beacon), dwell time (pause, cap, engagement link, SPA
  navigation), URL scrubbing.
- Backend: validator mirror, identity resolver, dedup protocol including a
  32-thread race, ingestion pipeline (crash window, per-event binding), dispatch
  and retry tiers, both provider mappers against their documented limits, server
  SDK retry and idempotency, contract test (taxonomy, commerce fields,
  sensitive-field list, URL denylist across TS/Java/schema).
- Against REAL infrastructure with Testcontainers (skipped without Docker; the
  skipped count must be 0 to trust a build): RabbitMQ retry, DLQ, provider
  isolation, no head-of-line blocking, rejected messages dead-lettered; Redis
  cross-node claim race, crashed lease, recently-viewed ordering and expiry.
- A test that boots the WHOLE assembled application with the Redis state AND
  recently-viewed feed enabled (module tests alone missed a bean collision).
- Cross-SDK test: a purchase reported by both browser and server is delivered once.
- Live end-to-end script: built browser SDK -> real Event API -> RabbitMQ ->
  Redis -> the Amazon adapter using the real AWS SDK against a local capture
  server (SigV4 signed). It must assert: signed calls, anonymous events have no
  `userId`, sessions differ across days, post-login events carry `userId`, no card
  data reaches the provider, dwell updates are not counted as views, the identity
  link is stored in Redis, and recently-viewed contains only post-login views.
- Anything not exercised against the real external provider must be labelled
  "implemented but externally unverified".

## 11. Acceptance criteria

The project is complete when: all modules build; the tests above pass with 0
skipped; the bundle scan finds no credentials or provider SDKs in the frontend;
a browser event and a backend purchase both reach the destinations through
RabbitMQ; killing a provider backs up only that provider's queues and events
drain after recovery; a crash mid-delivery does not lose or duplicate an event;
an anonymous visitor who logs in has later events attributed to them while earlier
events keep `userId: null`; and enabling a new destination needs no change to the
core, gateway, queue layer or either SDK.

## 12. Known limitations to preserve honestly

Live Personalize and Retail adapters unverified against real accounts; rate limit
is per instance; server SDK queue is in memory; queue-argument changes need a
broker migration; recently-viewed needs standalone Redis (not Cluster); dwell time
is lost on hard crash and some mobile freezes; Azure not implemented.

## Your task

[State what you want here. Examples:
- "Build this project phase by phase. Start with a repository analysis and plan,
  then implement the core module and its tests, then each further module. After
  each phase, report what was verified and what was not."
- "Explain this architecture to a new engineer and identify its weakest points."
- "Review this design and list risks, missing pieces and simplifications."
- "Write the implementation plan and task breakdown for a team of three."]

Answer format: begin with a restatement of the task and your assumptions; make
recommendations before options; state trade-offs and risks concretely; label
anything unverified; end with an ordered list of next steps.
