// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.worker;

import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.commerce.storage.EventStore.SaveOutcome;
import io.omnirec.commerce.storage.EventStoreException;
import io.omnirec.storage.metrics.StorageMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * The storage worker: writes every event to the {@link EventStore}.
 *
 * It is an ordinary {@link EventDestination}, which is what makes storage
 * asynchronous and failure-tolerant without any machinery of its own. The
 * existing pipeline gives it, like every destination:
 * <pre>
 *   omnirec.events ──events.event-storage──> omnirec.events.event-storage ── consumer ── this
 *                                            omnirec.events.event-storage.retry.1..n
 *                                            omnirec.events.event-storage.dlq
 * </pre>
 * The consumer acks a message only after {@link #send} returns — that is, only
 * after the row is committed. A transient failure (database down, failover)
 * throws, and the message moves to a retry tier, publisher-confirmed before the
 * original is acked; a permanent one (a row the database will never accept) is
 * dead-lettered. A slow or unavailable database backs up only this queue;
 * provider delivery carries on.
 *
 * It depends on the {@link EventStore} interface only. Whether that is plain
 * PostgreSQL, Neon, or TimescaleDB is invisible here.
 */
public class EventStorageDestination implements EventDestination {

    private static final Logger log = LoggerFactory.getLogger(EventStorageDestination.class);

    public static final String ID = "event-storage";

    private final EventStore store;
    private final StorageMetrics metrics;
    private final Clock clock;

    public EventStorageDestination(EventStore store, StorageMetrics metrics) {
        this(store, metrics, Clock.systemUTC());
    }

    public EventStorageDestination(EventStore store, StorageMetrics metrics, Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Everything, unlike a provider: history should be complete. That includes
     * the {@code identify} control event — it is what records the identity link
     * customer history is joined through — and dwell-time engagement updates.
     */
    @Override
    public boolean supports(CommerceEvent event) {
        return true;
    }

    @Override
    public void send(CommerceEvent event) {
        metrics.received(event.tenantId());
        long started = System.nanoTime();

        SaveOutcome outcome;
        try {
            outcome = store.save(event);
        } catch (EventStoreException e) {
            metrics.failed(event.tenantId(), e.isRetryable() ? "transient" : "permanent");
            // Identifiers only. Never the payload, and the message carries no row data.
            log.warn("Could not store event {} ({}) for tenant {}: {}",
                    event.eventId(), event.eventType().wireName(), event.tenantId(), e.getMessage());
            throw new DestinationException(ID, "could not store event " + event.eventId(), e, e.isRetryable());
        } catch (RuntimeException e) {
            // Unclassified, so retry rather than risk discarding the event.
            metrics.failed(event.tenantId(), "unexpected");
            throw new DestinationException(ID, "unexpected failure storing event " + event.eventId(), e, true);
        } finally {
            metrics.writeDuration(Duration.ofNanos(System.nanoTime() - started));
        }

        if (outcome == SaveOutcome.DUPLICATE) {
            metrics.duplicate(event.tenantId());
            log.debug("Event {} for tenant {} was already stored; redelivery ignored", event.eventId(), event.tenantId());
            return;
        }
        metrics.persisted(event.tenantId());
        if (event.receivedAt() != null) {
            metrics.lag(Duration.between(event.receivedAt(), clock.instant()));
        }
    }
}
