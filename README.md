# Omnirec

Open-source commerce event tracking. Omnirec collects shopper and business
events once, from the browser, your server and other systems' webhooks. It
validates them against a catalog of about 200 standard commerce events plus
your own custom events, resolves the identity each one belongs to, and delivers
them to Amazon Personalize, Google Cloud Retail, your own webhooks, historical
storage, or any destination you add. Provider credentials never reach the
browser.

```
Capture -> Normalize -> Validate -> Resolve identity -> Queue -> Deliver
```

Omnirec is infrastructure you host yourself, for one store or many. It has no
dashboard and no machine learning model of its own.

## License

Omnirec is licensed under the [Apache License, Version 2.0](./LICENSE).

## Status

Version 2.0.0. The highlights:

- the event catalog, with 205 events across 12 domains
- typed SDKs for the browser, React, Next.js, Vue, Node, Java and Spring Boot
- declarative HTML tracking
- tracking plans for custom events
- inbound webhooks (Stripe and generic JSON)
- derived events (abandoned carts, return visits, first and repeat purchases)
- outbound webhooks
- historical storage with customer deletion
- a CLI with a local development collector

All of this is covered by automated tests, including tests against real
RabbitMQ, Redis and PostgreSQL. The provider adapters have not yet been run
against live provider accounts; see
[Known limitations](docs/guide.md#10-known-limitations). Upgrading from 0.x:
[migration-v1-to-v2.md](docs/migration-v1-to-v2.md).

## Quick start

From nothing to a validated event, with no Java or Docker:

```bash
npx @omnirec/cli init     # detects Next.js, React, Vue, Spring Boot or plain HTML;
                          # writes omnirec.plan.yaml and prints the code to paste
npx @omnirec/cli dev      # local collector on http://localhost:8124, live list at /
```

```js
import { createOmnirec } from "@omnirec/commerce-web";

const omnirec = createOmnirec({ endpoint: "http://localhost:8124" });
omnirec.track("product_viewed", { product: { id: "P100", price: 89.99, currency: "USD" } });
omnirec.track("add_to_cart", { product: { id: "P100", quantity: 1 } });
```

Each event appears in the terminal, either accepted or with the exact field
that is wrong. When you are ready, run the real collector
([self-hosting.md](docs/self-hosting.md)):

```bash
docker compose -f deploy/lite/docker-compose.yml up -d --build       # one container
docker compose -f deploy/standard/docker-compose.yml up -d --build   # + RabbitMQ, Redis
```

Then point `endpoint` at it, ideally through a `/omnirec` path on your own domain.

## Usage

**Browser.** `track()` is typed from the catalog and your plan:

```js
import { createOmnirec } from "@omnirec/commerce-web";
import { dom } from "@omnirec/commerce-web/dom";
import { autocapture } from "@omnirec/commerce-web/autocapture";

const omnirec = createOmnirec({ endpoint: "/omnirec", plugins: [dom(), autocapture()] });
omnirec.track("search_performed", { search: { query: "gaming laptop", resultsCount: 24 } });
omnirec.identify("customer_123");
```

**HTML.** No JavaScript per element ([html-attributes.md](docs/html-attributes.md)):

```html
<button data-omnirec-event="product_added_to_cart" data-omnirec-product="P100" data-omnirec-quantity="1">Add</button>
```

**React, Next.js, Vue.** Use `@omnirec/commerce-react` (`OmnirecProvider`,
`useTrack`, `<Track>`), `@omnirec/commerce-next` or `@omnirec/commerce-vue`
(`v-track`, `v-impression`).

**Server.** Purchases and refunds are reported where the payment result is
known. The visitor's browsing identity is read from the cookies the browser
SDK sets:

```java
tracker.track(StandardEvents.PURCHASE_COMPLETED,
        Map.of("order", Map.of("id", order.getId(), "total", order.getTotal(), "currency", "USD", "items", lines)),
        order.getCustomerId(), order.getId());
```

**Custom events** go in `omnirec.plan.yaml`. `omnirec generate` types them, and
`omnirec validate --against origin/main` stops breaking changes in CI
([custom-events.md](docs/custom-events.md)).

## Repository layout

```
catalog/                         the event catalog (YAML): events, blocks, vocabularies
tools/cli/                       @omnirec/cli: init, dev, generate, validate
packages/
  commerce-web/                  @omnirec/commerce-web    browser SDK, plugins, script tag
  commerce-react/ commerce-next/ commerce-vue/ commerce-node/
backend/
  omnirec-commerce-core/         event model, catalog registry, validation, identity, EventDestination
  omnirec-event-api/             collector: auth modes, tenants, tracking plans, webhooks in
  omnirec-event-processing/      RabbitMQ topology, retry tiers, dead-letter queues and replay
  omnirec-derived-events/        cart_abandoned, return_visit, new/repeat purchase rules
  omnirec-webhook-destination/   signed outbound webhooks
  omnirec-amazon-personalize-destination/ omnirec-google-retail-destination/
  omnirec-recently-viewed-destination/ omnirec-redis-state/
  omnirec-event-storage/         optional history (PostgreSQL/TimescaleDB), deletion, retention
  omnirec-tracker-java/ commerce-tracker-spring-boot/   server-side SDKs
  omnirec-event-api-app/         the deployable collector
deploy/lite/ deploy/standard/    Docker Compose profiles
examples/nextjs-demo-store/      reference storefront
```

## Design principles

**Credentials are never present in the browser.** A keyless collector accepts
events only from allowed origins. A publishable key can only submit events for
its own tenant. `scripts/verify-bundle-security.mjs` fails the build if a
provider credential or SDK appears in a bundle.

**One catalog, every language.** Events are defined once in YAML. TypeScript
types, Java constants, the JSON Schema, the runtime catalog and the docs are
generated from it, and CI fails if any of them is stale.

**The core has no knowledge of providers.** Supporting another provider is one
`EventDestination` implementation registered as a bean
([extending.md](docs/extending.md)).

**History is never rewritten, except to delete it.** Authentication records a
link from the anonymous id to the user id, and attribution is a join.
`DELETE /v1/customers/{id}` removes a customer across all their devices and
leaves a tombstone, so late events cannot bring the history back.

**Provider outages do not cause event loss.** Durable queues, per-destination
retry tiers, dead-letter queues with replay, and idempotent consumers.

**Authoritative events originate server-side.** Purchases, refunds and
disputes come from the merchant backend or the payment provider's webhooks.

## Building and testing

```bash
npm install && npx turbo run build typecheck test   # frontend, SDK and CLI tests
npm run catalog:check                                # generated files match catalog/
cd backend && mvn clean install                     # backend tests (Docker for the real-infrastructure ones)
node scripts/verify-bundle-security.mjs
npm run test:browser                                 # Playwright
bash scripts/e2e/run.sh                              # end-to-end with real components, requires Docker
```

Tests that need RabbitMQ, Redis or PostgreSQL use Testcontainers and are
skipped when Docker is unavailable. See [testing.md](docs/testing.md).

## Observability

Structured logs and Micrometer metrics are exposed at `/actuator/prometheus`:

```
omnirec.events.received        omnirec.events.processed
omnirec.events.validated       omnirec.events.failed
omnirec.events.rejected        omnirec.events.duplicates
omnirec.events.queued          omnirec.provider.delivery.success
                               omnirec.provider.delivery.failure
```

With storage enabled there are also `omnirec.storage.*` metrics. Dead-letter
queue sizes and replay are available at `/actuator/deadletters` once that
endpoint is exposed. Logs record event ids and field names only, never payloads
or field values.

## Documentation

| Document | Contents |
| --- | --- |
| [guide.md](docs/guide.md) | Recommended starting point: architecture and complete usage |
| [catalog.md](docs/catalog.md) | The event catalog: domains, blocks, vocabularies, aliases |
| [events/](docs/events/README.md) | Generated reference for every standard event |
| [custom-events.md](docs/custom-events.md) | Tracking plans, typed custom events, breaking-change checks |
| [html-attributes.md](docs/html-attributes.md) | Declarative tracking with `data-omnirec-*` |
| [frontend-sdk.md](docs/frontend-sdk.md) | `@omnirec/commerce-web` and its plugins |
| [spring-boot-sdk.md](docs/spring-boot-sdk.md) | Java client and Spring Boot starter |
| [self-hosting.md](docs/self-hosting.md) | Lite and standard deployments, single or multi-tenant |
| [webhooks.md](docs/webhooks.md) | Inbound (Stripe, generic JSON) and outbound webhooks |
| [derived-events.md](docs/derived-events.md) | Abandoned carts, return visits, first and repeat purchases |
| [event-storage.md](docs/event-storage.md) | History, customer deletion, retention |
| [extending.md](docs/extending.md) | Destinations, webhook adapters, derived rules, tenant registries |
| [migration-v1-to-v2.md](docs/migration-v1-to-v2.md) | Upgrading from 0.x |
| [configuration.md](docs/configuration.md) | Complete property reference |
| [identity.md](docs/identity.md) | Identity model and linking |
| [security.md](docs/security.md) | Trust boundaries and request controls |
| [rabbitmq.md](docs/rabbitmq.md) | Queue topology, retries, dead letters |
| [amazon-personalize.md](docs/amazon-personalize.md), [google-retail.md](docs/google-retail.md) | Provider mappings |
| [troubleshooting.md](docs/troubleshooting.md) | Symptom-based diagnostics |

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) for
development setup, coding conventions, and the pull request process, and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## Security

Do not report security vulnerabilities through public issues. See
[SECURITY.md](SECURITY.md) for the disclosure process.
