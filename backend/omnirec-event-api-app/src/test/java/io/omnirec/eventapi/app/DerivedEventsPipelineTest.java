// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Derived events through the running service: events posted over HTTP, rules
 * fed by the pipeline, derived events delivered to destinations. The abandoned
 * cart timeout is shortened to a second.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false",
        "omnirec.derived.enabled=true",
        "omnirec.derived.cart-abandoned.timeout=PT1S",
        "omnirec.derived.poll-interval=PT0.2S"
})
class DerivedEventsPipelineTest {

    static class RecordingDestination implements EventDestination {
        final List<CommerceEvent> received = new CopyOnWriteArrayList<>();

        @Override public String id() { return "recording-derived"; }

        @Override public void send(CommerceEvent event) { received.add(event); }
    }

    @TestConfiguration
    static class TestDestinations {
        @Bean
        RecordingDestination recordingDerivedDestination() {
            return new RecordingDestination();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RecordingDestination destination;

    private void send(String event, String anonymousId, String userId, String data) throws Exception {
        String body = """
                {"events":[{"eventId":"%s","event":"%s","schemaVersion":"2.0","source":"browser","timestamp":"%s",
                 "identity":{"anonymousId":"%s","userId":%s,"sessionId":"s_%s"},"context":{},"data":%s}]}
                """.formatted(UUID.randomUUID(), event, Instant.now(), anonymousId,
                userId == null ? "null" : "\"" + userId + "\"", anonymousId, data);
        mockMvc.perform(post("/v1/events").header("X-Omnirec-Key", "pk_test_demo_store")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    private List<CommerceEvent> derived(String name, String anonymousId) {
        return destination.received.stream()
                .filter(e -> e.eventType().wireName().equals(name) && anonymousId.equals(e.identity().anonymousId()))
                .toList();
    }

    @Test
    void anAbandonedCartProducesCartAbandonedAfterTheTimeout() throws Exception {
        String visitor = "anon_" + UUID.randomUUID();
        send("add_to_cart", visitor, null, "{\"product\":{\"id\":\"p1\",\"quantity\":1},\"cart\":{\"id\":\"cart_1\"}}");

        long deadline = System.currentTimeMillis() + 10_000;
        while (derived("cart_abandoned", visitor).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        List<CommerceEvent> abandoned = derived("cart_abandoned", visitor);
        assertEquals(1, abandoned.size());
        assertEquals("cart_1", abandoned.get(0).data().cart().id());
        assertEquals("demo-store", abandoned.get(0).tenantId());
        assertEquals(EventSource.DERIVED, abandoned.get(0).source());
    }

    @Test
    void aCheckoutStopsTheAbandonment() throws Exception {
        String visitor = "anon_" + UUID.randomUUID();
        send("product_added_to_cart", visitor, null, "{\"product\":{\"id\":\"p1\",\"quantity\":1},\"cart\":{\"id\":\"c2\"}}");
        send("checkout_started", visitor, null, "{\"cart\":{\"id\":\"c2\"}}");
        Thread.sleep(2_000);
        assertEquals(List.of(), derived("cart_abandoned", visitor));
    }

    @Test
    void purchasesAreClassifiedAsNewOrRepeat() throws Exception {
        String visitor = "anon_" + UUID.randomUUID();
        String user = "user_" + UUID.randomUUID();
        String order = "{\"order\":{\"id\":\"%s\",\"total\":10.00,\"currency\":\"USD\",\"items\":[{\"productId\":\"p1\",\"quantity\":1}]}}";
        send("purchase_completed", visitor, user, order.formatted("o_" + UUID.randomUUID()));
        send("purchase_completed", visitor, user, order.formatted("o_" + UUID.randomUUID()));

        assertEquals(1, derived("new_customer_purchase", visitor).size());
        assertEquals(1, derived("repeat_purchase", visitor).size());
    }
}
