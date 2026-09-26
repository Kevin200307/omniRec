// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventprocessing.queue.QueueTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The full pipeline against a real RabbitMQ broker:
 *
 * <pre>
 *   HTTP -> gateway -> RabbitMQ -> consumer -> destination
 *                          |
 *                          +-- transient failure -> retry queue (TTL) -> back to main queue
 *                          +-- exhausted / permanent -> dead-letter queue
 * </pre>
 *
 * The mocked-template tests in DispatchAndRetryTest prove the consumer makes the
 * right <em>calls</em>. Only a real broker proves the topology is right: that
 * the retry queue's TTL actually dead-letters messages back onto the main queue,
 * that the DLQ binding actually catches exhausted messages, that messages
 * survive serialisation, and that one destination's failures don't hold up
 * another's.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=true",
        // Two retries at 1s and 2s keeps exhaustion under five seconds.
        "omnirec.processing.max-retries=2",
        "omnirec.processing.concurrency=1",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealBrokerPipelineTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    /**
     * Behaviour is chosen per event by its productId, so each test drives one
     * destination into exactly the failure mode it's asserting on.
     */
    static class ScriptedDestination implements EventDestination {
        private final String id;
        private final boolean followsScript;
        final List<CommerceEvent> delivered = new CopyOnWriteArrayList<>();
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        ScriptedDestination(String id, boolean followsScript) {
            this.id = id;
            this.followsScript = followsScript;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void send(CommerceEvent event) {
            int attempt = attempts.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
            String script = followsScript ? event.commerce().productId() : "";

            if ("fail-once".equals(script) && attempt == 1) {
                throw new DestinationException(id, "transient outage", null, true);
            }
            if ("always-fail".equals(script)) {
                throw new DestinationException(id, "still down", null, true);
            }
            if ("reject".equals(script)) {
                throw DestinationException.permanent(id, "provider refused the payload");
            }
            delivered.add(event);
        }

        int attemptsFor(String eventId) {
            AtomicInteger count = attempts.get(eventId);
            return count == null ? 0 : count.get();
        }

        boolean hasDelivered(String eventId) {
            return delivered.stream().anyMatch(e -> e.eventId().equals(eventId));
        }
    }

    @TestConfiguration
    static class Destinations {
        @Bean
        ScriptedDestination primaryDestination() {
            return new ScriptedDestination("primary", true);
        }

        /** A second, always-healthy provider, to prove failures are isolated per destination. */
        @Bean
        ScriptedDestination secondaryDestination() {
            return new ScriptedDestination("secondary", false);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ScriptedDestination primaryDestination;

    @Autowired
    private ScriptedDestination secondaryDestination;

    private String send(String productId) throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        postEvent(eventId, productId);
        return eventId;
    }

    private void postEvent(String eventId, String productId) throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", eventId,
                "eventType", "product_viewed",
                "schemaVersion", "1.0",
                "timestamp", "2026-01-01T12:00:00.000Z",
                "identity", Map.of("anonymousId", "anon_" + eventId, "sessionId", "session_1"),
                "context", Map.of("platform", "web"),
                "commerce", Map.of("productId", productId),
                "properties", Map.of());

        mockMvc.perform(post("/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_test_demo_store")
                        .content(objectMapper.writeValueAsString(Map.of("events", List.of(event)))))
                .andExpect(status().isAccepted());
    }

    /** Finds a specific event on a DLQ, draining it. Other tests' messages may be there too. */
    private CommerceEvent findOnDeadLetterQueue(String destinationId, String eventId) throws Exception {
        String dlq = QueueTopology.deadLetterQueueName(destinationId);
        Message message;
        while ((message = rabbitTemplate.receive(dlq, 200)) != null) {
            CommerceEvent event = objectMapper.readValue(message.getBody(), CommerceEvent.class);
            if (event.eventId().equals(eventId)) {
                assertNotNull(message.getMessageProperties().getHeaders().get(QueueTopology.FAILURE_REASON_HEADER),
                        "a dead-lettered message must say why, or triage is guesswork");
                return event;
            }
        }
        return null;
    }

    @Test
    void anEventTravelsThroughARealBrokerToEveryDestination() throws Exception {
        String eventId = send("p123");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertTrue(primaryDestination.hasDelivered(eventId));
            assertTrue(secondaryDestination.hasDelivered(eventId));
        });

        CommerceEvent delivered = primaryDestination.delivered.stream()
                .filter(e -> e.eventId().equals(eventId)).findFirst().orElseThrow();
        assertEquals("p123", delivered.commerce().productId(), "the event must survive the round trip through JSON");
        assertEquals("demo-store", delivered.tenantId());
        assertNotNull(delivered.receivedAt());
    }

    @Test
    void theTopologyIsDeclaredOnTheBroker() {
        for (String destination : List.of("primary", "secondary")) {
            assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.queueName(destination)));
            assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.retryQueueName(destination, 1)));
            assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.retryQueueName(destination, 2)));
            assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.deadLetterQueueName(destination)));
        }
    }

    /**
     * The real test of the retry queue: nothing in our code moves the message
     * back. Only the broker's TTL expiry dead-lettering it onto the main queue
     * can make the second attempt happen.
     */
    @Test
    void aTransientFailureIsRetriedThroughTheBrokerAndThenDelivered() throws Exception {
        String eventId = send("fail-once");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertTrue(primaryDestination.hasDelivered(eventId)));

        assertEquals(2, primaryDestination.attemptsFor(eventId), "one failure, then one successful retry");
        assertEquals(1, primaryDestination.delivered.stream().filter(e -> e.eventId().equals(eventId)).count(),
                "the retry must not produce a second delivery");
    }

    @Test
    void anEventThatKeepsFailingEndsUpOnTheDeadLetterQueue() throws Exception {
        String eventId = send("always-fail");

        // Initial attempt + 2 retries (1s, 2s), then dead-lettered.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertEquals(3, primaryDestination.attemptsFor(eventId)));

        CommerceEvent[] deadLettered = new CommerceEvent[1];
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            deadLettered[0] = findOnDeadLetterQueue("primary", eventId);
            assertNotNull(deadLettered[0], "an exhausted event must be parked, never discarded");
        });
        assertEquals("always-fail", deadLettered[0].commerce().productId());
        assertFalse(primaryDestination.hasDelivered(eventId));
    }

    @Test
    void aPermanentRejectionSkipsRetriesAndGoesStraightToTheDeadLetterQueue() throws Exception {
        String eventId = send("reject");

        CommerceEvent[] deadLettered = new CommerceEvent[1];
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            deadLettered[0] = findOnDeadLetterQueue("primary", eventId);
            assertNotNull(deadLettered[0]);
        });
        assertEquals(1, primaryDestination.attemptsFor(eventId),
                "retrying a payload the provider has already refused just delays the inevitable");
    }

    /**
     * Provider independence, demonstrated: the primary destination failing
     * repeatedly must not delay or block delivery to the secondary one.
     */
    @Test
    void oneProviderFailingDoesNotBlockAnother() throws Exception {
        String eventId = send("always-fail");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertTrue(secondaryDestination.hasDelivered(eventId),
                        "the healthy provider must receive the event while the other is still retrying"));
    }

    @Test
    void theSameEventSubmittedTwiceIsDeliveredOnce() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();

        mockMvc.perform(post("/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_test_demo_store")
                        .content(objectMapper.writeValueAsString(Map.of("events", List.of(Map.of(
                                "eventId", eventId,
                                "eventType", "product_viewed",
                                "schemaVersion", "1.0",
                                "timestamp", "2026-01-01T12:00:00.000Z",
                                "identity", Map.of("anonymousId", "anon_dup", "sessionId", "s1"),
                                "context", Map.of(),
                                "commerce", Map.of("productId", "p1"),
                                "properties", Map.of()))))))
                .andExpect(jsonPath("$.accepted").value(1));
        postEvent(eventId, "p1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertTrue(primaryDestination.hasDelivered(eventId)));
        // Give a would-be duplicate time to arrive before asserting it didn't.
        Thread.sleep(1500);

        assertEquals(1, primaryDestination.delivered.stream().filter(e -> e.eventId().equals(eventId)).count());
    }

    /**
     * RabbitMQ is at-least-once: redelivery is normal, not exceptional. Here we
     * bypass the gateway and publish the same message straight onto the queue
     * twice, exactly as a broker redelivery would, and assert the consumer-side
     * deduplication keeps it to one provider call.
     */
    @Test
    void aBrokerRedeliveryDoesNotReachTheProviderTwice() throws Exception {
        String eventId = send("p1");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertTrue(primaryDestination.hasDelivered(eventId)));

        CommerceEvent original = primaryDestination.delivered.stream()
                .filter(e -> e.eventId().equals(eventId)).findFirst().orElseThrow();
        rabbitTemplate.send(QueueTopology.EXCHANGE, QueueTopology.routingKey("primary"),
                new Message(objectMapper.writeValueAsBytes(original)));

        Thread.sleep(1500);
        assertEquals(1, primaryDestination.delivered.stream().filter(e -> e.eventId().equals(eventId)).count(),
                "a redelivered message must be recognised and skipped");
    }
}
