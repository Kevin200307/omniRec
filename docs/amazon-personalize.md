# Amazon Personalize destination

Maps canonical events onto the Amazon Personalize `PutEvents` API.

## Enabling the destination

```yaml
omnirec:
  destinations:
    amazon-personalize:
      enabled: true
      region: us-east-1
      tracking-id: ${AWS_PERSONALIZE_TRACKING_ID}
      # Keys from the deployment's own interactions schema to send in
      # `properties`, in camelCase. Empty by default; see "Properties" below.
      property-keys: [categoryId, currency]
      # endpoint-override: http://localhost:4566   # LocalStack or capture server only
```

Credentials are resolved by the AWS default provider chain: an IAM role or
instance profile in production, and `AWS_ACCESS_KEY_ID` with
`AWS_SECRET_ACCESS_KEY` in local development. There is deliberately no
`access-key` property, so there is no supported means of committing a long-lived
secret.

## Identity mapping

Personalize identifies interactions by `userId` or by `sessionId`.

For an anonymous visitor, the adapter sends the session identifier and omits
`userId` entirely.

This is the defect present in many naive integrations: placing the anonymous
identifier in `userId` creates a distinct Personalize user for each browser,
which is never reconciled with the actual customer and permanently fragments
that customer's history.

Personalize associates an anonymous session with a user when a subsequent
`PutEvents` call carries the same `sessionId` together with a `userId`, which is
precisely what occurs when the SDK identifies a visitor mid-session.

| Event | `userId` | `sessionId` |
| --- | --- | --- |
| Anonymous | omitted | `session_1` |
| Authenticated | `customer_123` | `session_1` |

This behaviour is asserted by
`AmazonPersonalizeMappingTest.anAnonymousVisitorSendsNoUserIdAtAll`.

## Field mapping

| Canonical field | Personalize field |
| --- | --- |
| `eventId` | `eventId`, which is the Personalize deduplication key |
| `eventType` | `eventType`, using the canonical wire names unchanged, because Personalize accepts free-form strings |
| `timestamp` | `sentAt` |
| `commerce.productId` | `itemId` |
| `commerce.total` or `commerce.price` | `eventValue` |
| `commerce.productIds` | `impression`, for recommendation and list events only, limited to the first 25 entries |
| `commerce.recommendationId` | `recommendationId`, only when `recommendationProvider` is `amazon-personalize` and the value is 40 characters or fewer |
| Allow-listed `commerce` and `properties` keys | `properties`, a JSON string map of at most 1024 characters |

The `eventValue` field weights interactions, so that a purchase of 1,500 units
of currency is not treated identically to one of 5 units.

The `impression` field records what the visitor was shown but did not select,
which is what makes a click informative rather than merely positive.

## Properties

Personalize applies strict constraints to `properties`, documented under
[Event](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_Event.html):
it is a JSON string map of at most 1024 characters, its keys must be fields of
the deployment's own interactions schema, and it must not contain `userId`,
`sessionId`, `eventType`, `timestamp`, `recommendationId`, or `impression`.

An earlier version of this adapter forwarded every commerce field and merchant
property, including `recommendationId`, so every recommendation event was
rejected (audit finding P2). The current behaviour is as follows:

- Only keys listed in `property-keys` are transmitted. The default is an empty
  list, because only the deployment knows its own schema.
- A reserved key in `property-keys` causes a startup failure rather than a
  runtime failure.
- Values are transmitted as strings, in accordance with the API string map.
- If the encoded result would exceed 1024 characters it is omitted with a
  warning, and the interaction is still transmitted. A truncated JSON string
  would be invalid and would fail the entire call.

## Recommendation attribution

`Event.recommendationId` is the Personalize attribution field. It is meaningful
only for a list that Personalize itself served, so the adapter populates it only
when `commerce.recommendationProvider` is `amazon-personalize` and the
identifier is within the 40-character limit. Supply the `recommendationId`
returned by Personalize together with the provider name:

```js
commerce.recommendation.clicked({
  recommendationId: response.recommendationId,
  recommendationProvider: "amazon-personalize",
  productId: "p2",
});
```

## Multi-line orders

A Personalize `Event` carries a single `itemId`, so an order containing three
products is split into three interactions; otherwise two thirds of the signal
would be discarded.

Each resulting line receives its own `eventId` of the form `<eventId>:<index>`,
because Personalize deduplicates on `eventId` and identical identifiers would
collapse the order back to a single item. Each line is weighted by quantity
multiplied by price.

`PutEvents` accepts at most 10 events per call, so a large order is transmitted
in chunks of 10 (audit finding P1). If a later chunk fails, the retry
retransmits the earlier chunks as well. This is safe, because Personalize
ignores repeated occurrences of an `eventId` for training purposes.

## Filtered event types

All event types are transmitted unchanged except the following, which have no
useful analogue in an interactions dataset and would incur cost while diluting
the signal: `session_started`, `session_ended`, `user_logged_out`,
`user_profile_updated`, and `identify`.

Dwell-time engagement updates, that is a `product_viewed` event carrying
`viewEventId`, are also skipped, so that each view corresponds to exactly one
interaction.

## Failure classification

| Condition | Retryable |
| --- | --- |
| Throttling | Yes |
| HTTP 5xx | Yes |
| Network error or timeout | Yes |
| Validation error, resource not found | No. Dead-lettered immediately |
| Missing `tracking-id` | No. This is a configuration error rather than a transient fault |

Retrying a permanently malformed event indefinitely delays the inevitable
outcome while occupying a consumer slot.

## Setup checklist

1. Create a dataset group containing an Interactions dataset.
2. Create an Event Tracker, which provides the `tracking-id`.
3. Grant the Event API role the `personalize:PutEvents` permission.
4. Allow interactions to accumulate. Personalize requires a meaningful volume
   before a solution is worth training.
5. Train a solution and deploy a campaign for the serving side.

## Testing

`AmazonPersonalizeMappingTest` covers the mapping exhaustively against a mocked
client, requiring no AWS account, credentials, or network access. The mapper is
implemented as a separate class from the destination specifically to make this
possible.

## References

- [PutEvents API](https://docs.aws.amazon.com/personalize/latest/dg/API_UBS_PutEvents.html)
- [Recording events](https://docs.aws.amazon.com/personalize/latest/dg/recording-events.html)
- [Anonymous users and sessions](https://docs.aws.amazon.com/personalize/latest/dg/recording-item-interaction-events.html)
