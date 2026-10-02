# Derived events

Some of the most useful events are ones nobody clicks: a cart left behind, a
customer coming back, a first purchase. `omnirec-derived-events` computes them
from the event stream and sends them through the pipeline like any other event,
with `source: derived`.

```yaml
omnirec:
  derived:
    enabled: true
```

| Event | Rule | Default |
| --- | --- | --- |
| `cart_abandoned` | `product_added_to_cart` with no `checkout_started`, `checkout_completed` or `purchase_completed` within the timeout | 60 minutes |
| `checkout_abandoned` | `checkout_started` with no `checkout_completed` or `purchase_completed` within the timeout | 30 minutes |
| `return_visit` | `session_started` when the visitor's previous session started at least the minimum gap earlier | 30 minutes |
| `new_customer_purchase` | The customer's first `purchase_completed` | |
| `repeat_purchase` | Every later `purchase_completed`, with `properties.purchaseNumber` | |

## How it works

The engine is a pipeline destination (`derived-events`). It has its own queue,
retry tiers and dead-letter queue, and it receives only the events its rules
subscribe to. Derived events go back into the pipeline and reach every
destination that wants them: storage, Personalize, your webhooks. The engine
ignores events whose source is `derived`, so a rule never feeds itself.

Abandonment rules keep one timer per visitor, keyed by anonymous id. The
browser and the server share the anonymous id through the identity cookies.
Each add to cart pushes the timer back. An end event (checkout, purchase)
cancels it and is remembered for a while, so an add that the queue delivers
after its checkout does not re-arm the timer. A poller checks due timers every
`poll-interval` (15 s), so an abandonment fires at most that late.

## State

| `store` | Timers and counters | Use |
| --- | --- | --- |
| `redis` | In the state Redis (`omnirec.state.redis`), under `omnirec:derived:*` | Any real deployment. Timers survive restarts, and with several instances each timer fires on exactly one of them. |
| `memory` | In the process | One instance, development. Lost on restart. |

The default `auto` picks Redis whenever `omnirec.state.redis.enabled=true`.

## Notes

- **Purchase history starts when the rule starts.** The counter knows only
  orders seen since it was enabled, so an existing customer's first order after
  that is reported as new. Orders are counted once by order id, so a purchase
  reported by both the browser and the server counts once.
- **Ids are stable.** A derived event's id is
  `derived:<event>:<tenant>:<cause>`, so a redelivery is dropped as a duplicate
  downstream.
- **The cart id is required.** An add to cart without `cart.id` is attributed
  to `visitor_<anonymousId>`, with `properties.cartIdInferred: true`.

## Your own rules

```java
@Component
class HighValueCartRule implements DerivedEventRule {
    public String name() { return "high_value_cart"; }
    public Set<String> subscribesTo() { return Set.of("cart_viewed"); }

    public void onEvent(CommerceEvent event, RuleContext context) {
        if (event.data().cart() == null || event.data().cart().total() == null) return;
        if (event.data().cart().total().compareTo(new BigDecimal("500")) < 0) return;
        if (!context.firstTime("cart:" + event.data().cart().id(), Duration.ofDays(1))) return;
        context.emit(DerivedEvents.create("high_value_cart", event.tenantId(), event.data().cart().id(),
                event.identity(), event.timestamp(), context.now(), Map.of("cart", Map.of("id", event.data().cart().id())), Map.of()));
    }
}
```

A rule can schedule timers (`context.schedule(key, dueAt, payload)`, then
`onTimer`), keep counters and facts (`increment`, `firstTime`, `get`, `put`),
and read the time from `context.now()`. Derived events do not pass through
the collector's validation, so build them valid. Use a catalog event, or a
custom event from your tracking plan, with its required fields.
