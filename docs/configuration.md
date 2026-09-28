# Configuration

## Event API (`omnirec-event-api-app`)

```yaml
server:
  port: 8081

spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    publisher-confirm-type: correlated
    publisher-returns: true

omnirec:
  events:
    tenants:
      demo-store:
        api-key: ${DEMO_STORE_API_KEY}    # publishable, safe in frontend code
        enabled: true
    max-batch-size: 500
    max-payload-bytes: 1048576
    deduplication-window: PT24H
    retain-ip-address: false
    rate-limit:
      enabled: true
      requests-per-window: 300
      window: PT1M
    cors:
      allowed-origins:
        - https://shop.example.com

  processing:
    queue-enabled: true
    max-retries: 5
    retry-initial-interval: 1s
    retry-max-interval: 5m
    delivery-lease: 2m
    confirm-timeout: 10s
    concurrency: 2
    prefetch-count: 10
    deduplication-window: PT24H

  # Required for deployments of more than one instance; see below.
  state:
    redis:
      enabled: true
      host: ${REDIS_HOST}
      port: 6379
      identity-link-ttl: P365D

  destinations:
    amazon-personalize:
      enabled: false
      region: us-east-1
      tracking-id: ${AWS_PERSONALIZE_TRACKING_ID:}
    google-retail:
      enabled: false
      project-number: ${GOOGLE_PROJECT_NUMBER:}
      location: global
      catalog-id: default_catalog
    recently-viewed:
      enabled: false
      host: ${CACHE_REDIS_HOST}     # the serving application's cache Redis
      tenant-id: demo-store
```

### `omnirec.events`

| Property | Default | Description |
| --- | --- | --- |
| `tenants.<id>.api-key` | none | Publishable key for the tenant |
| `tenants.<id>.secret-key` | none | Server-side key that reads this tenant's history (`omnirec.storage`). Must differ from every publishable key, or startup fails |
| `tenants.<id>.enabled` | `true` | Whether the tenant may submit events |
| `allow-anonymous-ingestion` | `false` | Disables tenant isolation. Startup fails outside the `dev`, `test`, and `local` profiles |
| `default-tenant-id` | `default` | Used only with anonymous ingestion |
| `max-batch-size` | 500 | Larger batches are rejected with 413 |
| `max-payload-bytes` | 1048576 | Maximum request body size |
| `deduplication-window` | PT24H | Retention period for an `eventId` |
| `retain-ip-address` | `false` | Retain the client IP address after geolocation |
| `rate-limit.enabled` | `true` | Enables rate limiting |
| `rate-limit.requests-per-window` | 300 | Per tenant, per client address |
| `rate-limit.window` | PT1M | Rate-limit window |
| `cors.allowed-origins` | `[]` | Permitted storefront origins |

### `omnirec.state.redis`

Shared deduplication and identity links. Required for any deployment running
more than one Event API instance: the in-memory defaults are per-process, so
without shared state the same event may be delivered once per instance and
identity links recorded by one instance are not visible to the others. Both
in-memory stores log a warning when they are active.

| Property | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Enables the shared stores |
| `host`, `port` | `localhost`, 6379 | Redis connection |
| `password`, `database` | none | Optional Redis credentials and database index |
| `identity-link-ttl` | P365D | Links are long-lived; the time-to-live only expires abandoned devices |

This configuration is deliberately separate from `omnirec.cache.redis`, the
serving-side cache. The two may address different instances, because the loss of
a cached recently-viewed list is inconsequential whereas the loss of identity
links is not.

### `omnirec.processing`

| Property | Default | Description |
| --- | --- | --- |
| `queue-enabled` | `true` | When `false`, destinations are called inline. Development only |
| `max-retries` | 5 | Retry tiers before dead-lettering |
| `retry-initial-interval` | 1s | Doubles on each attempt |
| `retry-max-interval` | 5m | Upper bound on the delay |
| `delivery-lease` | 2m | Must exceed the slowest provider call |
| `confirm-timeout` | 10s | Wait period for the broker publisher confirm |
| `concurrency` | 2 | Consumer threads per destination |
| `prefetch-count` | 10 | Intentionally low; see rabbitmq.md |
| `deduplication-window` | PT24H | Applied per destination |

Publisher confirms are mandatory. The Event API refuses to start unless
`spring.rabbitmq.publisher-confirm-type=correlated` and
`spring.rabbitmq.publisher-returns=true` are set.

### `omnirec.storage`

Optional historical event storage. See [event-storage.md](event-storage.md).
With `enabled: false` nothing below is read and no database is contacted.

| Property | Default | Description |
| --- | --- | --- |
| `enabled` | `false` | Enables the storage worker, the `event-storage` queue, and the history API |
| `provider` | `postgres` | `postgres` (any PostgreSQL: local, Neon, RDS, Supabase) or `timescale` |
| `postgres.url` | none | `jdbc:postgresql://...` or `postgresql://user:pass@host/db`. Required when enabled |
| `postgres.username`, `postgres.password` | none | Override credentials embedded in the URL. Supply through environment variables |
| `postgres.schema` | `omnirec` | Schema for the storage tables; created if absent |
| `postgres.maximum-pool-size` | 5 | Private connection pool; not an application `DataSource` |
| `postgres.connection-timeout` | 10s | |
| `migrate-on-startup` | `true` | When `false`, the schema is validated instead of migrated |
| `timescale.chunk-interval` | 7d | Hypertable chunk width, applied at creation |
| `retention.max-age` | none (keep indefinitely) | For example `400d`. Batched purge on `postgres`; retention policy on `timescale` |
| `retention.purge-interval` | 1h | `postgres` only |
| `retention.purge-batch-size` | 5000 | `postgres` only |
| `history-api.enabled` | `true` | `GET /v1/customers/{customerId}/events` |
| `history-api.default-limit`, `max-limit` | 50, 200 | Page size |
| `history-api.platform-keys.<name>.key` | none | A read key for several tenants, selected per request with `X-Omnirec-Tenant` |
| `history-api.platform-keys.<name>.tenants` | none | The tenants that key may read; unknown names fail startup |

### Destinations

| Property | Default | Description |
| --- | --- | --- |
| `amazon-personalize.property-keys` | `[]` | Keys from the interactions schema to include in `properties` |
| `amazon-personalize.endpoint-override` | none | For LocalStack or a capture server only |
| `recently-viewed.enabled` | `false` | Populates the serving API's `/v1/recently-viewed` |
| `recently-viewed.host`, `port`, `password`, `database` | `localhost`, `6379` | The serving application's cache Redis (`omnirec.cache.redis`), which need not be the state Redis |
| `recently-viewed.tenant-id` | all tenants | Set when more than one tenant submits events, because the serving keys carry no tenant |
| `recently-viewed.max-items`, `ttl` | `20`, `P30D` | Match the serving side's list length and retention |

### Deployment behind a load balancer

```yaml
server:
  forward-headers-strategy: native
```

This allows rate limiting and geolocation to observe the originating client
address, with forwarded headers trusted only from internal proxy addresses.

### Legacy properties

| Property | Default | Description |
| --- | --- | --- |
| `omnirec.web.legacy-ingestion.enabled` | `false` | The serving API's previous unauthenticated `/v1/events` endpoint. See security.md. Recently-viewed no longer depends on it; use the `recently-viewed` destination instead. The two must not be enabled together, because the destination rebuilds each list from its own index, so a view written only by the legacy path is removed at the next write. |

## Provider credentials

Provider credentials must never be placed in configuration files. Each provider
uses its own platform mechanism.

### AWS

```bash
AWS_REGION=us-east-1
AWS_PERSONALIZE_TRACKING_ID=...
# Local development only; production uses an IAM role:
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
```

Credentials are resolved by the AWS default provider chain. The destination
exposes no `access-key` property, so there is no supported means of committing a
long-lived secret.

### Google

```bash
GOOGLE_PROJECT_NUMBER=123456789
# Local development only; production uses workload identity:
GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json
```

Credentials are resolved by Application Default Credentials.

### Secret managers

Both credential chains already support the production path, namely IAM roles and
workload identity, without code changes. For other arrangements, the Spring
Cloud AWS Secrets Manager or GCP Secret Manager property sources can be added as
an additional `PropertySource`; no application code depends on the origin of a
value.

## Frontend

```bash
NEXT_PUBLIC_OMNIREC_ENDPOINT=https://events.example.com
NEXT_PUBLIC_OMNIREC_API_KEY=pk_live_xxxxx
NEXT_PUBLIC_OMNIREC_TENANT_ID=my-store
```

The `NEXT_PUBLIC_` prefix is appropriate because the key is intended to be
public. The complete option table is in [frontend-sdk.md](frontend-sdk.md).

## Server-side SDK

```yaml
omnirec:
  tracker:
    enabled: true
    endpoint: https://events.example.com
    api-key: ${OMNIREC_API_KEY}
    tenant-id: my-store
    async: true
    queue-capacity: 10000
    max-batch-size: 50
    validate-events: true
```

See [spring-boot-sdk.md](spring-boot-sdk.md).

## Production checklist

- [ ] A distinct `api-key` per tenant, with `allow-anonymous-ingestion` disabled
- [ ] `cors.allowed-origins` restricted to production storefront origins
- [ ] RabbitMQ reachable, with publisher confirms and returns enabled
- [ ] `omnirec.state.redis.enabled=true` for deployments of more than one
      instance, because the in-memory defaults are per-process and log a warning
      when active
- [ ] Provider credentials supplied by IAM role or workload identity rather than
      environment variables
- [ ] `retain-ip-address` disabled unless there is a documented requirement and
      retention policy
- [ ] `/actuator/prometheus` scraped; see the metric list in the README
- [ ] Alerts configured on `omnirec.events.failed` and dead-letter queue depth
- [ ] If historical storage is enabled: database credentials from a secret
      manager or environment, `retention.max-age` set deliberately, a distinct
      `secret-key` per tenant kept server-side only, and an alert on the
      `event-storage` dead-letter queue

## Configuration validation

Invalid configuration fails immediately throughout the system:

- The frontend SDK throws on a missing `apiKey`, an `apiKey` resembling a secret
  credential, a relative `endpoint`, or a non-positive batch size.
- The server-side SDK throws if `endpoint` is unset. Set `enabled: false` to
  disable tracking deliberately.
- The Event API refuses to start with `allow-anonymous-ingestion` outside a
  development profile, and logs a prominent warning when no tenant keys are
  configured.

A component that silently performs no operation because of a configuration error
is considerably worse than one that fails during development.
