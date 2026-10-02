// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.omnirec.commerce.dedup.InMemoryDeduplicationStore;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.storage.CustomerEventPage;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.commerce.storage.EventStoreException;
import io.omnirec.eventprocessing.consumer.DestinationConsumer;
import io.omnirec.eventprocessing.dispatch.EventDispatcher;
import io.omnirec.eventprocessing.queue.ConfirmedPublisher;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.eventprocessing.queue.RetrySchedule;
import io.omnirec.storage.metrics.StorageMetrics;
import io.omnirec.storage.worker.EventStorageDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.omnirec.storage.StorageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The storage worker's contract with the queue, using the pipeline's real
 * dispatcher and consumer: the message is acked only once the row is
 * committed; a failure is retried or dead-lettered, never acked into the void.
 *
 * "Acked" here means DestinationConsumer.handle() returned normally — the
 * listener container acks on a normal return and nacks (so the broker
 * redelivers) on an exception. RealStoragePipelineTest shows the same thing
 * against a real broker and database.
 */
class EventStorageDestinationTest {

    /** Scriptable store: records saves, fails on demand. */
    private static final class ScriptedStore implements EventStore {
        final List<CommerceEvent> saved = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public SaveOutcome save(CommerceEvent event) {
            if (failWith != null) throw failWith;
            boolean duplicate = saved.stream().anyMatch(e -> e.eventId().equals(event.eventId()));
            if (!duplicate) saved.add(event);
            return duplicate ? SaveOutcome.DUPLICATE : SaveOutcome.STORED;
        }

        @Override
        public CustomerEventPage findCustomerEvents(String tenantId, String customerId, EventQuery query) {
            return CustomerEventPage.empty(customerId);
        }
    }

    private ScriptedStore store;
    private SimpleMeterRegistry meters;
    private EventStorageDestination worker;
    private ConfirmedPublisher publisher;
    private DestinationConsumer consumer;

    @BeforeEach
    void setUp() {
        store = new ScriptedStore();
        meters = new SimpleMeterRegistry();
        worker = new EventStorageDestination(store, new StorageMetrics(meters));
        publisher = mock(ConfirmedPublisher.class);
        EventDispatcher dispatcher = new EventDispatcher(new InMemoryDeduplicationStore(), EventMetrics.noop(),
                Duration.ofHours(24), Duration.ofMinutes(2));
        consumer = new DestinationConsumer(worker, dispatcher, publisher, EventMetrics.noop(),
                new RetrySchedule(3, Duration.ofSeconds(1), Duration.ofMinutes(5)));
    }

    private static Message message() {
        return new Message(new byte[0], new MessageProperties());
    }

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void acceptsEveryEventIncludingIdentify() {
        String tenant = tenant();
        assertTrue(worker.supports(identify(tenant, "anon_1", "user_1", T0)),
                "identify is how the identity link reaches storage");
        assertTrue(worker.supports(anonymousView(tenant, "anon_1", "p1", T0)));
        assertEquals("event-storage", worker.id());
    }

    @Test
    void successfulPersistenceCompletesTheMessageSoItIsAcked() {
        CommerceEvent event = anonymousView(tenant(), "anon_1", "p1", T0);

        assertDoesNotThrow(() -> consumer.handle(event, message()));

        assertEquals(1, store.saved.size());
        verify(publisher, never()).publish(anyString(), anyString(), any(), any(MessagePostProcessor.class));
        assertEquals(1, counter("omnirec.storage.events.persisted"));
    }

    @Test
    void aTransientFailureMovesTheMessageToARetryTierInsteadOfDroppingIt() {
        store.failWith = new EventStoreException("database unavailable", null, true);
        CommerceEvent event = anonymousView(tenant(), "anon_1", "p1", T0);

        consumer.handle(event, message());

        assertTrue(store.saved.isEmpty());
        verify(publisher).publish(eq(""), eq(QueueTopology.retryQueueName("event-storage", 1)),
                any(), any(MessagePostProcessor.class));
        assertEquals(1, counter("omnirec.storage.events.failed"));
    }

    @Test
    void aPermanentFailureIsDeadLetteredNotRetried() {
        store.failWith = new EventStoreException("row rejected", null, false);

        consumer.handle(anonymousView(tenant(), "anon_1", "p1", T0), message());

        verify(publisher).publish(eq(QueueTopology.DEAD_LETTER_EXCHANGE),
                eq(QueueTopology.routingKey("event-storage")), any(), any(MessagePostProcessor.class));
    }

    /**
     * The failure the "never ack prematurely" rule exists for: persistence
     * failed AND the retry copy could not be confirmed. handle() must throw so
     * the container nacks and the broker redelivers the original.
     */
    @Test
    void ifTheRetryCannotBeParkedTheOriginalIsNotAcked() {
        store.failWith = new EventStoreException("database unavailable", null, true);
        doThrow(new AmqpException("no confirm"))
                .when(publisher).publish(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        assertThrows(AmqpException.class,
                () -> consumer.handle(anonymousView(tenant(), "anon_1", "p1", T0), message()));
    }

    @Test
    void anUnexpectedExceptionIsTreatedAsRetryable() {
        store.failWith = new IllegalStateException("bug");

        DestinationException e = assertThrows(DestinationException.class,
                () -> worker.send(anonymousView(tenant(), "anon_1", "p1", T0)));
        assertTrue(e.isRetryable());
    }

    @Test
    void aDuplicateIsCountedAndAcknowledgedWithoutAnError() {
        CommerceEvent event = event(tenant(), StandardEventNames.PRODUCT_VIEWED,
                io.omnirec.commerce.model.EventIdentity.anonymous("a", "s"), "p1", T0);
        worker.send(event);

        assertDoesNotThrow(() -> worker.send(event));

        assertEquals(1, store.saved.size());
        assertEquals(1, counter("omnirec.storage.events.duplicates"));
    }
}
