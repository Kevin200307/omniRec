# Amazon Personalize destination

Maps canonical events onto Personalize's `PutEvents` API.

## Enable

```yaml
omnirec:
  destinations:
    amazon-personalize:
      enabled: true
      region: us-east-1
      tracking-id: ${AWS_PERSONALIZE_TRACKING_ID}
      # Keys from YOUR interactions schema to send in `properties` (camelCase).
      # Empty by default; see "Properties" below.
      property-keys: [categoryId, currency]
      # endpoint-override: http://localhost:4566   # LocalStack / capture server only
```

Credentials come from the **AWS default provider chain** — an IAM role or
instance profile in production, `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
locally. There is deliberately no `access-key` property, so there's no supported
way to commit a long-lived secret.

## Identity — the part that's easy to get wrong

Personalize identifies interactions by `userId` **or** `sessionId`.

For an anonymous visitor we send the session and **omit `userId` entirely**.

This is the bug most naive integrations ship: putting the anonymous id into
`userId` creates a distinct Personalize "user" per browser that will never be
reconciled with the real customer, permanently fragmenting their history.

Personalize stitches an anonymous session to a user when a later `PutEvents` call
carries the same `sessionId` together with a `userId` — which is exactly what
happens when our SDK identifies mid-session.

| Our event | `userId` | `sessionId` |
|---|---|---|
| Anonymous | *(omitted)* | `session_1` |
| Authenticated | `customer_123` | `session_1` |

Asserted by `AmazonPersonalizeMappingTest.anAnonymousVisitorSendsNoUserIdAtAll`.

## Field mapping

| Canonical | Personalize |
|---|---|
| `eventId` | `eventId` (Personalize's own dedup key) |
| `eventType` | `eventType` — our wire names verbatim; Personalize accepts free-form strings |
| `timestamp` | `sentAt` |
| `commerce.productId` | `itemId` |
| `commerce.total` or `commerce.price` | `eventValue` |
| `commerce.productIds` | `impression` — recommendation and list events only, first 25 |
| `commerce.recommendationId` | `recommendationId` — only when `recommendationProvider` is `amazon-personalize`, ≤40 chars |
| allow-listed `commerce` / `properties` keys | `properties` (JSON string map, ≤1024 chars) |

`eventValue` matters: it weights interactions, so a £1,500 purchase doesn't count
the same as a £5 one.

`impression` tells Personalize what the shopper was *shown* but didn't pick,
which is what makes a click informative rather than merely positive.

## Properties

Personalize is strict about `properties`
([Event](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_Event.html)):
a JSON string map of at most **1024 characters**, whose keys must be fields of
**your** interactions schema, and which **may not contain** `userId`,
`sessionId`, `eventType`, `timestamp`, `recommendationId`, or `impression`.

An earlier version of this adapter forwarded every commerce field and merchant
property, including `recommendationId`, so every recommendation event was
rejected (audit finding P2). Now:

- Only keys listed in `property-keys` are sent. The default is **none**, because
  only you know your schema.
- A reserved key in `property-keys` fails at startup rather than at runtime.
- Values are sent as strings, per the API's string map.
- If the result would exceed 1024 characters it is dropped, with a warning, and
  the interaction is still sent. A truncated JSON string would be invalid and
  fail the whole call.

## Recommendation attribution

`Event.recommendationId` is Personalize's own attribution field. It is only
meaningful for a list Personalize served, so the adapter sets it only when
`commerce.recommendationProvider` is `amazon-personalize` and the id fits the
40-character limit. Pass the `recommendationId` Personalize returned, and name the
provider:

```js
commerce.recommendation.clicked({
  recommendationId: response.recommendationId,
  recommendationProvider: "amazon-personalize",
  productId: "p2",
});
```

## Multi-line orders

Personalize's `Event` carries a single `itemId`, so an order of three products is
split into three interactions — otherwise two thirds of the signal is lost.

Each split line gets its own `eventId` (`<eventId>:<index>`), because Personalize
deduplicates on `eventId` and identical ids would collapse the order back to one
item. Each is weighted by `quantity × price`.

`PutEvents` takes **at most 10 events** per call, so a large order is sent in
chunks of 10 (audit finding P1). If a later chunk fails, the retry resends the
earlier ones too; that is safe, because Personalize ignores repeats of an
`eventId` for training.

## Filtered event types

Sent as-is except for these, which have no useful analogue in an interactions
dataset and would cost money while diluting the signal:

`session_started`, `session_ended`, `user_logged_out`, `user_profile_updated`,
`identify`. Dwell-time engagement updates (a `product_viewed` carrying
`viewEventId`) are skipped too, so each view is one interaction.

## Failure classification

| Condition | Retryable |
|---|---|
| Throttling | yes |
| HTTP 5xx | yes |
| Network / timeout | yes |
| Validation, resource-not-found | **no** — dead-lettered immediately |
| Missing `tracking-id` | **no** — a configuration error, not a transient one |

Retrying a permanently malformed event forever just delays the inevitable while
holding a consumer slot.

## Setup checklist

1. Create a dataset group with an **Interactions** dataset.
2. Create an **Event Tracker** → gives you the `tracking-id`.
3. Grant the Event API's role `personalize:PutEvents`.
4. Let interactions accumulate — Personalize needs a meaningful volume before a
   solution is worth training.
5. Train a solution and deploy a campaign for the serving side.

## Testing

`AmazonPersonalizeMappingTest` covers the mapping exhaustively against a mocked
client — no AWS account, no credentials, no network. The mapper is a separate
class from the destination precisely so this is possible.

## References

- [PutEvents API](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_PutEvents.html)
- [Recording events](https://docs.aws.amazon.com/personalize/latest/dg/recording-events.html)
- [Anonymous users and sessions](https://docs.aws.amazon.com/personalize/latest/dg/recording-item-interaction-events.html)
