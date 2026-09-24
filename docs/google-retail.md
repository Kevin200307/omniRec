# Google Cloud Retail destination

Maps canonical events onto the Google Cloud Retail `UserEvent` resource.

This adapter also serves as a demonstration of the architecture: it was added
without modifying any line of the core, the gateway, the queue layer, or either
SDK. That is the test of whether the `EventDestination` abstraction is genuine.

## Enabling the destination

```yaml
omnirec:
  destinations:
    google-retail:
      enabled: true
      project-number: ${GOOGLE_PROJECT_NUMBER}
      location: global
      catalog-id: default_catalog
```

Authentication uses Application Default Credentials: workload identity on GKE, a
service account on Cloud Run, or `GOOGLE_APPLICATION_CREDENTIALS` in local
development. No key material appears in configuration.

## Differences from the Amazon adapter

Getting either of the following wrong produces an integration that appears to
function while silently training on incorrect data.

### 1. Identity spans two fields

```
anonymousId  ->  visitorId          (always, including after sign-in)
userId       ->  userInfo.userId    (in addition, not instead)
```

`visitorId` identifies the anonymous visitor and is required on every event.
`userInfo.userId` identifies the authenticated customer.

Placing the `userId` in `visitorId`, which appears to be the obvious shortcut,
breaks Google's own session stitching, because the same person then appears as
two different visitors on either side of signing in.

This is the opposite shape from Amazon Personalize, which requires `userId` to be
omitted for anonymous visitors. One canonical event therefore has two correct but
different mappings, which is precisely why the mapping belongs in the adapter
rather than in the canonical model.

Asserted by
`GoogleRetailMappingTest.visitorIdStaysAnonymousEvenWhenTheVisitorIsLoggedIn`.

### 2. The event vocabulary is closed

Retail accepts exactly seven event types
([documentation](https://docs.cloud.google.com/retail/docs/user-events)):

| Canonical event | Retail event |
| --- | --- |
| `home_page_viewed` | `home-page-view` |
| `category_viewed`, `product_list_viewed` (with a category) | `category-page-view` |
| `product_viewed` | `detail-page-view` (exactly one product) |
| `search_performed` | `search` |
| `cart_viewed` | `shopping-cart-page-view` |
| `product_added_to_cart` | `add-to-cart` |
| `purchase_completed` | `purchase-complete` |

Any event not in this table is dropped rather than coerced. In particular:

- `product_removed_from_cart` and `page_viewed` are dropped, because
  `remove-from-cart` and `page-visit` are not valid Retail types. An earlier
  version of this adapter sent both; Retail rejected them and they were
  dead-lettered (audit finding G1).
- Click events (`product_clicked`, `search_result_clicked`,
  `recommendation_clicked`) are dropped. The detail page that a click leads to
  sends its own `product_viewed`, so mapping the click as well would count every
  visit twice.
- `recommendation_added_to_cart` and `recommendation_purchased` are dropped for
  the same reason: the add-to-cart and the purchase are transmitted as
  themselves.
- Dwell-time engagement updates, that is a `product_viewed` event carrying
  `viewEventId`, are dropped so that each view is counted once.
- An event missing a field that Retail requires, such as a category view without
  a category or a search without a query, is skipped rather than transmitted to be
  rejected.

The following also have no genuine counterpart and are dropped: session and user
events, wishlist, share, and compare events, reviews, `cart_quantity_updated`,
`cart_abandoned`, all `checkout_*` events, `purchase_failed`, `order_cancelled`,
`order_refunded`, and `identify`.

## Required fields by type

Retail rejects an event outright if these fields are absent, so the mapper
always supplies them:

| Retail event type | Additional required field |
| --- | --- |
| `search` | `searchQuery` |
| `category-page-view` | `pageCategories` |
| `purchase-complete` | `purchaseTransaction` (identifier, revenue, currency) |

## Other mappings

| Canonical field | Retail field |
| --- | --- |
| `commerce.productId`, `items`, `productIds` | `productDetails[]` |
| `commerce.quantity` | `productDetails[].quantity` |
| `commerce.recommendationId` | `attributionToken`, only when `commerce.recommendationProvider` is `google-retail` |
| `context.url` | `uri` |
| `context.referrer` | `referrerUri` |
| `timestamp` | `eventTime` |
| `identity.sessionId` | `sessionId` |

The `attributionToken` must be a token that Google itself returned from a predict
or search call. Forwarding another engine's identifier would be invalid, so the
adapter sets it only when the recommendation originated from Google (audit
finding G3). To obtain attribution, supply the token you received as
`recommendationId` together with `recommendationProvider: "google-retail"`.

## Failure classification

gRPC status codes carry an explicit retryability flag, so the adapter relies on
Google's own classification rather than inferring one. `UNAVAILABLE`,
`DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, and `INTERNAL` are additionally
treated as retryable. All other codes, including `INVALID_ARGUMENT`,
`NOT_FOUND`, and `PERMISSION_DENIED`, cause the event to be dead-lettered
immediately.

A missing `project-number` is a permanent failure.

## Setup checklist

1. Enable the Retail API on the project.
2. Grant the Event API identity the `roles/retail.editor` role.
3. Import a product catalog. Retail rejects events that refer to unknown
   products.
4. Allow events to accumulate, then create and train a model.

## Testing

`GoogleRetailMappingTest` covers the mapping without requiring a Google Cloud
project. The mapper is implemented as a separate class from the destination so
that every vocabulary and identity decision can be asserted without credentials.

## Verification status

The mapping is validated against the documented API behaviour. The adapter has
not been executed against a live Retail project.

## References

- [User events](https://cloud.google.com/retail/docs/user-events)
- [UserEvent reference](https://cloud.google.com/retail/docs/reference/rest/v2/projects.locations.catalogs.userEvents)
- [Event types](https://cloud.google.com/retail/docs/user-events#types)
