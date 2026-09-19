# Testing

```bash
npx turbo run test                          # frontend, 160 tests
cd backend && mvn test                      # backend, 312 tests (real-broker tests need Docker)
node scripts/verify-bundle-security.mjs     # after a build
bash scripts/e2e/run.sh                     # live end-to-end journey (Docker)
```

## What's covered

| Suite | Tests | Covers |
|---|---|---|
| `commerce-web/tests/identity.test.ts` | 20 | anonymous persistence, session policy, login, logout, multi-device |
| `commerce-web/tests/validation.test.ts` | 32 | per-event-type rules |
| `commerce-web/tests/security.test.ts` | 25 | sensitive fields, credential guards, config validation |
| `commerce-web/tests/trackers.test.ts` | 28 | every tracker, automatic context, batching |
| `commerce-web/tests/transport.test.ts` | 21 | retry classification, backoff, offline buffer |
| `commerce-web/tests/dwellTime.test.ts` | 13 | pausing, capping, no heartbeats, engagement-update link, SPA navigation |
| `commerce-web/tests/auditFixes.test.ts` | 21 | chunking, keepalive cap, flush backoff, max age, unload, purchase ids, user switch, URL scrubbing |
| `EventValidatorTest` | 53 | the Java mirror of the validation rules |
| `IdentityResolverTest` | 19 | linking, resolution, the full journey |
| `InMemoryDeduplicationStoreTest` | 17 | lease → complete protocol, incl. a 32-thread race |
| `EventIngestionServiceTest` | 29 | the gateway pipeline, crash window, per-event binding, URL scrubbing |
| `DispatchAndRetryTest` | 18 | dispatch, delivery crash window, retry tiers, confirm-before-ack |
| `AmazonPersonalizeMappingTest` | 26 | Personalize mapping against the documented API limits |
| `GoogleRetailMappingTest` | 23 | Retail mapping against the documented event types |
| `RecentlyViewedDestinationTest` | 8 | which views reach the serving side's lists |
| `RealRedisRecentlyViewedTest` | 6 | a **real Redis**, read back through the serving side's `RedisCacheProvider`: order, re-views, late retries, cap, expiry |
| `RedisStateStoresTest` | 19 | which Redis commands the shared stores issue |
| `RealRedisStateTest` | 10 | shared stores against a **real Redis**, incl. a cross-node race and a crashed lease |
| `CommerceTrackerTest` | 26 | backend SDK, idempotency, shared id format |
| `HttpEventSenderTest` | 7 | backend SDK retry and permanent-failure handling |
| `CanonicalSchemaContractTest` | 8 | TS / Java / schema parity: taxonomy, commerce fields, sensitive fields, URL denylist |
| `RecommendationProviderMapperContractTest` | 3 | legacy serving-side mappers held to the new identity rules |
| `EndToEndPipelineTest` | 17 | HTTP → destination, through everything (inline dispatch) |
| `ApiSecurityTest` | 7 | payload cap, disabled tenant, CORS on rejection, 503, rate-limit bypass |
| `BackendSdkToEventApiTest` | 3 | the backend SDK over real HTTP into the API, incl. cross-SDK purchase dedup |
| `RealBrokerPipelineTest` | 8 | a **real RabbitMQ**: retry, DLQ, isolation, redelivery |
| `RecentlyViewedFeedTest` | 2 | the real app with shared state **and** the recently-viewed feed on a **real Redis**: starts, and stitched views reach the served list |
| `RetryTiersBrokerTest` | 3 | a **real RabbitMQ**: no head-of-line blocking, rejected messages dead-lettered |

## The tests worth knowing about

### Identity stitching, end to end

`EndToEndPipelineTest.linksDaysOfAnonymousBrowsingToTheUserWhoEventuallyLogsIn`
walks the journey from the spec over real HTTP: two anonymous days in different
sessions, then a login. It asserts the `anonymousId` is stable, the sessions
differ, the `identify` event is absorbed rather than forwarded, the link exists,
**the delivered events still carry their original null `userId`**, and everything
after the link carries the `userId` automatically.

That "history is not rewritten" assertion is the one that would catch a
well-meaning refactor turning the link into a backfill.

### Deduplication under concurrency

`InMemoryDeduplicationStoreTest.exactlyOneOfManyConcurrentClaimantsWins` races 32
threads on one `eventId` and asserts exactly one wins. A get-then-put
implementation passes every other test in that file and fails this one — which is
the point, because that's the version that lets two consumers both deliver a
purchase.

### Cross-language parity

`CanonicalSchemaContractTest` parses `types.ts`, the Java enum, and the JSON
schema and fails the build if they disagree. It also compares the two sensitive-
field lists: a field blocked on one side but not the other is the dangerous case,
because it reads as protected while a direct POST sails past.

This is what makes three definitions of the event shape safe to maintain.

### Provider identity mapping

Two assertions catch the mistakes that produce silently-wrong training data:

- `anAnonymousVisitorSendsNoUserIdAtAll` — Personalize must not be given the
  anonymous id as a user.
- `visitorIdStaysAnonymousEvenWhenTheVisitorIsLoggedIn` — Google's `visitorId`
  must stay anonymous, with the user in `userInfo`.

Both encode a rule that is easy to get backwards and impossible to notice from
the outside.

### Bundle security

`scripts/verify-bundle-security.mjs` greps the built frontend for credentials and
provider SDKs. It has been verified non-vacuous by planting a fake AWS key and a
service-account blob and confirming it fails.

## Real brokers

`RealBrokerPipelineTest` and `RealRedisStateTest` start RabbitMQ and Redis with
Testcontainers. They are marked `disabledWithoutDocker`, so a machine without a
Docker daemon still builds — it just reports them as skipped. **Check that the
skipped count is 0** before trusting a green build for queue or state changes.

The mocked tests prove the consumer makes the right *calls*. Only a real broker
proves the topology is right — that the retry queue's TTL really dead-letters
messages back onto the main queue, that exhausted messages really land on the
DLQ, and that one provider failing really doesn't hold up another. Writing these
tests surfaced three bugs the mocks could not: a duplicate `RabbitTemplate`
bean, listener containers outside the Spring lifecycle, and a message converter
that would have rejected every event because of Spring AMQP's trusted-package
default.

## Live end-to-end

```bash
bash scripts/e2e/run.sh
```

Runs the Definition of Done with every component real: the built
`@omnirec/commerce-web` SDK drives the day-1 / day-2 / login journey against the
Event API jar, through RabbitMQ and Redis, into the Amazon adapter, which makes
SigV4-signed `PutEvents` calls with the real AWS SDK. A local capture server
stands in for Personalize, so no AWS account is needed; the script then asserts
on what Personalize would have received. It uses ports 25672/26379/4566/8124 so
it won't collide with other local stacks, and removes its containers on exit.

To run the same journey against real Personalize, drop the
`endpoint-override` flag and supply real credentials and a tracking id.

## Writing tests here

- **Use the fixtures.** `CommerceEventFixtures` (Java) and the `harness()` helper
  (TS) exist so a new test starts from a valid event and changes one thing.
- **Scope ids per test.** Deduplication and identity links are process-wide and
  the Spring context is shared, so a literal `evt_1` reused across methods is a
  genuine duplicate. `EndToEndPipelineTest` derives ids from the test method name
  for exactly this reason — it caught this during development.
- **Test the mapper, not the client.** Every provider adapter splits pure mapping
  from the network call so the mapping can be asserted exhaustively with no
  credentials. Keep that split.
- **Name the behaviour, not the method.** `anAnonymousVisitorSendsNoUserIdAtAll`
  tells you what breaks; `testSend2` doesn't.
