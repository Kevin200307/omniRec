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
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two stores on one collector, each with its own key, plan and validation mode.
 * Neither can see or send the other's custom events.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.events.tenants.store-a.api-key=pk_store_a",
        "omnirec.events.tenants.store-a.plan-paths=classpath:plans/store-a.plan.yaml",
        "omnirec.events.tenants.store-a.allowed-origins=https://a.example",
        "omnirec.events.tenants.store-b.api-key=pk_store_b",
        "omnirec.events.tenants.store-b.plan-paths=classpath:plans/store-b.plan.yaml",
        "omnirec.events.tenants.store-b.validation-mode=strict",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
class MultiTenantTest {

    static class Recording implements EventDestination {
        final List<CommerceEvent> received = new ArrayList<>();
        @Override public String id() { return "recording-tenants"; }
        @Override public void send(CommerceEvent event) { received.add(event); }
    }

    @TestConfiguration
    static class Config {
        @Bean Recording recordingTenants() { return new Recording(); }
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

    private ResultActions send(String key, String id, String eventName, Map<String, Object> data) throws Exception {
        Map<String, Object> event = Map.of(
                "eventId", prefix + id,
                "event", eventName,
                "schemaVersion", "2.0",
                "timestamp", "2026-01-01T12:00:00Z",
                "identity", Map.of("anonymousId", prefix + "anon", "sessionId", "s1"),
                "data", data);
        var request = post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("events", List.of(event))));
        if (key != null) request = request.header("X-Omnirec-Key", key);
        return mockMvc.perform(request);
    }

    @Test
    void requiresAKeyOnceTenantsHaveKeys() throws Exception {
        send(null, "1", "page_viewed", Map.of()).andExpect(status().isUnauthorized());
    }

    @Test
    void eachTenantSendsItsOwnPlannedEvents() throws Exception {
        send("pk_store_a", "a", "action_x_clicked", Map.of("variant", "a")).andExpect(jsonPath("$.accepted").value(1));
        send("pk_store_b", "b", "newsletter_popup_closed", Map.of("subscribed", true)).andExpect(jsonPath("$.accepted").value(1));

        assertEquals(List.of("store-a", "store-b"), destination.received.stream().map(CommerceEvent::tenantId).toList());
        assertTrue(destination.received.stream().noneMatch(CommerceEvent::isUnplanned));
    }

    @Test
    void aPermissiveTenantAcceptsAnotherTenantsEventOnlyAsUnplanned() throws Exception {
        send("pk_store_a", "1", "newsletter_popup_closed", Map.of("subscribed", true))
                .andExpect(jsonPath("$.accepted").value(1));
        assertTrue(destination.received.isEmpty(), "unplanned events never reach provider destinations");
    }

    @Test
    void aStrictTenantRejectsAnotherTenantsEvent() throws Exception {
        send("pk_store_b", "1", "action_x_clicked", Map.of("variant", "a"))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.errors[0].reason").value(org.hamcrest.Matchers.containsString("unknown event type")));
    }

    @Test
    void enforcesATenantsOriginListInKeysMode() throws Exception {
        mockMvc.perform(post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_store_a").header("Origin", "https://b.example")
                        .content("{\"events\":[]}"))
                .andExpect(status().isForbidden());
        // store-b lists no origins, so the filter does not restrict it to a list of
        // its own. (Tenant origins also feed the collector-wide CORS allowlist, so a
        // browser origin must still appear somewhere in configuration.)
        mockMvc.perform(post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_store_b").header("Origin", "https://a.example")
                        .content("{\"events\":[]}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void theCatalogShowsOnlyTheCallersPlan() throws Exception {
        mockMvc.perform(get("/v1/catalog").header("X-Omnirec-Key", "pk_store_b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("store-b"))
                .andExpect(jsonPath("$.validationMode").value("strict"))
                .andExpect(jsonPath("$.events[?(@.name == 'newsletter_popup_closed')]").isNotEmpty())
                .andExpect(jsonPath("$.events[?(@.name == 'action_x_clicked')]").isEmpty());
    }
}
