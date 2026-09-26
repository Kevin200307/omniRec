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
import org.springframework.amqp.core.MessageProperties;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2 and R3 from the audit, against a real broker.
 *
 * <b>R2 — head-of-line blocking.</b> RabbitMQ only expires messages at the head
 * of a queue. With the old single retry queue and per-message TTLs, an event
 * due for its 1s retry sat behind one due in 8s and was held for the full 8s.
 * With one queue per attempt, each holding a single TTL, it isn't.
 *
 * <b>R3 — rejected messages vanished.</b> An unparseable message is rejected
 * without requeue; the main queue had no dead-letter exchange, so the broker
 * silently discarded it. Now it lands in the DLQ.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=true",
        "omnirec.processing.max-retries=4",
        "omnirec.processing.retry-initial-interval=1s",
        "omnirec.processing.retry-max-interval=8s",
        "omnirec.processing.concurrency=1",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetryTiersBrokerTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    static class ScriptedDestination implements EventDestination {
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Map<String, Instant> deliveredAt = new ConcurrentHashMap<>();

        @Override public String id() { return "tiers"; }

        @Override
        public void send(CommerceEvent event) {
            int attempt = attempts.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
            String script = event.commerce().productId();
            if ("always-fail".equals(script) || ("fail-once".equals(script) && attempt == 1)) {
                throw new DestinationException(id(), "provider down", null, true);
            }
            deliveredAt.put(event.eventId(), Instant.now());
        }
    }

    @TestConfiguration
    static class Destinations {
        @Bean
        ScriptedDestination tiersDestination() {
            return new ScriptedDestination();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private ScriptedDestination destination;

    private String send(String productId) throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        Map<String, Object> event = Map.of(
                "eventId", eventId,
                "eventType", "product_viewed",
                "schemaVersion", "1.0",
                "timestamp", Instant.now().toString(),
                "identity", Map.of("anonymousId", "anon_" + eventId, "sessionId", "s1"),
                "context", Map.of("platform", "web"),
                "commerce", Map.of("productId", productId),
                "properties", Map.of());
        mockMvc.perform(post("/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_test_demo_store")
                        .content(objectMapper.writeValueAsString(Map.of("events", List.of(event)))))
                .andExpect(status().isAccepted());
        return eventId;
    }

    @Test
    void declaresOneRetryQueuePerAttemptEachWithItsOwnTtl() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.retryQueueName("tiers", attempt)),
                    "missing retry tier " + attempt);
        }
    }

    @Test
    void aShortRetryIsNotHeldBehindALongOne() throws Exception {
        // Park an event in the 8s tier: it fails at attempts 0, 1 (1s), 2 (2s), 3 (4s).
        String slow = send("always-fail");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(4, destination.attempts.get(slow).get(), "should now be waiting 8s in tier 4"));

        // Now an event that needs a single 1s retry.
        Instant sentAt = Instant.now();
        String quick = send("fail-once");

        await().atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                assertNotNull(destination.deliveredAt.get(quick)));
        Duration waited = Duration.between(sentAt, destination.deliveredAt.get(quick));

        // With a single retry queue it would have waited for the 8s message
        // ahead of it to expire first.
        assertTrue(waited.compareTo(Duration.ofSeconds(5)) < 0,
                "a 1s retry took " + waited + "; it was held behind a longer TTL");
    }

    @Test
    void anUnparseableMessageIsDeadLetteredNotSilentlyDropped() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId("garbage-" + UUID.randomUUID());
        rabbitTemplate.send(QueueTopology.EXCHANGE, QueueTopology.routingKey("tiers"),
                new Message("{this is not json".getBytes(), properties));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message dead = rabbitTemplate.receive(QueueTopology.deadLetterQueueName("tiers"), 200);
            assertNotNull(dead, "a rejected message must land in the DLQ, not vanish");
            assertEquals("{this is not json", new String(dead.getBody()));
        });
    }
}
