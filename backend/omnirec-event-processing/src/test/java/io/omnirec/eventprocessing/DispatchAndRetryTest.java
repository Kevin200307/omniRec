package io.omnirec.eventprocessing;

import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.dedup.InMemoryDeduplicationStore;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import io.omnirec.eventprocessing.consumer.DestinationConsumer;
import io.omnirec.eventprocessing.dispatch.EventDispatcher;
import io.omnirec.eventprocessing.queue.ConfirmedPublisher;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.eventprocessing.queue.RetrySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DispatchAndRetryTest {

    /** Configurable destination: counts deliveries and fails on demand. */
    private static final class FakeDestination implements EventDestination {
        private final String id;
        final List<CommerceEvent> delivered = new ArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        RuntimeException failWith;
        boolean supportsEverything = true;

        FakeDestination(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }

        @Override
        public void send(CommerceEvent event) {
            attempts.incrementAndGet();
            if (failWith != null) throw failWith;
            delivered.add(event);
        }

        @Override
        public boolean supports(CommerceEvent event) {
            return supportsEverything && !event.eventType().isControlEvent();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static final Duration LEASE = Duration.ofMinutes(2);

    private FakeDestination destination;
    private MutableClock clock;
    private DeduplicationStore deduplicationStore;
    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        destination = new FakeDestination("fake-provider");
        clock = new MutableClock();
        deduplicationStore = new InMemoryDeduplicationStore(clock, 10_000);
        dispatcher = new EventDispatcher(deduplicationStore, EventMetrics.noop(), Duration.ofHours(24), LEASE, clock);
    }

    private CommerceEvent event(String eventId) {
        return CommerceEvent.builder()
                .eventId(eventId)
                .eventType(EventType.PRODUCT_VIEWED)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .tenantId("demo-store")
                .identity(EventIdentity.anonymous("anon_A", "session_1"))
                .context(EventContext.empty())
                .commerce(CommerceData.builder().productId("p1").build())
                .properties(Map.of())
                .build();
    }

    private Message messageWithRetryCount(Integer retryCount) {
        MessageProperties properties = new MessageProperties();
        if (retryCount != null) {
            properties.getHeaders().put(QueueTopology.RETRY_COUNT_HEADER, retryCount);
        }
        return new Message(new byte[0], properties);
    }

    @Nested
    @DisplayName("dispatching")
    class Dispatching {

        @Test
        void deliversAnEventToTheDestination() {
            dispatcher.dispatch(event("evt_1"), destination);

            assertEquals(1, destination.delivered.size());
        }

        @Test
        void skipsAnEventTheDestinationDoesNotSupport() {
            destination.supportsEverything = false;

            dispatcher.dispatch(event("evt_1"), destination);

            assertEquals(0, destination.attempts.get(), "an unsupported type should cost no provider call");
        }

        /**
         * RabbitMQ is at-least-once: a consumer that dies after calling the
         * provider but before acking will see the message again.
         */
        @Test
        void deliversTheSameEventOnlyOnceUnderRedelivery() {
            CommerceEvent event = event("evt_1");

            for (int i = 0; i < 5; i++) {
                dispatcher.dispatch(event, destination);
            }

            assertEquals(1, destination.delivered.size());
        }

        @Test
        void deduplicatesPerDestinationNotGlobally() {
            FakeDestination other = new FakeDestination("other-provider");
            CommerceEvent event = event("evt_1");

            dispatcher.dispatch(event, destination);
            dispatcher.dispatch(event, other);

            assertEquals(1, destination.delivered.size());
            assertEquals(1, other.delivered.size(),
                    "one destination having seen an event must not stop another receiving it");
        }

        @Test
        void releasesTheLeaseOnFailureSoARetryCanActuallyDeliver() {
            destination.failWith = new DestinationException("fake-provider", "boom", null, true);
            assertThrows(DestinationException.class, () -> dispatcher.dispatch(event("evt_1"), destination));

            destination.failWith = null;
            dispatcher.dispatch(event("evt_1"), destination);

            assertEquals(1, destination.delivered.size());
        }

        @Test
        void wrapsAnUnexpectedExceptionAsRetryable() {
            destination.failWith = new IllegalStateException("something we did not anticipate");

            DestinationException thrown = assertThrows(DestinationException.class,
                    () -> dispatcher.dispatch(event("evt_1"), destination));

            assertTrue(thrown.isRetryable(), "an unclassified bug should not silently discard events");
        }
    }

    @Nested
    @DisplayName("the delivery crash window")
    class CrashWindow {

        private String key(String eventId) {
            return DeduplicationStore.key("deliver:fake-provider", "demo-store", eventId);
        }

        /**
         * A consumer took the lease and crashed before completing. The old
         * protocol had already written "done" for 24h, so the redelivery was
         * skipped and the event never reached the provider.
         */
        @Test
        void aRedeliveryDuringACrashedLeaseIsRetriedNotSkipped() {
            deduplicationStore.claim(key("evt_crash"), LEASE);

            DestinationException thrown = assertThrows(DestinationException.class,
                    () -> dispatcher.dispatch(event("evt_crash"), destination));

            assertTrue(thrown.isRetryable(), "in progress is a reason to wait, not to give up");
            assertEquals(0, destination.attempts.get());
        }

        @Test
        void theEventIsDeliveredOnceTheCrashedLeaseExpires() {
            deduplicationStore.claim(key("evt_crash"), LEASE);

            clock.advance(LEASE.plusSeconds(1));
            dispatcher.dispatch(event("evt_crash"), destination);

            assertEquals(1, destination.delivered.size());
        }
    }

    @Nested
    @DisplayName("consumer: retry tiers and dead-lettering")
    class ConsumerBehaviour {

        private ConfirmedPublisher publisher;
        private DestinationConsumer consumer;
        private final RetrySchedule schedule = new RetrySchedule(3, Duration.ofSeconds(1), Duration.ofMinutes(5));

        @BeforeEach
        void setUp() {
            publisher = mock(ConfirmedPublisher.class);
            consumer = new DestinationConsumer(destination, dispatcher, publisher, EventMetrics.noop(), schedule);
        }

        @Test
        void aSuccessfulDeliveryMovesNothing() {
            consumer.handle(event("evt_1"), messageWithRetryCount(null));

            verify(publisher, never()).publish(anyString(), anyString(), any(), any(MessagePostProcessor.class));
        }

        @Test
        void aFirstTransientFailureGoesToRetryTierOne() {
            destination.failWith = new DestinationException("fake-provider", "provider down", null, true);

            consumer.handle(event("evt_1"), messageWithRetryCount(null));

            verify(publisher).publish(eq(""), eq(QueueTopology.retryQueueName("fake-provider", 1)),
                    any(), any(MessagePostProcessor.class));
        }

        @Test
        void eachLaterFailureGoesToTheNextTier() {
            destination.failWith = new DestinationException("fake-provider", "down", null, true);

            consumer.handle(event("evt_1"), messageWithRetryCount(2));

            verify(publisher).publish(eq(""), eq(QueueTopology.retryQueueName("fake-provider", 3)),
                    any(), any(MessagePostProcessor.class));
        }

        @Test
        void aPermanentFailureSkipsRetriesEntirely() {
            destination.failWith = DestinationException.permanent("fake-provider", "misconfigured");

            consumer.handle(event("evt_1"), messageWithRetryCount(null));

            verify(publisher).publish(eq(QueueTopology.DEAD_LETTER_EXCHANGE),
                    eq(QueueTopology.routingKey("fake-provider")), any(), any(MessagePostProcessor.class));
        }

        @Test
        void anExhaustedMessageIsDeadLetteredRatherThanRetriedForever() {
            destination.failWith = new DestinationException("fake-provider", "still down", null, true);

            consumer.handle(event("evt_1"), messageWithRetryCount(3));

            verify(publisher).publish(eq(QueueTopology.DEAD_LETTER_EXCHANGE),
                    eq(QueueTopology.routingKey("fake-provider")), any(), any(MessagePostProcessor.class));
        }

        /**
         * The container acks the original only if handle() returns. If moving
         * the message to a retry tier isn't confirmed, handle() must throw, so
         * the original is nacked and redelivered instead of acked into the void.
         */
        @Test
        void anUnconfirmedMoveThrowsSoTheOriginalIsNotAcked() {
            destination.failWith = new DestinationException("fake-provider", "down", null, true);
            doThrow(new AmqpException("no confirm"))
                    .when(publisher).publish(anyString(), anyString(), any(), any(MessagePostProcessor.class));

            assertThrows(AmqpException.class, () -> consumer.handle(event("evt_1"), messageWithRetryCount(null)));
        }
    }

    @Nested
    @DisplayName("retry schedule")
    class Schedule {

        private final RetrySchedule schedule = new RetrySchedule(20, Duration.ofSeconds(1), Duration.ofMinutes(5));

        @Test
        void growsExponentially() {
            assertEquals(Duration.ofSeconds(1), schedule.delayFor(1));
            assertEquals(Duration.ofSeconds(2), schedule.delayFor(2));
            assertEquals(Duration.ofSeconds(4), schedule.delayFor(3));
            assertEquals(Duration.ofSeconds(8), schedule.delayFor(4));
        }

        @Test
        void isCappedSoALongOutageDoesNotScheduleAnHourLongWait() {
            assertEquals(Duration.ofMinutes(5), schedule.delayFor(20));
        }

        @Test
        void rejectsANonsensicalConfiguration() {
            assertThrows(IllegalArgumentException.class, () -> new RetrySchedule(3, Duration.ZERO, Duration.ofMinutes(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> new RetrySchedule(3, Duration.ofMinutes(5), Duration.ofSeconds(1)));
        }
    }

    @Nested
    @DisplayName("topology naming")
    class Topology {

        @Test
        void givesEachDestinationItsOwnQueuesAndOneRetryTierPerAttempt() {
            assertEquals("omnirec.events.amazon-personalize", QueueTopology.queueName("amazon-personalize"));
            assertEquals("omnirec.events.amazon-personalize.retry.1", QueueTopology.retryQueueName("amazon-personalize", 1));
            assertEquals("omnirec.events.amazon-personalize.retry.3", QueueTopology.retryQueueName("amazon-personalize", 3));
            assertEquals("omnirec.events.amazon-personalize.dlq", QueueTopology.deadLetterQueueName("amazon-personalize"));
        }
    }
}
