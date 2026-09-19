# Omnirec

Provider-independent commerce event tracking. Collect interactions once,
normalise them, resolve who they belong to, and deliver them to Amazon
Personalize, Google Cloud Retail, or anything you add later — without the
browser ever holding a provider credential.

```
Track once → Normalize → Resolve identity → Queue → Transform → Deliver
```

This is infrastructure, not an analytics product. There is no dashboard, no
reporting UI, and no model of its own.

## What's here

```
packages/
  commerce-web/         @omnirec/commerce-web   — browser SDK (no React dependency)
  commerce-react/       @omnirec/commerce-react — ~40-line React binding
  core/ react/ react-ui/ create-omnirec-app/    — the serving-side packages

backend/
  omnirec-commerce-core/                    CommerceEvent, taxonomy, validation,
                                            identity linking, dedup, EventDestination
  omnirec-event-api/                        the gateway: auth, rate limit, normalize,
                                            validate, dedup, identity
  omnirec-event-processing/                 RabbitMQ topology, consumers, dispatcher,
                                            retry + DLQ
  omnirec-amazon-personalize-destination/   the only module that knows Personalize exists
  omnirec-google-retail-destination/        the only module that knows Retail exists
  omnirec-recently-viewed-destination/      feeds the serving side's recently-viewed lists
  omnirec-redis-state/                      shared dedup + identity links for multi-instance deploys
  commerce-tracker-spring-boot/             the SDK merchants embed for business events
  omnirec-event-api-app/                    the standalone deployable

  omnirec-core/ omnirec-web/ …              serving side: recommendations, search,
                                            recently-viewed
  omnirec-contract-tests/                   cross-language schema parity

examples/nextjs-demo-store/                 working Next.js storefront
schema/commerce-event.schema.json           the wire contract
scripts/verify-bundle-security.mjs          fails CI if a credential reaches the browser
scripts/e2e/run.sh                          live end-to-end journey, every component real
docs/                                       architecture, identity, security, …
```

## Quick start

```bash
docker-compose up -d           # RabbitMQ + Redis + Event API (:8081) + serving API (:8080)

npm install
cd examples/nextjs-demo-store && cp .env.local.example .env.local && cd ../..
npx turbo run dev --filter=nextjs-demo-store
```

Open http://localhost:3000. Click a product, add to cart, log in — the page shows
the identity it's using and the events it produced. Watch them leave in batches
in the Network tab, and queue and drain at http://localhost:15672 (guest/guest).

Every destination is disabled by default, so this runs end to end with **no cloud
account of any kind**.

## Track something

```js
import { createCommerceClient } from "@omnirec/commerce-web";

const commerce = createCommerceClient({
  apiKey: "pk_live_xxxxx",             // publishable — safe in browser code
  endpoint: "https://events.example.com",
});

commerce.product.viewed({ productId: "p123", price: 1500, currency: "USD" });
commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 2 });
commerce.user.loggedIn({ userId: "customer_123" });
```

You never pass `anonymousId`, `sessionId`, `eventId`, `timestamp`, or the page
context. The SDK attaches all of it.

Purchases come from the backend, where the payment result is actually known:

```java
commerce.purchase.completed(PurchaseCompleted.builder()
        .orderId(order.getId())
        .userId(order.getCustomerId())
        .anonymousId(order.getTrackingAnonymousId())
        .items(lines).total(order.getTotal()).currency("USD")
        .build());
```

## Design principles

**Credentials never reach the browser.** Not obfuscated — never sent. The
frontend holds a publishable key that can do one thing: write events for its own
tenant. `scripts/verify-bundle-security.mjs` fails CI if that ever stops being
true.

**The core doesn't know providers exist.** `omnirec-commerce-core` depends on
Jackson and SLF4J. Adding Azure means writing one `EventDestination` and
registering it as a bean — nothing in the core, gateway, queue, or either SDK
changes. Enforced by the module graph, not by discipline.

**History is never rewritten.** Logging in records an `anon → user` link; it does
not backfill past events. Attribution becomes a join, and what was genuinely
known at capture time is preserved.

**No event is lost to a provider outage.** Durable queues, per-destination retry
queues, dead-letter queues, and idempotent consumers. One provider being down
backs up only that provider's queue.

**Business truth comes from the backend.** A confirmation page can be reloaded,
bookmarked, or never reached. Purchases, refunds, and accepted reviews are
reported server-side.

## Building

```bash
npm install && npx turbo run build test typecheck   # 160 frontend tests
cd backend && mvn clean install                     # 312 backend tests
node scripts/verify-bundle-security.mjs
```

## Observability

No dashboard — structured logs plus Micrometer counters on
`/actuator/prometheus`:

```
omnirec.events.received        omnirec.events.processed
omnirec.events.validated       omnirec.events.failed
omnirec.events.rejected        omnirec.events.duplicates
omnirec.events.queued          omnirec.provider.delivery.success
                               omnirec.provider.delivery.failure
```

Logs carry event ids and field *names* — never payloads or field values.

## Docs

| | |
|---|---|
| [architecture.md](docs/architecture.md) | Modules, pipeline order, failure behaviour |
| [event-schema.md](docs/event-schema.md) | Taxonomy, validation rules, dwell time, cart abandonment |
| [identity.md](docs/identity.md) | The three identities and anonymous → registered linking |
| [frontend-sdk.md](docs/frontend-sdk.md) | `@omnirec/commerce-web` |
| [spring-boot-sdk.md](docs/spring-boot-sdk.md) | `commerce-tracker-spring-boot` |
| [configuration.md](docs/configuration.md) | Every property, production checklist |
| [amazon-personalize.md](docs/amazon-personalize.md) | Mapping and the anonymous-identity trap |
| [google-retail.md](docs/google-retail.md) | Mapping and the closed vocabulary |
| [rabbitmq.md](docs/rabbitmq.md) | Topology, retry, DLQ, scaling |
| [security.md](docs/security.md) | Trust boundaries, sensitive data, request controls |
| [testing.md](docs/testing.md) | What's covered and how to add to it |
| [troubleshooting.md](docs/troubleshooting.md) | Symptom → cause → fix |
| [AUDIT.md](docs/AUDIT.md) | The audit of this codebase: findings, fixes, and what remains |

[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) is the historical plan for the
serving side, kept for context.
