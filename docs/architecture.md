# Architecture

Omnirec collects commerce interactions, standardises them, resolves who they
belong to, and delivers them to whichever personalisation providers a merchant
has configured. It is infrastructure, not an analytics product: there is no
dashboard, no reporting UI, and no model of its own.

```
                E-COMMERCE APPLICATION
                         |
          +--------------+--------------+
          |                             |
          v                             v
  FRONTEND JS SDK               BACKEND JAVA SDK
  @omnirec/commerce-web         commerce-tracker-spring-boot
          |                             |
  User interactions              Business events
  (views, cart, search)          (purchases, refunds, reviews)
          |                             |
          +--------------+--------------+
                         |
                         v
                  EVENT GATEWAY            omnirec-event-api
                         |
                +--------+--------+
                |                 |
          Authentication      Validation
                |                 |
                +--------+--------+
                         |
                         v
                  EVENT NORMALIZER
                         |
                         v
                 IDENTITY RESOLVER
                         |
                         v
                   DEDUPLICATOR
                         |
                         v
                      RABBITMQ            omnirec-event-processing
                         |
                         v
                EVENT DISPATCHER
                         |
         +---------------+---------------+
         |               |               |
         v               v               v
   AMAZON ADAPTER   GOOGLE ADAPTER   AZURE ADAPTER
         |               |            (not built)
         v               v
   Amazon Personalize  Google Retail
```

## The one rule

**The core never depends on a provider.** `omnirec-commerce-core` has three
dependencies — Jackson, its date module, and SLF4J. It has no Spring, no queue,
and no cloud SDK. Everything provider-specific lives behind one interface:

```java
public interface EventDestination {
    String id();
    void send(CommerceEvent event);
}
```

Adding Azure means writing one implementation and registering it as a bean.
Nothing in the core, the gateway, the queue, or either SDK changes. That claim
is enforced by the module graph, not by discipline: those modules have no
compile-time path to a provider SDK, so a provider import there won't compile.

## Modules

| Module | Responsibility |
|---|---|
| `omnirec-commerce-core` | `CommerceEvent`, the taxonomy, validation, identity linking, deduplication, `EventDestination`. No framework. |
| `omnirec-event-api` | The gateway: API keys, rate limiting, payload caps, normalization, validation, dedup, identity resolution. |
| `omnirec-event-processing` | RabbitMQ topology, consumers, dispatcher, per-destination idempotency, retry and DLQ. |
| `omnirec-amazon-personalize-destination` | The only module that knows Personalize exists. |
| `omnirec-google-retail-destination` | The only module that knows Google Retail exists. |
| `omnirec-recently-viewed-destination` | Keeps the serving side's recently-viewed lists current from the pipeline. |
| `omnirec-redis-state` | Redis-backed deduplication and identity-link stores, for multi-instance deployments. |
| `commerce-tracker-spring-boot` | The SDK a merchant embeds for authoritative business events. |
| `omnirec-event-api-app` | The standalone deployable: gateway + queue + destinations. |
| `packages/commerce-web` | The browser SDK. No React dependency. |
| `packages/commerce-react` | ~40 lines of React binding over it. |

Alongside these, the original serving side (`omnirec-core`, `omnirec-web`,
`omnirec-personalize-starter`, `omnirec-google-recai-starter`,
`omnirec-redis-starter`, `omnirec-demo-app`) still answers
`/v1/recommendations`, `/v1/search`, and `/v1/recently-viewed`. Recently-viewed
is fed by the pipeline through `omnirec-recently-viewed-destination`, which writes
the lists in the format the serving side's Redis cache reads. Reading
personalisation results is a separate concern from collecting signals, and the
two deploy independently.

## Why the Event API is a separate service

The gateway is its own deployable rather than a library a merchant embeds. That
single decision is what makes the security model work:

- **Provider credentials live in exactly one process.** A merchant's storefront
  and their own backend hold a publishable key and nothing else, so neither can
  leak an AWS key it never had.
- **Tenant isolation is real.** The API key determines the tenant; a body field
  cannot.
- **One RabbitMQ, not one per merchant.** Durability, retry, and dead-lettering
  are operated once.

The cost is a network hop from the backend SDK, which is why that SDK delivers
asynchronously — our latency must never become the merchant's checkout latency.

## Why the pipeline is ordered the way it is

```
normalize -> validate -> deduplicate -> resolve identity -> queue
```

- **Validate before deduplicate**, so a malformed event never burns a
  deduplication key. Otherwise a client that sends a broken event, fixes it, and
  resends under the same `eventId` would have the corrected version silently
  dropped as a duplicate.
- **Deduplicate before identity**, so a redelivered `identify` doesn't re-link
  and duplicate work stops as early as possible.
- **Resolve identity before queueing**, so the event on the wire already carries
  the `userId`. Doing it in the consumer would make every destination worker
  re-query the link store for the same event.

## Two layers of deduplication

They guard different failures and neither replaces the other:

| Stage | Guards against |
|---|---|
| Ingestion (`dedup:ingest:…`) | A client sending the same event twice — an SDK retry after a timeout, a reloaded confirmation page. |
| Delivery (`dedup:deliver:<destination>:…`) | RabbitMQ's at-least-once redelivery. A consumer that dies after calling Amazon but before acking *will* see the message again. |

Both use a **lease → complete** protocol: a short lease is taken before the
work, replaced by a long-lived "done" record only after the work succeeds, and
released if it fails. A crash mid-work leaves only the lease, which expires, so
the event is retried instead of being marked done and lost. See
[rabbitmq.md](rabbitmq.md#at-least-once-so-consumers-are-idempotent).

## Failure behaviour

| Failure | What happens |
|---|---|
| Storefront offline | Events buffer in `localStorage` (bounded, oldest dropped) and flush when the network returns. |
| Event API returns 5xx | SDK retries with exponential backoff and full jitter. |
| Event API returns 4xx | SDK drops the batch — the payload is wrong and resending identical bytes cannot fix it. |
| RabbitMQ unreachable, or it nacks | Publisher confirm fails, ingestion returns 503 with Retry-After, the lease is released, the SDK retries. |
| Same event already being ingested | 503 with Retry-After; the retry then sees it as a duplicate. |
| Provider unavailable | The message moves to that destination's retry tier for the attempt, waits out its TTL, and comes back automatically. Other destinations keep draining. |
| Consumer crashes mid-delivery | Unacked message redelivered; the delivery lease expires, and it is delivered once. |
| Provider still failing after N retries | Dead-lettered with the reason attached, for triage. |
| Provider rejects permanently | Dead-lettered immediately, skipping retries. |

See [rabbitmq.md](rabbitmq.md) for the topology and [security.md](security.md)
for the trust boundaries.
