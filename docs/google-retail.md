# Google Cloud Retail destination

Maps canonical events onto Retail's `UserEvent`.

This adapter exists as much to prove a point as to serve Google: it was added
without changing a single line of the core, the gateway, the queue, or either
SDK. That is the test of whether the abstraction is real.

## Enable

```yaml
omnirec:
  destinations:
    google-retail:
      enabled: true
      project-number: ${GOOGLE_PROJECT_NUMBER}
      location: global
      catalog-id: default_catalog
```

Authentication uses **Application Default Credentials** — workload identity on
GKE, a service account on Cloud Run, `GOOGLE_APPLICATION_CREDENTIALS` locally.
No key material in configuration.

## Two differences from Amazon

Getting either wrong produces an integration that looks fine and silently trains
on nonsense.

### 1. Identity is two fields, not one

```
anonymousId  ->  visitorId          (always, even after login)
userId       ->  userInfo.userId    (alongside, not instead)
```

`visitorId` is the **anonymous visitor** and is required on every event.
`userInfo.userId` is the authenticated customer.

Putting the `userId` into `visitorId` — the obvious-looking shortcut — breaks
Google's own session stitching, because the same person then looks like two
different visitors either side of signing in.

Note this is the **opposite shape** from Personalize, which wants `userId`
omitted for anonymous visitors. Same canonical event, two correct-but-different
mappings — which is exactly why the mapping belongs in the adapter and not in the
canonical model.

Asserted by `GoogleRetailMappingTest.visitorIdStaysAnonymousEvenWhenTheVisitorIsLoggedIn`.

### 2. The event vocabulary is closed

Retail accepts exactly seven event types ([docs](https://docs.cloud.google.com/retail/docs/user-events)):

| Canonical | Retail |
|---|---|
| `home_page_viewed` | `home-page-view` |
| `category_viewed`, `product_list_viewed` (with a category) | `category-page-view` |
| `product_viewed` | `detail-page-view` (exactly one product) |
| `search_performed` | `search` |
| `cart_viewed` | `shopping-cart-page-view` |
| `product_added_to_cart` | `add-to-cart` |
| `purchase_completed` | `purchase-complete` |

**Anything not in this table is dropped, not coerced.** In particular:

- `product_removed_from_cart` and `page_viewed` are dropped. `remove-from-cart` and `page-visit` are **not** valid Retail types. An earlier version of this adapter sent both; Retail rejected them and they were dead-lettered (audit finding G1).
- Clicks (`product_clicked`, `search_result_clicked`, `recommendation_clicked`) are dropped. The detail page the click leads to sends its own `product_viewed`, so mapping the click too would count every visit twice.
- `recommendation_added_to_cart` and `recommendation_purchased` are dropped for the same reason: the add and the purchase are sent as themselves.
- Dwell-time engagement updates (a `product_viewed` carrying `viewEventId`) are dropped, so a view is counted once.
- An event missing a field Retail requires (a category view with no category, a search with no query) is skipped rather than sent to be rejected.

Also dropped, with no genuine counterpart: session and user events, wishlist/share/compare, reviews, `cart_quantity_updated`, `cart_abandoned`, all `checkout_*`, `purchase_failed`, `order_cancelled`, `order_refunded`, and `identify`.

## Required fields per type

Retail rejects an event outright if these are missing, so the mapper always
supplies them:

| Retail type | Also required |
|---|---|
| `search` | `searchQuery` |
| `category-page-view` | `pageCategories` |
| `purchase-complete` | `purchaseTransaction` (id, revenue, currency) |

## Other mappings

| Canonical | Retail |
|---|---|
| `commerce.productId` / `items` / `productIds` | `productDetails[]` |
| `commerce.quantity` | `productDetails[].quantity` |
| `commerce.recommendationId` | `attributionToken`, **only** when `commerce.recommendationProvider` is `google-retail` |
| `context.url` | `uri` |
| `context.referrer` | `referrerUri` |
| `timestamp` | `eventTime` |
| `identity.sessionId` | `sessionId` |

`attributionToken` must be a token Google itself returned from a predict or
search call. Forwarding another engine's id would be invalid, so the adapter
sets it only when the recommendation came from Google (audit finding G3). To get
attribution, pass the token you received as `recommendationId` with
`recommendationProvider: "google-retail"`.

## Failure classification

gRPC status codes carry an explicit retryability flag, so we take Google's own
judgement rather than guessing. `UNAVAILABLE`, `DEADLINE_EXCEEDED`,
`RESOURCE_EXHAUSTED`, and `INTERNAL` are also treated as retryable. Everything
else — `INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED` — is dead-lettered
immediately.

A missing `project-number` is a permanent failure.

## Setup checklist

1. Enable the Retail API on the project.
2. Grant the Event API's identity `roles/retail.editor`.
3. Import a product catalog — Retail rejects events for unknown products.
4. Accumulate events, then create and train a model.

## Testing

`GoogleRetailMappingTest` covers the mapping against no GCP project at all. The
mapper is a separate class from the destination precisely so every vocabulary and
identity decision is assertable without credentials.

## References

- [User events](https://cloud.google.com/retail/docs/user-events)
- [UserEvent reference](https://cloud.google.com/retail/docs/reference/rest/v2/projects.locations.catalogs.userEvents)
- [Event types](https://cloud.google.com/retail/docs/user-events#types)
