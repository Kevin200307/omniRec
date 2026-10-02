# Server-side SDK: `commerce-tracker-spring-boot`

The SDK a merchant embeds in their Spring Boot application to report
authoritative business events.

## Installation

```xml
<dependency>
    <groupId>io.omnirec</groupId>
    <artifactId>commerce-tracker-spring-boot</artifactId>
    <version>2.0.0</version>
</dependency>
```

```yaml
omnirec:
  tracker:
    endpoint: https://events.example.com   # the only required setting
    api-key: ${OMNIREC_API_KEY:}           # only when the collector runs in keys mode
```

## Quick start (v2)

Inject `OmnirecTracker` and track in one line. The visitor's browsing identity
comes from the `omnirec_anonymous_id` and `omnirec_session_id` cookies the
browser SDK sets, so server events join the same journey with no plumbing:

```java
@PostMapping("/api/cart")
public Cart add(@RequestBody AddItem req) {
    Cart cart = carts.add(req.productId(), req.quantity());
    tracker.track(StandardEvents.PRODUCT_ADDED_TO_CART,
            Map.of("product", Map.of("id", req.productId(), "quantity", req.quantity()),
                   "cart", Map.of("id", cart.id())));
    return cart;
}
```

- **Known customer:** `tracker.track(event, data, customerId)`.
- **Business key:** `tracker.track(event, data, customerId, order.getId())` derives
  a stable eventId (`evt:purchase_completed:<id>`), so a retried call deduplicates.
- **No request** (jobs, webhooks): pass a `ServerIdentity` explicitly.
- **Annotation:** `@TrackEvent` tracks after a method returns, with SpEL over
  parameters and `#result`:

```java
@Transactional
@TrackEvent(value = StandardEvents.PURCHASE_COMPLETED,
            data = "{order: {id: #result.id, total: #result.total, currency: #result.currency, items: #result.lines}}",
            userId = "#result.customerId", businessKey = "#result.id")
public Order placeOrder(Cart cart) { ... }
```

### Transactional outbox

```yaml
omnirec.tracker.outbox.enabled: true   # PostgreSQL DataSource required
```

Events tracked inside a transaction are written to `omnirec_outbox` with that
transaction and sent after it commits. A rolled-back order never reports a
purchase; a committed one always does, even if the collector is down at commit
or the process dies right after. A relay retries leftover rows with backoff;
several application instances can run it at once (`FOR UPDATE SKIP LOCKED`).

### Testing your application

```java
@SpringBootTest
@AutoConfigureOmnirecTest
class CheckoutTest {
    @Autowired OmnirecTestRecorder omnirec;

    @Test
    void placingAnOrderTracksThePurchase() {
        checkout.placeOrder(cart);
        omnirec.assertThat("purchase_completed").withData("order.id", "o1").withUserId("c_1");
    }
}
```

### Without Spring

`omnirec-tracker-java` has the same API with no framework:
`OmnirecClient.builder().endpoint(url).build().track(event, data, identity)`.

## v1 helpers (deprecated)

The SDK is auto-configured; inject `CommerceTracker` to use it. No AWS or Google
credentials are required, because the merchant application communicates only
with the Event API, and the Event API communicates with providers.

## Usage

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
                .anonymousId(order.getTrackingAnonymousId())   // recommended
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

## Applicable events

Any event for which the browser cannot be considered authoritative.

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

A confirmation page may be reloaded, bookmarked, closed before rendering, or
blocked entirely, so a purchase reported only from JavaScript is both
over-reported and under-reported. Revenue data should originate here.

## Idempotency

Events with a natural business key derive their `eventId` from that key, so the
same fact reported twice is collapsed into a single event rather than being
counted twice.

| Method | Key |
| --- | --- |
| `purchase.completed`, `failed`, `orderCancelled`, `orderRefunded` | `orderId` |
| `checkout.completed` | `orderId` |
| `cart.abandoned` | `cartId` |
| `product.reviewSubmitted` | `reviewId` |
| `user.registered` | `userId` |
| `recommendation.purchased` | `orderId:productId` |
| `identify` | `anonymousId:userId` |

The identifier format is `evt:<eventType>:<businessKey>`, for example
`evt:purchase_completed:order_1`. It is therefore deterministic across
processes: two nodes reporting the same order derive the same identifier, which
is what makes deduplication effective behind a load balancer. The browser SDK
derives the same identifier for the same purchase, so a purchase reported from
both sources is delivered once. This is verified end to end by
`BackendSdkToEventApiTest.aPurchaseReportedByBothTheBrowserAndTheBackendIsDeliveredOnce`.

A retried webhook, a redelivered queue message, and a sweep job that repeatedly
observes the same stale cart are therefore all safe.

The event type forms part of the key, so `purchase_completed` and
`order_cancelled` for the same order remain distinct events.

## Supplying the anonymous identifier

Supplying `anonymousId` is recommended wherever the value is available. Capture
it at checkout from the `omnirec_anonymous_id` cookie and store it with the
order. It is what links a purchase to the anonymous browsing that preceded it.

When it is omitted, the SDK derives a stable `server:<uuid>` identity from the
`userId`. Server-side events then remain coherent with one another, but the
association with that person's browsing history is lost, which is generally the
most valuable part.

## Delivery

Delivery is asynchronous by default, performed on a background thread with a
bounded queue (`queue-capacity`, 10,000 by default).

This is significant because `commerce.purchase.completed(...)` is called from
within the merchant's order-placement path. If it blocked on an HTTP round trip,
an outage in the Event API would slow or fail merchant checkout. Tracking must
never impair the transaction it observes.

### Retry

A transient failure (5xx, 408, 429, or a network error) is retried with
exponential backoff from 500ms, doubling to a maximum of 30s, for up to
`max-retries` attempts (8 by default), which is approximately three minutes. A
permanent rejection (any other 4xx) is not retried. Earlier versions logged and
discarded on any failure, so a brief Event API interruption resulted in the loss
of authoritative purchases (audit finding B1).

### Residual loss scenarios

The queue is in memory and bounded. Events are discarded, logged, and counted
through `HttpEventSender.droppedCount()` if the queue fills, if retries are
exhausted, or if the application terminates with events queued. Where guaranteed
delivery of purchases is required, record them in a transactional outbox and
replay from it. Deterministic event identifiers make replay safe.

```java
commerce.flush();   // prior to shutdown
```

Set `omnirec.tracker.async=false` for synchronous delivery where a test requires
deterministic behaviour.

## Validation

Events are validated before transmission, and an invalid event throws. This
differs from the frontend SDK, which logs and discards.

A server-side event is authoritative business data. Silently discarding a
purchase because a field was absent is considerably worse than failing at the
call site during development.

## Payment data

`paymentInformationAdded` accepts a payment method string and nothing further.
There is deliberately no parameter capable of carrying a card number, security
code, expiry date, or gateway token, so the unsafe call cannot be expressed. The
validator would reject such an event in any case, but an API that makes the
error impossible is preferable to one that detects it afterwards.

## Configuration

| Property | Default | Description |
| --- | --- | --- |
| `omnirec.tracker.enabled` | `true` | Enables the tracker. |
| `omnirec.tracker.endpoint` | none | Required. Startup fails if absent. |
| `omnirec.tracker.api-key` | none | Key for the Event API. |
| `omnirec.tracker.tenant-id` | none | Tenant identifier. |
| `omnirec.tracker.async` | `true` | Retain outside tests. |
| `omnirec.tracker.queue-capacity` | 10000 | Bounded in-memory queue size. |
| `omnirec.tracker.max-batch-size` | 50 | Maximum events per request. |
| `omnirec.tracker.validate-events` | `true` | Validate before transmission. |
| `omnirec.tracker.max-retries` | 8 | Applies to transient failures only. |
| `omnirec.tracker.retry-initial-interval` | 500ms | Doubles on each attempt. |
| `omnirec.tracker.retry-max-interval` | 30s | Upper bound on the retry interval. |

All beans are declared `@ConditionalOnMissingBean`, so supplying a custom
`EventSender`, for example to route events through an existing queue, replaces
only that component.
