// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.eventprocessing.queue.DeadLetterReplayer;
import org.junit.jupiter.api.Test;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A destination is down long enough for events to be dead-lettered; once it
 * recovers, a replay delivers them. Real broker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=true",
        "omnirec.processing.concurrency=1",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false",
        "management.endpoints.web.exposure.include=health,deadletters"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeadLetterReplayTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    /** Refuses everything permanently while down, so events go straight to the dead-letter queue. */
    static class Recovering implements EventDestination {
        final AtomicBoolean down = new AtomicBoolean(true);
        final Set<String> delivered = ConcurrentHashMap.newKeySet();

        @Override public String id() { return "replay-target"; }

        @Override
        public void send(CommerceEvent event) {
            if (down.get()) throw DestinationException.permanent(id(), "credentials revoked");
            delivered.add(event.eventId());
        }
    }

    @TestConfiguration
    static class Destinations {
        @Bean
        Recovering recoveringDestination() {
            return new Recovering();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private Recovering destination;
    @Autowired private DeadLetterReplayer replayer;
    @Autowired private ObjectMapper objectMapper;

    private String send() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String body = """
                {"events":[{"eventId":"%s","event":"product_viewed","schemaVersion":"2.0","source":"browser",
                 "timestamp":"%s","identity":{"anonymousId":"anon_1","sessionId":"s1"},"context":{},
                 "data":{"product":{"id":"p1"}}}]}""".formatted(eventId, Instant.now());
        mockMvc.perform(post("/v1/events").header("X-Omnirec-Key", "pk_test_demo_store")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        return eventId;
    }

    @Test
    void deadLetteredEventsAreDeliveredAfterTheDestinationRecovers() throws Exception {
        Set<String> sent = Set.of(send(), send(), send());
        await().atMost(Duration.ofSeconds(20)).until(() -> replayer.count("replay-target") == 3);
        assertTrue(destination.delivered.isEmpty());

        // Dry run: counts, moves nothing. Also over HTTP.
        DeadLetterReplayer.ReplayResult dry = replayer.replay("replay-target", 100, true);
        assertEquals(3, dry.waiting());
        assertEquals(0, dry.replayed());
        String counts = mockMvc.perform(get("/actuator/deadletters")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(3, objectMapper.readTree(counts).path("replay-target").asLong());

        destination.down.set(false);
        assertEquals(2, replayer.replay("replay-target", 2, false).replayed());
        String rest = mockMvc.perform(post("/actuator/deadletters/replay-target")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dryRun\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode result = objectMapper.readTree(rest);
        assertEquals(1, result.path("replayed").asLong());

        await().atMost(Duration.ofSeconds(20)).until(() -> destination.delivered.containsAll(sent));
        assertEquals(0, replayer.count("replay-target"));
    }

    @Test
    void unknownDestinationsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> replayer.replay("nope", 1, true));
    }
}
