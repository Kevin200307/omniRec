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
        api-key: ${DEMO_STORE_API_KEY}    # publishable; safe in frontend code
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

  # Required for more than one instance; see below.
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
      host: ${CACHE_REDIS_HOST}     # the serving app's cache Redis
      tenant-id: demo-store
```

### `omnirec.events`

| Property | Default | Notes |
|---|---|---|
| `tenants.<id>.api-key` | — | Publishable key for that tenant |
| `tenants.<id>.enabled` | `true` | |
| `allow-anonymous-ingestion` | `false` | Disables tenant isolation; **startup fails** outside `dev`/`test`/`local` |
| `default-tenant-id` | `default` | Only used with the above |
| `max-batch-size` | 500 | Larger batches get 413 |
| `max-payload-bytes` | 1048576 | |
| `deduplication-window` | PT24H | How long an `eventId` is remembered |
| `retain-ip-address` | `false` | Keep the client IP after geo lookup |
| `rate-limit.enabled` | `true` | |
| `rate-limit.requests-per-window` | 300 | Per tenant **per client IP** |
| `rate-limit.window` | PT1M | |
| `cors.allowed-origins` | `[]` | Storefront origins |

### `omnirec.state.redis`

Shared deduplication and identity links. **Required for any deployment running
more than one Event API instance** — the in-memory defaults are per-process, so
without this the same event can be delivered once per instance and one
instance's identity links are invisible to the others. Both in-memory stores log
a warning when they are the ones active.

| Property | Default | Notes |
|---|---|---|
| `enabled` | `false` | |
| `host` / `port` | `localhost` / 6379 | |
| `password`, `database` | — | |
| `identity-link-ttl` | P365D | Links are long-lived by nature; the TTL only ages out abandoned devices |

Deliberately separate from `omnirec.cache.redis` (the serving-side cache): the
two may point at different instances, because losing a cached recently-viewed
list is harmless and losing identity links is not.

### `omnirec.processing`

| Property | Default | Notes |
|---|---|---|
| `queue-enabled` | `true` | `false` calls destinations inline — dev only |
| `max-retries` | 5 | Retry tiers, then the DLQ |
| `retry-initial-interval` | 1s | Doubles per attempt |
| `retry-max-interval` | 5m | Cap on the delay |
| `delivery-lease` | 2m | Must outlast the slowest provider call |
| `confirm-timeout` | 10s | Wait for the broker's publisher confirm |
| `concurrency` | 2 | Consumer threads per destination |
| `prefetch-count` | 10 | Low on purpose; see rabbitmq.md |
| `deduplication-window` | PT24H | Per destination |

Publisher confirms are required: the Event API refuses to start without
`spring.rabbitmq.publisher-confirm-type=correlated` and
`spring.rabbitmq.publisher-returns=true`.

### Destinations

| Property | Default | Notes |
|---|---|---|
| `amazon-personalize.property-keys` | `[]` | Keys from your interactions schema to send in `properties` |
| `amazon-personalize.endpoint-override` | — | LocalStack or a capture server only |
| `recently-viewed.enabled` | `false` | Feeds the serving API's `/v1/recently-viewed` |
| `recently-viewed.host` / `port` / `password` / `database` | `localhost` / `6379` | The **serving app's cache** Redis (`omnirec.cache.redis`), which need not be the state Redis |
| `recently-viewed.tenant-id` | all tenants | Set it when more than one tenant sends events: the serving keys carry no tenant |
| `recently-viewed.max-items` / `ttl` | `20` / `P30D` | Match the serving side's list length and retention |

### Behind a load balancer

```yaml
server:
  forward-headers-strategy: native
```

So the rate limit and geo see the real client address, with forwarded headers
trusted only from internal proxies.

### Legacy

| Property | Default | Notes |
|---|---|---|
| `omnirec.web.legacy-ingestion.enabled` | `false` | The serving API's old unauthenticated `/v1/events`. See security.md. Recently-viewed no longer needs it: use the `recently-viewed` destination. Don't run both, because the destination rebuilds each list from its own index, so a view written only by the legacy path is dropped at the next write. |

## Provider credentials

**Never in configuration files.** Each provider uses its own platform mechanism:

### AWS

```bash
AWS_REGION=us-east-1
AWS_PERSONALIZE_TRACKING_ID=...
# Local development only — production uses an IAM role:
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
```

Resolved by the AWS default provider chain. There is no `access-key` property on
the destination, so there is no supported way to commit a long-lived secret.

### Google

```bash
GOOGLE_PROJECT_NUMBER=123456789
# Local development only — production uses workload identity:
GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json
```

Resolved by Application Default Credentials.

### Secret managers

Both chains already support the production path (IAM roles, workload identity)
with no code change. For anything else, Spring Cloud AWS Secrets Manager or GCP
Secret Manager property sources drop in as an additional `PropertySource` — no
application code is aware of where a value came from.

## Frontend

```bash
NEXT_PUBLIC_OMNIREC_ENDPOINT=https://events.example.com
NEXT_PUBLIC_OMNIREC_API_KEY=pk_live_xxxxx
NEXT_PUBLIC_OMNIREC_TENANT_ID=my-store
```

`NEXT_PUBLIC_` is correct: this key is meant to be public. See
[frontend-sdk.md](frontend-sdk.md) for the full option table.

## Backend SDK

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

- [ ] A real `api-key` per tenant; `allow-anonymous-ingestion` off
- [ ] `cors.allowed-origins` restricted to your storefronts
- [ ] RabbitMQ reachable, with confirms and returns on
- [ ] `omnirec.state.redis.enabled=true` if running more than one instance — the
      in-memory defaults are per-process and both log a warning when active
- [ ] Provider credentials via IAM role / workload identity, not env vars
- [ ] `retain-ip-address` left off unless you have a reason and a retention policy
- [ ] `/actuator/prometheus` scraped — see the counters in the README
- [ ] Alert on `omnirec.events.failed` and dead-letter queue depth

## Configuration validation

Bad configuration fails fast, everywhere:

- The frontend SDK throws on a missing/secret-looking `apiKey`, a relative
  `endpoint`, or a non-positive batch size.
- The backend SDK throws if `endpoint` is unset (set `enabled: false` to disable
  tracking deliberately).
- The Event API refuses to start with `allow-anonymous-ingestion` outside a
  development profile, and warns loudly when no tenant keys are configured.

A component that silently no-ops because of a typo is far worse than one that
fails while you're looking at it.
