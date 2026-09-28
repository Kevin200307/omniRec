# Historical event storage

Omnirec can keep every event it collects in a database of your choosing, and
serve a customer's history back through a tenant-scoped API. The feature is
**optional and off by default**: with it disabled, no database is needed, none
is contacted, and the pipeline behaves exactly as it does without it.

```
                          Event API
                   validate · normalize · dedupe · resolve identity
                              |
                           RabbitMQ
                              |
             +----------------+-----------------+
             |                |                 |
      amazon-personalize  google-retail   event-storage   <- storage worker
             ...              ...               |
                                            EventStore        (interface)
                                                |
                                  +-------------+-------------+
                                  |                           |
                                  v                           v
                         PostgresEventStore         TimescaleEventStore
                                  |                           |
                          +-------+-------+                   |
                          |               |                   v
                          v               v              TimescaleDB
                        Local           Neon
                      PostgreSQL     PostgreSQL     (or RDS, Supabase, ...)
```

## 1. Storage is optional

```yaml
omnirec:
  storage:
    enabled: false      # the default
```

`omnirec-event-storage` is on the Event API's classpath, but everything it
contributes sits behind `omnirec.storage.enabled=true`. Disabled, it registers no
bean, declares no queue, and opens no connection. It deliberately does not
depend on `spring-jdbc`, so Spring Boot's own `DataSource` auto-configuration
does not activate either. `StorageDisabledTest` starts the real application this
way with no database anywhere and asserts that no storage bean or `DataSource`
exists, that events are still accepted and delivered, and that the history
endpoint does not exist.

## 2. Storage is asynchronous

The Event API never writes to the database. An accepted event is published to
RabbitMQ and the HTTP response returns; a separate consumer writes it later:

```
HTTP request -> validate -> publish to RabbitMQ (confirmed) -> 202
                                   |
                                   +--> omnirec.events.event-storage -> storage worker -> database
```

A slow or unavailable database therefore never adds latency to event ingestion
and never causes a 5xx to a storefront.

## 3. RabbitMQ decouples ingestion from persistence

The storage worker is an ordinary `EventDestination` with the id
`event-storage`. Registering it is all it takes for the existing pipeline to give
it the same durable topology every provider has (see [rabbitmq.md](rabbitmq.md)):

| Queue | Purpose |
| --- | --- |
| `omnirec.events.event-storage` | Main queue, one consumer per `omnirec.processing.concurrency` |
| `omnirec.events.event-storage.retry.<n>` | Retry tiers with exponential delays |
| `omnirec.events.event-storage.dlq` | Events that could not be stored, with the reason in `x-omnirec-failure-reason` |

**Acknowledgement.** The listener container acknowledges a message only when the
worker returns normally, which happens only after the row is committed. On
failure:

| Failure | Behaviour |
| --- | --- |
| Database unreachable, failover, lock timeout, serialization conflict, schema not yet migrated | Retryable. The message moves to the next retry tier. The move is publisher-confirmed *before* the original is acknowledged; if the move cannot be confirmed, the original is negatively acknowledged and redelivered. |
| A row the database will never accept (SQLSTATE class 22 or 23, for example a U+0000 character in a JSON string) | Permanent. The message is dead-lettered immediately instead of occupying all retries. |
| Retries exhausted | Dead-lettered with the reason. Nothing is discarded. |

No second retry mechanism exists; storage reuses the pipeline's. Because storage
has its own queue set, a database outage backs up only storage while provider
delivery continues.

**Idempotency.** RabbitMQ is at-least-once. The dispatcher's per-destination
deduplication catches most redeliveries, and the database is the backstop:
writes are `INSERT ... ON CONFLICT DO NOTHING` against a unique key, so a
redelivered event is a zero-row insert, counted as a duplicate and acknowledged.

**Control events.** `identify` never reaches a provider. It is now published as
a control event that `RabbitEventPublisher` routes only to destinations whose
`supports()` accepts it. No provider destination accepts one, so provider queues
carry exactly the traffic they did before; the storage worker does, because
`identify` carries the identity link that customer history depends on.

## 4. `EventStore` is the abstraction

The storage worker and the history API depend on one interface in
`omnirec-commerce-core`, beside `EventDestination`:

```java
public interface EventStore {
    SaveOutcome save(CommerceEvent event);                        // STORED | DUPLICATE
    CustomerEventPage findCustomerEvents(String tenantId, String customerId, EventQuery query);
}
```

It stores and returns the canonical `CommerceEvent`. There is no second "stored
event" model. Implementations must be idempotent per (tenant, eventId), must
record the identity link an event carries in the same transaction as the event,
must scope every read to exactly the tenant they are given, and must report
failures as `EventStoreException` with a `retryable` flag.

## 5. PostgreSQL is generic

`PostgresEventStore` uses plain JDBC with a private HikariCP pool and Flyway. It
uses no extension and nothing beyond PostgreSQL 9.5 features (`JSONB`,
`ON CONFLICT`, row-value comparison), so the same code runs against any
PostgreSQL deployment. The pool is intentionally not a Spring `DataSource` bean,
so enabling storage can never replace or reconfigure an application's own
database.

## 6. Neon is hosted PostgreSQL

There is no Neon-specific code and no `NeonEventStore`. Neon is PostgreSQL, so
it uses `provider: postgres`, and only the URL changes. The URL may be given in
either form:

```
jdbc:postgresql://ep-example-123.eu-central-1.aws.neon.tech/omnirec?sslmode=require
postgresql://user:password@ep-example-123.eu-central-1.aws.neon.tech/omnirec?sslmode=require
```

The second form, which Neon's console provides, is converted to JDBC and its
embedded credentials are passed to the driver separately (explicit
`username`/`password` settings take precedence). The password is never logged.

Neon notes:

- Prefer the **direct** endpoint for Omnirec over the `-pooler` endpoint. The
  pool is already client-side, and Flyway migrations are best run over a direct
  connection.
- Keep `sslmode=require`.
- A suspended Neon compute can take a moment to wake up. The first write after
  idle may fail and be retried through the normal retry tiers; nothing is lost.

This implementation has been tested against real PostgreSQL 16 servers,
including a connection made through the `postgresql://user:password@host/db` URL
form. It has **not** been run against a live Neon project.

## 7. TimescaleDB is a separate provider

```yaml
omnirec:
  storage:
    provider: timescale
```

`TimescaleEventStore` extends `PostgresEventStore`. Reads and writes are
inherited unchanged; it adds only what requires the extension:

- `commerce_events` becomes a **hypertable** partitioned on `occurred_at`, with
  chunk width `omnirec.storage.timescale.chunk-interval` (default 7 days). The
  width applies when the hypertable is created.
- Retention is a **TimescaleDB retention policy** that drops whole expired chunks
  rather than deleting row by row.

TimescaleDB must be installed on the server. Startup checks
`pg_available_extensions` and fails with a clear message if it is missing, rather
than failing halfway through a migration. Retention policies are a TimescaleDB
Community-licensed feature; on an Apache-2-only build, startup fails with the
database's explanation when retention is configured.

Because a hypertable requires every unique index to include its time column, the
Timescale idempotency key is `(tenant_id, event_id, occurred_at)` rather than
`(tenant_id, event_id)`. For redelivery this makes no difference, because a
redelivered message carries the same timestamp.

The provider is chosen when the database is created. Flyway refuses to open a
database created by one provider with the other, because their `V4` migrations
differ. Converting an existing PostgreSQL store to TimescaleDB is a manual
migration.

## Database schema

Migrations live in `omnirec-event-storage/src/main/resources/db/omnirec-storage/`
and run automatically at startup against the configured schema (default
`omnirec`, created if absent). Their history table is
`omnirec_storage_schema_history`, so it cannot collide with an application's own
Flyway history in a shared database.

| Version | Location | Contents |
| --- | --- | --- |
| V1 | `common/` | `commerce_events` |
| V2 | `common/` | `identity_links` |
| V3 | `common/` | history indexes |
| V4 | `postgres/` | primary key `(tenant_id, event_id)` and a BRIN index on `occurred_at` for retention |
| V4 | `timescale/` | the extension, key `(tenant_id, event_id, occurred_at)`, and the hypertable |

### `commerce_events`

One table for every event type. Fields common to every event, or used for
filtering, are columns; everything type-specific stays in JSONB exactly as the
canonical model holds it, so a new event type never requires a migration.

| Column | Source |
| --- | --- |
| `tenant_id` | from the API key, never the request body |
| `event_id` | `eventId` |
| `event_type` | wire name, for example `product_viewed` |
| `schema_version` | `schemaVersion` |
| `occurred_at` | `timestamp` (the time dimension) |
| `received_at` | `receivedAt`, stamped by the Event API |
| `stored_at` | when the worker wrote it (operational only) |
| `anonymous_id`, `user_id`, `session_id` | `identity`, as captured |
| `product_id` | `commerce.productId`, denormalized |
| `commerce` | JSONB: the full `commerce` object (prices, items, order id...) |
| `properties` | JSONB |
| `context` | JSONB |

Rows are never updated after insert.

### Indexes

Each index exists for a query Omnirec actually runs:

| Index | Serves |
| --- | --- |
| primary key `(tenant_id, event_id)` | idempotent inserts |
| `(tenant_id, user_id, occurred_at DESC, event_id DESC) WHERE user_id IS NOT NULL` | the customer's own events, in keyset order |
| `(tenant_id, anonymous_id, occurred_at DESC, event_id DESC) WHERE user_id IS NULL` | anonymous events of linked devices, in keyset order |
| `identity_links (tenant_id, user_id)` | customer to linked devices |
| BRIN `(occurred_at)` (postgres only) | the retention purge |

Evaluated and deliberately not created: `(tenant_id, event_type, occurred_at)`,
because no query reads events by type across a tenant (the `eventType` filter
operates within one customer's already-narrow history), and an index on
`product_id`, because nothing queries by product yet. The column exists so that
such an index can be added later without a backfill.

## 8. Tenant isolation

- Every row has a non-null `tenant_id`. An event without one is refused
  permanently rather than stored outside every tenant's boundary.
- Uniqueness is per tenant. Event ids are client-supplied, so a global key would
  let one tenant suppress another tenant's event by reusing its id.
- Identity links are keyed by `(tenant_id, anonymous_id)`. Tenant A linking
  `anon_123` to `user_456` has no effect on tenant B's `anon_123`.
- Every history query is scoped by `tenant_id` in both halves of the query and in
  the identity-link subquery.
- The tenant for a read comes only from the credential (see
  [Customer history API](#10-customer-history-api)), never from the path, query,
  or body.

A single store and a multi-tenant platform use the same schema and code; a
single store is a platform with one tenant.

Tests: `RealPostgresEventStoreTest` (tenant isolation, same event id in two
tenants, tenant-scoped links) and `RealStoragePipelineTest` (tenant keys over
HTTP, cross-tenant header, query-string tenant ignored, publishable key refused).

## 9. Identity links

```
identity_links
--------------
tenant_id       \  primary key
anonymous_id    /
user_id
first_seen_at   earliest event time this device was seen linked
linked_at       latest event time the current mapping was asserted
```

The worker records a link from any stored event that names both an
`anonymousId` and a `userId`: an `identify`, a `user_logged_in`, or an event the
Event API resolved to a user. This matches `IdentityResolver`. The link is
written in the same transaction as the event.

Historical events are **not rewritten**. An event captured anonymously keeps
`user_id = NULL` in its row forever; the link is what makes it part of the
customer's journey at query time. See [identity.md](identity.md).

One device maps to one customer at a time. If a device is linked to a different
customer, for example a shared computer, the most recent link wins, by event time
rather than arrival time, so an old event retried late cannot override a newer
link. The previous customer's history then no longer includes that device's
anonymous events; events they generated while signed in remain theirs.

## 10. Customer history API

```
GET /v1/customers/{customerId}/events
Authorization: Bearer <secret key>
```

| Parameter | Description |
| --- | --- |
| `limit` | Page size; default 50, maximum 200 (`omnirec.storage.history-api.*`) |
| `cursor` | The previous page's `nextCursor` |
| `from` | ISO-8601 instant, inclusive |
| `to` | ISO-8601 instant, exclusive |
| `eventType` | Repeatable or comma-separated, for example `eventType=product_viewed,product_added_to_cart` |

```json
{
  "customerId": "user_456",
  "events": [
    {
      "eventId": "e101",
      "eventType": "product_viewed",
      "occurredAt": "2026-09-28T08:15:20Z",
      "receivedAt": "2026-09-28T08:15:20.412Z",
      "anonymousId": "anon_123",
      "sessionId": "s_1",
      "productId": "P999",
      "commerce": { "productId": "P999" },
      "properties": {},
      "context": { "url": "https://shop.example/p/P999", "platform": "web", "device": "mobile" }
    }
  ],
  "nextCursor": "MXwxNzU5MDQ3..."
}
```

`nextCursor` is absent on the last page. An event without `userId` is an
anonymous event attributed to the customer through an identity link. IP
addresses and user agents are never returned.

How a request is answered:

1. `CustomerHistoryAuthFilter` authenticates the secret key.
2. It determines the one tenant the request reads (below).
3. The store resolves the customer's linked anonymous ids from `identity_links`.
4. It reads the customer's authenticated events.
5. It reads the linked devices' anonymous events.
6. It merges both, newest first, with `UNION ALL`. The halves are disjoint
   (`user_id = ?` compared with `user_id IS NULL`), so the merge cannot duplicate.
7. It applies filters to both halves.
8. It applies keyset pagination on `(occurred_at DESC, event_id DESC)`. The event
   id breaks ties, so events that share a timestamp are neither repeated nor
   skipped across page boundaries, and a page costs the same regardless of its
   position. The cursor is an opaque, versioned token.
9. It returns a DTO rather than a database row.

An unknown customer receives an empty page, not a 404: the API cannot distinguish
"no such customer" from "no history yet". An invalid `customerId` (empty, longer
than 256 characters, or containing control characters), cursor, limit, time, or
event type receives a 400 that does not echo the value.

### Authentication

Reading history is a different privilege from writing events. The publishable
`api-key` is in every storefront's JavaScript, so it can never read: it is
refused by this endpoint, and startup fails if any read key equals a publishable
key.

```yaml
omnirec:
  events:
    tenants:
      tenant_A:
        api-key: pk_live_...                 # publishable, write-only
        secret-key: ${TENANT_A_SECRET_KEY}   # server-side, reads tenant_A only
  storage:
    history-api:
      platform-keys:                         # optional: one key, several tenants
        ops:
          key: ${OMNIREC_PLATFORM_READ_KEY}
          tenants: [tenant_A, tenant_B]
```

| Request | Result |
| --- | --- |
| Tenant secret key | Reads that tenant. `X-Omnirec-Tenant` may be omitted |
| Tenant secret key with `X-Omnirec-Tenant` naming another tenant | 403 |
| Platform key with `X-Omnirec-Tenant` naming one of its tenants | Reads that tenant |
| Platform key with another tenant, or without the header | 403, or 400 |
| Publishable key, unknown key, missing key, or key in the query string | 401 |
| Key of a tenant with `enabled: false` | 401 (a platform key loses that tenant) |

The endpoint has no CORS mapping. It is server-to-server only.

## 11. Retention

```yaml
omnirec:
  storage:
    retention:
      max-age: 400d          # unset: keep indefinitely (logged at startup)
      purge-interval: 1h     # postgres only
      purge-batch-size: 5000 # postgres only
```

| Provider | Mechanism |
| --- | --- |
| `postgres` | A background job deletes events whose `occurred_at` is older than `max-age`, committing each batch separately so that no long lock is held. It runs on each instance, which is harmless. |
| `timescale` | A TimescaleDB retention policy drops whole chunks. It is re-applied from configuration at every startup, so changing or removing `max-age` changes or removes the policy. |

Retention is measured by event time. Identity links are not purged: they are
small, and a link that outlives its events is harmless. This is a storage setting
only and implies no plan or billing logic.

## 12. Local development

`docker-compose.yml` defines one database per provider under a Compose profile,
so neither starts unless selected. The host ports default to 5433 and 5434 to
avoid a PostgreSQL already listening on 5432.

**Option A: OmniRec, RabbitMQ, and local PostgreSQL**

```bash
export OMNIREC_STORAGE_ENABLED=true
export OMNIREC_DATABASE_URL=jdbc:postgresql://postgres:5432/omnirec
export DEMO_STORE_SECRET_KEY=sk_test_$(openssl rand -hex 16)
docker compose --profile postgres up -d
```

**Option B: OmniRec, RabbitMQ, and Neon (external)**

```bash
export OMNIREC_STORAGE_ENABLED=true
export OMNIREC_DATABASE_URL='postgresql://user:password@ep-example-123.eu-central-1.aws.neon.tech/omnirec?sslmode=require'
export DEMO_STORE_SECRET_KEY=sk_test_$(openssl rand -hex 16)
docker compose up -d                       # no database container
```

**Option C: OmniRec, RabbitMQ, and TimescaleDB**

```bash
export OMNIREC_STORAGE_ENABLED=true
export OMNIREC_STORAGE_PROVIDER=timescale
export OMNIREC_DATABASE_URL=jdbc:postgresql://timescaledb:5432/omnirec
export DEMO_STORE_SECRET_KEY=sk_test_$(openssl rand -hex 16)
docker compose --profile timescale up -d
```

Running the Event API from an IDE instead of Compose, use the host port:
`jdbc:postgresql://localhost:5433/omnirec` or `...:5434/omnirec`.

Then:

```bash
curl -H "Authorization: Bearer $DEMO_STORE_SECRET_KEY" \
  "http://localhost:8081/v1/customers/customer_123/events?limit=20"
```

The Compose password default (`omnirec_local_only`) is for a throwaway local
container. Set `OMNIREC_DB_PASSWORD`, for example in an untracked `.env`, for
anything else, and never commit a real database URL or secret key.

## 13. External PostgreSQL and Neon configuration

```yaml
# Local PostgreSQL
omnirec:
  storage:
    enabled: true
    provider: postgres
    postgres:
      url: jdbc:postgresql://localhost:5433/omnirec
      username: ${OMNIREC_DB_USERNAME}
      password: ${OMNIREC_DB_PASSWORD}
```

```yaml
# Neon, or any hosted PostgreSQL (RDS, Supabase, ...)
omnirec:
  storage:
    enabled: true
    provider: postgres
    postgres:
      url: ${OMNIREC_DATABASE_URL}     # postgresql://user:pass@host/db?sslmode=require
```

```yaml
# TimescaleDB
omnirec:
  storage:
    enabled: true
    provider: timescale
    postgres:
      url: ${OMNIREC_DATABASE_URL}
    retention:
      max-age: 400d
```

```yaml
# Disabled (the default)
omnirec:
  storage:
    enabled: false
```

For deployments that apply migrations as a separate, privileged step, set
`omnirec.storage.migrate-on-startup: false`. Startup then validates the schema
instead of changing it.

The full property reference is in [configuration.md](configuration.md#omnirecstorage).

## Observability

| Metric | Tags | Meaning |
| --- | --- | --- |
| `omnirec.storage.events.received` | `tenant` | Events handed to the worker |
| `omnirec.storage.events.persisted` | `tenant` | Rows written |
| `omnirec.storage.events.duplicates` | `tenant` | Redeliveries absorbed by the unique key |
| `omnirec.storage.events.failed` | `tenant`, `reason` | `transient`, `permanent`, or `unexpected` |
| `omnirec.storage.write.duration` | | Time in `EventStore.save` |
| `omnirec.storage.lag` | | From Event API receipt to commit |
| `omnirec.storage.history.duration` | | History query time |
| `hikaricp.connections.*` | `pool=omnirec-storage` | Connection pool |

Retries, dead-lettering, and queue depth are reported by the pipeline's existing
metrics with `destination=event-storage`. Alert on
`omnirec.queue.depth{destination="event-storage",queue="dlq"}`.

Logs carry event ids, event types, and tenant ids only, never payloads. The
driver is configured with `logServerErrorDetail=false`, so PostgreSQL error
details, which can quote row values, do not reach logs or the dead-letter reason
header. History reads log the key's name (`tenant:<id>` or `platform:<name>`),
never the key and never the customer id.

## Limitations

- **Neon was not tested live.** The code path is plain PostgreSQL and was tested
  against PostgreSQL 16, including the libpq URL form.
- **Switching providers on an existing database** is a manual migration; Flyway
  refuses it by design.
- **One device, one customer.** Re-linking a device moves its anonymous history
  to the newest customer.
- **Storage uniqueness on TimescaleDB includes `occurred_at`.** An event id
  re-sent *after* the 24-hour ingestion deduplication window with a different
  timestamp would be stored twice on TimescaleDB. It would not be on PostgreSQL.
- **No rate limit on history reads.** The key is a server-side secret; put the
  endpoint behind the same network controls as any internal API.
- **The chunk interval is fixed at hypertable creation.**
- **`omnirec.processing.queue-enabled=false`** (development only) calls every
  destination inline, storage included, so in that mode the database write
  happens during the HTTP request. Deployments keep the queue enabled.
