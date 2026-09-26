// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.queue;

import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventapi.queue.EventPublisher;
import io.omnirec.eventprocessing.dispatch.EventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Queue-free {@link EventPublisher} that dispatches straight to every
 * destination on the calling thread.
 *
 * Used when RabbitMQ is not configured, which keeps the whole pipeline runnable
 * for local development and integration tests without a broker. It is
 * <strong>not</strong> a substitute for the real thing: there is no retry, no
 * dead-letter queue, and no durability, so a destination being down means the
 * event is gone. The auto-configuration logs a warning when this is what's
 * active.
 *
 * One destination failing must not stop the others, so failures are collected
 * and reported after every destination has had its turn.
 */
public class DirectEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DirectEventPublisher.class);

    private final List<EventDestination> destinations;
    private final EventDispatcher dispatcher;

    public DirectEventPublisher(List<EventDestination> destinations, EventDispatcher dispatcher) {
        this.destinations = destinations;
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(CommerceEvent event) {
        RuntimeException firstFailure = null;

        for (EventDestination destination : destinations) {
            try {
                dispatcher.dispatch(event, destination);
            } catch (DestinationException e) {
                log.warn("Direct delivery of event {} to {} failed: {}",
                        event.eventId(), destination.id(), e.getMessage());
                if (firstFailure == null) firstFailure = e;
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
