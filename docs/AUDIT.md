# Audit: provider-independent commerce event tracking

**Date:** 2026-09-18
**Scope:** the entire repository
**Method:** code review, targeted searches, the existing test suites, live runs,
and the providers' current API documentation.

Most of the code under review had been written recently, so this audit checks it
against the vendor documentation and against its own claims. A significant
number of findings are places where the code did not do what its comments or
documentation stated. These are more serious than ordinary gaps, because they
appear to work.

Status key: **Correct**, **Needs improvement**, **Incorrect**, **Unverified**
(cannot be verified without external access).

This document is a record of the audit, the resolution of each finding, and the
resulting status. The first part describes the codebase as audited; the
"Resolution" part describes its state afterwards.

---

## 1. Architecture as audited

```
@omnirec/commerce-web --+                        +--> omnirec.events.amazon-personalize --> AmazonPersonalizeDestination
                        +--> omnirec-event-api --> RabbitMQ (topic exchange,       (retry queue, DLQ)
commerce-tracker-       |    auth, normalize,      one queue set per destination)-+--> omnirec.events.google-retail --> GoogleRetailDestination
spring-boot ------------+    validate, dedup,                                     |
                             identity, publish                                     +--> (future destinations)
```

- **Shared state:** deduplication and identity links, held either in memory (per
  process) or in Redis (`omnirec-redis-state`).
- **Legacy serving side:** `omnirec-web` and related modules answer
  recommendations, search, and recently-viewed. They still exposed a second,
  unauthenticated `POST /v1/events` (see finding S1).

## 2. Modules

| Module | Role | Status at audit |
| --- | --- | --- |
| `packages/commerce-web` | Browser SDK | Needs improvement |
| `packages/commerce-react` | React binding | Correct |
| `packages/core`, `react`, `react-ui` | Legacy tracker and UI components | Superseded; still posts to legacy ingestion |
| `omnirec-commerce-core` | Canonical model, validation, identity, deduplication SPI | Needs improvement |
| `omnirec-event-api` | Gateway | Needs improvement |
| `omnirec-event-processing` | RabbitMQ, consumers, retry and dead-lettering | Incorrect |
| `omnirec-amazon-personalize-destination` | Personalize adapter | Incorrect |
| `omnirec-google-retail-destination` | Retail adapter | Incorrect |
| `omnirec-redis-state` | Shared deduplication and identity links | Needs improvement |
| `commerce-tracker-spring-boot` | Server-side SDK | Needs improvement |
| `omnirec-event-api-app` | Deployable service | Correct |
| `omnirec-web` and serving starters | Serving side and legacy ingestion | Incorrect |

## 3. Model, SDKs, identity, and API

| Area | Status | Notes |
| --- | --- | --- |
| Canonical `CommerceEvent` | Correct | Provider-independent. `schemaVersion` is the string `"1.0"` rather than an integer; this is retained and documented. |
| Taxonomy (37 event types plus `identify`) | Correct | Identical in TypeScript, Java, and JSON Schema, enforced by the contract test. |
| Per-type validation | Correct | Applied on both sides; sensitive field names are rejected at any depth. |
| Frontend tracker API | Correct | 11 dedicated trackers with typed inputs. |
| Identity (anonymous, session, user) | Needs improvement | Correct, except that switching directly from user A to user B kept a single session (I1). |
| Anonymous-to-user linking | Correct | Separate link record, history not rewritten, many-to-one. Verified live. |
| Sign-out | Correct | `userId` cleared, `anonymousId` retained, session rotated. |
| Dwell time | Incorrect | Double-counted views downstream (D1), continued across single-page navigation (D2), and `viewId` was documented but never set (D3). |
| Cart abandonment | Correct | Derived by the backend only; there is no browser `abandoned()` method. |
| Recommendation events | Needs improvement | Distinguishable, but provider attribution was mapped incorrectly (P3, G3). |
| Server-side SDK | Needs improvement | No retry, so a brief Event API interruption dropped authoritative purchases (B1). Its HTTP path to the API had never been tested (B2). |
| Frontend and backend purchase deduplication | Incorrect | The documentation promised a shared identifier derived from the order identifier, but the frontend did not derive one (B3). |
| Event API | Needs improvement | See A1 to A6. |

## 4. RabbitMQ

| Check | Status | Notes |
| --- | --- | --- |
| Durable exchanges and queues, persistent messages | Correct | |
| Publisher confirms | Incorrect | Configured but never awaited (R1). A broker negative acknowledgement was invisible, and the API returned 202 for a lost event. |
| Consumer acknowledgements | Correct | Container-managed; verified against a real broker. |
| Retry | Incorrect | One retry queue with per-message time-to-live values (R2). RabbitMQ expires messages only at the head of a queue, so a 1-second retry waited behind a 5-minute one. |
| Dead-letter queue | Needs improvement | Exhausted and permanent failures were dead-lettered correctly (verified), but rejected messages on the main queue were dropped because the queue had no dead-letter exchange (R3). |
| Idempotent consumers | Incorrect | The deduplication key was claimed before the send, with a 24-hour time-to-live. A failure between the claim and the send marked the event as done permanently, causing silent loss (R4). The same window existed at ingestion. |
| Prefetch | Correct | 10. |
| Provider isolation | Correct | Verified against a real broker. |

## 5. Provider adapters

Checked against current vendor documentation.

### Amazon Personalize

References:
[PutEvents](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_PutEvents.html),
[Event](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_Event.html).

| # | Status | Finding |
| --- | --- | --- |
| P1 | Incorrect | `eventList` allows at most 10 events. Orders were split per line without chunking, so an order with more than 10 lines was rejected and dead-lettered. |
| P2 | Incorrect | `properties` must not exceed 1024 characters, must use keys from the interactions schema, and must not contain `recommendationId` or `impression`. The adapter serialized all commerce fields, including `recommendationId`, plus every merchant property, so every recommendation event was rejected. |
| P3 | Incorrect | `Event.recommendationId` (at most 40 characters) is the actual attribution field and was never set. It is valid only when Personalize served the list. |
| P4 | Needs improvement | `impression` is limited to 25 items. |
| P5 | Correct | Anonymous visitors send no `userId`, with `sessionId` supplied. Verified live with SigV4-signed requests. |

### Google Cloud Retail

References:
[user events](https://docs.cloud.google.com/retail/docs/user-events),
[UserEvent](https://docs.cloud.google.com/retail/docs/reference/rest/v2/projects.locations.catalogs.userEvents).

| # | Status | Finding |
| --- | --- | --- |
| G1 | Incorrect | `page-visit` and `remove-from-cart` are not supported event types. The adapter sent both, so they were rejected and dead-lettered. The documentation had presented `remove-from-cart` as a correctness improvement, which was wrong. |
| G2 | Incorrect | Click events (`product_clicked`, `search_result_clicked`, `recommendation_clicked`) were mapped to `detail-page-view`, and `recommendation_added_to_cart` to `add-to-cart`. Each double-counted with the real view or add that followed. `recommendation_purchased` was mapped to `purchase-complete` without the required `purchaseTransaction`, so it was rejected and would otherwise have double-counted the purchase. |
| G3 | Incorrect | `attributionToken` must be a token returned by Google. The adapter sent the merchant's `recommendationId` regardless of which system served the recommendation. |
| G4 | Needs improvement | A `category-page-view` without a category, or a `search` without a query, would be rejected. Such events should be skipped. |
| G5 | Correct | `visitorId` is always the `anonymousId`, with `userInfo.userId` supplied alongside. |

### Azure

**Unverified; not implemented.** Azure AI Personalizer retires on
[1 October 2026](https://learn.microsoft.com/en-us/azure/ai-services/personalizer/what-is-personalizer),
and new resources have been blocked since 2023, so there is no current Azure
user-event recommendation API to target. The `EventDestination` interface is
ready for whichever Azure service is chosen, for example Event Hubs for
analytics. The choice of target is a project decision.

## 6. Frontend transport

| # | Status | Finding |
| --- | --- | --- |
| F1 | Incorrect | `flush()` sent the entire offline buffer plus the queue as one request, potentially 500 or more events. That could exceed the server's batch limit, producing a permanent 413 and dropping the whole batch. |
| F2 | Incorrect | `keepalive: true` was set on every fetch. Browsers reject keepalive bodies over 64KB, so a large backlog failed as a network error, was retried, and failed again: an infinite loop that never delivered. |
| F3 | Incorrect | No backoff between flushes. A persistent 5xx was retried every 5 seconds indefinitely. |
| F4 | Needs improvement | No maximum event age. Buffered events resent after the 24-hour deduplication window could be delivered twice. |
| F5 | Needs improvement | A batch in the middle of a retry was held in memory only, so a page unload during backoff lost it. |

## 7. Event API

| # | Status | Finding |
| --- | --- | --- |
| A1 | Incorrect | `maxPayloadBytes` was configured and documented but never enforced. |
| A2 | Incorrect | `tenants.<id>.enabled: false` was documented but ignored; a disabled tenant's key still worked. |
| A3 | Incorrect | An unknown `eventType` or malformed `timestamp` failed JSON binding for the whole batch (400), so the SDK discarded every event in it. This contradicted the documented forward compatibility. |
| A4 | Needs improvement | No exception handler: a broker failure produced a 500 (the documentation stated 503), and error bodies were Spring's default page. |
| A5 | Needs improvement | The rate limiter keyed on the left-most `X-Forwarded-For` value, which the client controls, so the limit could be bypassed by rotating that header. |
| A6 | Needs improvement | `context.url` and `referrer` were stored verbatim. Query strings routinely carry reset tokens, email addresses, and session identifiers, placing personal data in the event stream. |

## 8. Security

| Rule | Status |
| --- | --- |
| 1-2. No provider credentials in the frontend | Correct; bundle scan verified non-vacuous |
| 3-4. Canonical model is provider-independent; mapping lives in adapters | Correct |
| 5. Backend is authoritative | Correct |
| 6. `eventId` on every event | Correct |
| 7. Idempotent processing | Incorrect (R4) |
| 8. Correct acknowledgements | Correct |
| 9. Retries create no duplicates | Incorrect (B3, F4) |
| 10. Provider isolation | Correct |
| 11. No sensitive data | Needs improvement (A6) |
| 12. Tenant isolation | Needs improvement (A2, S1) |
| 13. History not rewritten | Correct |
| 14. No IP or fingerprint identity | Correct |
| 15. Provider-independent SDK API | Correct |

**S1, Incorrect: the legacy ingestion path.** `omnirec-web` still served an
unauthenticated `POST /v1/events` on the serving API. It trusted the body's
`tenantId`, skipped validation, deduplication, and the queue, and called
providers synchronously through the old adapters, which contained precisely the
identity defects the new adapters had fixed: Personalize `userId` set to the
`anonymousId`, Google `visitorId` set to the `userId`, and `CART_REMOVE` mapped
to `add-to-cart`. The legacy starters also accepted a static AWS key pair from
configuration.

**Secrets:** no credentials were committed. `.env.local.example` contains only a
publishable test key.

## 9. Observability

**Needs improvement.** The core counters existed and were verified live. Missing
were retry count, dead-letter count, delivery latency, and queue depth, which are
the four measures needed to detect a provider outage.

## 10. Tests

137 frontend and 237 backend tests passed, but a passing suite proved less than
it appeared to: several tests encoded the incorrect behaviour above as correct.
For example, the Google test asserted `remove-from-cart`. Every fix below changes
or adds a test that fails before the fix and passes after it.

---

## Migration plan

The fixes were executed in this order:

1. **Adapters:** P1 to P4 and G1 to G4, plus the canonical `recommendationProvider`
   field required for attribution.
2. **Dwell time:** D1 to D3. Mark dwell follow-ups and have adapters skip them.
3. **Idempotency:** R4 with a lease-and-complete protocol at ingestion and
   delivery; B3 with a shared deterministic `eventId`.
4. **RabbitMQ:** R1 confirms, R2 tiered retry queues, R3 dead-letter exchange on
   main queues.
5. **Frontend transport:** F1 to F5.
6. **Event API:** A1 to A6 and I1.
7. **Server-side SDK:** B1 retry; B2 an HTTP integration test.
8. **Security:** S1, disabling legacy ingestion by default.
9. **Observability:** retry, dead-letter, latency, and queue-depth metrics.
10. **Verification:** full suites, real brokers, live end-to-end run.
11. **Documentation:** corrections and a troubleshooting page.

---

# Resolution

Every finding below was fixed, and each fix has a test that fails against the
pre-audit code. "Verified" means exercised by a test or a live run; nothing is
reported as working merely because it compiles.

| # | Finding | Fix | Verified by |
| --- | --- | --- | --- |
| P1 | Personalize: more than 10 events per call | Chunks of 10 | `sendsALargeOrderInChunksOfAtMostTen` |
| P2 | Reserved and arbitrary keys in `properties` | Operator allow-list, reserved keys refused at startup, 1024-character cap | `neverPutsRecommendationIdInProperties`, `refusesToStartWithAReservedKeyAllowListed`, `dropsPropertiesThatWouldExceedTheApiLimit...` |
| P3 | `Event.recommendationId` never set | Set when `recommendationProvider` is Personalize, at most 40 characters | `Attribution` tests |
| P4 | Impression over 25 items | Capped | `capsImpressionsAtTheApis25Items` |
| G1 | Invalid Retail types `page-visit` and `remove-from-cart` | Dropped | `emitsOnlyTheSevenDocumentedEventTypes` |
| G2 | Clicks and recommendation events double-counted | Dropped | `dropsEventsThatWouldDoubleCountAnotherEvent` |
| G3 | Foreign identifiers sent as `attributionToken` | Only Google-served identifiers | `neverForwardsAnotherProvidersRecommendationIdAsAttribution` |
| G4 | Events missing required fields sent to be rejected | Skipped | `skipsEventsMissingFieldsRetailRequires` |
| D1 | Dwell follow-up double-counted views | `viewEventId` marks an engagement update; both adapters skip it | Unit tests and live end-to-end run (`p1 views: 1`) |
| D2 | Dwell counted across single-page navigation; `flush()` ended it | `page.viewed()`, `viewEnded()`, and `useProductView` end it; `flush()` does not | `ends the measurement on an SPA route change...` |
| D3 | `viewId` documented but never set | Set from the view's `eventId` | `sends the dwell as an engagement update that points at its view` |
| R1 | Publisher confirms not awaited | `ConfirmedPublisher` waits for the acknowledgement and checks returns; startup fails without confirms | `anUnconfirmedMoveThrowsSoTheOriginalIsNotAcked`, real-broker suites |
| R2 | Retry head-of-line blocking | One retry queue per attempt, each with a queue-level TTL | Real broker: `aShortRetryIsNotHeldBehindALongOne` |
| R3 | Rejected messages silently dropped | Dead-letter exchange on main queues | Real broker: `anUnparseableMessageIsDeadLetteredNotSilentlyDropped` |
| R4 | Claim-before-work failure window lost events | Lease-and-complete at ingestion and delivery; Redis release is a conditional script | Failure-window tests, in-memory and against a real Redis |
| F1 | One oversized request for a backlog | Chunked to `maxBatchSize` | `splits 45 buffered events into requests of at most 20` |
| F2 | Keepalive over 64KB caused infinite retry | Keepalive only under 60KB | `drops keepalive for a body over 64KB` |
| F3 | No backoff between flushes | Exponential with jitter, capped, reset when back online | `grows the backoff but caps it` |
| F4 | Resends past the deduplication window | `maxEventAgeMs` (12 hours) | `drops buffered events older than maxEventAgeMs` |
| F5 | In-flight batch lost on unload | Sent by beacon; the server deduplicates | `beacons events that were in flight` |
| A1 | Payload limit not enforced | Filter returns 413 before parsing, including chunked bodies | `anOversizedPayloadIsRefusedBeforeItIsParsed` |
| A2 | Disabled tenant still accepted | Excluded from the key index | `aDisabledTenantsKeyIsRejected` |
| A3 | One bad event failed the whole batch | Per-event binding; errors name the field, never the value | `PerEventBinding` tests |
| A4 | 500 on broker failure; default error pages | JSON error contract; 503 with `Retry-After` | `aBrokerOutageIsA503WithRetryAfter...` |
| A5 | Rate limit bypassable through `X-Forwarded-For` | Keyed on the connection address | `theRateLimitCannotBeBypassedByRotatingXForwardedFor` |
| A6 | Tokens and emails in URLs | Sanitized in the SDK and again in the API; lists kept identical by a contract test | `A6` tests, `scrubsSecretsFromTheUrl...`, `urlDenylistsAgree` |
| n/a | Authentication ran after body parsing | Moved to a pre-parse filter, with CORS headers on rejections | `aMissingKeyIsRejectedEvenWithAnUnparseableBody`, `aRejectionCarriesCorsHeaders...` |
| B1 | Server-side SDK dropped events on any failure | Bounded exponential retry, 4xx not retried, drops counted | `HttpEventSenderTest` |
| B2 | Server-side SDK to API path never exercised | Real HTTP integration test | `BackendSdkToEventApiTest` |
| B3 | Browser and backend purchase identifiers differed | Shared `evt:<type>:<key>` format on both sides | Cross-SDK test: `aPurchaseReportedByBothTheBrowserAndTheBackendIsDeliveredOnce` |
| I1 | Switching users kept one session | Session rotates | `rotates the session when one user replaces another` |
| S1 | Unauthenticated legacy `/v1/events` | Disabled by default, warns if enabled; legacy mappers corrected | `RecommendationProviderMapperContractTest` |
| n/a | Metrics missing retries, dead-lettering, latency, depth | Added | Live `/actuator/metrics` |
| L1 | Recently-viewed lost its feed when legacy ingestion was disabled | `omnirec-recently-viewed-destination`, which writes the serving format atomically and in viewed-at order | `RealRedisRecentlyViewedTest` (read back through `RedisCacheProvider`), `RecentlyViewedFeedTest`, live end-to-end run. The live run also exposed a startup failure (two `RedisConnectionFactory` beans) that every module test had missed; `RecentlyViewedFeedTest` now guards against it |
| n/a | Commerce-field drift between TypeScript, Java, and schema | Contract test (it detected that the schema lacked `recommendationProvider`) | `commerceFieldsAgree` |

## Final status

| Area | Status | Notes |
| --- | --- | --- |
| Canonical event model | Correct | Parity across TypeScript, Java, and schema enforced by contract tests |
| Frontend SDK | Correct | 160 tests; builds for vanilla JavaScript, React, and Next.js |
| Server-side SDK | Correct | Real HTTP path verified; see limitation 3 |
| Identity and sessions | Correct | Verified live: stable `anonymousId`, new session per day, link stored in Redis |
| Validation | Correct | Both sides; per-event rejection |
| Event identifiers and idempotency | Correct | Lease protocol; cross-node race and abandoned lease verified on a real Redis |
| Event API | Correct | Authentication, tenant isolation, size limit, rate limit, and error contract all tested |
| RabbitMQ | Correct | Confirms, retry tiers, dead-letter exchange, isolation, and redelivery verified on a real broker |
| Amazon adapter | Correct locally; unverified externally | Checked against the API documentation, and real SigV4 requests verified against a capture server. Implemented but externally unverified against live Personalize |
| Google adapter | Correct locally; unverified externally | Checked against the API documentation, and mapping tested. Implemented but externally unverified: never sent to a live Retail project |
| Azure | Unverified; not implemented | Azure AI Personalizer retires on 1 October 2026; the choice of target service is a project decision |
| Security | Correct | Bundle scan, sensitive-field rejection, URL sanitization, pre-parse authentication |
| Observability | Correct | Counters, latency timer, queue-depth gauges |
| Documentation | Correct | Corrected where it described incorrect behaviour; troubleshooting added |

## Known limitations

1. **Live providers are externally unverified.** Personalize and Retail behaviour
   is checked against their documentation and a capture server, not against real
   accounts. A first live run may surface schema-specific issues, for example
   `property-keys` that do not match the deployment's interactions dataset.
2. **Rate limiting is per instance**, using a fixed window. Behind N replicas the
   effective limit is N times the configured value.
3. **The server-side SDK's queue is in memory.** A long Event API outage or a
   restart loses queued events, which are counted rather than silently dropped.
   Use a merchant-side outbox for purchases that must never be lost.
4. **Queue argument changes require a migration** on an existing broker; see
   rabbitmq.md.
5. **Recently-viewed** is fed by the `recently-viewed` destination, which covers
   signed-in users only, as the legacy path did. It uses two keys per user, so it
   requires standalone Redis rather than Redis Cluster, which is also true of the
   serving side's cache.
6. **Dwell time** is lost on process termination and on some mobile page
   freezes. This is a browser lifecycle limit, documented in event-schema.md.

## Remaining work

- Run the end-to-end script against real Personalize and Retail accounts.
- Decide on an Azure target, or remove Azure from scope.
- Remove legacy ingestion entirely once no deployment enables it.
  Recently-viewed no longer depends on it.
- Provide a shared Redis rate limiter, if multi-instance quotas are required.
