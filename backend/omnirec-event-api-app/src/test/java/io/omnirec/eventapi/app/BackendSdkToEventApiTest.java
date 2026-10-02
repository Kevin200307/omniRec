// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.Platform;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.tracker.CommerceTracker;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.config.CommerceTrackerProperties;
import io.omnirec.tracker.trackers.PurchaseTracker.PurchaseCompleted;
import io.omnirec.tracker.transport.HttpEventSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2 from the audit: the backend SDK's path into the Event API had never been
 * exercised. This runs the real SDK ({@link HttpEventSender} over a real
 * RestTemplate) against the real API on a random port — serialisation,
 * authentication, validation, deduplication and all — and asserts what reaches
 * the destination.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false",
        // The SDK is constructed by hand below, pointed at this server's port.
        "omnirec.tracker.enabled=false"
})
@ActiveProfiles("test")
class BackendSdkToEventApiTest {

    static class RecordingDestination implements EventDestination {
        final List<CommerceEvent> received = new CopyOnWriteArrayList<>();

        @Override public String id() { return "recording"; }

        @Override
        public void send(CommerceEvent event) {
            received.add(event);
        }
    }

    @TestConfiguration
    static class Destinations {
        @Bean
        RecordingDestination recordingDestination() {
            return new RecordingDestination();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RecordingDestination destination;

    @Autowired
    private ObjectMapper objectMapper;

    private CommerceTracker commerce;

    @BeforeEach
    void setUp() {
        destination.received.clear();
        CommerceTrackerProperties properties = new CommerceTrackerProperties();
        properties.setEndpoint("http://localhost:" + port);
        properties.setApiKey("pk_test_demo_store");
        properties.setTenantId("demo-store");
        properties.setAsync(false);
        commerce = new CommerceTracker(new ServerEventEmitter(
                new HttpEventSender(new RestTemplate(), properties), new EventValidator(), "demo-store", true));
    }

    private PurchaseCompleted.Builder purchase(String orderId) {
        return PurchaseCompleted.builder()
                .orderId(orderId)
                .userId("customer_123")
                .anonymousId("anon_A")
                .items(List.of(CommerceItem.of("p1", 2, new BigDecimal("50.00"), "USD")))
                .total(new BigDecimal("100.00"))
                .currency("USD");
    }

    private List<CommerceEvent> receivedFor(String orderId) {
        return destination.received.stream()
                .filter(e -> orderId.equals(e.commerce().orderId()))
                .toList();
    }

    @Test
    void anAuthoritativePurchaseTravelsFromTheBackendSdkToTheDestination() {
        String orderId = "order_" + UUID.randomUUID();

        commerce.purchase.completed(purchase(orderId).build());

        List<CommerceEvent> received = receivedFor(orderId);
        assertEquals(1, received.size());
        CommerceEvent event = received.get(0);
        assertEquals(StandardEventNames.PURCHASE_COMPLETED, event.eventType());
        assertEquals("evt:purchase_completed:" + orderId, event.eventId());
        assertEquals(0, new BigDecimal("100.00").compareTo(event.commerce().total()));
        assertEquals("customer_123", event.identity().userId());
        assertEquals("anon_A", event.identity().anonymousId());
        assertEquals(Platform.SERVER, event.context().platform());
        assertEquals("demo-store", event.tenantId());
    }

    @Test
    void aRetriedPurchaseFromTheBackendIsDeliveredOnce() {
        String orderId = "order_" + UUID.randomUUID();

        commerce.purchase.completed(purchase(orderId).build());
        commerce.purchase.completed(purchase(orderId).build());

        assertEquals(1, receivedFor(orderId).size());
    }

    /**
     * B3: the same purchase reported by the browser SDK and the backend SDK.
     * Both derive evt:purchase_completed:&lt;orderId&gt;, so the pipeline
     * delivers it once instead of counting the revenue twice.
     */
    @Test
    void aPurchaseReportedByBothTheBrowserAndTheBackendIsDeliveredOnce() throws Exception {
        String orderId = "order_" + UUID.randomUUID();

        // Exactly what @omnirec/commerce-web sends for purchase.completed().
        Map<String, Object> browserEvent = Map.of(
                "eventId", "evt:purchase_completed:" + orderId,
                "eventType", "purchase_completed",
                "schemaVersion", "1.0",
                "timestamp", java.time.Instant.now().toString(),
                "identity", Map.of("anonymousId", "anon_A", "userId", "customer_123", "sessionId", "s1"),
                "context", Map.of("platform", "web"),
                "commerce", Map.of("orderId", orderId, "total", 100.00, "currency", "USD",
                        "items", List.of(Map.of("productId", "p1", "quantity", 2, "price", 50.00))),
                "properties", Map.of());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Omnirec-Key", "pk_test_demo_store");
        new RestTemplate().postForEntity("http://localhost:" + port + "/v1/events/batch",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("events", List.of(browserEvent))), headers),
                String.class);

        commerce.purchase.completed(purchase(orderId).build());

        assertEquals(1, receivedFor(orderId).size(), "the revenue must be counted once");
    }
}
