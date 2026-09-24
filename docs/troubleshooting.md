# Troubleshooting

Each section begins with the symptom, followed by how to identify the cause and
the corrective action.

## No events arrive at the Event API

Enable `debug: true` in the browser SDK and inspect the console. Every event is
logged as it is constructed, and every refusal is reported through `onError`.

| Console message | Cause | Corrective action |
| --- | --- | --- |
| `invalid "product_viewed" event not sent: commerce.productId: ...` | A required field is missing | Supply the field; see the table in [event-schema.md](event-schema.md#validation-rules) |
| `"cardNumber" looks like sensitive data` | A blocked field name appears in the event | Remove it and send only non-sensitive metadata such as `paymentMethod` |
| `apiKey looks like a secret credential` | A secret key was placed in browser configuration | Use the publishable `pk_...` key |
| `dropped N event(s) (rejected)` | The API responded with a 4xx status | Inspect the network panel for the status, as described below |
| `dropped N event(s) (expired)` | The events were buffered offline for longer than `maxEventAgeMs` | Expected after a long offline period |

Inspect the `POST /v1/events/batch` request in the browser network panel:

| Status | Meaning | Corrective action |
| --- | --- | --- |
| Failed, or a CORS error | The origin is not permitted | Add the storefront origin to `omnirec.events.cors.allowed-origins` |
| 401 | Unknown or disabled key | Check `omnirec.events.tenants.<id>.api-key` and `enabled` |
| 413 | Body exceeds `max-payload-bytes`, or the batch exceeds `max-batch-size` | Lower `maxBatchSize` in the SDK |
| 429 | Rate limited | Expected under abuse. Behind a proxy, check `server.forward-headers-strategy` (see below) |
| 503 | The broker is unavailable, or the event is being ingested elsewhere | The SDK retries automatically. See "Events are accepted but nothing reaches the provider" |
| 202 with `rejected` greater than 0 | Some events failed validation | `errors[].reason` names the offending field |

## All requests behind a load balancer are rate limited together

The rate limit is keyed on the client address. Behind a proxy, that address is
the proxy's, so every visitor shares a single budget. Set:

```yaml
server:
  forward-headers-strategy: native
```

Tomcat then resolves the originating client address from forwarded headers,
trusting them only from internal proxy addresses. Do not attempt to key the limit
on a raw `X-Forwarded-For` header: the client controls that value, so rotating a
forged one would bypass the limit.

## Events are accepted but nothing reaches the provider

1. **Confirm that the destination is enabled.** Startup logs `No EventDestination
   beans are active` if none is. Check `omnirec.destinations.<provider>.enabled`.
2. **Confirm that the provider accepts the event type.** Each adapter drops types
   that have no genuine counterpart. Google Retail accepts only seven types, and
   Personalize skips session and user events. A skipped event is not an error. See
   [google-retail.md](google-retail.md) and
   [amazon-personalize.md](amazon-personalize.md).
3. **Check whether the message is waiting in a retry tier.** Inspect
   `omnirec.queue.depth{queue="retry-n"}`, or run:
   ```bash
   rabbitmqctl list_queues name messages | grep omnirec
   ```
   Messages in `.retry.n` return automatically after the tier's delay.
4. **Check whether the message was dead-lettered.** Messages in `.dlq` carry the
   `x-omnirec-failure-reason` header. Common reasons:

   | Reason | Corrective action |
   | --- | --- |
   | `permanent: ...tracking-id is not configured` | Set `AWS_PERSONALIZE_TRACKING_ID` |
   | `permanent: ...project-number is not configured` | Set `GOOGLE_PROJECT_NUMBER` |
   | `Personalize rejected ... (HTTP 400)` | Usually a `properties` key that the interactions schema does not define; check `property-keys` |
   | `Google Retail rejected ... (INVALID_ARGUMENT)` | Often a product that is missing from the Retail catalog |
   | `exhausted N retries` | The provider was unavailable for longer than the retry schedule; restore it, then replay |

   To replay, move the dead-letter queue contents back onto the `omnirec.events`
   exchange with routing key `events.<destination>`. Events already delivered are
   skipped, so replay is safe.

## The Event API does not start

| Error | Corrective action |
| --- | --- |
| `RabbitMQ publisher confirms and returns are required` | Set `spring.rabbitmq.publisher-confirm-type=correlated` and `publisher-returns=true` |
| `PRECONDITION_FAILED - inequivalent arg` | A queue exists with older arguments. See "Upgrading an existing broker" in [rabbitmq.md](rabbitmq.md) |
| `allow-anonymous-ingestion=true ... is only permitted under the dev, test, or local profile` | Configure tenant API keys instead |
| `'recommendationId' is reserved by Personalize` | Remove it from `amazon-personalize.property-keys` |

## The same event is delivered more than once

- **Is more than one Event API instance running?** Without
  `omnirec.state.redis.enabled=true`, each instance deduplicates only its own
  traffic. Startup logs `Using the in-memory deduplication store` when this
  applies.
- **Are the event identifiers different?** Deduplication is by `eventId`. A
  purchase is deduplicated between the browser and the backend only if both use
  the SDKs' derived identifiers (`evt:purchase_completed:<orderId>`).
- **Was the event resubmitted after more than 24 hours?** The deduplication
  window is `deduplication-window`. The browser SDK discards buffered events older
  than `maxEventAgeMs` (12 hours) for this reason; keep that value below the
  window.

## Product view counts appear inflated

Engagement updates, meaning `product_viewed` events carrying `viewEventId` and
`dwellTimeMs`, are follow-ups to a view rather than views themselves. The
Personalize and Retail adapters skip them. A custom destination or analytics
consumer should filter on `CommerceEvent.isEngagementUpdate()`.

## Dwell times appear too long

The timer runs until another product is viewed, `page.viewed()` is called, the
component unmounts (through `product.viewEnded()`, or `useProductView` in
React), or the page unloads. In a single-page application, ensure that route
changes call `page.viewed()`. Values are capped at `maxDwellMs` (30 minutes) and
should be interpreted as "at least" the capped value.

## A signed-in user's events have no `userId`

- The page never called `identify()` or `user.loggedIn()`, and no earlier link
  exists for that device. Call `identify` on every page load once the user is
  known; repeated calls are inexpensive.
- For server-side events, supply `userId`, which is required, and where
  available the browser's `anonymousId` from the `omnirec_anonymous_id` cookie.

## Server-side SDK events are missing

Check `HttpEventSender.droppedCount()` and the logs:

| Log message | Cause |
| --- | --- |
| `Event queue is full` | The API was unreachable for long enough to fill `queue-capacity` |
| `Giving up on N event(s) after M retries` | The API was unavailable for longer than the retry schedule (about three minutes by default) |
| `permanently rejected ... with 401` | Incorrect `api-key` |

For purchases that must not be lost, record them in a transactional outbox
maintained by the merchant application and replay from it. Deterministic event
identifiers make replay safe.
