// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing.queue;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventapi.queue.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;

import java.util.List;

/**
 * Fans one canonical event out to a durable queue per enabled destination, and
 * returns only once the broker has confirmed every copy.
 *
 * Per destination rather than one shared queue, so a slow or broken provider
 * backs up in isolation. If a later destination's publish fails after an
 * earlier one succeeded, the exception makes ingestion answer 503 and the
 * client retries the whole event; the destination that already has it drops
 * the repeat through delivery-stage deduplication.
 *
 * A control event ({@code identify}) is routed only to destinations whose
 * {@code supports()} accepts it. Provider destinations never do, so they see
 * exactly the traffic they always did; historical storage does, because the
 * identity link it carries is what customer history is joined through.
 * Behavioural events are routed to every destination, as before, and filtered
 * by {@code supports()} at delivery.
 */
public class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final ConfirmedPublisher publisher;
    private final List<EventDestination> destinations;
    private final List<String> destinationIds;

    public RabbitEventPublisher(ConfirmedPublisher publisher, List<EventDestination> destinations) {
        this.publisher = publisher;
        this.destinations = List.copyOf(destinations);
        this.destinationIds = destinations.stream().map(EventDestination::id).toList();
    }

    @Override
    public void publish(CommerceEvent event) {
        if (destinationIds.isEmpty()) {
            log.debug("No destinations configured — event {} accepted but not routed", event.eventId());
            return;
        }
        boolean controlEvent = event.eventType().isControlEvent();
        for (EventDestination destination : destinations) {
            if (controlEvent && !destination.supports(event)) {
                continue;
            }
            String destinationId = destination.id();
            publisher.publish(
                    QueueTopology.EXCHANGE,
                    QueueTopology.routingKey(destinationId),
                    event,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        // Carried to the consumer's MDC, so one event can be followed
                        // across the whole pipeline in the logs.
                        message.getMessageProperties().setMessageId(event.eventId());
                        return message;
                    });
        }
    }

    public List<String> destinationIds() {
        return destinationIds;
    }
}
