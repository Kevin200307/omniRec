// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A self-hosted install with no keys at all: auth-mode auto resolves to open,
 * events need no key, and browsers are limited to allowed origins.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.events.auth-mode=open",
        "omnirec.events.cors.allowed-origins=https://shop.example",
        "omnirec.events.default-plan-paths=classpath:plans/store-a.plan.yaml",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
class OpenModeTest {

    static class Recording implements EventDestination {
        final List<CommerceEvent> received = new ArrayList<>();
        @Override public String id() { return "recording-open"; }
        @Override public void send(CommerceEvent event) { received.add(event); }
    }

    @TestConfiguration
    static class Config {
        @Bean Recording recordingOpen() { return new Recording(); }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Recording destination;

    private String prefix;

    @BeforeEach
    void setUp(TestInfo info) {
        destination.received.clear();
        prefix = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("t") + "_";
    }

    private MockHttpServletRequestBuilder events(String eventName, Map<String, Object> data) throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", prefix + eventName,
                "event", eventName,
                "schemaVersion", "2.0",
                "timestamp", "2026-01-01T12:00:00Z",
                "identity", Map.of("anonymousId", prefix + "anon", "sessionId", "s1"),
                "context", Map.of("platform", "web"),
                "data", data);
        return post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("events", List.of(event))));
    }

    @Test
    void acceptsEventsWithoutAKeyIntoTheDefaultTenant() throws Exception {
        mockMvc.perform(events("product_viewed", Map.of("product", Map.of("id", "p1"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));
        assertEquals("default", destination.received.get(0).tenantId());
    }

    @Test
    void acceptsAnAllowedBrowserOriginAndTheCollectorsOwnOrigin() throws Exception {
        mockMvc.perform(events("product_viewed", Map.of("product", Map.of("id", "p1")))
                        .header("Origin", "https://shop.example"))
                .andExpect(status().isAccepted());
        mockMvc.perform(events("page_viewed", Map.of())
                        .header("Origin", "http://events.local:8124").header("Host", "events.local:8124"))
                .andExpect(status().isAccepted());
    }

    @Test
    void refusesAnyOtherBrowserOrigin() throws Exception {
        mockMvc.perform(events("product_viewed", Map.of("product", Map.of("id", "p1")))
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("origin not allowed"));
        assertTrue(destination.received.isEmpty());
    }

    @Test
    void stillRefusesAWrongKeyRatherThanReroutingIt() throws Exception {
        mockMvc.perform(events("product_viewed", Map.of("product", Map.of("id", "p1"))).header("X-Omnirec-Key", "pk_guess"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appliesTheDefaultPlanToKeylessTraffic() throws Exception {
        mockMvc.perform(events("action_x_clicked", Map.of("variant", "a")))
                .andExpect(jsonPath("$.accepted").value(1));
        CommerceEvent planned = destination.received.get(0);
        assertEquals(CommerceEvent.KIND_CUSTOM, planned.kind());
        assertFalse(planned.isUnplanned(), "a plan event is custom but planned");

        mockMvc.perform(events("action_x_clicked", Map.of("variant", "z")))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.errors[0].reason").value(org.hamcrest.Matchers.containsString("data.variant")));
    }

    @Test
    void servesTheCatalogWithoutAKey() throws Exception {
        mockMvc.perform(get("/v1/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("default"))
                .andExpect(jsonPath("$.validationMode").value("permissive"))
                .andExpect(jsonPath("$.vocabularies.variant[1]").value("b"))
                .andExpect(jsonPath("$.events[?(@.name == 'action_x_clicked')].kind").value("custom"))
                .andExpect(jsonPath("$.events[?(@.name == 'product_viewed')].required[0]").value("product.id"));
    }
}
