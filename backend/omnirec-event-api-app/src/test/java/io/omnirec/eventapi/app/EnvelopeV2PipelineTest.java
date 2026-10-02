// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventSource;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Envelope v2 through the whole HTTP pipeline, and v1 next to it: both must
 * arrive at a destination as the same canonical v2 event.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
class EnvelopeV2PipelineTest {

    static class RecordingDestination implements EventDestination {
        final List<CommerceEvent> received = new ArrayList<>();

        @Override public String id() { return "recording-v2"; }

        @Override
        public void send(CommerceEvent event) {
            received.add(event);
        }
    }

    @TestConfiguration
    static class TestDestinations {
        @Bean
        RecordingDestination recordingV2Destination() {
            return new RecordingDestination();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RecordingDestination destination;
    @Autowired private ObjectMapper objectMapper;

    private String prefix;

    @BeforeEach
    void setUp(TestInfo info) {
        destination.received.clear();
        prefix = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("t") + "_";
    }

    private Map<String, Object> identity() {
        return Map.of("anonymousId", prefix + "anon", "userId", "customer_9", "sessionId", "s1");
    }

    private Map<String, Object> v2(String eventId, String event, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", prefix + eventId);
        body.put("event", event);
        body.put("schemaVersion", "2.0");
        body.put("source", "server");
        body.put("timestamp", "2026-01-01T12:00:00.000Z");
        body.put("identity", identity());
        body.put("context", Map.of("platform", "server"));
        body.put("data", data);
        body.put("properties", Map.of());
        return body;
    }

    private Map<String, Object> v1(String eventId, String eventType, Map<String, Object> commerce) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", prefix + eventId);
        body.put("eventType", eventType);
        body.put("schemaVersion", "1.0");
        body.put("timestamp", "2026-01-01T12:00:00.000Z");
        body.put("identity", identity());
        body.put("context", Map.of("platform", "web"));
        body.put("commerce", commerce);
        body.put("properties", Map.of());
        return body;
    }

    private ResultActions post(Object... events) throws Exception {
        // Raw JSON so 2400.00 reaches the server with its scale, as an SDK would send it.
        String body = objectMapper.writeValueAsString(Map.of("events", List.of(events)))
                .replace("\"2400.00\"", "2400.00").replace("\"1200.00\"", "1200.00");
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON)
                .header("X-Omnirec-Key", "pk_test_demo_store").content(body));
    }

    private CommerceEvent delivered(String eventId) {
        Optional<CommerceEvent> event = destination.received.stream()
                .filter(e -> e.eventId().equals(prefix + eventId)).findFirst();
        assertTrue(event.isPresent(), eventId + " was not delivered");
        return event.get();
    }

    private static Map<String, Object> purchaseData() {
        return Map.of("order", Map.of(
                "id", "o_77",
                "total", "2400.00",
                "currency", "USD",
                "items", List.of(Map.of("productId", "p123", "quantity", 2, "price", "1200.00"))));
    }

    @Test
    void deliversAV2EventWithItsBlocksAndExactMoney() throws Exception {
        post(v2("1", "purchase_completed", purchaseData()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        CommerceEvent event = delivered("1");
        assertEquals(StandardEventNames.PURCHASE_COMPLETED, event.eventType());
        assertEquals(CommerceEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion());
        assertEquals(EventSource.SERVER, event.source());
        assertEquals(CommerceEvent.KIND_STANDARD, event.kind());
        assertEquals(1, event.eventVersion());
        assertEquals("o_77", event.data().order().id());
        assertEquals(new BigDecimal("2400.00"), event.data().order().total(), "money keeps its scale");
        assertEquals("p123", event.data().order().items().get(0).productId());
        assertEquals("demo-store", event.tenantId());
    }

    @Test
    void v1AndV2FormsOfTheSamePurchaseArriveIdentical() throws Exception {
        post(v2("v2", "purchase_completed", purchaseData()),
                v1("v1", "purchase_completed", Map.of(
                        "orderId", "o_77", "total", "2400.00", "currency", "USD",
                        "items", List.of(Map.of("productId", "p123", "quantity", 2, "price", "1200.00")))))
                .andExpect(jsonPath("$.accepted").value(2));

        assertEquals(delivered("v2").data(), delivered("v1").data());
        assertEquals(EventSource.BROWSER, delivered("v1").source(), "v1 web events are inferred as browser");
        assertEquals(CommerceEvent.CURRENT_SCHEMA_VERSION, delivered("v1").schemaVersion(),
                "everything past the collector is v2");
    }

    @Test
    void namesTheMissingV2FieldWhenRejecting() throws Exception {
        post(v2("1", "product_viewed", Map.of("product", Map.of("name", "No id"))))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.errors[0].reason").value(org.hamcrest.Matchers.containsString("data.product.id")));
        assertTrue(destination.received.isEmpty());
    }

    @Test
    void ignoresServerOwnedFieldsSentByTheClient() throws Exception {
        Map<String, Object> event = v2("1", "product_viewed", Map.of("product", Map.of("id", "p1")));
        event.put("kind", "custom");
        event.put("unplanned", true);
        event.put("tenantId", "someone-else");
        post(event).andExpect(jsonPath("$.accepted").value(1));

        CommerceEvent received = delivered("1");
        assertEquals(CommerceEvent.KIND_STANDARD, received.kind());
        assertFalse(received.isUnplanned());
        assertEquals("demo-store", received.tenantId());
    }

    @Test
    void acceptsAndFlagsAnUnknownEventButKeepsItAwayFromDestinations() throws Exception {
        // Permissive is the default validation mode: the event is accepted and
        // stored, but destinations other than storage never see it.
        post(v2("1", "action_x_clicked", Map.of("variant", "a")))
                .andExpect(jsonPath("$.accepted").value(1));
        assertTrue(destination.received.stream().noneMatch(e -> e.eventId().equals(prefix + "1")));
    }
}
