// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lite profile starts with no RabbitMQ, Redis or database anywhere, takes
 * a keyless event, and delivers it to a destination.
 */
@SpringBootTest(properties = {
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "lite"})
class LiteProfileTest {

    static class Recording implements EventDestination {
        final List<CommerceEvent> received = new CopyOnWriteArrayList<>();

        @Override public String id() { return "lite-recording"; }

        @Override public void send(CommerceEvent event) { received.add(event); }
    }

    @TestConfiguration
    static class Destinations {
        @Bean
        Recording liteRecording() {
            return new Recording();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private Recording destination;
    @Autowired private ApplicationContext context;

    @Test
    void startsWithoutInfrastructureAndDeliversAKeylessEvent() throws Exception {
        assertEquals(0, context.getBeanNamesForType(
                org.springframework.amqp.rabbit.connection.ConnectionFactory.class).length, "no broker connection");

        String body = """
                {"events":[{"eventId":"evt_lite_1","event":"product_viewed","schemaVersion":"2.0","source":"browser",
                 "timestamp":"%s","identity":{"anonymousId":"anon_1","sessionId":"s1"},"context":{},
                 "data":{"product":{"id":"p1"}}}]}""".formatted(Instant.now());
        mockMvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        assertEquals(1, destination.received.size());
        assertEquals("default", destination.received.get(0).tenantId());

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
