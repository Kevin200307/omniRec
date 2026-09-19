package io.omnirec.eventprocessing.dispatch;

import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.dedup.DeduplicationStore.ClaimResult;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;

/**
 * Delivers one event to one destination, exactly once as far as the pipeline
 * can make it.
 *
 * This deduplication is <em>per destination</em> and separate from ingestion:
 * ingestion stops a client sending the same event twice; this stops RabbitMQ's
 * at-least-once redelivery from reaching a provider twice. A consumer that dies
 * after calling Amazon but before acking <em>will</em> see the message again.
 *
 * <h2>Lease, then complete</h2>
 * <pre>
 *   claim(lease)  CLAIMED            -> send -> complete(window) | release on failure
 *                 ALREADY_COMPLETED  -> a redelivery of something delivered: ack and skip
 *                 IN_PROGRESS        -> another consumer is sending it right now, or one
 *                                       crashed mid-send: retry later; the lease expires
 * </pre>
 * The lease must outlast the slowest provider call. If a consumer crashes
 * mid-send, the event waits at most one lease before being retried, instead of
 * being marked "done" and lost, which is what claiming for the whole window up
 * front did.
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final DeduplicationStore deduplicationStore;
    private final EventMetrics metrics;
    private final Duration deduplicationWindow;
    private final Duration deliveryLease;
    private final Clock clock;

    public EventDispatcher(DeduplicationStore deduplicationStore, EventMetrics metrics,
                           Duration deduplicationWindow, Duration deliveryLease) {
        this(deduplicationStore, metrics, deduplicationWindow, deliveryLease, Clock.systemUTC());
    }

    public EventDispatcher(DeduplicationStore deduplicationStore, EventMetrics metrics,
                           Duration deduplicationWindow, Duration deliveryLease, Clock clock) {
        this.deduplicationStore = deduplicationStore;
        this.metrics = metrics;
        this.deduplicationWindow = deduplicationWindow;
        this.deliveryLease = deliveryLease;
        this.clock = clock;
    }

    /**
     * Returns normally when the event was delivered or deliberately skipped
     * (ack it). Throws {@link DestinationException} otherwise, so the consumer
     * can retry or dead-letter.
     */
    public void dispatch(CommerceEvent event, EventDestination destination) {
        MDC.put("eventId", event.eventId());
        MDC.put("destination", destination.id());
        try {
            if (!destination.supports(event)) {
                log.debug("Destination {} does not handle {} — skipping", destination.id(), event.eventType());
                return;
            }

            String stage = "deliver:" + destination.id();
            String dedupKey = DeduplicationStore.key(stage, event.tenantId(), event.eventId());

            ClaimResult claim = deduplicationStore.claim(dedupKey, deliveryLease);
            if (claim == ClaimResult.ALREADY_COMPLETED) {
                metrics.duplicateEvents(event.tenantId(), stage, 1);
                log.debug("Skipping duplicate delivery of {} to {}", event.eventId(), destination.id());
                return;
            }
            if (claim == ClaimResult.IN_PROGRESS) {
                throw new DestinationException(destination.id(),
                        "delivery of " + event.eventId() + " already in progress", null, true);
            }

            try {
                destination.send(event);
            } catch (DestinationException e) {
                deduplicationStore.release(dedupKey);
                metrics.providerDeliveryFailure(destination.id(), e.isRetryable() ? "transient" : "permanent", 1);
                throw e;
            } catch (RuntimeException e) {
                // An adapter throwing something unexpected is treated as
                // transient: assuming otherwise would silently drop events on a
                // bug nobody has classified yet.
                deduplicationStore.release(dedupKey);
                metrics.providerDeliveryFailure(destination.id(), "unexpected", 1);
                throw new DestinationException(destination.id(),
                        "Unexpected failure delivering event to " + destination.id(), e);
            }

            deduplicationStore.complete(dedupKey, deduplicationWindow);
            metrics.providerDeliverySuccess(destination.id(), 1);
            metrics.eventsProcessed(event.tenantId(), 1);
            if (event.receivedAt() != null) {
                metrics.deliveryLatency(destination.id(), Duration.between(event.receivedAt(), clock.instant()));
            }
        } finally {
            MDC.remove("eventId");
            MDC.remove("destination");
        }
    }
}
