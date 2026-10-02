// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventprocessing;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.eventprocessing.queue.ConfirmedPublisher;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.eventprocessing.queue.RabbitEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Which destination queues an event is published to.
 *
 * Behavioural events go to every destination, exactly as before storage
 * existed. A control event (identify) goes only where it is wanted, so no
 * provider queue ever carries one.
 */
class RabbitEventPublisherRoutingTest {

    /** A provider: the default supports(), which refuses control events. */
    private static EventDestination provider(String id) {
        return new EventDestination() {
            @Override public String id() { return id; }
            @Override public void send(CommerceEvent event) { }
        };
    }

    /** Historical storage: wants everything. */
    private static EventDestination storage() {
        return new EventDestination() {
            @Override public String id() { return "event-storage"; }
            @Override public void send(CommerceEvent event) { }
            @Override public boolean supports(CommerceEvent event) { return true; }
        };
    }

    private static CommerceEvent event(EventName type) {
        return CommerceEvent.builder()
                .eventId("evt_" + type.wireName())
                .eventType(type)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .tenantId("demo-store")
                .identity(EventIdentity.authenticated("anon_A", "customer_1", "s1"))
                .commerce(CommerceData.builder().productId("p1").build())
                .build();
    }

    private final ConfirmedPublisher confirmed = mock(ConfirmedPublisher.class);

    @Test
    void aBehaviouralEventGoesToEveryDestination() {
        new RabbitEventPublisher(confirmed, List.of(provider("amazon-personalize"), storage()))
                .publish(event(StandardEventNames.PRODUCT_VIEWED));

        verify(confirmed).publish(eq(QueueTopology.EXCHANGE), eq(QueueTopology.routingKey("amazon-personalize")),
                any(), any(MessagePostProcessor.class));
        verify(confirmed).publish(eq(QueueTopology.EXCHANGE), eq(QueueTopology.routingKey("event-storage")),
                any(), any(MessagePostProcessor.class));
    }

    @Test
    void anIdentifyReachesOnlyDestinationsThatAcceptControlEvents() {
        new RabbitEventPublisher(confirmed, List.of(provider("amazon-personalize"), provider("google-retail"), storage()))
                .publish(event(StandardEventNames.IDENTIFY));

        verify(confirmed, times(1)).publish(anyString(), anyString(), any(), any(MessagePostProcessor.class));
        verify(confirmed).publish(eq(QueueTopology.EXCHANGE), eq(QueueTopology.routingKey("event-storage")),
                any(), any(MessagePostProcessor.class));
    }

    /** With storage disabled, an identify is published nowhere — the behaviour before storage existed. */
    @Test
    void withNoDestinationWantingIt_anIdentifyIsPublishedNowhere() {
        new RabbitEventPublisher(confirmed, List.of(provider("amazon-personalize"), provider("google-retail")))
                .publish(event(StandardEventNames.IDENTIFY));

        verify(confirmed, never()).publish(anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }
}
