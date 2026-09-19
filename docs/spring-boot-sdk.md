# Backend SDK — `commerce-tracker-spring-boot`

The SDK a merchant embeds in their own Spring Boot application to report
authoritative business events.

## Install

```xml
<dependency>
    <groupId>io.omnirec</groupId>
    <artifactId>commerce-tracker-spring-boot</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
omnirec:
  tracker:
    endpoint: https://events.example.com
    api-key: ${OMNIREC_API_KEY}
    tenant-id: my-store
```

Auto-configured — inject `CommerceTracker` and go. Note there are no AWS or
Google credentials here: a merchant's application talks only to the Event API,
and the Event API talks to providers.

## Use

```java
@Service
public class OrderService {

    private final CommerceTracker commerce;

    public OrderService(CommerceTracker commerce) {
        this.commerce = commerce;
    }

    public void onPaymentSettled(Order order) {
        commerce.purchase.completed(PurchaseCompleted.builder()
                .orderId(order.getId())
                .userId(order.getCustomerId())
                .anonymousId(order.getTrackingAnonymousId())   // strongly recommended
                .items(order.getLines().stream()
                        .map(line -> CommerceItem.of(line.getSku(), line.getQuantity(),
                                line.getPrice(), "USD"))
                        .toList())
                .total(order.getTotal())
                .currency("USD")
                .build());
    }
}
```

## What belongs here

Anything where the browser cannot be trusted to know the truth.

```java
commerce.purchase.completed(...)
commerce.purchase.failed(orderId, userId, reason)
commerce.purchase.orderCancelled(orderId, userId, reason)
commerce.purchase.orderRefunded(orderId, userId, amount, currency)

commerce.product.reviewSubmitted(productId, userId, reviewId, rating)
commerce.product.wishlisted(productId, userId, anonymousId)

commerce.user.registered(userId, anonymousId)
commerce.user.loggedIn(userId, anonymousId)
commerce.user.profileUpdated(userId, changedFields)

commerce.cart.abandoned(cartId, userId, anonymousId, items)
commerce.checkout.started(cartId, userId, anonymousId)
commerce.checkout.paymentInformationAdded(cartId, userId, "card")
commerce.checkout.completed(cartId, orderId, userId)

commerce.recommendation.purchased(recommendationId, productId, orderId, userId, anonymousId)

commerce.identify(anonymousId, userId)
```

A confirmation page can be reloaded, bookmarked, closed before it renders, or
blocked outright — so a purchase reported only from JavaScript is both over- and
under-counted. Revenue data should come from here.

## Idempotency

Events with a natural business key derive their `eventId` from it, so the same
fact reported twice collapses to one event rather than being double-counted:

| Method | Key |
|---|---|
| `purchase.completed` / `failed` / `orderCancelled` / `orderRefunded` | `orderId` |
| `checkout.completed` | `orderId` |
| `cart.abandoned` | `cartId` |
| `product.reviewSubmitted` | `reviewId` |
| `user.registered` | `userId` |
| `recommendation.purchased` | `orderId:productId` |
| `identify` | `anonymousId:userId` |

The id is `evt:<eventType>:<businessKey>` (e.g. `evt:purchase_completed:order_1`),
so it is **deterministic across processes**: two nodes reporting the same order
agree on the id, which is what makes deduplication work behind a load balancer.
It is also exactly what the browser SDK derives for the same purchase, so a
purchase reported from both sides is delivered once. Verified end to end by
`BackendSdkToEventApiTest.aPurchaseReportedByBothTheBrowserAndTheBackendIsDeliveredOnce`.

A retried webhook, a redelivered queue message, and a sweeper job that keeps
seeing the same stale cart are all safe.

The event type is part of the key, so `purchase_completed` and `order_cancelled`
for one order remain distinct events.

## Pass the `anonymousId`

Strongly recommended wherever it exists. Capture it at checkout (it's in the
`omnirec_anonymous_id` cookie) and store it against the order. It is what links
a purchase back to the anonymous browsing that led to it.

When omitted, the SDK derives a stable `server:<uuid>` identity from the
`userId`. Server-side events stay coherent with each other, but the connection to
that person's browsing is lost — which is usually the most valuable part.

## Delivery

Asynchronous by default, on a background thread with a bounded queue
(`queue-capacity`, default 10,000).

This matters: `commerce.purchase.completed(...)` is called from inside a
merchant's order-placement path. If it blocked on an HTTP round trip, an outage
in *our* service would slow down or fail *their* checkout. **Tracking must never
break the transaction it observes.**

### Retry

A transient failure (5xx, 408, 429, network error) is retried with exponential
backoff, 500ms doubling to 30s, up to `max-retries` (8) times, roughly three
minutes. A permanent rejection (other 4xx) is not retried. Earlier versions
logged and dropped on any failure, so a brief Event API blip lost authoritative
purchases (audit finding B1).

### What can still be lost

The queue is in memory and bounded. Events are dropped, logged, and counted
(`HttpEventSender.droppedCount()`) if the queue fills, if retries are exhausted,
or if the application stops with events queued. **If you need guaranteed
delivery of purchases, record them in your own transactional outbox and replay
from it.** The deterministic eventIds make any replay safe.

```java
commerce.flush();   // before shutdown
```

Set `omnirec.tracker.async=false` for synchronous delivery when a test needs
determinism.

## Validation

Events are validated before sending and an invalid one **throws**. This differs
from the frontend SDK, which logs and drops.

A backend event is authoritative business data: silently discarding a purchase
because a field was missing is far worse than failing loudly at the call site
while the developer is looking at it.

## Payment data

`paymentInformationAdded` takes a method string and nothing else. There is
deliberately no parameter that could carry a card number, CVV, expiry, or gateway
token, so the unsafe call cannot be written. The validator would reject such an
event anyway — but an API that makes the mistake impossible beats one that
catches it afterwards.

## Configuration

| Property | Default | Notes |
|---|---|---|
| `omnirec.tracker.enabled` | `true` | |
| `omnirec.tracker.endpoint` | — | Required; startup fails without it. |
| `omnirec.tracker.api-key` | — | For the Event API. |
| `omnirec.tracker.tenant-id` | — | |
| `omnirec.tracker.async` | `true` | Keep on outside tests. |
| `omnirec.tracker.queue-capacity` | 10000 | |
| `omnirec.tracker.max-batch-size` | 50 | |
| `omnirec.tracker.validate-events` | `true` | |
| `omnirec.tracker.max-retries` | 8 | Transient failures only |
| `omnirec.tracker.retry-initial-interval` | 500ms | Doubles each attempt |
| `omnirec.tracker.retry-max-interval` | 30s | |

Every bean is `@ConditionalOnMissingBean`, so supplying your own `EventSender`
(to route through a queue you already run, say) replaces just that piece.
