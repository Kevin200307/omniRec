# Frontend SDK — `@omnirec/commerce-web`

Works with vanilla JavaScript, React, and Next.js. The core package has **no
React dependency**; `@omnirec/commerce-react` is a ~40-line binding over it.

## Install

```bash
npm install @omnirec/commerce-web
# React/Next.js, optional:
npm install @omnirec/commerce-react
```

## Set up

```js
import { createCommerceClient } from "@omnirec/commerce-web";

const commerce = createCommerceClient({
  apiKey: "pk_live_xxxxx",             // publishable — safe in browser code
  endpoint: "https://events.example.com",
  tenantId: "my-store",
});
```

That is the whole configuration surface a storefront needs. There is no place to
put an AWS key, and the SDK **throws at startup** if `apiKey` looks like a secret
credential (`sk_`, `AKIA…`, a PEM block) — a guardrail against the one mistake
this architecture exists to prevent.

### React / Next.js

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

`NEXT_PUBLIC_` is correct here: this key is *meant* to be public.

For product pages, `useProductView` records the view on mount and ends the dwell
measurement on unmount, so client-side navigation away doesn't keep the timer
running:

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
commerce.product.viewEnded()   // ends dwell measurement, e.g. on unmount
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

// recommendationProvider tells adapters whose id this is, so attribution is
// forwarded only to the provider that issued it.
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

You never pass `anonymousId`, `sessionId`, `eventId`, `timestamp`, `url`,
`referrer`, `platform`, or `device` — the SDK attaches all of them.

`commerce.track(eventType, commerce, properties)` exists as an escape hatch, but
reaching for it usually means a tracker method is missing.

### Purchases

`commerce.purchase.*` exists but **prefer the backend SDK**. A browser can't
know whether a payment settled, and a confirmation page can be reloaded,
bookmarked, or never reached. See [spring-boot-sdk.md](spring-boot-sdk.md).

If you do use it, its eventIds are derived from the orderId
(`evt:purchase_completed:<orderId>`), identically to the backend SDK, so a
purchase reported from both sides, or a reloaded confirmation page, is delivered
once.

## Batching

Events buffer and flush on whichever comes first: `maxBatchSize` (default 20) or
`maxWaitMs` (default 5000). They also flush on `pagehide`, on tab hide, and when
the browser comes back online.

However large the backlog, each request carries at most `maxBatchSize` events.
`keepalive` is only used for bodies under 64KB, the browser limit; an earlier
version set it always, so a large backlog failed as a network error and was
retried forever without ever sending.

```js
await commerce.flush();   // force a send
commerce.pending();       // events buffered in memory + on disk
```

The unload path uses `navigator.sendBeacon`, which survives the page going away
where a `fetch` would not. A beacon can't set headers, so the publishable key
rides as a query parameter on that path only.

## Offline and retry

| Response | Behaviour |
|---|---|
| 2xx | Delivered |
| 408, 429 | Retried — the server explicitly asked us to back off |
| Other 4xx | **Dropped.** The payload is wrong; resending identical bytes cannot fix it |
| 5xx, network failure | Retried with exponential backoff and **full jitter** |

Jitter matters more than it looks: without it every browser that hit the same
503 retries in lockstep and recreates the spike that caused it.

Between failed flushes the SDK **backs off** exponentially with jitter, up to 5
minutes, instead of retrying every `maxWaitMs`; coming back online resets it. An
explicit `flush()` ignores the backoff. Buffered events older than
`maxEventAgeMs` (12 hours) are dropped rather than sent: the server remembers
eventIds for 24 hours, and resending an event it has forgotten could
double-count it. A batch in the middle of a retry is beaconed if the page
unloads, and the server drops the copy if the original also lands.

A batch that can't be delivered goes to a bounded `localStorage` buffer
(`maxOfflineEvents`, default 500) and is retried on the next flush, surviving
reloads. The bound is deliberate — an unbounded queue shares the origin's ~5MB
quota with the merchant's own app, so it would eventually break *their* writes.
On overflow the oldest events are dropped, because for behavioural data the most
recent signals are the ones still worth having.

## Configuration

| Option | Default | Notes |
|---|---|---|
| `apiKey` | — | Required. Publishable key. |
| `endpoint` | — | Required. Absolute http(s) URL. |
| `tenantId` | — | Optional; the key already identifies the tenant. |
| `sessionTimeoutMs` | 1800000 | Inactivity before a new session. |
| `autoTrackSessions` | `true` | Emit `session_started` automatically. |
| `autoTrackDwellTime` | `true` | Measure time-on-product. |
| `maxBatchSize` | 20 | |
| `maxWaitMs` | 5000 | |
| `maxRetries` | 3 | |
| `maxOfflineEvents` | 500 | |
| `maxEventAgeMs` | 43200000 | 12h; keep below the server's 24h dedup window |
| `validateEvents` | `true` | Reject invalid events client-side. |
| `onError` | `console.warn` | Validation failures and dropped batches. |
| `debug` | `false` | Log every event as it's built. |

Invalid configuration throws immediately. A tracker that silently no-ops because
of a typo is far worse than one that fails while you're looking at it.

## Privacy

`context.url` and `context.referrer` are scrubbed before an event is queued:
query parameters such as `token`, `code`, `email`, `session`, and anything ending
in `token` are removed, as are any parameter whose value looks like an email
address, URL credentials, and the fragment. The Event API applies the same list
server-side, and a contract test keeps the two identical.

## SSR

Every browser API is guarded, so importing the SDK in a server component or
during SSR is safe — it degrades to in-memory identity rather than throwing.
Create the client in a `"use client"` component.
