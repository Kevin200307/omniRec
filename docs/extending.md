# Extending omniRec

Every extension point is a Java interface you implement and register as a
Spring bean. Nothing in the core changes.

| You want to | Implement | Gets |
| --- | --- | --- |
| Send events to a new system | `EventDestination` | its own queue, retry tiers, dead-letter queue and replay |
| Accept another system's webhooks | `WebhookAdapter` | `POST /v1/webhooks/{source}`, signature check, the normal pipeline |
| Compute events from events | `DerivedEventRule` | subscriptions, timers, counters, shared via Redis |
| Keep tenants in your own system | `TenantRegistry` | keys, origins, plans, validation mode per tenant |
| Store history elsewhere | `EventStore` | the storage worker, history API, deletion |

## A destination

```java
@Component
public class WarehouseDestination implements EventDestination {

    @Override public String id() { return "warehouse"; }      // queue: omnirec.events.warehouse

    @Override
    public boolean supports(CommerceEvent event) {             // skip what you don't want, cheaply
        return EventDestination.super.supports(event)
                && event.eventType().wireName().startsWith("purchase_");
    }

    @Override
    public void send(CommerceEvent event) {
        try {
            client.insert(event.eventId(), event.data().asMap());   // must be idempotent by eventId
        } catch (TimeoutException e) {
            throw new DestinationException(id(), "warehouse timeout", e, true);   // retried
        } catch (RejectedException e) {
            throw DestinationException.permanent(id(), "rejected: " + e.getCode()); // dead-lettered
        }
    }
}
```

The rules:

- Be idempotent.
- Throw a retryable exception for transient failures and a permanent one for
  payloads that will never be accepted.
- Never mutate the event.
- Unplanned events are withheld unless `acceptsUnplanned()` returns true.

If a plain HTTP POST is all you need, configure the outbound webhook destination
instead ([webhooks.md](webhooks.md#outbound-webhooks)).

## A webhook adapter

See [webhooks.md](webhooks.md#your-own-adapter). Return v2 envelopes built with
`WebhookEnvelope`, and give each event an id derived from the sender's own id,
so the sender's retries deduplicate. Use `WebhookSignatures` for constant-time
HMAC checks and per-tenant secrets.

## A derived-event rule

See [derived-events.md](derived-events.md#your-own-rules). Use
`RuleContext.now()` rather than the system clock, and
`RuleContext.derivedEventId(...)` for stable ids. Schedule timers by key, so
rescheduling replaces the old timer.

## A tenant registry

```java
@Component
public class CrmTenantRegistry implements TenantRegistry {
    public Optional<Tenant> find(String tenantId) { ... }
    public Optional<String> tenantForPublishableKey(String key) { ... }   // compare hashes in constant time
    public Optional<String> tenantForSecretKey(String key) { ... }
    public Collection<Tenant> tenants() { ... }
    public boolean hasAnyPublishableKey() { ... }
}
```

Your bean replaces the file and JDBC registries. Cache lookups: the collector
calls `tenantForPublishableKey` on every request.

## An event store

Implement `save` (idempotent per tenant and event id) and `findCustomerEvents`.
To support customer deletion, also implement `anonymousIdsOf` and
`eraseCustomer`. The PostgreSQL implementation is the reference.

## Testing an extension

- **Spring Boot apps:** use `@AutoConfigureOmnirecTest` and
  `OmnirecTestRecorder` to assert on the events your code tracks.
- **Collectors:** start the app with `omnirec.processing.queue-enabled=false`
  for inline delivery in tests, as the collector's own tests do, or use
  Testcontainers RabbitMQ for the real queues.
