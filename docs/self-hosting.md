# Self-hosting

Omnirec is one service, the collector (`omnirec-event-api-app`), plus the
infrastructure you choose to give it. There are two ready-made profiles.

| | Lite | Standard |
| --- | --- | --- |
| Command | `docker compose -f deploy/lite/docker-compose.yml up -d --build` | `docker compose -f deploy/standard/docker-compose.yml up -d --build` |
| Containers | collector | collector, RabbitMQ, Redis (+ PostgreSQL with `--profile storage`) |
| Delivery | inline, no retries | durable queues, retry tiers, dead-letter queues |
| State | in memory, lost on restart | Redis: deduplication, identity links, derived-event timers |
| Instances | one | as many as you like |
| Good for | a trial, staging, a small store sending to storage or webhooks you can afford to lose | production |

Both serve the collector on port 8081 (`OMNIREC_PORT` changes it), with
health at `/actuator/health`.

## 1. Choose how browsers authenticate

| Mode | Set | Use |
| --- | --- | --- |
| `open` | `omnirec.events.auth-mode: open` | One store. No key. Browsers must come from `omnirec.events.cors.allowed-origins`, or from the collector's own origin through a `/omnirec` path on your site. Keyless events go to `default-tenant-id`. |
| `keys` | `auth-mode: keys` and a publishable key per tenant | Several stores in one collector, or when you want a key anyway. |
| `auto` (default) | | `keys` when any tenant has a key, otherwise `open`. |

The lite profile runs `open`. The simplest secure setup in either mode is a
same-origin path: proxy `https://shop.example.com/omnirec/*` to the collector,
and use `createOmnirec({ endpoint: "/omnirec" })`. The browser then makes no
cross-site request, and ad blockers that block third-party trackers leave it
alone.

```nginx
location /omnirec/ {
    proxy_pass http://omnirec:8081/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

Behind a proxy, set `server.forward-headers-strategy: native` so rate limits
apply to the real client address.

## 2. Single store or many

**Single store.** Nothing to configure in open mode. To add a tracking plan:

```yaml
omnirec:
  events:
    default-plan-paths: [file:/config/omnirec.plan.yaml]
```

**Several stores (multi-tenant).** One tenant per store, from configuration or
a database table:

```yaml
omnirec:
  events:
    auth-mode: keys
    tenants:
      store-a:
        api-key: ${STORE_A_PK}              # publishable, goes in the storefront
        secret-key: ${STORE_A_SK}           # server-side: history and deletion
        allowed-origins: [https://store-a.example]
        plan-paths: [file:/config/store-a.plan.yaml]
        validation-mode: strict             # reject events outside catalog + plan
      store-b:
        api-key: ${STORE_B_PK}
```

With `tenant-source: jdbc`, tenants live in the `omnirec_tenant` table, with
keys stored as SHA-256 hashes. Changes are picked up every 30 seconds, without
a restart. Every event, queue message, stored row, identity link, derived
timer and webhook secret is scoped to its tenant.

## 3. Turn on what you need

| Capability | Setting | Docs |
| --- | --- | --- |
| History, customer deletion, retention | `OMNIREC_STORAGE_ENABLED=true` (+ `--profile storage`, or an external PostgreSQL / Neon / TimescaleDB URL) | [event-storage.md](event-storage.md) |
| Abandoned carts, return visits, new/repeat customers | `DERIVED_EVENTS_ENABLED=true` (on in standard) | [derived-events.md](derived-events.md) |
| Stripe disputes and refunds | `STRIPE_WEBHOOKS_ENABLED=true`, `STRIPE_WEBHOOK_SECRET` | [webhooks.md](webhooks.md) |
| Your own systems | `omnirec.destinations.webhook.endpoints.*` | [webhooks.md](webhooks.md#outbound-webhooks) |
| Amazon Personalize / Google Retail | `PERSONALIZE_ENABLED`, `GOOGLE_RETAIL_ENABLED` + credentials | [amazon-personalize.md](amazon-personalize.md), [google-retail.md](google-retail.md) |

Settings that are not environment variables go in an `application.yml`
mounted into the container, with `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/`.

## 4. Operate it

- **Health and metrics:** `/actuator/health` and `/actuator/prometheus`.
- **Dead letters:** expose the endpoint
  (`management.endpoints.web.exposure.include: health,prometheus,deadletters`)
  on a management port that is not public. `GET /actuator/deadletters` lists
  what is waiting. `POST /actuator/deadletters/{destination}` with
  `{"dryRun": false}` sends it back once the cause is fixed.
- **Deleting a customer:**
  `curl -X DELETE -H "Authorization: Bearer $SECRET_KEY" https://collector/v1/customers/{id}`.
  It returns a receipt. Copies already delivered to providers must be deleted
  there.
- **Retention:** `OMNIREC_STORAGE_RETENTION=400d`, and per tenant
  `omnirec.storage.retention.tenants.<id>: 90d`.
- **Scaling (standard):** run more collector containers behind a load balancer.
  Queues, Redis and the database are shared; derived-event timers fire on one
  instance only.

## Production checklist

- [ ] RabbitMQ, Redis and database passwords set, not the `*_local_only` defaults
- [ ] `allowed-origins` lists exactly your storefronts
- [ ] TLS in front of the collector
- [ ] `server.forward-headers-strategy: native` behind a proxy
- [ ] Management endpoints not exposed publicly
- [ ] Storage: retention set deliberately; secret keys only on servers
- [ ] Alerts on `omnirec.events.failed` and dead-letter queue depth
