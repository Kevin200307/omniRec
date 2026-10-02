# Omnirec Guide

This guide describes the system architecture and explains how to use it. It
covers local setup, storefront integration, server-side integration, provider
configuration, deployment, operation, and extension. Each section links to the
corresponding reference document.

## Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Running locally](#3-running-locally)
4. [Storefront integration](#4-storefront-integration)
5. [Server-side integration](#5-server-side-integration)
6. [Provider configuration](#6-provider-configuration)
7. [Deployment](#7-deployment)
8. [Operations](#8-operations)
9. [Extending the system](#9-extending-the-system)
10. [Known limitations](#10-known-limitations)
11. [Reference documentation](#11-reference-documentation)

## 1. Overview

Omnirec collects commerce interactions (product views, searches, cart activity,
checkouts, and purchases), converts them into a single canonical event format,
resolves the identity each event belongs to, and delivers them to the
personalization providers a merchant has configured.

A storefront is instrumented once. Adding or replacing a provider is a
configuration change and does not require changes to storefront code.

Omnirec is infrastructure rather than an analytics product. It provides no
dashboard, no reporting interface, and no machine learning model of its own.

The system consists of four parts:

| Component | Purpose |
| --- | --- |
| `@omnirec/commerce-web` | Browser SDK for interactions that occur on the page. React bindings are provided separately. |
| `commerce-tracker-spring-boot` | Server-side SDK for events that only the merchant backend can confirm, such as a settled payment. |
| Event API | Standalone service that authenticates, validates, deduplicates, queues, and delivers events. |
| Canonical event schema | A single event definition shared by both SDKs and the server, kept consistent by contract tests. |

## 2. Architecture

### System overview

```
  Merchant systems                        Event API (self-hosted)
+---------------------------+       +-------------------------------------------+
| Browser                   |       | Gateway                                   |
|   @omnirec/commerce-web   | HTTPS |   authentication (publishable key to      |
|   views, search, cart     |------>|   tenant), payload limit, rate limit,     |
|                           |       |   normalization, validation,              |
| Merchant backend          | HTTPS |   deduplication, identity resolution      |
|   commerce-tracker        |------>|                     |                     |
|   purchases, refunds      |       |                     v                     |
+---------------------------+       | RabbitMQ                                  |
                                    |   one queue set per destination, with     |
                                    |   retry tiers and dead-letter queues      |
                                    |         |          |           |          |
                                    |         v          v           v          |
                                    |   Amazon       Google      Recently       |
                                    |   adapter      adapter     viewed         |
                                    +---------|----------|-----------|----------+
                                              v          v           v
                                         Amazon      Google        Redis
                                       Personalize   Retail   (serving cache)
```

Storefront code holds only a publishable key. Provider credentials, including
AWS access keys and Google service accounts, are held exclusively by the Event
API.

### Event lifecycle

The following describes the processing of a single product view.

1. **Capture.** The storefront calls `commerce.product.viewed({ productId: "p123" })`.
   The SDK attaches the remaining fields automatically:
   - a unique `eventId` and a `timestamp`;
   - the device `anonymousId` (a first-party cookie) and the `sessionId`;
   - the `userId`, if the visitor is signed in;
   - the page URL, with tokens and email addresses removed;
   - platform and device attributes.
2. **Client-side validation.** Required fields are checked, and values resembling
   payment card data or credentials are rejected. Invalid events are reported
   through the `onError` callback and are not transmitted.
3. **Batching.** Events are buffered and transmitted in batches of up to 20
   events, or every 5 seconds, whichever occurs first. If the network is
   unavailable, events are held in a bounded `localStorage` buffer and retried
   with exponential backoff. Events still buffered when the page unloads are
   transmitted using `navigator.sendBeacon`.
4. **Admission control.** The Event API performs four checks before the request
   body is parsed:
   - the API key resolves to an enabled tenant, and the tenant is derived from
     the key rather than from the request body;
   - the body is within the configured payload limit;
   - the client is within its rate limit;
   - the request origin is permitted by the CORS configuration.
5. **Normalization and server-side validation.** Each event in a batch is bound
   and validated individually, so that a single invalid event does not cause the
   remainder of the batch to be rejected. Client-side validation is never
   treated as authoritative.
6. **Deduplication.** The `eventId` is claimed in the deduplication store, backed
   by Redis in multi-instance deployments. An event already accepted within the
   deduplication window (24 hours by default) is recorded as a duplicate and
   discarded. This property is what makes retries safe from the browser onward.
7. **Identity resolution.** If the device has previously been linked to a
   customer, the `userId` is populated. See [identity resolution](#identity-resolution).
8. **Queueing.** The event is published to RabbitMQ once per enabled
   destination. The API returns `202 Accepted` only after the broker confirms
   the publication. If the broker is unavailable, the API returns `503` with a
   `Retry-After` header and the SDK retries.
9. **Delivery.** Each destination has a dedicated consumer. The adapter maps the
   canonical event to the provider format and transmits it. Transient failures
   are retried after 1s, 2s, 4s, 8s, and 16s. Once retries are exhausted, or on
   a permanent failure, the event is routed to a dead-letter queue for
   inspection and replay.
10. **Delivery idempotency.** Each delivery acquires a lease on the pair
    (destination, `eventId`), so that a message redelivered by RabbitMQ is not
    transmitted to the provider twice.

### Modules

| Module | Responsibility |
| --- | --- |
| `packages/commerce-web` | Browser SDK. No framework dependency. |
| `packages/commerce-react` | React bindings: `CommerceProvider`, `useCommerce`, `useProductView`. |
| `omnirec-commerce-core` | Canonical `CommerceEvent`, event taxonomy, validation, identity linking, deduplication, and the `EventDestination` interface. No Spring and no cloud SDK dependencies. |
| `omnirec-event-api` | Gateway: API keys, request limits, normalization, validation, deduplication, identity resolution. |
| `omnirec-event-processing` | RabbitMQ topology, consumers, retry scheduling, dead-lettering, per-destination idempotency. |
| `omnirec-amazon-personalize-destination` | Amazon Personalize adapter. |
| `omnirec-google-retail-destination` | Google Retail adapter. |
| `omnirec-recently-viewed-destination` | Maintains recently-viewed lists for the serving side. |
| `omnirec-redis-state` | Shared deduplication and identity-link stores, required for multi-instance deployments. |
| `commerce-tracker-spring-boot` | Server-side SDK embedded in the merchant application. |
| `omnirec-event-api-app` | Deployable service that assembles the modules above. |

The serving side (`omnirec-web`, `omnirec-demo-app`, and related modules)
provides `/v1/recommendations`, `/v1/search`, and `/v1/recently-viewed`. Serving
personalization results is a separate concern from collecting events, and the
two deploy independently.

### Design principles

- **Provider independence.** The core has no compile-time dependency on any
  provider SDK. All provider-specific behaviour is implemented behind the
  `EventDestination` interface, and the module graph enforces this constraint.
- **Credential isolation.** The Event API is a separate service rather than a
  library embedded in merchant applications, which confines provider
  credentials to a single process.
- **Destination isolation.** Each destination has its own queue set, so an
  outage affecting one provider does not delay delivery to another.
- **Lease-based idempotency.** A deduplication claim is completed only after
  successful processing, so a process failure during delivery releases the claim
  rather than discarding the event.
- **Immutable history.** Identity links are stored rather than applied
  retroactively. Authentication does not rewrite previously captured events;
  attribution is performed as a join.
- **Authoritative purchase reporting.** Purchases are reported from the merchant
  backend, because a browser cannot confirm payment settlement and confirmation
  pages may be reloaded or never rendered.

### Identity resolution

| Identifier | Represents | Storage |
| --- | --- | --- |
| `anonymousId` | The device | First-party cookie `omnirec_anonymous_id` |
| `sessionId` | The visit; replaced after 30 minutes of inactivity | `localStorage` |
| `userId` | The customer | Supplied by the application through `identify()` or `user.loggedIn()` |

When a visitor authenticates, the SDK emits an `identify` event and the server
records the link from `anonymousId` to `userId`. The following rules apply:

- Events captured anonymously retain a null `userId` permanently. No historical
  event is modified.
- Subsequent events from the linked device are enriched with the `userId`
  automatically, including on pages that do not call `identify()`.
- A customer may link multiple devices to one account.
- Signing out, or authenticating as a different user, starts a new session.

IP addresses and device fingerprints are never used as identity signals. See
[identity.md](identity.md).

## 3. Running locally

**Fastest: the CLI, with no Java or Docker.**

```bash
npx @omnirec/cli init      # starter tracking plan + setup for your framework
npx @omnirec/cli dev       # collector on http://localhost:8124, live list at /
```

`omnirec dev` validates against the catalog and your plan with the collector's
rules, and prints each event as it arrives. See
[tools/cli/README.md](../tools/cli/README.md).

**The full stack.** This needs Docker, Node.js 20 or later, and JDK 17 with
Maven for backend builds.

```bash
docker-compose up -d        # RabbitMQ, Redis, Event API (8081), serving API (8080)

npm install
cd examples/nextjs-demo-store && cp .env.local.example .env.local && cd ../..
npx turbo run dev --filter=nextjs-demo-store
```

Open `http://localhost:3000` and use the demo storefront. You can then observe:

- event batches posted to `http://localhost:8081/v1/events/batch`
- the `omnirec.events.*` queues in the RabbitMQ management interface at
  `http://localhost:15672` (default credentials `guest` / `guest`)
- recently-viewed results for an authenticated user:
  `curl "http://localhost:8080/v1/recently-viewed?tenantId=demo-store&userId=<id>"`

No cloud account is required: all provider destinations are disabled by
default. To run the collector the way you would in production, see
[self-hosting.md](self-hosting.md) (`deploy/lite`, `deploy/standard`).

If ports 5672, 6379, 8080 or 8081 are in use, stop the conflicting service or
change the port mappings in `docker-compose.yml`.

### Submitting an event directly

```bash
curl -i http://localhost:8081/v1/events \
  -H "Content-Type: application/json" \
  -H "X-Omnirec-Key: pk_test_demo_store" \
  -d '{
    "events": [{
      "eventId": "evt_manual_1",
      "event": "product_viewed",
      "schemaVersion": "2.0",
      "timestamp": "2026-10-01T10:00:00Z",
      "identity": { "anonymousId": "anon_1", "sessionId": "s1" },
      "context":  { "url": "https://shop.example/p/123" },
      "data":     { "product": { "id": "p123", "price": 15.00, "currency": "USD" } }
    }]
  }'
```

The service responds with `202 Accepted` and a summary:

```json
{ "accepted": 1, "rejected": 0, "duplicates": 0, "retryLater": 0, "errors": [] }
```

Repeating the request returns `"duplicates": 1`. A rejected event names the
field, for example `data.product.id: id is required`.

### End-to-end verification

```bash
bash scripts/e2e/run.sh
```

This drives the built browser SDK through a two-day anonymous journey followed
by authentication, against a running Event API, RabbitMQ and Redis. A local
capture server stands in for Amazon Personalize, and a second one for a webhook
receiver, so no cloud account is needed. It asserts eleven conditions:

- identity and session behaviour
- rejection of payment card data
- dwell-time handling
- the recently-viewed feed
- an abandoned cart derived with Redis timers, then delivered by a signed
  webhook

## 4. Storefront integration

### Step 1: Decide how the browser authenticates

For one store, nothing is needed. The collector runs in **open mode**, and
accepts browser events from `omnirec.events.cors.allowed-origins`, or from its
own origin when you proxy a `/omnirec` path on your site to it
([self-hosting.md](self-hosting.md#1-choose-how-browsers-authenticate)).

For several stores in one collector, give each tenant a publishable key:

```yaml
omnirec:
  events:
    tenants:
      my-store:
        api-key: ${MY_STORE_API_KEY}     # publishable, for example pk_live_7f3c...
        allowed-origins: [https://www.my-store.com]
```

A publishable key authorizes event submission for one tenant and nothing more,
which is why it may be embedded in browser code. Setting `enabled: false`
revokes it.

### Step 2: Create the client

```bash
npm install @omnirec/commerce-web
```

```js
import { createOmnirec } from "@omnirec/commerce-web";
import { autocapture } from "@omnirec/commerce-web/autocapture";
import { dom } from "@omnirec/commerce-web/dom";

export const omnirec = createOmnirec({
  endpoint: "/omnirec",              // or https://events.my-store.com
  // apiKey: "pk_live_xxxxx",        // keys mode only
  plugins: [autocapture(), dom()],
});
```

`autocapture()` records page views (including single-page navigation),
campaign parameters, scroll depth and dwell time. `dom()` turns
`data-omnirec-*` attributes into events ([html-attributes.md](html-attributes.md)).
The SDK refuses to start if `apiKey` looks like a secret, such as `sk_…`, an AWS
access key or a PEM block. Without a bundler, use the script tag build
(`dist/omnirec.min.js`, see [frontend-sdk.md](frontend-sdk.md)).

### Step 3: Record interactions

```js
omnirec.track("search_performed", { search: { query: "gaming laptop", resultsCount: 24 } });
omnirec.track("category_viewed", { category: { id: "laptops" } });
omnirec.track("product_viewed", { product: { id: "p123", price: 1500, currency: "USD" } });
omnirec.track("add_to_cart", { product: { id: "p123", quantity: 1 }, cart: { id: "c1" } });
omnirec.track("checkout_started", { cart: { id: "c1" } });
```

`track()` is typed from the catalog. TypeScript knows every event, its aliases
(`add_to_cart` is `product_added_to_cart`) and its required fields. Your own
events come from the tracking plan ([custom-events.md](custom-events.md)).
Identifiers, timestamps, URLs and device attributes are attached by the SDK.
The full list of events is in the [event reference](events/README.md).

Or, without JavaScript:

```html
<button data-omnirec-event="product_added_to_cart" data-omnirec-product="p123" data-omnirec-quantity="1">
  Add to cart
</button>
```

### Step 4: Identify the visitor

```js
omnirec.identify("customer_123");   // after sign-in, or on each page load once known
omnirec.logout();                   // on sign-out: a fresh anonymous id from here on
```

Calling `identify` on every page load is fine: repeated calls for the same user
emit nothing. Use a stable internal customer id, not an email address. The
anonymous id and session id are also kept in first-party cookies
(`omnirec_anonymous_id`, `omnirec_session_id`), so your server's events join
the same journey ([§5](#5-server-side-integration)).

### Step 5: Consent

```js
import { consent } from "@omnirec/commerce-web/consent";
const cmp = consent();
const omnirec = createOmnirec({ endpoint: "/omnirec", plugins: [cmp] });
cmp.set({ analytics: true, marketing: false });   // from your consent banner
```

Events wait until the visitor decides. They are then sent or dropped, by
category.

### React and Next.js

```tsx
// Next.js: app/layout.tsx
import { OmnirecNextProvider } from "@omnirec/commerce-next";
<OmnirecNextProvider endpoint="/omnirec">{children}</OmnirecNextProvider>

// React
import { OmnirecProvider } from "@omnirec/commerce-react";
<OmnirecProvider endpoint="/omnirec"><App /></OmnirecProvider>
```

```tsx
"use client";
import { useRef } from "react";
import { useTrack, useImpression, Track } from "@omnirec/commerce-react";

export function ProductCard({ product }) {
  const track = useTrack();
  const ref = useRef<HTMLDivElement>(null);
  useImpression(ref, "product_list_viewed", { list: { id: "home", productIds: [product.id] } });
  return (
    <div ref={ref}>
      <Track event="product_clicked" data={{ product: { id: product.id } }}>
        <a href={product.url}>{product.name}</a>
      </Track>
      <button onClick={() => track("add_to_cart", { product: { id: product.id, quantity: 1 } })}>Add</button>
    </div>
  );
}
```

The provider is safe under React Strict Mode. The Next.js package also tracks
App Router navigation, and gives server code the visitor's identity
(`@omnirec/commerce-next/server`). For Vue, use `@omnirec/commerce-vue`
(`app.use(OmnirecPlugin, { endpoint })`, `v-track`, `v-impression`).

### Recommendation attribution

Name the provider that produced a recommendation list, so attribution is
forwarded only to that provider:

```js
omnirec.track("recommendation_impression", {
  recommendation: { id: "rec_123", provider: "amazon-personalize" },   // or "google-retail"
  list: { id: "homepage", productIds: ["p1", "p2"] },
});
omnirec.track("recommendation_clicked", { recommendation: { id: "rec_123" }, product: { id: "p2" } });
```

### Diagnostics

```js
import { debug } from "@omnirec/commerce-web/debug";
createOmnirec({ endpoint: "/omnirec", plugins: [debug()] });
```

In development, `debug()` logs each event and warns about anything the
collector will reject: a missing field, a negative quantity, a misspelt event
name (with a suggestion). The production bundle checks only required fields.
`omnirec dev` shows the same problems from the collector's side.

## 5. Server-side integration

Events that a browser cannot confirm, including settled payments, refunds,
cancelled orders, and verified reviews, are reported from the merchant backend.

```xml
<dependency>
  <groupId>io.omnirec</groupId>
  <artifactId>commerce-tracker-spring-boot</artifactId>
  <version>2.0.0</version>
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
                .anonymousId(order.getTrackingAnonymousId())   // recommended
                .items(order.getLines().stream()
                        .map(l -> CommerceItem.of(l.getSku(), l.getQuantity(), l.getPrice(), "USD"))
                        .toList())
                .total(order.getTotal())
                .currency("USD")
                .build());
    }
}
```

Three properties of the server-side SDK are relevant to integration:

- **Non-blocking delivery.** Events are placed on a bounded in-memory queue and
  transmitted by a background thread. Transient failures are retried for
  approximately three minutes. An Event API outage cannot delay order
  placement.
- **Idempotency.** The `eventId` for a purchase is derived from the order
  identifier (`evt:purchase_completed:<orderId>`), and the browser SDK derives
  the same identifier. A purchase reported by a retried webhook, a reloaded
  confirmation page, or both SDKs is therefore delivered once.
- **Anonymous identifier propagation.** Read `omnirec_anonymous_id` from the
  cookie at checkout and persist it with the order. It links the purchase to the
  anonymous browsing that preceded it.

For purchases that must survive a prolonged outage combined with a process
restart, record them in a transactional outbox and replay from it. Deterministic
event identifiers make replay safe. See [spring-boot-sdk.md](spring-boot-sdk.md).

## 6. Provider configuration

Destinations are disabled by default. Enabling one requires a configuration flag
and the provider credentials, both applied to the Event API only.

### Amazon Personalize

```yaml
omnirec:
  destinations:
    amazon-personalize:
      enabled: true
      region: us-east-1
      tracking-id: ${AWS_PERSONALIZE_TRACKING_ID}
      property-keys: [price, currency]    # keys defined by the interactions schema
```

Credentials are resolved through the AWS default provider chain: an IAM role in
production, or `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in development.
The adapter observes the documented API constraints: a maximum of 10 events per
request, `userId` populated only for authenticated visitors, recommendation
identifiers forwarded only when issued by Personalize, and unsupported event
types skipped. See [amazon-personalize.md](amazon-personalize.md).

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
production, or `GOOGLE_APPLICATION_CREDENTIALS` in development. Google Retail
defines seven user event types; the adapter transmits those and skips all
others. The `visitorId` field always carries the anonymous device identifier,
with the customer identifier supplied in `userInfo`. See
[google-retail.md](google-retail.md).

### Recently viewed

```yaml
omnirec:
  destinations:
    recently-viewed:
      enabled: true
      host: ${CACHE_REDIS_HOST}    # the Redis instance read by the serving side
      tenant-id: my-store          # required when more than one tenant submits events
```

This destination maintains the lists returned by `/v1/recently-viewed` for
authenticated visitors.

Any number of destinations may be enabled simultaneously. Because each has its
own queue set, a failure affecting one provider does not delay another.

> **Verification status.** The Amazon Personalize and Google Retail adapters are
> validated against the providers' published API constraints and a local capture
> server. They have not been executed against live provider accounts. An initial
> run against a test dataset or project is recommended.

## 7. Deployment

### Building and running

```bash
cd backend && mvn clean install              # builds all modules and runs 312 tests
java -jar omnirec-event-api-app/target/omnirec-event-api-app-*.jar
```

A container image is also provided:
`docker build -f backend/omnirec-event-api-app/Dockerfile .`. The image runs as
a non-root user. The service listens on port 8081.

### Runtime dependencies

| Dependency | Purpose | Configuration |
| --- | --- | --- |
| RabbitMQ 3.13 or later | Durable queue between ingestion and delivery | `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` |
| Redis | Shared deduplication and identity links, required for multiple instances | `REDIS_STATE_ENABLED=true`, `REDIS_HOST`, `REDIS_PORT` |
| Provider credentials | Required only for enabled destinations | See [section 6](#6-provider-configuration) |

Publisher confirms and publisher returns must be enabled; both are set in the
distributed `application.yml`. The service refuses to start without them,
because an unconfirmed publication cannot be distinguished from a successful one
and would result in event loss.

### Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `QUEUE_ENABLED` | `true` | When `false`, events are dispatched inline without RabbitMQ. Intended for development only. |
| `REDIS_STATE_ENABLED` | `false` | Enables the shared state stores. Required for multi-instance deployments. |
| `DEMO_STORE_API_KEY` | `pk_test_demo_store` | Key for the sample tenant. Replace with the tenants of the deployment. |
| `PERSONALIZE_ENABLED`, `AWS_PERSONALIZE_TRACKING_ID`, `AWS_REGION` | disabled | Amazon Personalize destination. |
| `GOOGLE_RETAIL_ENABLED`, `GOOGLE_PROJECT_NUMBER` | disabled | Google Retail destination. |
| `RECENTLY_VIEWED_ENABLED`, `RECENTLY_VIEWED_REDIS_HOST`, `RECENTLY_VIEWED_TENANT_ID` | disabled | Recently-viewed destination. |

The complete property reference is in [configuration.md](configuration.md).

### Production checklist

- [ ] A distinct `api-key` per tenant, and `cors.allowed-origins` restricted to
      the production storefront origins.
- [ ] `REDIS_STATE_ENABLED=true` for deployments of more than one instance.
      Without it, each instance deduplicates only its own traffic; both
      in-memory stores log a warning at startup when active.
- [ ] `server.forward-headers-strategy: native` when deployed behind a load
      balancer, so that rate limiting observes the originating client address.
- [ ] Provider credentials supplied by an IAM role or workload identity rather
      than environment variables.
- [ ] `/actuator/prometheus` scraped, with alerts configured on
      `omnirec.events.failed` and on dead-letter queue depth.

### Scaling

With Redis providing shared state, the Event API is stateless and may be
replicated. Consumer parallelism is controlled by
`omnirec.processing.concurrency`. Per-destination queues ensure that a slow
provider affects only its own delivery path.

## 8. Operations

### Health and metrics

- `GET /actuator/health` reports service health for load balancer and
  orchestrator probes.
- `GET /actuator/prometheus` exposes the metrics listed below.

| Metric | Description |
| --- | --- |
| `omnirec.events.received`, `validated`, `rejected`, `duplicates`, `queued` | Ingestion counters, tagged by tenant |
| `omnirec.events.processed`, `failed` | Delivery outcome counters |
| `omnirec.provider.delivery.success`, `failure`, `latency` | Per-provider delivery results and latency |
| `omnirec.provider.retries`, `dead_lettered` | Retries scheduled and events abandoned |
| `omnirec.queue.depth{queue=...}` | Queue depth, including retry tiers and dead-letter queues |

### Queues

For each destination:

- `omnirec.events.<destination>`: the main queue;
- `omnirec.events.<destination>.retry.1` through `.retry.5`: one queue per retry
  delay, from which messages return to the main queue automatically;
- `omnirec.events.<destination>.dlq`: events abandoned after retries were
  exhausted or after a permanent failure. Each message carries the
  `x-omnirec-failure-reason` header.

To replay dead-lettered messages, resolve the underlying cause, then move the
messages back onto the `omnirec.events` exchange with routing key
`events.<destination>`. Events already delivered are skipped, so replay is safe.
See [rabbitmq.md](rabbitmq.md).

### Diagnostics

[troubleshooting.md](troubleshooting.md) is organized by symptom and covers
events not arriving, events accepted but not delivered, duplicate delivery,
inflated view counts, missing `userId` values, and startup failures.

## 9. Extending the system

Support for an additional provider, data warehouse, or internal service is
implemented as a single class. No change is required in the SDKs, the gateway,
or the queue layer.

```java
public class MyProviderDestination implements EventDestination {

    @Override
    public String id() {
        return "my-provider";                  // used in configuration, queue names, and metrics
    }

    @Override
    public boolean supports(CommerceEvent event) {
        return !event.eventType().isControlEvent()
                && !event.isEngagementUpdate();     // dwell updates are not new interactions
    }

    @Override
    public void send(CommerceEvent event) {
        try {
            client.post(map(event));
        } catch (TimeoutException e) {
            throw new DestinationException(id(), "timed out", e);            // retried
        } catch (BadRequestException e) {
            throw DestinationException.permanent(id(), e.getMessage());      // dead-lettered
        }
    }
}
```

Register the implementation as a bean, preferably from a dedicated
auto-configuration guarded by an `omnirec.destinations.my-provider.enabled`
property, consistent with the existing adapters. The required queues are
declared automatically.

Implementations must observe the following contract:

- **Idempotency.** Delivery is at-least-once, so an implementation may receive
  the same event more than once.
- **Transient failures must throw.** Returning normally signals successful
  handling, so a suppressed error results in permanent event loss.
- **Permanent failures must use `DestinationException.permanent(...)`**, which
  routes the event directly to the dead-letter queue.
- **Events must not be modified.** The event instance is shared across
  destinations.
- **Mapping must be separable from transport**, so that mapping logic can be
  tested exhaustively without credentials.

## 10. Known limitations

- **Provider adapters are unverified against live accounts.** The Amazon
  Personalize and Google Retail adapters are tested against documented API
  constraints and a capture server only.
- **Azure is not implemented.** Azure AI Personalizer is scheduled for
  retirement on 1 October 2026, and a replacement target has not been selected.
- **Rate limiting is per instance.** Across N instances the effective limit is N
  times the configured value.
- **The server-side SDK buffers in memory.** A prolonged outage combined with a
  restart discards queued events. Such events are counted rather than silently
  dropped. Use a transactional outbox for events that must not be lost.
- **Dwell time is best-effort.** Measurements are lost on process termination
  and on certain mobile page-freeze transitions.
- **Queue argument changes require migration** on an existing broker. See
  [rabbitmq.md](rabbitmq.md#upgrading-an-existing-broker).

## 11. Reference documentation

| Document | Contents |
| --- | --- |
| [architecture.md](architecture.md) | Module boundaries and design decisions |
| [event-schema.md](event-schema.md) | Canonical event structure, event taxonomy, validation rules, dwell time |
| [identity.md](identity.md) | Anonymous and authenticated identity, linking, multiple devices, sign-out |
| [frontend-sdk.md](frontend-sdk.md) | Browser SDK: trackers, batching, offline behaviour, configuration |
| [spring-boot-sdk.md](spring-boot-sdk.md) | Server-side SDK: idempotency keys, retry behaviour |
| [rabbitmq.md](rabbitmq.md) | Queue topology, delivery guarantees, retry schedule, operations |
| [amazon-personalize.md](amazon-personalize.md), [google-retail.md](google-retail.md) | Provider mapping details |
| [security.md](security.md) | Key handling, tenant isolation, sensitive data, network controls |
| [configuration.md](configuration.md) | Complete property reference |
| [testing.md](testing.md) | Test suites and the properties each verifies |
| [troubleshooting.md](troubleshooting.md) | Symptom-based diagnostics |
| [AUDIT.md](AUDIT.md) | Audit findings, resolutions, and current status |
