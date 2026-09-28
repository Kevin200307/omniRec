# Architecture

Omnirec collects commerce interactions, standardizes them, resolves the identity
they belong to, and delivers them to the personalization providers a merchant
has configured. It is infrastructure rather than an analytics product: it
provides no dashboard, no reporting interface, and no model of its own.

```
                E-COMMERCE APPLICATION
                         |
          +--------------+--------------+
          |                             |
          v                             v
  FRONTEND JS SDK               BACKEND JAVA SDK
  @omnirec/commerce-web         commerce-tracker-spring-boot
          |                             |
  User interactions              Business events
  (views, cart, search)          (purchases, refunds, reviews)
          |                             |
          +--------------+--------------+
                         |
                         v
                  EVENT GATEWAY            omnirec-event-api
                         |
                +--------+--------+
                |                 |
          Authentication      Validation
                |                 |
                +--------+--------+
                         |
                         v
                  EVENT NORMALIZER
                         |
                         v
                 IDENTITY RESOLVER
                         |
                         v
                   DEDUPLICATOR
                         |
                         v
                      RABBITMQ            omnirec-event-processing
                         |
                         v
                EVENT DISPATCHER
                         |
         +---------------+---------------+-----------------+
         |               |               |                 |
         v               v               v                 v
   AMAZON ADAPTER   GOOGLE ADAPTER   AZURE ADAPTER    STORAGE WORKER     omnirec-event-storage
         |               |            (not implemented)    |             (optional)
         v               v                                 v
   Amazon Personalize  Google Retail                  EventStore
                                                           |
                                              +------------+------------+
                                              |                         |
                                              v                         v
                                     PostgresEventStore        TimescaleEventStore
                                              |                         |
                                          +---+---+                     v
                                          |       |                TimescaleDB
                                          v       v
                                        Local    Neon
                                      Postgres  Postgres
```

## The core constraint

The core must never depend on a provider. `omnirec-commerce-core` has three
dependencies: Jackson, the Jackson date module, and SLF4J. It uses no Spring, no
queue, and no cloud SDK. All provider-specific behaviour is implemented behind a
single interface:

```java
public interface EventDestination {
    String id();
    void send(CommerceEvent event);
}
```

Supporting an additional provider requires one implementation registered as a
bean. The core, the gateway, the queue layer, and both SDKs remain unchanged.
The constraint is enforced by the module graph rather than by convention: those
modules have no compile-time path to a provider SDK, so such an import does not
compile.

## Modules

| Module | Responsibility |
| --- | --- |
| `omnirec-commerce-core` | `CommerceEvent`, the event taxonomy, validation, identity linking, deduplication, and `EventDestination`. No framework dependencies. |
| `omnirec-event-api` | The gateway: API keys, rate limiting, payload limits, normalization, validation, deduplication, identity resolution. |
| `omnirec-event-processing` | RabbitMQ topology, consumers, dispatcher, per-destination idempotency, retry tiers, dead-letter queues. |
| `omnirec-amazon-personalize-destination` | The only module with knowledge of Amazon Personalize. |
| `omnirec-google-retail-destination` | The only module with knowledge of Google Retail. |
| `omnirec-recently-viewed-destination` | Maintains the serving side's recently-viewed lists from the pipeline. |
| `omnirec-redis-state` | Redis-backed deduplication and identity-link stores for multi-instance deployments. |
| `omnirec-event-storage` | Optional historical storage: the storage worker (an `EventDestination`), `PostgresEventStore` and `TimescaleEventStore`, Flyway migrations, and the customer history API. Inert unless `omnirec.storage.enabled=true`. |
| `commerce-tracker-spring-boot` | The SDK embedded by merchants for authoritative business events. |
| `omnirec-event-api-app` | The deployable service: gateway, queue, and destinations. |
| `packages/commerce-web` | The browser SDK. No React dependency. |
| `packages/commerce-react` | React bindings over the browser SDK. |

The original serving side (`omnirec-core`, `omnirec-web`,
`omnirec-personalize-starter`, `omnirec-google-recai-starter`,
`omnirec-redis-starter`, and `omnirec-demo-app`) continues to serve
`/v1/recommendations`, `/v1/search`, and `/v1/recently-viewed`. Recently-viewed
lists are populated by the pipeline through
`omnirec-recently-viewed-destination`, which writes them in the format the
serving side's Redis cache reads. Serving personalization results is a separate
concern from collecting signals, and the two deploy independently.

## Historical storage

Storage is one more destination rather than a new path. The storage worker is an
`EventDestination` (`event-storage`), so it receives its own durable queue,
retry tiers, and dead-letter queue from the same topology code as every provider,
and it is acknowledged only after the row is committed. It depends on the
`EventStore` interface in `omnirec-commerce-core`, never on a database:

```
EventStorageDestination -> EventStore -> PostgresEventStore   (any PostgreSQL: local, Neon, RDS...)
                                      -> TimescaleEventStore  (extends it: hypertable, chunk retention)
```

The Event API never touches the database, so storage cannot slow ingestion, and
with `omnirec.storage.enabled=false` (the default) the module contributes nothing
at all. The `identify` control event is routed to destinations that accept
control events, meaning only storage, so that identity links reach the
database. Provider queues are unaffected. See
[event-storage.md](event-storage.md).

## Rationale: the Event API as a separate service

The gateway is an independent deployable rather than a library embedded by
merchants. This decision underpins the security model:

- **Provider credentials are confined to one process.** The storefront and the
  merchant backend hold only a publishable key, so neither can disclose a
  credential it never possessed.
- **Tenant isolation is enforced.** The API key determines the tenant; a request
  body field cannot.
- **A single RabbitMQ deployment serves all merchants.** Durability, retry, and
  dead-lettering are operated once.

The cost is an additional network hop from the server-side SDK, which is why
that SDK delivers asynchronously: Omnirec latency must not become merchant
checkout latency.

## Rationale: pipeline ordering

```
normalize -> validate -> deduplicate -> resolve identity -> queue
```

- **Validation precedes deduplication**, so that a malformed event does not
  consume a deduplication key. Otherwise a client that submits an invalid event,
  corrects it, and resubmits it under the same `eventId` would have the
  corrected version discarded as a duplicate.
- **Deduplication precedes identity resolution**, so that a redelivered
  `identify` event does not repeat the link operation, and duplicate work is
  eliminated as early as possible.
- **Identity resolution precedes queueing**, so that the queued event already
  carries the `userId`. Performing resolution in the consumer would require
  every destination worker to query the link store for the same event.

## Two layers of deduplication

The two layers guard different failure modes, and neither replaces the other.

| Stage | Protects against |
| --- | --- |
| Ingestion (`dedup:ingest:...`) | A client submitting the same event twice, for example an SDK retry after a timeout or a reloaded confirmation page. |
| Delivery (`dedup:deliver:<destination>:...`) | RabbitMQ at-least-once redelivery. A consumer that terminates after calling the provider but before acknowledging the message will receive that message again. |

Both use a lease-and-complete protocol: a short lease is acquired before the
work begins, replaced by a long-lived completion record only after the work
succeeds, and released if the work fails. A failure during processing leaves
only the lease, which expires, so the event is retried rather than being
recorded as complete and lost. See
[rabbitmq.md](rabbitmq.md#at-least-once-delivery-and-consumer-idempotency).

## Failure behaviour

| Failure | Behaviour |
| --- | --- |
| Storefront offline | Events are buffered in `localStorage`, bounded with oldest-first eviction, and flushed when connectivity returns. |
| Event API returns 5xx | The SDK retries with exponential backoff and full jitter. |
| Event API returns 4xx | The SDK discards the batch, because the payload is invalid and resubmitting identical content cannot succeed. |
| RabbitMQ unreachable or returns a negative acknowledgement | The publisher confirm fails, ingestion returns 503 with `Retry-After`, the lease is released, and the SDK retries. |
| The same event is already being ingested | 503 with `Retry-After`. The subsequent retry observes it as a duplicate. |
| Provider unavailable | The message is routed to the retry tier for that attempt, waits for the queue time-to-live, and returns automatically. Other destinations continue to drain. |
| Consumer terminates during delivery | The unacknowledged message is redelivered, the delivery lease expires, and the event is delivered exactly once. |
| Provider still failing after the configured retries | The event is dead-lettered with the failure reason attached for triage. |
| Provider rejects the event permanently | The event is dead-lettered immediately, bypassing retries. |
| Storage database unavailable (storage enabled) | Only the `event-storage` queue backs up and retries; ingestion and provider delivery are unaffected. |

See [rabbitmq.md](rabbitmq.md) for the queue topology and
[security.md](security.md) for the trust boundaries.
