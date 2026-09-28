# RabbitMQ

The queue layer separates ingestion from delivery, so that a provider outage
delays events rather than discarding them.

## Topology

For each destination (`amazon-personalize`, `google-retail`, and others):

```
omnirec.events (topic exchange)
     | routing key events.<destination>
     v
omnirec.events.<destination> --consumer--+-- delivered or skipped ----------> ack
     | x-dead-letter-exchange            |
     | = omnirec.events.dlx              +-- transient failure, attempt n --> omnirec.events.<destination>.retry.n
     | (rejected messages)               |     (no consumer; queue TTL = delay n;
     |                                   |      expiry dead-letters it back to the main queue)
     v                                   +-- exhausted or permanent failure -> omnirec.events.dlx
omnirec.events.dlx -------------------------------------------------------> omnirec.events.<destination>.dlq
```

**One queue set per destination**, rather than a single shared queue. This is
what makes providers independent: an Amazon outage backs up only the Amazon
queues while Google continues to drain. Verified against a real broker by
`RealBrokerPipelineTest.oneProviderFailingDoesNotBlockAnother`.

**One retry queue per attempt.** RabbitMQ expires messages only at the head of a
queue. An earlier implementation used a single retry queue with per-message
time-to-live values, so a message due for a 1-second retry waited behind a
message due in 5 minutes and was delayed for the full interval (audit finding
R2). Each tier now holds messages of a single delay, expressed as a queue-level
time-to-live. Verified by
`RetryTiersBrokerTest.aShortRetryIsNotHeldBehindALongOne`.

**The main queue declares a dead-letter exchange**, so that a message rejected
without requeue, such as an unparseable body, is routed to the dead-letter queue
rather than discarded by the broker (audit finding R3).

**Historical storage is a destination too.** With `omnirec.storage.enabled=true`,
the storage worker registers as destination `event-storage` and receives the same
queue set: `omnirec.events.event-storage`, its retry tiers, and its dead-letter
queue. A database outage therefore backs up only those queues. See
[event-storage.md](event-storage.md).

**Control events are routed selectively.** Behavioural events are published to
every destination's queue. An `identify` control event is published only to
destinations whose `supports()` accepts it. No provider does, so provider queues
never carry one; the storage worker does. With storage disabled, `identify` is
published nowhere, as before. Verified by `RabbitEventPublisherRoutingTest`.

## Reliability guarantees

| Guarantee | Mechanism |
| --- | --- |
| The broker holds the event before the API returns 202 | Publisher confirms, awaited by `ConfirmedPublisher`. Previously configured but never awaited, so a negative acknowledgement was undetectable (audit finding R1). |
| An unroutable message is not silently discarded | Mandatory publishing with returns: a returned message fails the publication. |
| Events survive a broker restart | Durable exchanges and queues with persistent messages. |
| A consumer failure does not lose the message | Container-managed acknowledgement: the message is acknowledged only after it has been delivered, skipped, or confirmed onto a retry tier or the dead-letter queue. If that move is not confirmed, the consumer throws, the container issues a negative acknowledgement, and the broker redelivers. |
| Redelivery does not reach a provider twice | Per-destination deduplication with a lease, described below. |
| A provider outage does not stall other providers | Separate queues and consumers per destination. |
| Retries terminate | `max-retries` tiers, followed by the dead-letter queue with `x-omnirec-failure-reason`. |

The Event API refuses to start if publisher confirms or returns are disabled.
Operating without them produces precisely the silent-loss failure this design
exists to prevent.

## At-least-once delivery and consumer idempotency

RabbitMQ provides at-least-once delivery: a consumer that terminates after
calling the provider but before acknowledging the message will receive that
message again. `EventDispatcher` therefore deduplicates per destination using a
lease-and-complete protocol:

```
claim(key, lease)  CLAIMED            -> send -> complete(key, 24h)   (release on failure)
                   ALREADY_COMPLETED  -> redelivery of a delivered event: acknowledge and skip
                   IN_PROGRESS        -> another consumer is sending it, or a consumer
                                         terminated while holding the lease: retry later
```

An earlier implementation recorded the key as complete for 24 hours before
sending. A failure between the claim and the send left the event permanently
marked as delivered, so every redelivery was skipped and the event was lost
(audit finding R4). A failure now leaves only a lease (`delivery-lease`, 2
minutes by default), and the event is retried once that lease expires.

This mechanism is distinct from ingestion-stage deduplication, which uses the
same protocol. See
[architecture.md](architecture.md#two-layers-of-deduplication).

## Retry schedule

Delays double from `retry-initial-interval` (1s) up to `retry-max-interval` (5
minutes) across `max-retries` attempts (5), producing intervals of 1s, 2s, 4s,
8s, and 16s, after which the event is dead-lettered.

| Failure | Behaviour |
| --- | --- |
| `DestinationException(retryable = true)` | Routed to the next retry tier |
| `DestinationException(retryable = false)` | Dead-lettered immediately, because the provider has indicated the outcome will not change |
| Any other exception | Treated as retryable, because an unclassified defect must not silently discard events |
| Delivery lease held elsewhere | Treated as retryable |
| Retries exhausted | Dead-lettered with `x-omnirec-failure-reason` |
| Unparseable message | Rejected; the main queue's dead-letter exchange routes it to the dead-letter queue |

## Configuration

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    publisher-confirm-type: correlated   # required
    publisher-returns: true              # required

omnirec:
  processing:
    queue-enabled: true
    max-retries: 5
    retry-initial-interval: 1s
    retry-max-interval: 5m
    delivery-lease: 2m        # must exceed the slowest provider call
    confirm-timeout: 10s
    concurrency: 2
    prefetch-count: 10
    deduplication-window: PT24H
```

The prefetch count is deliberately low. A consumer that terminates with a large
prefetch leaves more in-flight messages to be redelivered, and delivery is
I/O-bound rather than CPU-bound.

## Operations

```bash
docker-compose up -d rabbitmq
# Management interface: http://localhost:15672 (guest/guest)
```

### Dead-letter queues

```bash
rabbitmqctl list_queues name messages | grep dlq
```

Each message carries the `x-omnirec-failure-reason` header. To replay messages
after resolving the cause, move the dead-letter queue contents back onto the
`omnirec.events` exchange with routing key `events.<destinationId>`.
Delivery-stage deduplication ensures that events already delivered are skipped,
so replay is safe.

### Upgrading an existing broker

RabbitMQ refuses to redeclare an existing queue with different arguments. This
release changes queue arguments: main queues gain a dead-letter exchange, and
the single `.retry` queue is replaced by `.retry.1` through `.retry.n`. On a
broker that previously ran an earlier version:

1. Stop the Event API and allow the main queues to drain.
2. Delete `omnirec.events.<destination>` and
   `omnirec.events.<destination>.retry`, after relocating any remaining messages,
   for example with a shovel.
3. Start the new version, which declares the updated topology.

Subsequently changing `max-retries` or the retry intervals alters the retry
tiers' time-to-live values and requires the same procedure for the affected
`.retry.n` queues.

### Operating without a broker

```yaml
omnirec:
  processing:
    queue-enabled: false
```

Destinations are then called inline on the request thread. This is useful for
local development and integration tests but is not suitable for deployment,
because it provides no retry, no dead-lettering, and no durability. The
auto-configuration logs a warning to that effect.

## Metrics

| Metric | Description |
| --- | --- |
| `omnirec.queue.depth{destination, queue=main\|retry-n\|dlq}` | Messages awaiting delivery. Growth in retry or dead-letter depth is the primary indicator of a provider outage. Read from the broker at most every 15 seconds. |
| `omnirec.provider.retries{destination, attempt}` | Retries scheduled |
| `omnirec.provider.dead_lettered{destination, reason}` | Events moved to the dead-letter queue, requiring operator attention |
| `omnirec.provider.delivery.latency{destination}` | Elapsed time from receipt at the API to acceptance by the provider |

## Scaling

Consumers are stateless and may be replicated. Before doing so, set
`omnirec.state.redis.enabled=true`, provided by the `omnirec-redis-state`
module. It replaces the deduplication and identity-link stores with shared
Redis-backed implementations. Without it, both stores are per-process: the same
event may be delivered once per instance, and identity links recorded by one
instance are invisible to the others. Both log a warning when active.
