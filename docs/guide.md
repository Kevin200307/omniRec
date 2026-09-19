# Omnirec guide: the architecture, and how to use it

This is the starting point. It explains how the system fits together, then walks
through using it: running it locally, adding it to a storefront, reporting
purchases from your backend, connecting a recommendation provider, deploying it,
and extending it. Each section links to the detailed reference.

**Contents**

1. [What Omnirec is](#1-what-omnirec-is)
2. [The architecture](#2-the-architecture)
3. [Run it locally in five minutes](#3-run-it-locally-in-five-minutes)
4. [Add it to your storefront](#4-add-it-to-your-storefront)
5. [Report purchases from your backend](#5-report-purchases-from-your-backend)
6. [Connect a recommendation provider](#6-connect-a-recommendation-provider)
7. [Deploy the Event API](#7-deploy-the-event-api)
8. [Operate it](#8-operate-it)
9. [Extend it: add a destination](#9-extend-it-add-a-destination)
10. [Limits to know about](#10-limits-to-know-about)
11. [Reference docs](#11-reference-docs)

---

## 1. What Omnirec is

Omnirec collects what shoppers do in your store: views, searches, carts,
checkouts, purchases. It puts every event into one standard shape, works out who
each event belongs to, and delivers it reliably to the personalisation providers
you choose: Amazon Personalize, Google Retail, or your own.

You instrument your store once. Changing or adding a provider is a
configuration change, not a code change in your store.

It is infrastructure. There is no dashboard, no analytics UI, and no machine
learning of its own: the providers do the learning.

**What you get:**

- A browser SDK (`@omnirec/commerce-web`, plus React bindings) for what shoppers
  do on the page.
- A Spring Boot SDK (`commerce-tracker-spring-boot`) for facts only your server
  knows, such as a settled payment.
- A standalone **Event API** service that receives, checks and queues events,
  then delivers them to providers.
- One standard event schema shared by both SDKs and the server, kept identical by
  contract tests.

---

## 2. The architecture

### The big picture

```
            YOUR STORE                                   OMNIREC EVENT API (you deploy it)
 ┌─────────────────────────────┐          ┌──────────────────────────────────────────────────────┐
 │ Browser                     │          │  Gateway                                              │
 │  @omnirec/commerce-web ─────┼── HTTPS ─┼─► auth (publishable key → tenant) · size cap ·        │
 │  views, search, cart, …     │  batches │    rate limit · normalise · validate · dedupe ·       │
 │                             │          │    resolve identity                                   │
 │ Your Spring Boot backend    │          │                    │                                  │
 │  commerce-tracker ──────────┼── HTTPS ─┼────────────────────┤                                  │
 │  purchases, refunds, …      │          │                    ▼                                  │
 └─────────────────────────────┘          │               RabbitMQ                                │
                                          │     one queue set per destination, with retry and     │
                                          │     dead-letter queues                                │
                                          │        │               │                │             │
                                          │        ▼               ▼                ▼             │
                                          │   Amazon adapter  Google adapter  Recently-viewed     │
                                          └────────┼───────────────┼────────────────┼─────────────┘
                                                   ▼               ▼                ▼
                                          Amazon Personalize  Google Retail   Redis (serving cache)
```

The store holds only a **publishable key**. Every provider credential (AWS keys,
Google service accounts) lives in the Event API, and only there.

### What happens to one event

Take a shopper opening a product page.

1. **Captured.** Your page calls `commerce.product.viewed({ productId: "p123" })`.
   The SDK adds everything you didn't pass:
   - a unique `eventId` and a `timestamp`;
   - the device's `anonymousId` (a first-party cookie) and the visit's `sessionId`;
   - the `userId`, if the shopper has signed in;
   - the page URL, with tokens and email addresses removed;
   - the platform and device.
2. **Checked in the browser.** The event is validated: required fields must be
   present, and anything that looks like card data or a password is refused.
   Invalid events are reported to `onError` and never sent.
3. **Batched.** Events are buffered and sent in batches of up to 20, or every 5
   seconds. If the network is down they wait in a small `localStorage` buffer and
   are retried with backoff. Anything still waiting when the page closes is sent
   with `sendBeacon`.
4. **Admitted by the gateway.** Before the body is even parsed, the Event API
   checks four things:
   - the key maps to an enabled tenant, and the tenant comes from the key, never
     from the request body;
   - the body is under the size limit;
   - the client is under its rate limit;
   - the request's origin is allowed by CORS.
5. **Normalised and validated again.** Each event in the batch is checked on its
   own, so one bad event doesn't sink the other 19. The server never trusts the
   browser's validation.
6. **Deduplicated.** The `eventId` is claimed in the dedup store (Redis in
   production). A repeat of an event already accepted in the last 24 hours is
   counted as a duplicate and dropped. This is what makes retries safe all the way
   back to the browser.
7. **Identity resolved.** If this device has been linked to a customer before,
   the `userId` is filled in. See [Identity](#identity-in-one-minute).
8. **Queued.** The event is published to RabbitMQ, once for each enabled
   destination. The API answers `202` only after the broker has **confirmed** the
   write. If the broker is down, the API answers `503` and the SDK retries.
9. **Delivered.** Each destination has its own consumer. The adapter translates
   the standard event into the provider's format and sends it. A temporary
   failure is retried after 1s, 2s, 4s, 8s, then 16s. After that, or on a
   permanent failure, the event goes to a dead-letter queue to inspect and replay.
10. **Delivered once per destination.** Each delivery also takes a lease on
    `(destination, eventId)`, so a message RabbitMQ redelivers is not sent to the
    provider twice.

### The modules

| Module | What it does |
|---|---|
| `packages/commerce-web` | Browser SDK. No framework dependency. |
| `packages/commerce-react` | React provider, `useCommerce`, `useProductView`. |
| `omnirec-commerce-core` | The standard `CommerceEvent`, event types, validation, identity linking, deduplication, and the `EventDestination` interface. No Spring, no cloud SDKs. |
| `omnirec-event-api` | The gateway: keys, limits, normalisation, validation, dedup, identity. |
| `omnirec-event-processing` | RabbitMQ queues, consumers, retries, dead-lettering, per-destination idempotency. |
| `omnirec-amazon-personalize-destination` | Amazon adapter. The only code that knows Personalize exists. |
| `omnirec-google-retail-destination` | Google adapter. The only code that knows Retail exists. |
| `omnirec-recently-viewed-destination` | Keeps the serving side's recently-viewed lists current. |
| `omnirec-redis-state` | Shared dedup and identity links, needed once you run more than one instance. |
| `commerce-tracker-spring-boot` | The SDK you embed in your own backend. |
| `omnirec-event-api-app` | The deployable service that bundles all of the above. |

Alongside these, the original **serving side** (`omnirec-web`, `omnirec-demo-app`
and friends) answers `/v1/recommendations`, `/v1/search`, and
`/v1/recently-viewed`. Reading results is a separate service from collecting
events, and the two deploy independently.

### The design rules, and why

- **The core never depends on a provider.** Everything provider-specific sits
  behind one interface, `EventDestination`. The module graph enforces this: the
  core has no path to a cloud SDK, so a provider import there won't compile.
- **The Event API is a separate service**, not a library in your store. That's
  what keeps provider credentials out of your storefront and your app servers.
- **One queue set per destination.** If Amazon is down, only Amazon's queue
  backs up; Google keeps draining.
- **Deduplication uses a lease that is completed only after success.** A crash
  mid-delivery releases the claim instead of losing the event.
- **Identity links are stored, not applied to past events.** Logging in doesn't
  rewrite history; it resolves future events. Attribution becomes a join, not a
  mass update.
- **Purchases come from the server.** A browser can't know whether a payment
  settled, and confirmation pages get reloaded or never load.

### Identity in one minute

| Id | What it is | Where it lives |
|---|---|---|
| `anonymousId` | This device | First-party cookie `omnirec_anonymous_id` |
| `sessionId` | This visit; a new one after 30 minutes idle | `localStorage` |
| `userId` | The customer | Set by your code with `identify()` / `user.loggedIn()` |

When a shopper signs in, the SDK sends an `identify` event and the server stores
the link `anonymousId → userId`:

- Events already captured anonymously **keep their null `userId`**. Nothing is
  rewritten.
- Later events from that device get the `userId` filled in automatically, even
  on a page that forgot to call `identify()`.
- A shopper can link several devices to one account.
- Logging out, or a different user signing in, starts a new session.

IP addresses and browser fingerprints are never used as identity. See
[identity.md](identity.md).

---

## 3. Run it locally in five minutes

You need Docker, Node 18+, and (to build the backend yourself) JDK 17 with Maven.

```bash
docker-compose up -d        # RabbitMQ, Redis, Event API (:8081), serving API (:8080)

npm install
cd examples/nextjs-demo-store && cp .env.local.example .env.local && cd ../..
npx turbo run dev --filter=nextjs-demo-store
```

Open http://localhost:3000, click around, add to cart, and log in. Then:

- **Network tab:** batches going to `POST http://localhost:8081/v1/events/batch`.
- **RabbitMQ UI** at http://localhost:15672 (guest / guest): the `omnirec.events.*`
  queues.
- **Recently viewed** for a signed-in user:
  `curl "http://localhost:8080/v1/recently-viewed?tenantId=demo-store&userId=<id>"`.

No cloud account is needed: every provider destination is off by default.

> **Port clash?** If something else on your machine already uses 5672, 6379,
> 8080, or 8081, stop it or change the ports in `docker-compose.yml`.

### Send an event by hand

```bash
curl -i http://localhost:8081/v1/events/batch \
  -H "Content-Type: application/json" \
  -H "X-Omnirec-Key: pk_test_demo_store" \
  -d '{
    "events": [{
      "eventId": "evt_manual_1",
      "eventType": "product_viewed",
      "schemaVersion": "1.0",
      "timestamp": "2026-09-19T10:00:00Z",
      "identity": { "anonymousId": "anon_1", "sessionId": "s1", "userId": null },
      "context":  { "platform": "web", "url": "https://shop.example/p/123" },
      "commerce": { "productId": "p123" },
      "properties": {}
    }]
  }'
```

The API answers `202 Accepted` with a summary:

```json
{ "accepted": 1, "rejected": 0, "duplicates": 0, "retryLater": 0, "errors": [] }
```

Send the same request again and you get `"duplicates": 1`.

### Run the full end-to-end check

```bash
bash scripts/e2e/run.sh
```

This drives the built browser SDK through a two-day anonymous journey and a
login, against the real Event API, RabbitMQ and Redis. Amazon calls go to a
local capture server in place of Personalize. The script checks nine things,
including identity, sessions, no card data, dwell handling, and recently-viewed.
It uses its own ports and removes its containers when it's done.

---

## 4. Add it to your storefront

### Step 1: get a publishable key

A tenant is one store. It's defined in the Event API's configuration:

```yaml
omnirec:
  events:
    tenants:
      my-store:
        api-key: ${MY_STORE_API_KEY}     # e.g. pk_live_7f3c…  (publishable)
    cors:
      allowed-origins:
        - https://www.my-store.com
```

The key only lets a client write events for that one tenant, so it is safe to
ship in browser code. Set `enabled: false` under a tenant to shut its key off.

### Step 2: install and create the client

```bash
npm install @omnirec/commerce-web
```

```js
import { createCommerceClient } from "@omnirec/commerce-web";

export const commerce = createCommerceClient({
  apiKey: "pk_live_xxxxx",
  endpoint: "https://events.my-store.com",
});
```

That's the whole required configuration. The SDK **throws** if `apiKey` looks
like a secret (`sk_…`, an AWS key, a PEM block), so a leaked credential fails
loudly in development instead of shipping.

### Step 3: call the trackers

```js
commerce.page.viewed();
commerce.search.performed({ query: "gaming laptop", resultCount: 24 });
commerce.category.viewed({ categoryId: "laptops" });
commerce.product.viewed({ productId: "p123", price: 1500, currency: "USD" });
commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 1, price: 1500, currency: "USD" });
commerce.checkout.started({ cartId: "c1" });
```

There's a tracker for each of the 37 event types. See
[frontend-sdk.md](frontend-sdk.md#the-trackers) for the full list and
[event-schema.md](event-schema.md#validation-rules) for each type's required
fields.

You never pass ids, timestamps, URLs, or device details; the SDK adds them.

### Step 4: tell it who the shopper is

```js
// After a successful login, or on every page load once you know the user:
commerce.user.loggedIn({ userId: "customer_123" });   // or commerce.identify({ userId })

// On logout:
commerce.user.loggedOut();
```

Calling `identify` on every page load is fine; repeats cost nothing. Use your
stable internal customer id, never an email address.

### Step 5: single-page apps

In an SPA, call `commerce.page.viewed()` on every route change. That also ends
the time-on-product measurement for the page being left. Without it, dwell time
keeps running across navigation.

### React and Next.js

```bash
npm install @omnirec/commerce-react
```

```tsx
// app/providers.tsx
"use client";
import { CommerceProvider } from "@omnirec/commerce-react";

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <CommerceProvider
      apiKey={process.env.NEXT_PUBLIC_OMNIREC_API_KEY!}
      endpoint={process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT!}
    >
      {children}
    </CommerceProvider>
  );
}
```

```tsx
"use client";
import { useCommerce, useProductView } from "@omnirec/commerce-react";

export function ProductPage({ product }) {
  // Records the view on mount, ends dwell measurement on unmount.
  useProductView({ productId: product.id, price: product.price, currency: "USD" });

  const commerce = useCommerce();
  return (
    <button onClick={() => commerce.cart.productAdded({
      cartId: "c1", productId: product.id, quantity: 1, price: product.price, currency: "USD",
    })}>
      Add to cart
    </button>
  );
}
```

`NEXT_PUBLIC_` is correct: this key is meant to be public. Importing the SDK
during server rendering is safe; create the client in a `"use client"` component.

### Recommendation attribution

When you show recommendations, pass the provider that produced them, so
attribution goes back only to that provider:

```js
commerce.recommendation.impression({
  recommendationId: "rec_123",
  recommendationProvider: "amazon-personalize",   // or "google-retail"
  productIds: ["p1", "p2"],
  source: "homepage",
});
commerce.recommendation.clicked({ recommendationId: "rec_123", productId: "p2" });
```

### Debugging

Pass `debug: true` to log every event as it's built, and `onError` to see
refusals:

```js
createCommerceClient({ apiKey, endpoint, debug: true, onError: (e) => console.warn(e) });
```

---

## 5. Report purchases from your backend

Anything the browser can't be trusted to know belongs on the server: a settled
payment, a refund, a cancelled order, a verified review.

```xml
<dependency>
  <groupId>io.omnirec</groupId>
  <artifactId>commerce-tracker-spring-boot</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
omnirec:
  tracker:
    endpoint: https://events.my-store.com
    api-key: ${OMNIREC_API_KEY}
    tenant-id: my-store
```

```java
@Service
public class OrderService {

    private final CommerceTracker commerce;

    public OrderService(CommerceTracker commerce) {
        this.commerce = commerce;
    }

    public void onPaymentSettled(Order order) {
        commerce.purchase.completed(PurchaseCompleted.builder()
                .orderId(order.getId())
                .userId(order.getCustomerId())
                .anonymousId(order.getTrackingAnonymousId())   // strongly recommended
                .items(order.getLines().stream()
                        .map(l -> CommerceItem.of(l.getSku(), l.getQuantity(), l.getPrice(), "USD"))
                        .toList())
                .total(order.getTotal())
                .currency("USD")
                .build());
    }
}
```

Three things to know:

- **It never blocks your checkout.** Events go onto a bounded in-memory queue and
  are sent in the background. Temporary failures are retried for about three
  minutes. An outage on our side can't slow down your order placement.
- **It's idempotent.** A purchase's `eventId` is derived from the order id
  (`evt:purchase_completed:<orderId>`), and the browser SDK derives the same id.
  A purchase reported twice, by a retried webhook, a reloaded confirmation page,
  or both SDKs, is delivered once.
- **Pass the `anonymousId`.** Read it from the `omnirec_anonymous_id` cookie at
  checkout and store it with the order. It links the purchase to the browsing that
  led to it, which is usually the most valuable signal.

For purchases that must never be lost, even through a long outage plus a
restart, record them in your own transactional outbox and replay from it. The
deterministic ids make replay safe. See [spring-boot-sdk.md](spring-boot-sdk.md).

---

## 6. Connect a recommendation provider

Each destination is off until you turn it on. Turning one on is a flag plus that
provider's credentials, set on the Event API only.

### Amazon Personalize

```yaml
omnirec:
  destinations:
    amazon-personalize:
      enabled: true
      region: us-east-1
      tracking-id: ${AWS_PERSONALIZE_TRACKING_ID}
      property-keys: [price, currency]    # only keys your interactions schema defines
```

Credentials come from the AWS default chain: an IAM role in production, or
`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` locally. The adapter:

- sends at most 10 events per call;
- sends `userId` only for signed-in shoppers;
- forwards recommendation ids only when Personalize issued them;
- skips event types Personalize has no use for.

Details: [amazon-personalize.md](amazon-personalize.md).

### Google Retail

```yaml
omnirec:
  destinations:
    google-retail:
      enabled: true
      project-number: ${GOOGLE_PROJECT_NUMBER}
      location: global
      catalog-id: default_catalog
```

Authentication uses Application Default Credentials: workload identity in
production, or `GOOGLE_APPLICATION_CREDENTIALS` locally. Retail accepts only
seven event types, so the adapter sends those and skips the rest. `visitorId` is
always the anonymous device id; the customer goes in `userInfo`. Details:
[google-retail.md](google-retail.md).

### Recently viewed (serving side)

```yaml
omnirec:
  destinations:
    recently-viewed:
      enabled: true
      host: ${CACHE_REDIS_HOST}    # the Redis the serving app reads
      tenant-id: my-store          # required if more than one tenant sends events
```

This keeps `/v1/recently-viewed` current for signed-in shoppers.

### Both at once

Enable as many as you like. Each has its own queues, so one provider failing
never delays another.

> **Status:** the Amazon and Google adapters are checked against the providers'
> published API rules and a local capture server. They have **not yet been run
> against live accounts**. Do a first run against a test dataset or project.

---

## 7. Deploy the Event API

### Build and run

```bash
cd backend && mvn clean install              # builds and runs all 312 tests
java -jar omnirec-event-api-app/target/omnirec-event-api-app-*.jar
```

Or use the image: `docker build -f backend/omnirec-event-api-app/Dockerfile .`
(it runs as a non-root user). The service listens on port 8081.

### What it needs

| Dependency | Why | Setting |
|---|---|---|
| RabbitMQ 3.13+ | Durable queue between intake and delivery | `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` |
| Redis | Shared dedup and identity links (needed for more than one instance) | `REDIS_STATE_ENABLED=true`, `REDIS_HOST`, `REDIS_PORT` |
| Provider credentials | Only for the destinations you enable | See section 6 |

Publisher confirms and returns must be on (they are in the shipped
`application.yml`). The service **refuses to start** without them, because
without them a failed publish is invisible and the event is lost.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `QUEUE_ENABLED` | `true` | `false` delivers inline without RabbitMQ (development only) |
| `REDIS_STATE_ENABLED` | `false` | Shared state; turn on for more than one instance |
| `DEMO_STORE_API_KEY` | `pk_test_demo_store` | The sample tenant's key; replace with your own tenants |
| `PERSONALIZE_ENABLED` / `AWS_PERSONALIZE_TRACKING_ID` / `AWS_REGION` | off | Amazon |
| `GOOGLE_RETAIL_ENABLED` / `GOOGLE_PROJECT_NUMBER` | off | Google |
| `RECENTLY_VIEWED_ENABLED` / `RECENTLY_VIEWED_REDIS_HOST` / `RECENTLY_VIEWED_TENANT_ID` | off | Recently-viewed feed |

The full list is in [configuration.md](configuration.md).

### Production checklist

- [ ] A real `api-key` per tenant, and `cors.allowed-origins` limited to your
      storefronts.
- [ ] `REDIS_STATE_ENABLED=true` if you run more than one instance. Without it,
      each instance dedupes only its own traffic, and both in-memory stores log a
      warning at startup.
- [ ] Behind a load balancer, set `server.forward-headers-strategy: native` so
      rate limiting sees the real client address.
- [ ] Provider credentials from an IAM role or workload identity, not
      environment variables.
- [ ] `/actuator/prometheus` scraped, with alerts on `omnirec.events.failed` and
      on dead-letter queue depth.

### Scaling

The Event API is stateless once Redis holds the shared state, so you can run as
many instances as you need. Consumers scale with
`omnirec.processing.concurrency`. Per-destination queues mean a slow provider
only slows itself.

---

## 8. Operate it

### Health and metrics

- `GET /actuator/health`: service health, for load-balancer and orchestrator checks.
- `GET /actuator/prometheus`: every metric below.

| Metric | Meaning |
|---|---|
| `omnirec.events.received` / `validated` / `rejected` / `duplicates` / `queued` | Intake, per tenant |
| `omnirec.events.processed` / `failed` | Delivery outcome |
| `omnirec.provider.delivery.success` / `failure` / `latency` | Per provider |
| `omnirec.provider.retries` / `dead_lettered` | Retries scheduled, events given up on |
| `omnirec.queue.depth{queue=…}` | Backlog per queue, including retry tiers and the DLQ |

### The queues

For each destination:

- `omnirec.events.<dest>`: the main queue.
- `omnirec.events.<dest>.retry.1` … `.retry.5`: one per delay; messages return to
  the main queue on their own.
- `omnirec.events.<dest>.dlq`: events given up on. Each carries the header
  `x-omnirec-failure-reason`.

**Replaying dead letters.** Fix the cause, then shovel the DLQ back onto the
`omnirec.events` exchange with routing key `events.<dest>`. Events already
delivered are skipped, so replay is safe. See [rabbitmq.md](rabbitmq.md).

### When something's wrong

[troubleshooting.md](troubleshooting.md) is organised by symptom:

- no events arriving;
- events accepted but not reaching the provider;
- duplicates;
- inflated views;
- a missing `userId`;
- startup failures.

---

## 9. Extend it: add a destination

A new provider (Azure, a data warehouse, your own service) is one class.
Nothing in the SDKs, the gateway, or the queue changes.

```java
public class MyProviderDestination implements EventDestination {

    @Override
    public String id() {
        return "my-provider";                  // used in config, queue names, metrics
    }

    @Override
    public boolean supports(CommerceEvent event) {
        return !event.eventType().isControlEvent()
                && !event.isEngagementUpdate();     // dwell follow-ups aren't new views
    }

    @Override
    public void send(CommerceEvent event) {
        try {
            client.post(map(event));
        } catch (TimeoutException e) {
            throw new DestinationException(id(), "timed out", e);            // retried
        } catch (BadRequestException e) {
            throw DestinationException.permanent(id(), e.getMessage());      // straight to the DLQ
        }
    }
}
```

Register it as a bean, ideally from its own auto-configuration behind an
`omnirec.destinations.my-provider.enabled` flag, like the existing adapters. The
queues are created for it automatically.

Rules every destination follows:

- **Be idempotent.** Delivery is at-least-once, so the same event can arrive
  again.
- **Throw on a temporary failure.** Returning normally tells the system the event
  was handled, so a swallowed error loses it for good.
- **Use `permanent(...)` for failures a retry can't fix.**
- **Never modify the event.** It's shared with the other destinations.
- **Keep the mapping separate from the network call**, so the mapping can be
  tested exhaustively without credentials.

---

## 10. Limits to know about

- **Live providers are unverified.** Amazon and Google are tested against their
  documented rules and a capture server, not real accounts.
- **Azure is not implemented.** Azure AI Personalizer retires on 1 October 2026,
  so choosing a replacement is still an open decision.
- **Rate limiting is per instance.** Behind N instances, the effective limit is
  N times the setting.
- **The backend SDK buffers in memory.** A long outage plus a restart loses
  queued events. They are counted, not silently dropped. Use an outbox for
  must-not-lose events.
- **Dwell time is best effort.** It's lost on a hard crash or some mobile page
  freezes.
- **Changing queue arguments on an existing broker is a migration.** See
  [rabbitmq.md](rabbitmq.md#upgrading-an-existing-broker).

---

## 11. Reference docs

| Doc | Read it for |
|---|---|
| [architecture.md](architecture.md) | Module boundaries and design decisions in depth |
| [event-schema.md](event-schema.md) | The event shape, all 37 types, validation rules, dwell time |
| [identity.md](identity.md) | Anonymous and signed-in identity, linking, devices, logout |
| [frontend-sdk.md](frontend-sdk.md) | Every tracker, batching, offline, configuration |
| [spring-boot-sdk.md](spring-boot-sdk.md) | The backend SDK, idempotency keys, retry |
| [rabbitmq.md](rabbitmq.md) | Queue topology, guarantees, retry schedule, operations |
| [amazon-personalize.md](amazon-personalize.md) · [google-retail.md](google-retail.md) | Exactly how each provider mapping works |
| [security.md](security.md) | Keys, tenant isolation, sensitive data, network controls |
| [configuration.md](configuration.md) | Every setting |
| [testing.md](testing.md) | The test suites and what each one proves |
| [troubleshooting.md](troubleshooting.md) | Symptom → cause → fix |
| [AUDIT.md](AUDIT.md) | The audit findings, their fixes, and current status |
