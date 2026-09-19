# Event schema

One event shape, defined three times — in
[`packages/commerce-web/src/events/types.ts`](../packages/commerce-web/src/events/types.ts),
in `io.omnirec.commerce.model.CommerceEvent`, and in
[`schema/commerce-event.schema.json`](../schema/commerce-event.schema.json).

Three copies is a deliberate trade: generating two from one would mean a codegen
step in every build, and the shape changes rarely. What is not acceptable is
silent drift, so `CanonicalSchemaContractTest` parses all three and fails the
build if they disagree.

## Shape

```jsonc
{
  "eventId": "9f1c…",              // unique; the deduplication key
  "eventType": "product_viewed",
  "schemaVersion": "1.0",
  "timestamp": "2026-01-01T12:00:00.000Z",
  "tenantId": "demo-store",        // server-set from the API key

  "identity": {
    "anonymousId": "anon_A",
    "userId": null,
    "sessionId": "session_1"
  },

  "context": {
    "url": "https://shop.example/p/123",
    "path": "/p/123",
    "referrer": "https://google.com",
    "platform": "web",
    "device": "mobile",
    "locale": "en-GB",
    "timezone": "Europe/London",
    "ip": null,                    // server-derived, dropped by default
    "country": "GB"                // server-derived
  },

  "commerce": {
    "productId": "p123",
    "categoryId": "laptops",
    "price": 1500.00,
    "currency": "USD"
  },

  "properties": { "dwellTimeMs": 42500 },

  "receivedAt": "2026-01-01T12:00:01.113Z"  // server-set
}
```

`tenantId`, `ip`, `country`, and `receivedAt` are server-owned. A client value
for any of them is discarded.

## Taxonomy

| Category | Event types |
|---|---|
| **Session** | `session_started`, `session_ended`, `page_viewed`, `home_page_viewed` |
| **Discovery** | `search_performed`, `search_result_clicked`, `product_list_viewed`, `category_viewed`, `product_viewed`, `product_clicked` |
| **Product interaction** | `product_wishlisted`, `product_shared`, `product_compared`, `product_review_viewed`, `product_review_submitted` |
| **Cart** | `cart_viewed`, `product_added_to_cart`, `product_removed_from_cart`, `cart_quantity_updated`, `cart_abandoned` |
| **Checkout** | `checkout_started`, `shipping_information_added`, `payment_information_added`, `checkout_completed`, `checkout_failed` |
| **Purchase** | `purchase_completed`, `purchase_failed`, `order_cancelled`, `order_refunded` |
| **Recommendation** | `recommendation_impression`, `recommendation_clicked`, `recommendation_added_to_cart`, `recommendation_purchased` |
| **User** | `user_registered`, `user_logged_in`, `user_logged_out`, `user_profile_updated` |
| **Control** | `identify` |

`identify` is the one addition to the specified taxonomy. It is a control event:
it establishes an identity link and is **never delivered to a provider**, because
it carries no behavioural signal.

## Validation rules

Requiredness is per event type, not global — `productId` is mandatory for
`product_viewed` and meaningless for `session_started`.

**Every event:** `eventId`, `eventType`, `schemaVersion`, a valid `timestamp`,
`identity.anonymousId`, `identity.sessionId`.

| Event type | Also required |
|---|---|
| `product_viewed`, `product_clicked`, `product_wishlisted`, `product_shared`, `product_compared`, `product_review_*` | `productId` |
| `product_added_to_cart` | `productId`, `quantity` > 0 |
| `product_removed_from_cart`, `cart_quantity_updated` | `productId` |
| `cart_viewed`, `cart_abandoned`, all `checkout_*` | `cartId` |
| `purchase_completed` | `orderId`, non-empty `items`, ISO-4217 `currency`, non-negative `total` |
| `purchase_failed`, `order_cancelled`, `order_refunded` | `orderId` |
| `search_performed` | `searchQuery` |
| `search_result_clicked` | `searchQuery`, `productId` |
| `category_viewed` | `categoryId` |
| `product_list_viewed` | non-empty `productIds` |
| `recommendation_impression` | `recommendationId`, non-empty `productIds` |
| `recommendation_clicked`, `..._added_to_cart`, `..._purchased` | `recommendationId`, `productId` |
| `user_registered`, `user_logged_in`, `user_profile_updated`, `identify` | `userId` |

Session events and `page_viewed` require nothing beyond the universal fields.
That is intentional, not an oversight.

Every event is also rejected outright if a sensitive field name appears anywhere
in it, at any depth — see [security.md](security.md).

## Dwell time

`product.viewed()` starts a timer. It **pauses when the tab is hidden** (Page
Visibility API) and resumes when it returns, so only foreground time counts.
When the visitor moves to another product, the route changes (`page.viewed()`),
the product component unmounts (`product.viewEnded()`, or `useProductView` in
React), or the page unloads, the total is emitted as an **engagement update**: a
second `product_viewed` carrying `dwellTimeMs` and `viewEventId`, the eventId of
the view it measures:

```jsonc
{ "eventType": "product_viewed", "commerce": { "productId": "p123" },
  "properties": { "dwellTimeMs": 42500, "viewEventId": "9f1c…" } }
```

The update is **not a second view**. Interaction-counting destinations
(Personalize, Retail) skip any event with `viewEventId`, so each view is counted
once; an analytics destination can join the two on it. An earlier version sent
the update with no link, and both providers counted every product view twice
(audit finding D1). It also kept the timer running across SPA navigation, so time
on later pages counted as dwell (D2).

`flush()` does not end a measurement: flushing is about transport, not about the
shopper leaving the product.

**What it cannot tell you:**

- **Presence, not attention.** A focused tab the visitor isn't looking at still
  accrues time. No browser API reports eyes-on-screen.
- **Nothing survives a crash.** An in-flight measurement is lost if the tab dies;
  only completed ones reach the buffer.
- **Mobile browsers** may freeze a backgrounded page without firing `pagehide`.
  The clock still stops on `visibilitychange`, but the update is only sent if
  the page later resumes or unloads normally.
- **Refresh** fires `pagehide` first, so the measurement is flushed.
- **Values are censored at the cap.** Above `maxDwellMs` (default 30 minutes) the
  value is recorded as exactly the cap, so treat it as "at least this", not a
  measurement. Without the cap, a tab left open overnight would report eight
  hours of engagement and poison any average built on it.
- **Bounces below `minDwellMs`** (default 1s) are discarded as scroll-through
  noise.

Deliberately **no heartbeats**. Emitting every few seconds would buy crash
resilience at the cost of flooding the pipeline with events nobody reads.

## Cart abandonment

`cart_abandoned` is **not produced by the browser**, and `CartTracker` in the
frontend SDK has no `abandoned()` method at all.

Closing a tab is not abandoning a cart. Neither is navigating away, losing
connectivity, or switching to a phone to finish the purchase — and the browser
can observe all of those and tell none of them apart.

It is a **derived** event, and only the backend has the state to derive it:

```
the cart has items
AND no order exists for it
AND nothing has touched it for longer than the configured threshold
```

So a merchant runs a scheduled sweep over their own cart table and calls
`commerce.cart.abandoned(...)` from the backend SDK. The `eventId` derives from
the `cartId`, so a sweeper running every five minutes reports a stale cart once,
not once per run.

## Versioning

`schemaVersion` is `1.0` and changes only on a breaking change. Adding an
optional field is not breaking. The Event API ignores unknown fields, so a newer
SDK can deploy before the API catches up without 400-ing whole batches.
