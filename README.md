# Omnirec

Provider-independent commerce event tracking. Omnirec collects customer
interactions once, normalizes them into a canonical schema, resolves the
identity each event belongs to, and delivers them to Amazon Personalize, Google
Cloud Retail, or any destination added later, without provider credentials ever
being present in the browser.

```
Capture -> Normalize -> Resolve identity -> Queue -> Transform -> Deliver
```

Omnirec is infrastructure rather than an analytics product. It provides no
dashboard, no reporting interface, and no machine learning model of its own.

## License

Omnirec is licensed under the [Apache License, Version 2.0](./LICENSE).

## Status

Version 0.1.0. The pipeline, both SDKs, and the Amazon Personalize and Google
Retail adapters are implemented and covered by automated tests, including tests
that run against a real RabbitMQ broker and a real Redis instance. The provider
adapters have not yet been executed against live provider accounts; see
[Known limitations](docs/guide.md#10-known-limitations).

## Repository layout

```
packages/
  commerce-web/         @omnirec/commerce-web    browser SDK, no React dependency
  commerce-react/       @omnirec/commerce-react  React bindings
  core/ react/ react-ui/ create-omnirec-app/     serving-side packages

backend/
  omnirec-commerce-core/                    canonical event, taxonomy, validation,
                                            identity linking, deduplication,
                                            EventDestination interface
  omnirec-event-api/                        gateway: authentication, rate limiting,
                                            normalization, validation, deduplication,
                                            identity resolution
  omnirec-event-processing/                 RabbitMQ topology, consumers, dispatcher,
                                            retry tiers, dead-letter queues
  omnirec-amazon-personalize-destination/   Amazon Personalize adapter
  omnirec-google-retail-destination/        Google Retail adapter
  omnirec-recently-viewed-destination/      recently-viewed lists for the serving side
  omnirec-redis-state/                      shared deduplication and identity links
  omnirec-event-storage/                    optional historical storage: storage worker,
                                            PostgreSQL/TimescaleDB EventStore, customer
                                            history API
  commerce-tracker-spring-boot/             server-side SDK for business events
  omnirec-event-api-app/                    deployable Event API service

  omnirec-core/ omnirec-web/ and related    serving side: recommendations, search,
                                            recently viewed
  omnirec-contract-tests/                   cross-language schema parity tests

examples/nextjs-demo-store/                 reference Next.js storefront
schema/commerce-event.schema.json           the wire contract
scripts/verify-bundle-security.mjs          fails the build if a credential is bundled
scripts/e2e/run.sh                          end-to-end verification with live components
docs/                                       architecture, identity, security, operations
```

## Quick start

Requirements: Docker, Node.js 20 or later, and JDK 17 with Maven for backend
builds.

```bash
docker-compose up -d           # RabbitMQ, Redis, Event API (8081), serving API (8080)

npm install
cd examples/nextjs-demo-store && cp .env.local.example .env.local && cd ../..
npx turbo run dev --filter=nextjs-demo-store
```

Open `http://localhost:3000`. The demo storefront displays the identity in use
and the events produced. Batches are visible in the browser network panel, and
queue activity in the RabbitMQ management interface at `http://localhost:15672`
(default credentials `guest` / `guest`).

All destinations are disabled by default, so the stack runs end to end without a
cloud account.

## Usage

Browser:

```js
import { createCommerceClient } from "@omnirec/commerce-web";

const commerce = createCommerceClient({
  apiKey: "pk_live_xxxxx",             // publishable key, safe in browser code
  endpoint: "https://events.example.com",
});

commerce.product.viewed({ productId: "p123", price: 1500, currency: "USD" });
commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 2 });
commerce.user.loggedIn({ userId: "customer_123" });
```

The caller does not supply `anonymousId`, `sessionId`, `eventId`, `timestamp`,
or page context. The SDK attaches these fields.

Purchases are reported from the merchant backend, where the payment result is
known:

```java
commerce.purchase.completed(PurchaseCompleted.builder()
        .orderId(order.getId())
        .userId(order.getCustomerId())
        .anonymousId(order.getTrackingAnonymousId())
        .items(lines).total(order.getTotal()).currency("USD")
        .build());
```

See the [guide](docs/guide.md) for complete integration instructions.

## Design principles

**Credentials are never present in the browser.** The frontend holds a
publishable key whose only capability is submitting events for its own tenant.
`scripts/verify-bundle-security.mjs` fails the build if a provider credential or
SDK appears in a bundle.

**The core has no knowledge of providers.** `omnirec-commerce-core` depends only
on Jackson and SLF4J. Supporting an additional provider requires one
`EventDestination` implementation registered as a bean; the core, gateway,
queue layer, and both SDKs are unchanged. The constraint is enforced by the
module graph rather than by convention.

**History is never rewritten.** Authentication records a link from the anonymous
identifier to the user identifier. Previously captured events are not modified,
so attribution is performed as a join and the information genuinely available at
capture time is preserved.

**Provider outages do not cause event loss.** The system uses durable queues,
per-destination retry queues, dead-letter queues, and idempotent consumers. An
outage affecting one provider backs up only that provider's queues.

**Historical storage is optional and asynchronous.** With
`omnirec.storage.enabled=true`, a storage worker consumes its own RabbitMQ queue
and writes every event to PostgreSQL (local, Neon, or any hosted PostgreSQL) or
TimescaleDB through the `EventStore` interface, and
`GET /v1/customers/{customerId}/events` returns a customer's journey, including
linked anonymous history, to a caller holding that tenant's secret key. Disabled,
which is the default, no database is required. See
[event-storage.md](docs/event-storage.md).

**Authoritative events originate server-side.** A confirmation page may be
reloaded, bookmarked, or never rendered. Purchases, refunds, and accepted
reviews are reported from the merchant backend.

## Building and testing

```bash
npm install && npx turbo run build test typecheck   # 160 frontend tests
cd backend && mvn clean install                     # 406 backend tests
node scripts/verify-bundle-security.mjs
bash scripts/e2e/run.sh                             # end-to-end verification, requires Docker
```

Tests that require a real RabbitMQ broker or Redis instance use Testcontainers
and are skipped automatically when Docker is unavailable. See
[testing.md](docs/testing.md).

## Observability

Structured logs and Micrometer metrics are exposed at `/actuator/prometheus`:

```
omnirec.events.received        omnirec.events.processed
omnirec.events.validated       omnirec.events.failed
omnirec.events.rejected        omnirec.events.duplicates
omnirec.events.queued          omnirec.provider.delivery.success
                               omnirec.provider.delivery.failure
```

With historical storage enabled, additionally `omnirec.storage.events.received`,
`.persisted`, `.duplicates`, and `.failed`, plus `omnirec.storage.write.duration`,
`omnirec.storage.lag`, and `omnirec.storage.history.duration`. See
[event-storage.md](docs/event-storage.md#observability).

Logs record event identifiers and field names only. Payloads and field values
are never logged.

## Documentation

| Document | Contents |
| --- | --- |
| [guide.md](docs/guide.md) | Recommended starting point: architecture and complete usage instructions |
| [architecture.md](docs/architecture.md) | Modules, pipeline order, failure behaviour |
| [event-schema.md](docs/event-schema.md) | Event taxonomy, validation rules, dwell time, cart abandonment |
| [identity.md](docs/identity.md) | Identity model and anonymous-to-registered linking |
| [event-storage.md](docs/event-storage.md) | Optional historical storage: PostgreSQL/Neon/TimescaleDB, identity links, customer history API, retention |
| [frontend-sdk.md](docs/frontend-sdk.md) | `@omnirec/commerce-web` reference |
| [spring-boot-sdk.md](docs/spring-boot-sdk.md) | `commerce-tracker-spring-boot` reference |
| [configuration.md](docs/configuration.md) | Complete property reference and production checklist |
| [amazon-personalize.md](docs/amazon-personalize.md) | Amazon Personalize mapping and identity constraints |
| [google-retail.md](docs/google-retail.md) | Google Retail mapping and supported event types |
| [rabbitmq.md](docs/rabbitmq.md) | Queue topology, retry, dead-lettering, scaling |
| [security.md](docs/security.md) | Trust boundaries, sensitive data handling, request controls |
| [testing.md](docs/testing.md) | Test coverage and contribution guidance for tests |
| [troubleshooting.md](docs/troubleshooting.md) | Symptom-based diagnostics |
| [AUDIT.md](docs/AUDIT.md) | Audit findings, resolutions, and current status |

[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) records the historical plan for
the serving side and is retained for context.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) for
development setup, coding conventions, and the pull request process, and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## Security

Do not report security vulnerabilities through public issues. See
[SECURITY.md](SECURITY.md) for the disclosure process.

