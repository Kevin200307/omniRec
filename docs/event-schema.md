# Event schema

Event names and their required fields are defined once, in the
[event catalog](../catalog/README.md). `npm run catalog:generate` produces the
TypeScript types, the Java constants, the event-name enum in
[`schema/commerce-event.schema.json`](../schema/commerce-event.schema.json), and
the [event reference](events/README.md). CI fails if any generated file is stale.

The envelope itself, meaning the fields every event shares, is still defined in
[`packages/commerce-web/src/events/types.ts`](../packages/commerce-web/src/events/types.ts),
`io.omnirec.commerce.model.CommerceEvent`, and the schema.
`CanonicalSchemaContractTest` parses all three and fails the build if they
disagree.

## Structure

```jsonc
{
  "eventId": "9f1c...",            // unique; the deduplication key
  "eventType": "product_viewed",
  "schemaVersion": "1.0",
  "timestamp": "2026-01-01T12:00:00.000Z",
  "tenantId": "demo-store",        // set by the server from the API key

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
    "ip": null,                    // derived by the server, discarded by default
    "country": "GB"                // derived by the server
  },

  "commerce": {
    "productId": "p123",
    "categoryId": "laptops",
    "price": 1500.00,
    "currency": "USD"
  },

  "properties": { "dwellTimeMs": 42500 },

  "receivedAt": "2026-01-01T12:00:01.113Z"  // set by the server
}
```

The fields `tenantId`, `ip`, `country`, and `receivedAt` are owned by the
server. Client-supplied values for these fields are discarded.

## Taxonomy

The full list of events, grouped by domain with their sources, required fields
and examples, is generated from the catalog: see the
[event reference](events/README.md).

The `identify` event is the only addition to the specified taxonomy. It is a
control event: it establishes an identity link and is never delivered to a
provider, because it carries no behavioural signal.

## Validation rules

Field requirements are defined per event type rather than globally. For example,
`productId` is mandatory for `product_viewed` and has no meaning for
`session_started`.

Required for every event: `eventId`, `eventType`, `schemaVersion`, a valid
`timestamp`, `identity.anonymousId`, and `identity.sessionId`.

The additional fields each event requires are listed in the
[event reference](events/README.md), generated from the catalog. For example,
`product_added_to_cart` requires `productId` and a `quantity` of at least 1, and
`purchase_completed` requires `orderId`, non-empty `items`, an ISO 4217
`currency` and a non-negative `total`.

Session events and `page_viewed` require nothing beyond the universal fields.
This is intentional.

An event is also rejected in full if a sensitive field name appears anywhere
within it, at any depth. See [security.md](security.md).

## Dwell time

`product.viewed()` starts a timer. The timer pauses when the tab is hidden, as
reported by the Page Visibility API, and resumes when the tab becomes visible
again, so that only foreground time is accumulated. The total is emitted when
the visitor views another product, the route changes through `page.viewed()`,
the product component unmounts through `product.viewEnded()` or the
`useProductView` hook, or the page unloads.

The total is emitted as an engagement update: a second `product_viewed` event
carrying `dwellTimeMs` and `viewEventId`, where `viewEventId` is the `eventId` of
the view being measured.

```jsonc
{ "eventType": "product_viewed", "commerce": { "productId": "p123" },
  "properties": { "dwellTimeMs": 42500, "viewEventId": "9f1c..." } }
```

The engagement update is not a second view. Interaction-counting destinations,
including Amazon Personalize and Google Retail, skip any event carrying
`viewEventId`, so each view is counted once, while an analytics destination can
join the two events on that field. An earlier implementation emitted the update
without the link, and both providers counted every product view twice (audit
finding D1). The same implementation also allowed the timer to continue across
single-page-application navigation, so time spent on subsequent pages was
attributed as dwell time (audit finding D2).

The client's `flush()` method does not end a measurement. Flushing concerns
transport and does not indicate that the visitor has left the product.

Measurement constraints:

- **Presence rather than attention.** A focused tab that the visitor is not
  looking at continues to accumulate time. No browser API reports visual
  attention.
- **Process termination discards measurements in progress.** Only completed
  measurements reach the transmission buffer.
- **Mobile browsers** may freeze a backgrounded page without emitting
  `pagehide`. The timer still stops on `visibilitychange`, but the update is
  transmitted only if the page later resumes or unloads normally.
- **Page reload** emits `pagehide` first, so the measurement is flushed.
- **Values are censored at the configured maximum.** Above `maxDwellMs` (30
  minutes by default) the value recorded is exactly the maximum and should be
  interpreted as a lower bound rather than a measurement. Without this limit, a
  tab left open overnight would report several hours of engagement and distort
  any aggregate computed from the data.
- **Measurements below `minDwellMs`** (1 second by default) are discarded as
  scroll-through noise.

Periodic heartbeats are deliberately not used. Emitting an event every few
seconds would provide resilience against process termination at the cost of a
substantial and largely unread increase in event volume.

## Cart abandonment

The `cart_abandoned` event is not produced by the browser, and `CartTracker` in
the frontend SDK provides no `abandoned()` method.

Closing a tab does not constitute cart abandonment, nor does navigating away,
losing connectivity, or switching to another device to complete the purchase.
The browser can observe all of these and cannot distinguish between them.

Cart abandonment is a derived event, and only the merchant backend holds the
state required to derive it:

```
the cart has items
AND no order exists for it
AND it has not been modified for longer than the configured threshold
```

A merchant therefore runs a scheduled sweep over their own cart table and calls
`commerce.cart.abandoned(...)` through the server-side SDK. The `eventId` is
derived from the `cartId`, so a sweep that runs every five minutes reports a
stale cart once rather than once per execution.

## Versioning

`schemaVersion` is `1.0` and is incremented only for a breaking change. Adding
an optional field is not breaking. The Event API ignores unknown fields, so a
newer SDK may be deployed before the API is updated without causing batch
rejections.
