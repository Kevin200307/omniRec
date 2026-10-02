# Testing

```bash
npx turbo run test                          # frontend, 160 tests
cd backend && mvn test                      # backend, 406 tests (real-broker tests require Docker)
node scripts/verify-bundle-security.mjs     # run after a build
bash scripts/e2e/run.sh                     # live end-to-end journey (requires Docker)
```

## Coverage

| Suite | Tests | Covers |
| --- | --- | --- |
| `commerce-web/tests/identity.test.ts` | 20 | anonymous persistence, session policy, sign-in, sign-out, multiple devices |
| `commerce-web/tests/validation.test.ts` | 32 | per-event-type rules |
| `commerce-web/tests/security.test.ts` | 25 | sensitive fields, credential guards, configuration validation |
| `commerce-web/tests/trackers.test.ts` | 28 | every tracker, automatic context, batching |
| `commerce-web/tests/transport.test.ts` | 21 | retry classification, backoff, offline buffer |
| `commerce-web/tests/dwellTime.test.ts` | 13 | pausing, capping, no heartbeats, engagement-update link, single-page navigation |
| `commerce-web/tests/auditFixes.test.ts` | 21 | chunking, keepalive limit, flush backoff, maximum age, unload, purchase ids, user switch, URL sanitization |
| `EventValidatorTest` | 53 | the Java implementation of the validation rules |
| `IdentityResolverTest` | 19 | linking, resolution, the full journey |
| `InMemoryDeduplicationStoreTest` | 17 | lease-and-complete protocol, including a 32-thread race |
| `EventIngestionServiceTest` | 29 | the gateway pipeline, failure window, per-event binding, URL sanitization |
| `DispatchAndRetryTest` | 18 | dispatch, delivery failure window, retry tiers, confirm-before-acknowledge |
| `AmazonPersonalizeMappingTest` | 26 | Personalize mapping against the documented API limits |
| `GoogleRetailMappingTest` | 23 | Retail mapping against the documented event types |
| `RecentlyViewedDestinationTest` | 8 | which views reach the serving side's lists |
| `RealRedisRecentlyViewedTest` | 6 | a real Redis, read back through the serving side's `RedisCacheProvider`: ordering, repeat views, late retries, size cap, expiry |
| `RedisStateStoresTest` | 19 | which Redis commands the shared stores issue |
| `RealRedisStateTest` | 10 | shared stores against a real Redis, including a cross-node race and an abandoned lease |
| `CommerceTrackerTest` | 26 | server-side SDK, idempotency, shared identifier format |
| `HttpEventSenderTest` | 7 | server-side SDK retry and permanent-failure handling |
| `CanonicalSchemaContractTest` | 8 | parity across TypeScript, Java, and schema: taxonomy, commerce fields, sensitive fields, URL deny-list |
| `RecommendationProviderMapperContractTest` | 3 | legacy serving-side mappers held to the current identity rules |
| `EndToEndPipelineTest` | 17 | HTTP to destination through the full pipeline (inline dispatch) |
| `ApiSecurityTest` | 7 | payload limit, disabled tenant, CORS on rejection, 503, rate-limit bypass |
| `BackendSdkToEventApiTest` | 3 | the server-side SDK over real HTTP into the API, including cross-SDK purchase deduplication |
| `RealBrokerPipelineTest` | 8 | a real RabbitMQ: retry, dead-lettering, isolation, redelivery |
| `RecentlyViewedFeedTest` | 2 | the real application with shared state and the recently-viewed feed on a real Redis: it starts, and stitched views reach the served list |
| `RetryTiersBrokerTest` | 3 | a real RabbitMQ: no head-of-line blocking, rejected messages dead-lettered |
| `RabbitEventPublisherRoutingTest` | 3 | control events reach only destinations that accept them |
| `EventCursorTest` | 5 | the opaque history cursor |
| `PostgresConnectionUrlTest` | 5 | JDBC and hosted-style (`postgresql://user:pass@host`) URLs, credentials never printed |
| `HistoryAccessAuthenticatorTest` | 9 | read keys: publishable keys refused, tenant and platform grants, startup validation |
| `EventStorageDestinationTest` | 7 | the storage worker through the real dispatcher and consumer: acknowledge after commit, retry, dead-letter |
| `RealPostgresEventStoreTest` | 28 | a real PostgreSQL: migrations, indexes, round-trip fidelity, idempotency, tenant isolation, identity links, pagination, filters, retention |
| `RealTimescaleEventStoreTest` | 6 | a real TimescaleDB: hypertable, chunks, idempotency, history, retention policy |
| `EventStorageAutoConfigurationTest` | 8 | enabled and disabled modes, provider selection, TimescaleDB missing |
| `StorageDisabledTest` | 4 | the real application with storage off: no storage bean or `DataSource`, pipeline intact |
| `RealStoragePipelineTest` | 19 | real RabbitMQ and PostgreSQL: HTTP to database, acknowledgement, retry and dead-letter, identity, tenant isolation, history API |

## Notable tests

### Identity stitching end to end

`EndToEndPipelineTest.linksDaysOfAnonymousBrowsingToTheUserWhoEventuallyLogsIn`
walks through the specified journey over real HTTP: two anonymous days in
different sessions, followed by a sign-in. It asserts that the `anonymousId` is
stable, that the sessions differ, that the `identify` event is absorbed rather
than forwarded, that the link exists, that the delivered events still carry
their original null `userId`, and that everything after the link carries the
`userId` automatically.

The assertion that history is not rewritten is the one that would detect a
well-intentioned refactor converting the link into a backfill.

### Deduplication under concurrency

`InMemoryDeduplicationStoreTest.exactlyOneOfManyConcurrentClaimantsWins` races 32
threads on a single `eventId` and asserts that exactly one succeeds. A
get-then-put implementation passes every other test in that file and fails this
one, which is the purpose of the test: that implementation allows two consumers
to both deliver a purchase.

### Cross-language parity

Event names are generated from the [event catalog](../catalog/README.md).
`npm run catalog:check` fails if any generated file is stale, and the
`@omnirec/cli` parity tests fail if a v1 event disappears from the catalog or if
the catalog disagrees with the TypeScript validator. The Java validator reads its
rules from the catalog, and `CatalogDrivenValidationTest` checks every catalog
example against it.

`CanonicalSchemaContractTest` parses the generated TypeScript catalog, the Java
event registry, and the JSON schema, and fails the build if they disagree. It also checks
that the browser and JVM copies of the runtime catalog are identical, and
compares the two sensitive-field lists: a field blocked on one side but not the
other is the dangerous case, because it appears protected while a direct HTTP
request bypasses the protection.

### Provider identity mapping

Two assertions catch mistakes that produce silently incorrect training data:

- `anAnonymousVisitorSendsNoUserIdAtAll`: Personalize must not be given the
  anonymous identifier as a user.
- `visitorIdStaysAnonymousEvenWhenTheVisitorIsLoggedIn`: the Google `visitorId`
  must remain anonymous, with the user carried in `userInfo`.

Each encodes a rule that is easy to reverse and impossible to notice from the
outside.

### Bundle security

`scripts/verify-bundle-security.mjs` searches the built frontend for credentials
and provider SDKs. It has been verified to be non-vacuous by planting a fake AWS
key and a service-account document and confirming that it fails.

## Tests against real infrastructure

`RealBrokerPipelineTest`, `RetryTiersBrokerTest`, `RealRedisStateTest`,
`RealRedisRecentlyViewedTest`, and `RecentlyViewedFeedTest` start RabbitMQ and
Redis using Testcontainers. `RealPostgresEventStoreTest`,
`RealTimescaleEventStoreTest`, `EventStorageAutoConfigurationTest`, and
`RealStoragePipelineTest` start PostgreSQL (`postgres:16-alpine`) and TimescaleDB
(`timescale/timescaledb:2.17.2-pg16`) the same way. They are marked `disabledWithoutDocker`, so a machine
without a Docker daemon still builds and reports them as skipped. Before
trusting a passing build for queue or state changes, confirm that the skipped
count is 0.

The mocked tests establish that the consumer makes the correct calls. Only a
real broker establishes that the topology is correct: that the retry queue's
time-to-live genuinely dead-letters messages back onto the main queue, that
exhausted messages genuinely reach the dead-letter queue, and that one provider
failing genuinely does not delay another. Writing these tests exposed defects
that the mocks could not: a duplicate `RabbitTemplate` bean, listener containers
outside the Spring lifecycle, and a message converter that would have rejected
every event because of the Spring AMQP trusted-package default.

`RealStoragePipelineTest` deliberately has no `@Nested` classes. Spring caches
one application context for an enclosing test class and another for its nested
classes. With live queue consumers, two contexts on one broker become two storage
workers competing for the same queue, which caused the failure-path assertions to
observe the wrong consumer. Tests that consume from a real broker should remain
flat.

Similarly, `RecentlyViewedFeedTest` exists because every module test passed while
the assembled application refused to start when both Redis features were enabled.
Two connection-factory beans left Spring Boot's own Redis auto-configuration
unable to choose between them. Modules tested in isolation cannot detect
composition faults of this kind.

## Live end-to-end verification

```bash
bash scripts/e2e/run.sh
```

This runs the definition of done with every component real. The built
`@omnirec/commerce-web` SDK drives the day-one, day-two, and sign-in journey
against the Event API jar, through RabbitMQ and Redis, into the Amazon adapter,
which issues SigV4-signed `PutEvents` calls using the real AWS SDK. A local
capture server stands in for Personalize, so no AWS account is required, and the
script then asserts on what Personalize would have received. It also verifies
the recently-viewed feed. The script uses ports 25672, 26379, 4566, and 8124 to
avoid collisions with other local stacks, and removes its containers on exit.

To run the same journey against real Personalize, omit the `endpoint-override`
flag and supply real credentials and a tracking identifier. This has not yet
been done.

## Writing tests

- **Use the fixtures.** `CommerceEventFixtures` (Java) and the `harness()` helper
  (TypeScript) exist so that a new test starts from a valid event and changes one
  attribute.
- **Scope identifiers per test.** Deduplication and identity links are
  process-wide and the Spring context is shared, so a literal `evt_1` reused
  across methods is a genuine duplicate. `EndToEndPipelineTest` derives
  identifiers from the test method name for exactly this reason.
- **Test the mapper rather than the client.** Every provider adapter separates
  pure mapping from the network call so that the mapping can be asserted
  exhaustively without credentials. Preserve that separation.
- **Name the behaviour rather than the method.**
  `anAnonymousVisitorSendsNoUserIdAtAll` states what breaks; `testSend2` does
  not.
- **Assert on the assembled application** when a change affects configuration or
  wiring, in addition to the module's own tests.
