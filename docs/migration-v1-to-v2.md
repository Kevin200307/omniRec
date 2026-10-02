# Migrating from 0.x (envelope v1) to 2.0

2.0 keeps accepting everything 0.x sent. Existing storefronts and backends keep
working unchanged, and you can move one piece at a time. This guide lists what
changed and the order that is least work.

## 1. Upgrade the collector first

The 2.0 collector accepts both envelopes:

| v1 (still accepted) | v2 |
| --- | --- |
| `eventType` | `event` |
| flat `commerce: { productId, cartId, total, ... }` | `data` grouped by block: `product`, `cart`, `order`, ... |
| `schemaVersion: "1.0"` | `schemaVersion: "2.0"`, plus `eventVersion`, `kind`, `source` |

v1 events are converted to v2 at the edge (`productId` becomes `product.id`,
`total` becomes `order.total`, and so on), so destinations and storage only
ever see v2. Validation errors name v2 paths, for example `data.product.id`.

Behaviour that changed:

- **Unknown event names are accepted** and flagged `unplanned`
  (`validation-mode: permissive`, the default). They reach storage, never a
  provider. Set `strict` for the old behaviour.
- **Keys are optional.** With no keys configured, the collector runs in `open`
  mode. Configured keys keep working, and `auth-mode: auto` switches to `keys`
  as soon as any tenant has one. `allow-anonymous-ingestion` is deprecated; use
  `auth-mode: open`.
- **Server events no longer need a userId.** The anonymous id from the
  `omnirec_anonymous_id` cookie is enough.
- **History responses** add `event`, `eventVersion`, `kind`, `source` and
  `data`. `eventType` and `commerce` remain for one release.

Storage applies migrations V5 (v2 columns) and V6 (erasure tombstones) on
startup.

## 2. Browser

```js
// 0.x, still works (deprecated)
import { createCommerceClient } from "@omnirec/commerce-web";
const commerce = createCommerceClient({ apiKey: "pk_live_...", endpoint: "https://events.example.com" });
commerce.cart.productAdded({ cartId: "c1", productId: "p123", quantity: 2 });

// 2.0
import { createOmnirec } from "@omnirec/commerce-web";
const omnirec = createOmnirec({ endpoint: "/omnirec" });    // apiKey only in keys mode
omnirec.track("product_added_to_cart", { product: { id: "p123", quantity: 2 }, cart: { id: "c1" } });
```

| 0.x | 2.0 |
| --- | --- |
| `commerce.product.viewed({ productId })` | `track("product_viewed", { product: { id } })` |
| `commerce.user.loggedIn({ userId })` | `identify(userId)` |
| `commerce.user.loggedOut()` | `logout()` |
| `track(type, commerceObject)` | `track(name, data)` with v2 `data` |
| hand-written page views | `autocapture()` plugin |
| per-element JavaScript | `data-omnirec-*` attributes with the `dom()` plugin |

Breaking for TypeScript users only:

- `track()` takes v2 `data`.
- The generated per-domain union types follow the new domain list, for example
  `CartCheckoutEventType`.

The production bundle now checks only required fields. Constraint errors (a
negative quantity) come from the collector, or from the `debug()` plugin during
development.

**React.** `CommerceProvider` still works. `OmnirecProvider` replaces it and
fixes events being dropped under React Strict Mode.

## 3. Java

`CommerceTracker` and its per-event builders keep working. New code uses
`OmnirecTracker`:

```java
tracker.track(StandardEvents.PURCHASE_COMPLETED, Map.of("order", order), customerId, order.getId());
```

| 0.x | 2.0 |
| --- | --- |
| `EventType` / `EventCategory` enums | **removed**: `EventName`, `StandardEvents`, `StandardEventNames` |
| `CommerceEvent.commerce()` | `data()`; `commerce()` is a deprecated v1 view |
| `api-key` required | optional |
| identity passed by hand | read from the browser cookies by `OmnirecIdentityFilter` |

If you implement `EventDestination`, switch on `event.eventType().wireName()`
instead of the enum, and read `event.data()` blocks.

## 4. Then add what is new

Each of these is optional:

- **Custom events:** a tracking plan with `omnirec init`, `generate` and
  `validate`.
- **Webhooks:** Stripe and generic JSON in, signed webhooks out.
- **Derived events:** `omnirec.derived.enabled=true`.
- **Customer deletion:** `DELETE /v1/customers/{id}`.
- **Deployment profiles:** `deploy/lite` and `deploy/standard`.

The [CHANGELOG](../CHANGELOG.md) has the complete list.
