# RabbitMQ

The durable bus between ingestion and provider delivery. Without it, a provider
outage loses events; with it, they queue and drain when the provider recovers.

## Topology

Per destination (`amazon-personalize`, `google-retail`, …):

```
omnirec.events (topic exchange)
     │ routing key events.<dest>
     ▼
omnirec.events.<dest> ──consumer──┬── delivered or skipped ─────────────► ack
     │ x-dead-letter-exchange     ├── transient failure, attempt n ─────► omnirec.events.<dest>.retry.n
     │ = omnirec.events.dlx       │     (no consumer; queue TTL = delay n;
     │ (rejected messages)        │      expiry dead-letters it back to the main queue)
     ▼                            └── exhausted, or permanent failure ──► omnirec.events.dlx
omnirec.events.dlx ──────────────────────────────────────────────────────► omnirec.events.<dest>.dlq
```

**One queue set per destination**, not one shared queue. That is what makes
providers independent: Amazon being down backs up only Amazon's queues while
Google keeps draining. Verified against a real broker by
`RealBrokerPipelineTest.oneProviderFailingDoesNotBlockAnother`.

**One retry queue per attempt.** RabbitMQ only expires messages at the *head* of
a queue. An earlier version used a single retry queue with per-message TTLs, so
a message due for a 1-second retry sat behind one due in 5 minutes and waited the
full 5 minutes (audit finding R2). Each tier now holds messages of a single delay,
set as a queue-level TTL. Verified by
`RetryTiersBrokerTest.aShortRetryIsNotHeldBehindALongOne`.

**The main queue has a dead-letter exchange**, so a message rejected without
requeue (an unparseable body) lands in the DLQ instead of being silently
discarded by the broker (audit finding R3).

## Reliability guarantees, and how each is achieved

| Guarantee | Mechanism |
|---|---|
| The broker has an event before the API answers 202 | Publisher confirms, **awaited** (`ConfirmedPublisher`). Previously configured but never waited on, so a nack was invisible (R1). |
| An unroutable message isn't silently dropped | Mandatory publishing plus returns: a returned message fails the publish. |
| Survives a broker restart | Durable exchanges and queues, persistent messages. |
| A consumer crash doesn't lose the message | Container-managed ack: the message is acked only after it is delivered, skipped, or **confirmed** onto a retry tier or the DLQ. If that move isn't confirmed, the consumer throws, the container nacks, and the broker redelivers. |
| Redelivery doesn't reach a provider twice | Per-destination deduplication with a lease (below). |
| A provider outage doesn't stall other providers | Separate queues and consumers per destination. |
| Retries end | `max-retries` tiers, then the DLQ with `x-omnirec-failure-reason`. |

The Event API **refuses to start** if publisher confirms or returns are not
enabled. Running without them is exactly the silent-loss failure this design
exists to prevent.

## At-least-once, so consumers are idempotent

RabbitMQ is at-least-once: a consumer that dies after calling Amazon but before
acking **will** see the message again. `EventDispatcher` deduplicates per
destination with a **lease → complete** protocol:

```
claim(key, lease)  CLAIMED            → send → complete(key, 24h)   (release on failure)
                   ALREADY_COMPLETED  → a redelivery of something delivered: ack and skip
                   IN_PROGRESS        → another consumer is sending it, or one crashed
                                        holding the lease: retry later
```

An earlier version claimed the key as "done" for 24 hours *before* sending. A
crash between claim and send left the event permanently marked delivered; every
redelivery was skipped and the event was lost (audit finding R4). Now a crash
leaves only a lease (`delivery-lease`, 2 minutes by default), and the event is
retried once it expires.

This is separate from the ingestion-stage deduplication, which uses the same
protocol; see [architecture.md](architecture.md#two-layers-of-deduplication).

## Retry schedule

Delays double from `retry-initial-interval` (1s) up to `retry-max-interval`
(5 minutes), for `max-retries` (5) attempts: 1s, 2s, 4s, 8s, 16s, then the DLQ.

| Failure | Behaviour |
|---|---|
| `DestinationException(retryable = true)` | Next retry tier |
| `DestinationException(retryable = false)` | DLQ immediately; the provider has said the answer won't change |
| Any other exception | Treated as retryable; an unclassified bug should not silently discard events |
| Delivery lease held elsewhere | Retryable |
| Retries exhausted | DLQ, with `x-omnirec-failure-reason` |
| Unparseable message | Rejected; the main queue's DLX moves it to the DLQ |

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
    delivery-lease: 2m        # must outlast the slowest provider call
    confirm-timeout: 10s
    concurrency: 2
    prefetch-count: 10
    deduplication-window: PT24H
```

Prefetch is deliberately low: a consumer that dies with a large prefetch has more
in-flight messages to redeliver, and delivery is IO-bound.

## Operating

```bash
docker-compose up -d rabbitmq
# management UI: http://localhost:15672  (guest/guest)
```

### Dead-letter queues

```bash
rabbitmqctl list_queues name messages | grep dlq
```

Each message carries `x-omnirec-failure-reason`. To replay after fixing the
cause, shovel the DLQ back onto `omnirec.events` with routing key
`events.<destinationId>`. Delivery-stage deduplication means anything already
delivered is skipped, so a replay is safe.

### Upgrading an existing broker

RabbitMQ refuses to redeclare an existing queue with different arguments. This
release changes queue arguments (main queues gain a dead-letter exchange; the
single `.retry` queue becomes `.retry.1` … `.retry.n`), so on a broker that ran
an earlier version:

1. Stop the Event API and let the main queues drain.
2. Delete `omnirec.events.<dest>` and `omnirec.events.<dest>.retry` (after moving
   any remaining messages, e.g. with a shovel).
3. Start the new version; it declares the new topology.

Changing `max-retries` or the retry intervals later changes the retry tiers'
TTLs and needs the same treatment for the affected `.retry.n` queues.

### Running without a broker

```yaml
omnirec:
  processing:
    queue-enabled: false
```

Destinations are called inline on the request thread. Useful for local
development and integration tests — **not for a deployment**, because there is
then no retry, no dead-lettering, and no durability. The auto-configuration logs
a warning saying so.

## Metrics

| Metric | Meaning |
|---|---|
| `omnirec.queue.depth{destination, queue=main\|retry-n\|dlq}` | Messages waiting. A growing retry or DLQ depth is how you notice an outage. Read from the broker at most every 15s. |
| `omnirec.provider.retries{destination, attempt}` | Retries scheduled |
| `omnirec.provider.dead_lettered{destination, reason}` | Moved to the DLQ: needs a human |
| `omnirec.provider.delivery.latency{destination}` | Receipt at the API to acceptance by the provider |

## Scaling

Consumers are stateless; run more instances. Before you do, set
`omnirec.state.redis.enabled=true` (module `omnirec-redis-state`). It replaces
the deduplication and identity-link stores with shared Redis-backed ones.
Without it, both are per-process: the same event can be delivered once per
instance, and one instance's identity links are invisible to the others. Both log
a warning when they are the ones active.
