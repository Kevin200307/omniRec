# Troubleshooting

Symptom first, then how to tell what's going on, then the fix.

## No events arrive at the Event API

**Check the browser console with `debug: true`.** Every event is logged as it is
built, and every refusal is reported through `onError`.

| Console message | Cause | Fix |
|---|---|---|
| `invalid "product_viewed" event not sent — commerce.productId: …` | A required field is missing | Pass the field; see the table in [event-schema.md](event-schema.md#validation-rules) |
| `"cardNumber" looks like sensitive data` | A blocked field name somewhere in the event | Remove it; send only safe metadata such as `paymentMethod` |
| `apiKey looks like a secret credential` | A secret key was pasted into browser config | Use the publishable `pk_…` key |
| `dropped N event(s) (rejected)` | The API answered 4xx | Check the Network tab for the status (below) |
| `dropped N event(s) (expired)` | Buffered offline for more than `maxEventAgeMs` | Expected after a long offline spell |

**Check the Network tab** for `POST /v1/events/batch`:

| Status | Meaning | Fix |
|---|---|---|
| (failed) / CORS error | Origin not allowed | Add the storefront origin to `omnirec.events.cors.allowed-origins` |
| 401 | Unknown or disabled key | Check `omnirec.events.tenants.<id>.api-key` and `enabled` |
| 413 | Body over `max-payload-bytes`, or more than `max-batch-size` events | Lower `maxBatchSize` in the SDK |
| 429 | Rate limited | Expected under abuse; behind a proxy, check `server.forward-headers-strategy` (below) |
| 503 | Broker unavailable, or an event is mid-ingestion elsewhere | The SDK retries on its own; see "Nothing reaches the provider" |
| 202 with `rejected > 0` | Some events failed validation | `errors[].reason` names the field |

## Every request from behind a load balancer is rate-limited together

The limit is keyed per client address. Behind a proxy that address is the proxy's,
so every shopper shares one budget. Set:

```yaml
server:
  forward-headers-strategy: native
```

Tomcat then resolves the real client address from forwarded headers, trusting
them only from internal proxy addresses. Do **not** try to key on a raw
`X-Forwarded-For` header: the client writes it, so rotating a fake value would
bypass the limit.

## Events are accepted but nothing reaches the provider

1. **Is the destination enabled?** Startup logs `No EventDestination beans are
   active` if not. Check `omnirec.destinations.<provider>.enabled`.
2. **Is the event type one the provider accepts?** Each adapter drops types with no
   genuine counterpart. Google Retail accepts only seven types, and Personalize
   skips session and user events. A skipped event is not an error. See
   [google-retail.md](google-retail.md) and [amazon-personalize.md](amazon-personalize.md).
3. **Is it waiting in a retry tier?** Check `omnirec.queue.depth{queue="retry-n"}`, or:
   ```bash
   rabbitmqctl list_queues name messages | grep omnirec
   ```
   Messages in `.retry.n` come back on their own after the tier's delay.
4. **Is it dead-lettered?** Messages in `.dlq` carry `x-omnirec-failure-reason`.
   Common reasons:

   | Reason | Fix |
   |---|---|
   | `permanent: …tracking-id is not configured` | Set `AWS_PERSONALIZE_TRACKING_ID` |
   | `permanent: …project-number is not configured` | Set `GOOGLE_PROJECT_NUMBER` |
   | `Personalize rejected … (HTTP 400)` | Usually a `properties` key your interactions schema doesn't define; check `property-keys` |
   | `Google Retail rejected … (INVALID_ARGUMENT)` | Often a product missing from the Retail catalog |
   | `exhausted N retries` | The provider was down longer than the retry schedule; fix it, then replay |

   Replay by shovelling the DLQ back onto `omnirec.events` with routing key
   `events.<dest>`. Already-delivered events are skipped, so replay is safe.

## The Event API won't start

| Error | Fix |
|---|---|
| `RabbitMQ publisher confirms and returns are required` | Set `spring.rabbitmq.publisher-confirm-type=correlated` and `publisher-returns=true` |
| `PRECONDITION_FAILED - inequivalent arg` | A queue exists with older arguments. See "Upgrading an existing broker" in [rabbitmq.md](rabbitmq.md) |
| `allow-anonymous-ingestion=true … is only permitted under the dev, test, or local profile` | Configure tenant API keys instead |
| `'recommendationId' is reserved by Personalize` | Remove it from `amazon-personalize.property-keys` |

## The same event is delivered more than once

- **More than one Event API instance?** Without `omnirec.state.redis.enabled=true`
  each instance deduplicates only its own traffic. The log says `Using the
  in-memory deduplication store` at startup when this applies.
- **Different eventIds?** Deduplication is by eventId. A purchase is only
  deduplicated across the browser and the backend if both use the SDKs'
  derived ids (`evt:purchase_completed:<orderId>`).
- **Resent after more than 24 hours?** The dedup window is
  `deduplication-window`. The browser SDK drops buffered events older than
  `maxEventAgeMs` (12h) for exactly this reason; keep it below the window.

## Product views look inflated

Engagement updates (a `product_viewed` carrying `viewEventId` and `dwellTimeMs`)
are follow-ups to a view, not views. The Personalize and Retail adapters skip
them. If you've written your own destination or analytics, filter on
`CommerceEvent.isEngagementUpdate()`.

## Dwell times look too long

The timer runs until another product is viewed, `page.viewed()` is called, the
component unmounts (`product.viewEnded()`, or `useProductView` in React), or the
page unloads. In an SPA, make sure route changes call `page.viewed()`. Values are
capped at `maxDwellMs` (30 minutes); treat the cap as "at least".

## A logged-in user's events have no `userId`

- The page never called `identify()` / `user.loggedIn()`, and no earlier link
  exists for that device. Call `identify` on every page load once the user is
  known; repeat calls are free.
- Server-side events: pass `userId` (required) and, where you have it, the
  browser's `anonymousId` from the `omnirec_anonymous_id` cookie.

## Backend SDK events go missing

Check `HttpEventSender.droppedCount()` and the logs:

| Log | Cause |
|---|---|
| `Event queue is full` | The API was unreachable long enough to fill `queue-capacity` |
| `Giving up on N event(s) after M retries` | The API was down longer than the retry schedule (~3 minutes by default) |
| `permanently rejected … with 401` | Wrong `api-key` |

For purchases that must not be lost, record them in your own transactional
outbox and replay from it; the deterministic eventIds make replay safe.
