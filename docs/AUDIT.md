# Audit — provider-independent commerce event tracking

**Date:** 2026-09-18 · **Scope:** the whole repository · **Method:** code reading,
targeted greps, the existing test suites, live runs, and the providers' current
API documentation.

Most of this code was written in the previous session, so this audit checks that
work against the vendor documentation and against its own claims. A good number
of findings are places where the **code does not do what its comments or docs
say**. Those are worse than plain gaps, because they read as working.

Status key: ✅ correct · ⚠️ needs improvement · ❌ incorrect · ❓ cannot verify

---

## 1. Current architecture

```
@omnirec/commerce-web ──┐                          ┌─► omnirec.events.amazon-personalize ─► AmazonPersonalizeDestination
                        ├─► omnirec-event-api ─►  RabbitMQ (topic exchange,          (retry queue, DLQ)
commerce-tracker-       │   auth → normalize →   one queue set per destination) ─┬─► omnirec.events.google-retail ─► GoogleRetailDestination
spring-boot ────────────┘   validate → dedup →                                     │
                            identity → publish                                     └─► (future destinations)
```

- **Shared state:** dedup and identity links, either in-memory (per process) or in Redis (`omnirec-redis-state`).
- **Legacy serving side:** `omnirec-web` and friends answer recommendations, search, and recently-viewed. **They still expose a second, unauthenticated `POST /v1/events`** (see finding S1).

## 2. Modules

| Module | Role | Status |
|---|---|---|
| `packages/commerce-web` | Browser SDK | ⚠️ |
| `packages/commerce-react` | React binding | ✅ |
| `packages/core`, `react`, `react-ui` | Legacy tracker + UI components | ⚠️ superseded, still posts to legacy ingestion |
| `omnirec-commerce-core` | Canonical model, validation, identity, dedup SPI | ⚠️ |
| `omnirec-event-api` | Gateway | ⚠️ |
| `omnirec-event-processing` | RabbitMQ, consumers, retry/DLQ | ❌ |
| `omnirec-amazon-personalize-destination` | Personalize adapter | ❌ |
| `omnirec-google-retail-destination` | Retail adapter | ❌ |
| `omnirec-redis-state` | Shared dedup + identity links | ⚠️ |
| `commerce-tracker-spring-boot` | Backend SDK | ⚠️ |
| `omnirec-event-api-app` | Deployable | ✅ |
| `omnirec-web` (+ serving starters) | Serving + **legacy ingestion** | ❌ |

## 3–9. Model, APIs, identity, sessions, SDKs, API

| Area | Status | Notes |
|---|---|---|
| Canonical `CommerceEvent` | ✅ | Provider-independent. `schemaVersion` is the string `"1.0"`, not the integer the brief shows; kept as-is (documented). |
| Taxonomy (37 + `identify`) | ✅ | Identical in TS, Java, and JSON Schema, enforced by the contract test. |
| Per-type validation | ✅ | Both sides; sensitive-field names rejected at any depth. |
| Frontend tracker API | ✅ | 11 dedicated trackers, typed inputs. |
| Identity (anon / session / user) | ⚠️ | Correct, except that switching directly from user A to user B keeps one session (I1). |
| anon → user linking | ✅ | Separate link record, history not rewritten, many-to-one. Verified live. |
| Logout | ✅ | userId cleared, anonymousId kept, session rotated. |
| Dwell time | ❌ | Double-counts views downstream (D1); keeps counting across SPA navigation (D2); `viewId` is documented but never set (D3). |
| Cart abandonment | ✅ | Backend-derived only; no browser `abandoned()`. |
| Recommendation events | ⚠️ | Distinguishable, but provider attribution is mapped incorrectly (P3, G3). |
| Backend SDK | ⚠️ | No retry: an Event API blip drops authoritative purchases (B1). Its HTTP path to the API has never been tested (B2). |
| Frontend/backend purchase dedup | ❌ | The docs promise a shared orderId-derived eventId; the frontend doesn't derive one (B3). |
| Event API | ⚠️ | See A1–A6. |

## 10. RabbitMQ

| Check | Status | Notes |
|---|---|---|
| Durable exchanges, queues, persistent messages | ✅ | |
| Publisher confirms | ❌ | Configured but **never awaited** (R1). A broker nack is invisible and the API returns 202 for a lost event. |
| Consumer acks | ✅ | Container-managed; verified against a real broker. |
| Retry | ❌ | One retry queue with **per-message TTLs** (R2). RabbitMQ only expires messages at the head of a queue, so a 1s retry waits behind a 5-minute one. |
| DLQ | ⚠️ | Exhausted and permanent failures dead-letter correctly (verified). But **rejected messages on the main queue are dropped**, because the queue has no DLX (R3). |
| Idempotent consumers | ❌ | The dedup key is claimed *before* the send with a 24h TTL. A crash between claim and send marks the event as done forever: **silent loss** (R4). The same window exists at ingestion. |
| Prefetch | ✅ | 10. |
| Provider isolation | ✅ | Verified with a real broker. |

## 11. Provider adapters (checked against current vendor docs)

**Amazon Personalize** ([PutEvents](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_PutEvents.html), [Event](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_Event.html))

| # | Status | Finding |
|---|---|---|
| P1 | ❌ | `eventList` allows at most **10** events. Orders are split per line with no chunking, so an order with more than 10 lines is rejected and dead-lettered. |
| P2 | ❌ | `properties` must be ≤1024 chars, use keys from the interactions schema, and **must not contain `recommendationId` or `impression`**. The adapter dumps all commerce fields, including `recommendationId`, plus every merchant property, so every recommendation event is rejected. |
| P3 | ❌ | `Event.recommendationId` (≤40 chars) is the real attribution field and is never set. It is only valid when Personalize served that list. |
| P4 | ⚠️ | `impression` is capped at 25 items. |
| P5 | ✅ | Anonymous → no `userId`, with `sessionId`. Verified live with SigV4-signed requests. |

**Google Cloud Retail** ([user events](https://docs.cloud.google.com/retail/docs/user-events), [UserEvent](https://docs.cloud.google.com/retail/docs/reference/rest/v2/projects.locations.catalogs.userEvents))

| # | Status | Finding |
|---|---|---|
| G1 | ❌ | **`page-visit` and `remove-from-cart` are not supported types.** The adapter sends both, so they are rejected and dead-lettered. The docs presented `remove-from-cart` as a correctness win; it was wrong. |
| G2 | ❌ | Clicks (`product_clicked`, `search_result_clicked`, `recommendation_clicked`) are mapped to `detail-page-view`, and `recommendation_added_to_cart` to `add-to-cart`. Each double-counts with the real view or add that follows. `recommendation_purchased` maps to `purchase-complete` without the required `purchaseTransaction`, so it is rejected, and it would double-count the purchase if it weren't. |
| G3 | ❌ | `attributionToken` must be a token returned by Google. The adapter sends the merchant's `recommendationId` regardless of who served the recommendation. |
| G4 | ⚠️ | `category-page-view` without a category, or `search` without a query, would be rejected. They should be skipped. |
| G5 | ✅ | `visitorId` = anonymousId always; `userInfo.userId` alongside. |

**Azure:** ❓ not implemented. Azure AI Personalizer retires on
[1 October 2026](https://learn.microsoft.com/en-us/azure/ai-services/personalizer/what-is-personalizer),
and new resources have been blocked since 2023, so there is no current Azure
user-event recommendation API to target. The `EventDestination` seam is ready
for whichever Azure service you choose (Event Hubs for analytics, for example).
That choice is yours.

## 12. Frontend transport

| # | Status | Finding |
|---|---|---|
| F1 | ❌ | `flush()` sends the entire offline buffer plus the queue as **one request**, up to 500+ events. That can exceed the server's batch cap (a permanent 413, so the whole batch is dropped). |
| F2 | ❌ | `keepalive: true` on every fetch. Browsers reject keepalive bodies over 64KB. A large backlog then fails as a "network error", is retried, fails again: **an infinite loop that never delivers**. |
| F3 | ❌ | No backoff between flushes. A persistent 5xx is hammered every 5s indefinitely. |
| F4 | ⚠️ | No maximum event age. Buffered events resent after the 24h dedup window could be **double-delivered**. |
| F5 | ⚠️ | A batch in the middle of a retry is held in memory only. A page unload during backoff loses it. |

## 13. Event API

| # | Status | Finding |
|---|---|---|
| A1 | ❌ | `maxPayloadBytes` is configured and documented but **never enforced**. |
| A2 | ❌ | `tenants.<id>.enabled: false` is documented but **ignored**: a disabled tenant's key still works. |
| A3 | ❌ | An unknown `eventType` or malformed `timestamp` fails JSON binding for the **whole batch** (400), so the SDK drops every event in it. This contradicts the documented forward compatibility. |
| A4 | ⚠️ | No exception handler: a broker failure is a 500 (the docs say 503), and error bodies are Spring's default page. |
| A5 | ⚠️ | The rate limiter keys on the left-most `X-Forwarded-For`, which the client controls, so the limit can be bypassed by rotating that header. |
| A6 | ⚠️ | `context.url` / `referrer` are stored verbatim. Query strings routinely carry reset tokens, emails, and session ids: **PII in the event stream**. |

## 14. Security

| Rule | Status |
|---|---|
| 1–2 No provider credentials in the frontend | ✅ bundle scan (verified non-vacuous) |
| 3–4 Canonical model provider-independent, mapping in adapters | ✅ |
| 5 Backend authoritative | ✅ |
| 6 eventId on every event | ✅ |
| 7 Idempotent processing | ❌ R4 |
| 8 Correct acks | ✅ |
| 9 Retries create no duplicates | ❌ B3, F4 |
| 10 Provider isolation | ✅ |
| 11 No sensitive data | ⚠️ A6 |
| 12 Tenant isolation | ⚠️ A2, **S1** |
| 13 History not rewritten | ✅ |
| 14 No IP / fingerprint identity | ✅ |
| 15 Provider-independent SDK API | ✅ |

**S1 ❌ — the legacy ingestion path.** `omnirec-web` still serves an
**unauthenticated** `POST /v1/events` on the serving API. It trusts the body's
`tenantId`, skips validation, dedup, and the queue, and calls providers
synchronously through the old adapters, which have exactly the identity bugs the
new adapters fixed (Personalize `userId = anonymousId`; Google `visitorId =
userId`; `CART_REMOVE → add-to-cart`). The legacy starters also accept a static
AWS key pair from configuration.

**Secrets:** no credentials are committed. `.env.local.example` holds only a
publishable test key.

## 15. Observability

⚠️ The core counters exist and are verified live. **Missing:** retry count,
dead-letter count, delivery latency, and queue depth, which are exactly the four
you need to notice a provider outage.

## 16. Tests

137 frontend and 237 backend tests pass, but a passing suite proves less than
it looks: several tests encoded the wrong behaviour above as correct. For example, the
Google test asserted `remove-from-cart`. **Every fix below changes or adds a test
that fails before it and passes after.**

---

## Migration plan (executed in this order)

1. **Adapters:** P1–P4, G1–G4, plus the canonical `recommendationProvider` field that attribution needs.
2. **Dwell:** D1–D3. Mark dwell follow-ups and have adapters skip them.
3. **Idempotency:** R4 with a lease → complete protocol, at ingestion and delivery; B3 with a shared deterministic eventId.
4. **RabbitMQ:** R1 confirms, R2 tiered retry queues, R3 DLX on main queues.
5. **Frontend transport:** F1–F5.
6. **Event API:** A1–A6, I1.
7. **Backend SDK:** B1 retry; B2 an HTTP integration test.
8. **Security:** S1, gating legacy ingestion off by default.
9. **Observability:** retry, DLQ, latency, and queue-depth metrics.
10. **Verification:** full suites, real brokers, live end-to-end.
11. **Docs:** corrections plus a troubleshooting page.

---

# Resolution

Every finding below was fixed, and each fix has a test that fails against the
pre-audit code. "Verified" means exercised by a test or a live run; nothing here
is reported as working because it compiles.

| # | Finding | Fix | Verified by |
|---|---|---|---|
| P1 | Personalize >10 events per call | Chunks of 10 | `sendsALargeOrderInChunksOfAtMostTen` |
| P2 | Reserved keys / arbitrary keys in `properties` | Operator allow-list, reserved keys refused at startup, 1024-char cap | `neverPutsRecommendationIdInProperties`, `refusesToStartWithAReservedKeyAllowListed`, `dropsPropertiesThatWouldExceedTheApiLimit…` |
| P3 | `Event.recommendationId` never set | Set when `recommendationProvider` is Personalize, ≤40 chars | `Attribution` tests |
| P4 | Impression over 25 items | Capped | `capsImpressionsAtTheApis25Items` |
| G1 | Invalid Retail types `page-visit`, `remove-from-cart` | Dropped | `emitsOnlyTheSevenDocumentedEventTypes` |
| G2 | Clicks / recommendation events double-counted | Dropped | `dropsEventsThatWouldDoubleCountAnotherEvent` |
| G3 | Foreign ids sent as `attributionToken` | Only Google-served ids | `neverForwardsAnotherProvidersRecommendationIdAsAttribution` |
| G4 | Events missing required fields sent to be rejected | Skipped | `skipsEventsMissingFieldsRetailRequires` |
| D1 | Dwell follow-up double-counted views | `viewEventId` marks an engagement update; both adapters skip it | unit tests + **live e2e** (`p1 views: 1`) |
| D2 | Dwell counted across SPA navigation; `flush()` ended it | `page.viewed()` / `viewEnded()` / `useProductView` end it; `flush()` doesn't | `ends the measurement on an SPA route change…` |
| D3 | `viewId` documented, never set | Set from the view's eventId | `sends the dwell as an engagement update that points at its view` |
| R1 | Publisher confirms not awaited | `ConfirmedPublisher` waits for the ack and checks returns; startup fails without confirms | `anUnconfirmedMoveThrowsSoTheOriginalIsNotAcked`, real-broker suites |
| R2 | Retry head-of-line blocking | One retry queue per attempt, each with a queue-level TTL | **real broker**: `aShortRetryIsNotHeldBehindALongOne` |
| R3 | Rejected messages silently dropped | DLX on main queues | **real broker**: `anUnparseableMessageIsDeadLetteredNotSilentlyDropped` |
| R4 | Claim-before-work crash window lost events | Lease → complete, at ingestion and delivery; Redis release is a conditional script | crash-window tests (in-memory and **real Redis**) |
| F1 | One oversized request for a backlog | Chunked to `maxBatchSize` | `splits 45 buffered events into requests of at most 20` |
| F2 | keepalive over 64KB caused infinite retry | keepalive only under 60KB | `drops keepalive for a body over 64KB` |
| F3 | No backoff between flushes | Exponential with jitter, capped, reset when back online | `grows the backoff but caps it` |
| F4 | Resends past the dedup window | `maxEventAgeMs` (12h) | `drops buffered events older than maxEventAgeMs` |
| F5 | In-flight batch lost on unload | Beaconed; the server deduplicates | `beacons events that were in flight` |
| A1 | Payload cap not enforced | Filter: 413 before parsing, including chunked bodies | `anOversizedPayloadIsRefusedBeforeItIsParsed` |
| A2 | Disabled tenant still accepted | Excluded from the key index | `aDisabledTenantsKeyIsRejected` |
| A3 | One bad event failed the whole batch | Per-event binding; errors name the field, never the value | `PerEventBinding` tests |
| A4 | 500 for broker failure; default error pages | JSON error contract; 503 + Retry-After | `aBrokerOutageIsA503WithRetryAfter…` |
| A5 | Rate limit bypassable via X-Forwarded-For | Keyed on the connection address | `theRateLimitCannotBeBypassedByRotatingXForwardedFor` |
| A6 | Tokens and emails in URLs | Scrubbed in the SDK and again in the API; lists kept identical by a contract test | `A6` tests, `scrubsSecretsFromTheUrl…`, `urlDenylistsAgree` |
| — | Auth ran after body parsing | Moved to a pre-parse filter, with CORS headers on rejections | `aMissingKeyIsRejectedEvenWithAnUnparseableBody`, `aRejectionCarriesCorsHeaders…` |
| B1 | Backend SDK dropped on any failure | Bounded exponential retry, 4xx not retried, drops counted | `HttpEventSenderTest` |
| B2 | Backend SDK → API never exercised | Real HTTP integration test | `BackendSdkToEventApiTest` |
| B3 | Browser and backend purchase ids differed | Shared `evt:<type>:<key>` format on both sides | **cross-SDK test**: `aPurchaseReportedByBothTheBrowserAndTheBackendIsDeliveredOnce` |
| I1 | Switching users kept one session | Session rotates | `rotates the session when one user replaces another` |
| S1 | Unauthenticated legacy `/v1/events` | Off by default, warns if enabled; legacy mappers fixed | `RecommendationProviderMapperContractTest` |
| — | Metrics missing retries, DLQ, latency, depth | Added | live `/actuator/metrics` |
| L1 | Recently-viewed lost its feed when legacy ingestion was switched off | `omnirec-recently-viewed-destination`, which writes the serving format atomically and in viewed-at order | `RealRedisRecentlyViewedTest` (read back through `RedisCacheProvider`), `RecentlyViewedFeedTest`, **live e2e**. The live run also caught a startup failure (two `RedisConnectionFactory` beans) that every module test missed; `RecentlyViewedFeedTest` now guards it |
| — | Commerce-field drift between TS / Java / schema | Contract test (it caught the schema missing `recommendationProvider`) | `commerceFieldsAgree` |

## Final status

| Area | Status | Notes |
|---|---|---|
| Canonical event model | ✅ | Parity across TS / Java / schema enforced by contract tests |
| Frontend SDK | ✅ | 160 tests; builds for vanilla JS, React, Next.js |
| Backend SDK | ✅ | Real HTTP path verified; see limitation 3 |
| Identity & sessions | ✅ | Verified live: stable anonymousId, new session per day, link stored in Redis |
| Validation | ✅ | Both sides; per-event rejection |
| Event IDs & idempotency | ✅ | Lease protocol; cross-node race and crashed lease verified on real Redis |
| Event API | ✅ | Auth, tenant isolation, size cap, rate limit, error contract all tested |
| RabbitMQ | ✅ | Confirms, retry tiers, DLX, isolation, redelivery verified on a real broker |
| Amazon adapter | ✅ locally · ❓ externally | Checked against the API docs; real SigV4 requests verified against a capture server. **Implemented but externally unverified** against live Personalize (no credentials here). |
| Google adapter | ✅ locally · ❓ externally | Checked against the API docs; mapping tested. **Implemented but externally unverified**: never sent to a live Retail project. |
| Azure | ❓ | Not implemented. Azure AI Personalizer retires 1 October 2026; choosing a target service is your call. |
| Security | ✅ | Bundle scan, sensitive-field rejection, URL scrubbing, pre-parse auth |
| Observability | ✅ | Counters, latency timer, queue-depth gauges |
| Documentation | ✅ | Corrected where it described wrong behaviour; troubleshooting added |

## Known limitations

1. **Live providers are externally unverified.** Personalize and Retail behaviour
   is checked against their documentation and a capture server, not against real
   accounts. The first live run may surface schema-specific issues, for example
   `property-keys` that don't match your interactions dataset.
2. **Rate limiting is per instance**, a fixed window. Behind N replicas the
   effective limit is N× the setting.
3. **The backend SDK's queue is in memory.** A long Event API outage or a restart
   loses queued events (counted, not silent). Use a merchant-side outbox for
   purchases that must never be lost.
4. **Queue-argument changes are a migration** on an existing broker; see
   rabbitmq.md.
5. **Recently-viewed** is fed by the `recently-viewed` destination, which covers
   signed-in users only, as the legacy path did. It uses two keys per user, so it
   needs standalone Redis rather than Redis Cluster, which is also true of the
   serving side's cache.
6. **Dwell time** is lost on a hard crash and on some mobile freezes. That is a
   browser lifecycle limit, documented in event-schema.md.

## Remaining TODOs

- Run the e2e script against real Personalize and Retail accounts.
- Decide on an Azure target, or drop Azure from scope.
- Remove legacy ingestion entirely once no deployment enables it. Recently-viewed
  no longer depends on it.
- A shared (Redis) rate limiter, if multi-instance quotas matter.
