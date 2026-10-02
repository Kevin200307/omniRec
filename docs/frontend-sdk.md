# Frontend SDK: `@omnirec/commerce-web`

The browser SDK supports vanilla JavaScript, React, and Next.js. The core
package has no React dependency; `@omnirec/commerce-react` provides a thin
binding over it.

## Installation

```bash
npm install @omnirec/commerce-web
# For React and Next.js applications:
npm install @omnirec/commerce-react
```

## Quick start (v2)

```js
import { createOmnirec } from "@omnirec/commerce-web";

const omnirec = createOmnirec({ endpoint: "/omnirec" }); // or "https://events.example.com"

omnirec.track("product_viewed", { product: { id: "P100", price: "12.50", currency: "USD" } });
omnirec.track("product_added_to_cart", { product: { id: "P100", quantity: 1 }, cart: { id: "c1" } });
omnirec.identify("customer_123");
```

- **The endpoint is the only required setting.** Add `apiKey` only when your
  collector runs in keys mode. A path such as `/omnirec` posts to your own
  site, which you proxy to the collector; no CORS setup is needed.
- **`track()` is typed from the event catalog.** Event names autocomplete, and a
  missing required field or a misspelled name is a compile error. Use
  `trackUntyped(name, data)` for names known only at runtime.
- **Custom events** from your tracking plan become typed after
  `npx omnirec generate`, which augments `OmnirecCustomEvents`.
- **Events use envelope v2**: `event` plus `data` blocks (`product`, `cart`,
  `order`, ...). See the [event reference](events/README.md).
- **Middleware** sees every valid event before batching:
  `createOmnirec({ endpoint, middleware: [(event, next) => next(event)] })`.
  Not calling `next` drops the event.
- **Plugins** add HTML attributes, impressions, autocapture, consent and debug
  output, each a separate import such as `@omnirec/commerce-web/dom`. See
  [Tracking with HTML attributes](html-attributes.md).
- The session id is mirrored to a first-party `omnirec_session_id` cookie so your
  backend SDK can attach server events to the same session. Set `cookieDomain`
  to share identity across subdomains.

The core is under 10 KB gzipped; CI enforces the budget.

## Initialization (v1 helpers, deprecated)

`createCommerceClient` keeps the v1 per-event helper methods. They take the v1
flat payload and convert it to v2 blocks before sending. They remain for
existing integrations; new code should use `createOmnirec` and `track()`.

```js
import { createCommerceClient } from "@omnirec/commerce-web";

const commerce = createCommerceClient({
  endpoint: "https://events.example.com",
  apiKey: "pk_live_xxxxx",             // optional; publishable, safe in browser code
  tenantId: "my-store",
});
```

This is the complete configuration surface required by a storefront. There is no
field for a provider credential, and the SDK throws during initialization if
`apiKey` resembles a secret credential, such as a value prefixed with `sk_`, an
AWS access key identifier, or a PEM block. This guards against the specific
mistake the architecture exists to prevent.

### React and Next.js

```tsx
// app/providers.tsx
"use client";
import { CommerceProvider } from "@omnirec/commerce-react";

export function Providers({ children }) {
  return (
    <CommerceProvider
      apiKey={process.env.NEXT_PUBLIC_OMNIREC_API_KEY}
      endpoint={process.env.NEXT_PUBLIC_OMNIREC_ENDPOINT}
      tenantId="my-store"
    >
      {children}
    </CommerceProvider>
  );
}
```

```tsx
"use client";
import { useCommerce } from "@omnirec/commerce-react";

export function ProductCard({ product }) {
  const commerce = useCommerce();
  return (
    <button onClick={() => commerce.product.viewed({ productId: product.id })}>
      {product.title}
    </button>
  );
}
```

The `NEXT_PUBLIC_` prefix is appropriate here, because the key is intended to be
public.

For product pages, `useProductView` records the view on mount and ends the
dwell-time measurement on unmount, so that client-side navigation away from the
page does not leave the timer running:

```tsx
import { useProductView } from "@omnirec/commerce-react";

function ProductPage({ product }) {
  useProductView({ productId: product.id, price: product.price, currency: "USD" });
  return <h1>{product.title}</h1>;
}
```

## The trackers

```js
commerce.session.started()
commerce.session.ended()

commerce.page.viewed()
commerce.home.viewed()

commerce.search.performed({ query: "gaming laptop", resultCount: 24 })
commerce.search.resultClicked({ query: "gaming laptop", productId: "p123", position: 3 })

commerce.productList.viewed({ listId: "gaming-laptops", productIds: ["p1", "p2", "p3"] })
commerce.category.viewed({ categoryId: "gaming-laptops" })

commerce.product.viewed({ productId: "p123", categoryId: "gaming-laptops", price: 1500, currency: "USD" })
commerce.product.viewEnded()   // ends dwell measurement, for example on unmount
commerce.product.clicked({ productId: "p123" })
commerce.product.wishlisted({ productId: "p123" })
commerce.product.shared({ productId: "p123", method: "whatsapp" })
commerce.product.compared({ productId: "p123" })
commerce.product.reviewViewed({ productId: "p123" })
commerce.product.reviewSubmitted({ productId: "p123", rating: 5 })

commerce.cart.viewed({ cartId: "cart_123" })
commerce.cart.productAdded({ cartId: "cart_123", productId: "p123", quantity: 2, price: 1200, currency: "USD" })
commerce.cart.productRemoved({ cartId: "cart_123", productId: "p123" })
commerce.cart.quantityUpdated({ cartId: "cart_123", productId: "p123", previousQuantity: 1, newQuantity: 3 })

commerce.checkout.started({ cartId: "cart_123" })
commerce.checkout.shippingInformationAdded({ cartId: "cart_123" })
commerce.checkout.paymentInformationAdded({ cartId: "cart_123", paymentMethod: "card" })
commerce.checkout.completed({ cartId: "cart_123", orderId: "order_1" })
commerce.checkout.failed({ cartId: "cart_123", reason: "declined" })

// recommendationProvider identifies the issuer of the recommendation, so that
// attribution is forwarded only to the provider that produced it.
commerce.recommendation.impression({ recommendationId: "rec_123", recommendationProvider: "amazon-personalize", productIds: ["p1", "p2"], source: "homepage" })
commerce.recommendation.clicked({ recommendationId: "rec_123", productId: "p2" })
commerce.recommendation.addedToCart({ recommendationId: "rec_123", productId: "p2" })
commerce.recommendation.purchased({ recommendationId: "rec_123", productId: "p2" })

commerce.user.registered({ userId: "customer_123" })
commerce.user.loggedIn({ userId: "customer_123" })
commerce.user.loggedOut()
commerce.user.profileUpdated({ userId: "customer_123" })

commerce.identify({ userId: "customer_123" })
commerce.logout()
```

The caller never supplies `anonymousId`, `sessionId`, `eventId`, `timestamp`,
`url`, `referrer`, `platform`, or `device`. The SDK attaches all of these
fields.

`commerce.track(eventType, commerce, properties)` is available as an escape
hatch, but its use generally indicates a missing tracker method.

### Purchases

The `commerce.purchase.*` methods exist, but the server-side SDK is preferred. A
browser cannot determine whether a payment settled, and a confirmation page may
be reloaded, bookmarked, or never rendered. See
[spring-boot-sdk.md](spring-boot-sdk.md).

If the browser methods are used, their event identifiers are derived from the
order identifier (`evt:purchase_completed:<orderId>`) identically to the
server-side SDK, so a purchase reported from both sources, or a reloaded
confirmation page, results in a single delivery.

## Batching

Events are buffered and flushed when either `maxBatchSize` (20 by default) or
`maxWaitMs` (5000 by default) is reached, whichever occurs first. A flush is
also triggered on `pagehide`, when the tab is hidden, and when the browser
regains connectivity.

Regardless of backlog size, each request carries at most `maxBatchSize` events.
The `keepalive` flag is used only for bodies below 64KB, which is the browser
limit. An earlier implementation set it unconditionally, so a large backlog
failed as a network error and was retried indefinitely without ever being
transmitted.

```js
await commerce.flush();   // force transmission
commerce.pending();       // events buffered in memory and in storage
```

The unload path uses `navigator.sendBeacon`, which completes after the page is
discarded, where `fetch` would not. A beacon cannot set request headers, so the
publishable key is supplied as a query parameter on that path only.

## Offline behaviour and retry

| Response | Behaviour |
| --- | --- |
| 2xx | Delivered |
| 408, 429 | Retried, because the server has explicitly requested a delay |
| Other 4xx | Discarded. The payload is invalid, and resubmitting identical content cannot succeed |
| 5xx, network failure | Retried with exponential backoff and full jitter |

Jitter is significant: without it, every client that received the same 503
retries simultaneously and reproduces the load spike that caused the failure.

Between failed flushes the SDK applies exponential backoff with jitter, up to 5
minutes, rather than retrying at every `maxWaitMs` interval. Regaining
connectivity resets the backoff, and an explicit `flush()` bypasses it. Buffered
events older than `maxEventAgeMs` (12 hours) are discarded rather than
transmitted, because the server retains event identifiers for 24 hours and
resubmitting an event that is no longer recorded could result in double
counting. A batch in the process of being retried is transmitted by beacon if
the page unloads, and the server discards the duplicate if the original request
also completes.

A batch that cannot be delivered is written to a bounded `localStorage` buffer
(`maxOfflineEvents`, 500 by default) and retried on the next flush, surviving
page reloads. The bound is deliberate: an unbounded queue would compete for the
origin's storage quota, which is typically around 5MB and shared with the
merchant application, and would eventually cause that application's writes to
fail. On overflow the oldest events are discarded, because for behavioural data
the most recent signals retain the most value.

## Configuration

| Option | Default | Description |
| --- | --- | --- |
| `apiKey` | none | Required. Publishable key. |
| `endpoint` | none | Required. Absolute HTTP or HTTPS URL. |
| `tenantId` | none | Optional. The key already identifies the tenant. |
| `sessionTimeoutMs` | 1800000 | Inactivity period before a new session begins. |
| `autoTrackSessions` | `true` | Emit `session_started` automatically. |
| `autoTrackDwellTime` | `true` | Measure time spent on a product. |
| `maxBatchSize` | 20 | Maximum events per request. |
| `maxWaitMs` | 5000 | Maximum buffering delay. |
| `maxRetries` | 3 | Retry attempts per batch. |
| `maxOfflineEvents` | 500 | Capacity of the offline buffer. |
| `maxEventAgeMs` | 43200000 | 12 hours. Keep below the server deduplication window of 24 hours. |
| `validateEvents` | `true` | Reject invalid events before transmission. |
| `onError` | `console.warn` | Receives validation failures and discarded batches. |
| `debug` | `false` | Log each event as it is constructed. |

Invalid configuration throws immediately. A tracker that silently performs no
operation because of a configuration error is considerably worse than one that
fails during development.

## Privacy

`context.url` and `context.referrer` are sanitized before an event is queued.
Query parameters such as `token`, `code`, `email`, and `session`, along with any
parameter whose name ends in `token`, are removed, as are parameters whose
values resemble email addresses, URL credentials, and the fragment component.
The Event API applies the same list server-side, and a contract test keeps the
two lists identical.

## Server-side rendering

All browser APIs are guarded, so importing the SDK in a server component or
during server-side rendering is safe; it degrades to in-memory identity rather
than throwing. Create the client within a `"use client"` component.
