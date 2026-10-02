// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.identity.IdentityLinkStore;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Definition of Done, exercised end to end:
 *
 * <pre>
 *   HTTP POST (what @omnirec/commerce-web sends)
 *        -> API key authentication
 *        -> normalization
 *        -> validation
 *        -> deduplication
 *        -> identity resolution
 *        -> publisher
 *        -> EventDestination
 * </pre>
 *
 * The destination is a recording fake rather than Amazon or Google: the mapping
 * to each provider is covered exhaustively by that provider's own mapper tests,
 * and what this test is for is the <em>pipeline</em> — that a real HTTP request
 * with a real API key comes out the far end as a canonical event with the right
 * identity attached.
 *
 * {@code queue-enabled=false} swaps RabbitMQ for inline dispatch so this runs
 * without a broker. The queue's own behaviour — retry, dead-lettering,
 * at-least-once redelivery — is covered by DispatchAndRetryTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.tenants.other-store.api-key=pk_test_other_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false"
})
class EndToEndPipelineTest {

    /** Stands in for a provider adapter and records everything that reaches it. */
    static class RecordingDestination implements EventDestination {
        final List<CommerceEvent> received = new ArrayList<>();

        @Override public String id() { return "recording"; }

        @Override
        public void send(CommerceEvent event) {
            received.add(event);
        }
    }

    @TestConfiguration
    static class TestDestinations {
        @Bean
        RecordingDestination recordingDestination() {
            return new RecordingDestination();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingDestination destination;

    @Autowired
    private IdentityLinkStore identityLinkStore;

    @Autowired
    private ObjectMapper objectMapper;

    private String idPrefix;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        destination.received.clear();
        // Deduplication is process-wide and the Spring context is shared across
        // these tests, so reusing a literal eventId between methods would make
        // the second one a legitimate duplicate. Scope ids per test instead.
        idPrefix = testInfo.getTestMethod().map(java.lang.reflect.Method::getName).orElse("t") + "_";
    }

    /** Test-scoped event id, so one test method cannot deduplicate another. */
    private String id(String suffix) {
        return idPrefix + suffix;
    }

    private Map<String, Object> event(String eventId, String eventType, Map<String, Object> identity, Map<String, Object> commerce) {
        return Map.of(
                "eventId", eventId,
                "eventType", eventType,
                "schemaVersion", "1.0",
                "timestamp", "2026-01-01T12:00:00.000Z",
                "identity", identity,
                "context", Map.of("platform", "web", "url", "https://shop.example/p/1"),
                "commerce", commerce,
                "properties", Map.of());
    }

    /**
     * Test-scoped anonymous id. Identity links persist in the shared in-memory
     * store, so a link one test creates would otherwise resolve in the next —
     * correct behaviour, wrong test isolation.
     */
    private String anon(String name) {
        return idPrefix + name;
    }

    private Map<String, Object> anonymous(String anonymousId, String sessionId) {
        Map<String, Object> identity = new java.util.HashMap<>();
        identity.put("anonymousId", anon(anonymousId));
        identity.put("sessionId", sessionId);
        identity.put("userId", null);
        return identity;
    }

    private Map<String, Object> authenticated(String anonymousId, String userId, String sessionId) {
        return Map.of("anonymousId", anon(anonymousId), "userId", userId, "sessionId", sessionId);
    }

    private org.springframework.test.web.servlet.ResultActions postEvents(String apiKey, Map<String, Object>... events) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("events", List.of(events)));
        var request = post("/v1/events/batch").contentType(MediaType.APPLICATION_JSON).content(body);
        if (apiKey != null) {
            request = request.header("X-Omnirec-Key", apiKey);
        }
        return mockMvc.perform(request);
    }

    @Nested
    @DisplayName("authentication and tenant isolation")
    class Authentication {

        @Test
        void rejectsARequestWithNoApiKey() throws Exception {
            postEvents(null, event(id("1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of("productId", "p1")))
                    .andExpect(status().isUnauthorized());

            assertTrue(destination.received.isEmpty());
        }

        @Test
        void rejectsAnUnknownApiKey() throws Exception {
            postEvents("pk_not_a_real_key", event(id("1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of("productId", "p1")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void acceptsAValidPublishableKey() throws Exception {
            postEvents("pk_test_demo_store", event(id("1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of("productId", "p1")))
                    .andExpect(status().isAccepted());
        }

        /**
         * The key decides the tenant, not the request body — otherwise one
         * merchant's publishable key could write into another's stream.
         */
        @Test
        void derivesTheTenantFromTheKeyRatherThanTheBody() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "tenantId", "other-store",
                    "events", List.of(event(id("1"), "product_viewed",
                            anonymous("anon_A", "session_1"), Map.of("productId", "p1")))));

            mockMvc.perform(post("/v1/events/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Omnirec-Key", "pk_test_demo_store")
                            .content(body))
                    .andExpect(status().isAccepted());

            assertEquals("demo-store", destination.received.get(0).tenantId(),
                    "a spoofed tenantId in the body must be ignored");
        }
    }

    @Nested
    @DisplayName("the full path a product view takes")
    class ProductView {

        @Test
        void arrivesAtTheDestinationAsACanonicalEvent() throws Exception {
            postEvents("pk_test_demo_store", event(id("1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of("productId", "p123")))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.accepted").value(1));

            assertEquals(1, destination.received.size());
            CommerceEvent delivered = destination.received.get(0);
            assertEquals(StandardEventNames.PRODUCT_VIEWED, delivered.eventType());
            assertEquals("p123", delivered.commerce().productId());
            assertEquals(anon("anon_A"), delivered.identity().anonymousId());
            assertEquals("session_1", delivered.identity().sessionId());
            assertNull(delivered.identity().userId());
        }

        @Test
        void isStampedWithAServerReceiveTime() throws Exception {
            postEvents("pk_test_demo_store", event(id("1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of("productId", "p1")));

            assertNotNull(destination.received.get(0).receivedAt());
        }

        @Test
        void carriesTheWholeBatchThrough() throws Exception {
            postEvents("pk_test_demo_store",
                    event(id("1"), "product_viewed", anonymous("anon_A", "session_1"), Map.of("productId", "p1")),
                    event(id("2"), "product_clicked", anonymous("anon_A", "session_1"), Map.of("productId", "p2")),
                    event(id("3"), "cart_viewed", anonymous("anon_A", "session_1"), Map.of("cartId", "c1")))
                    .andExpect(jsonPath("$.accepted").value(3));

            assertEquals(3, destination.received.size());
        }
    }

    @Nested
    @DisplayName("validation at the boundary")
    class Validation {

        @Test
        void rejectsAnEventMissingItsRequiredField() throws Exception {
            postEvents("pk_test_demo_store", event(id("1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.rejected").value(1));

            assertTrue(destination.received.isEmpty());
        }

        @Test
        void keepsTheGoodEventsWhenOneInABatchIsBad() throws Exception {
            postEvents("pk_test_demo_store",
                    event(id("1"), "product_viewed", anonymous("anon_A", "session_1"), Map.of("productId", "p1")),
                    event(id("bad"), "product_viewed", anonymous("anon_A", "session_1"), Map.of()),
                    event(id("3"), "product_viewed", anonymous("anon_A", "session_1"), Map.of("productId", "p3")))
                    .andExpect(jsonPath("$.accepted").value(2))
                    .andExpect(jsonPath("$.rejected").value(1));

            assertEquals(2, destination.received.size());
        }

        @Test
        void refusesPaymentCredentialsOutright() throws Exception {
            Map<String, Object> unsafe = Map.of(
                    "eventId", id("unsafe"),
                    "eventType", "payment_information_added",
                    "schemaVersion", "1.0",
                    "timestamp", "2026-01-01T12:00:00.000Z",
                    "identity", anonymous("anon_A", "session_1"),
                    "context", Map.of("platform", "web"),
                    "commerce", Map.of("cartId", "cart_1"),
                    "properties", Map.of("paymentMethod", "card", "cardNumber", "4111111111111111"));

            postEvents("pk_test_demo_store", unsafe)
                    .andExpect(jsonPath("$.rejected").value(1));

            assertTrue(destination.received.isEmpty(), "a card number must never reach a provider");
        }

        @Test
        void safePaymentMetadataIsAccepted() throws Exception {
            Map<String, Object> safe = Map.of(
                    "eventId", id("safe"),
                    "eventType", "payment_information_added",
                    "schemaVersion", "1.0",
                    "timestamp", "2026-01-01T12:00:00.000Z",
                    "identity", anonymous("anon_A", "session_1"),
                    "context", Map.of("platform", "web"),
                    "commerce", Map.of("cartId", "cart_1"),
                    "properties", Map.of("paymentMethod", "card"));

            postEvents("pk_test_demo_store", safe)
                    .andExpect(jsonPath("$.accepted").value(1));

            assertEquals(1, destination.received.size());
        }
    }

    @Nested
    @DisplayName("deduplication across requests")
    class Deduplication {

        @Test
        void deliversTheSameEventIdOnlyOnce() throws Exception {
            Map<String, Object> purchase = event(id("purchase_1"), "purchase_completed",
                    authenticated("anon_A", "customer_123", "session_1"),
                    Map.of("orderId", "order_1",
                            "items", List.of(Map.of("productId", "p1", "quantity", 1, "price", 10.00)),
                            "total", 10.00,
                            "currency", "USD"));

            postEvents("pk_test_demo_store", purchase).andExpect(jsonPath("$.accepted").value(1));
            postEvents("pk_test_demo_store", purchase).andExpect(jsonPath("$.duplicates").value(1));

            assertEquals(1, destination.received.size(),
                    "a retried purchase must not be counted twice");
        }
    }

    @Nested
    @DisplayName("anonymous to registered identity stitching")
    class IdentityStitching {

        /**
         * The journey from the spec: two anonymous days, then a login, and the
         * earlier behaviour becomes attributable without any of it being rewritten.
         */
        @Test
        void linksDaysOfAnonymousBrowsingToTheUserWhoEventuallyLogsIn() throws Exception {
            // Day 1 — anonymous, session_1.
            postEvents("pk_test_demo_store", event(id("day1"), "product_viewed",
                    anonymous("anon_A", "session_1"), Map.of("productId", "p1")));

            // Day 2 — same device, new session, still anonymous.
            postEvents("pk_test_demo_store", event(id("day2"), "product_viewed",
                    anonymous("anon_A", "session_2"), Map.of("productId", "p2")));

            CommerceEvent day1 = destination.received.get(0);
            CommerceEvent day2 = destination.received.get(1);
            assertNull(day1.identity().userId());
            assertNull(day2.identity().userId());
            assertEquals(anon("anon_A"), day1.identity().anonymousId());
            assertNotEquals(day1.identity().sessionId(), day2.identity().sessionId());

            // Day 3 — the visitor logs in.
            postEvents("pk_test_demo_store", event(id("identify"), "identify",
                    authenticated("anon_A", "customer_123", "session_3"), Map.of()))
                    .andExpect(jsonPath("$.accepted").value(1));

            // The identify event itself is absorbed, not forwarded.
            assertEquals(2, destination.received.size(),
                    "identify is a control event and carries no behavioural signal");

            // The link now exists, which is what makes the earlier events attributable.
            assertEquals(Optional.of("customer_123"),
                    identityLinkStore.resolveUserId("demo-store", anon("anon_A")));

            // History was not rewritten — the delivered events keep their original identity.
            assertNull(destination.received.get(0).identity().userId());

            // And everything after the link carries the userId automatically.
            postEvents("pk_test_demo_store", event(id("day3"), "product_viewed",
                    anonymous("anon_A", "session_3"), Map.of("productId", "p3")));

            CommerceEvent afterLogin = destination.received.get(2);
            assertEquals("customer_123", afterLogin.identity().userId());
            assertEquals(anon("anon_A"), afterLogin.identity().anonymousId());
        }

        @Test
        void linksTwoDevicesToTheSameShopper() throws Exception {
            postEvents("pk_test_demo_store", event(id("id_laptop"), "identify",
                    authenticated("anon_laptop", "customer_456", "session_l"), Map.of()));
            postEvents("pk_test_demo_store", event(id("id_phone"), "identify",
                    authenticated("anon_phone", "customer_456", "session_p"), Map.of()));

            List<String> anonymousIds = identityLinkStore.anonymousIdsFor("demo-store", "customer_456");

            assertEquals(2, anonymousIds.size());
            assertTrue(anonymousIds.containsAll(List.of(anon("anon_laptop"), anon("anon_phone"))));
        }

        @Test
        void identifyEndpointLinksWithoutAFullEvent() throws Exception {
            mockMvc.perform(post("/v1/identify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Omnirec-Key", "pk_test_demo_store")
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "anonymousId", anon("anon_server"),
                                    "userId", "customer_789"))))
                    .andExpect(status().isAccepted());

            assertEquals(Optional.of("customer_789"),
                    identityLinkStore.resolveUserId("demo-store", anon("anon_server")));
        }

        @Test
        void identifyEndpointRequiresBothIds() throws Exception {
            mockMvc.perform(post("/v1/identify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Omnirec-Key", "pk_test_demo_store")
                            .content(objectMapper.writeValueAsString(Map.of("userId", "customer_789"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("beacon support")
    class Beacon {

        /**
         * navigator.sendBeacon cannot set a Content-Type or headers, so the
         * unload path arrives as text/plain with the key in the query string.
         * Without this the last events of every visit would be lost.
         */
        @Test
        void acceptsATextPlainBeaconWithTheKeyAsAQueryParameter() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "events", List.of(event(id("beacon"), "product_viewed",
                            anonymous("anon_A", "session_1"), Map.of("productId", "p1")))));

            mockMvc.perform(post("/v1/events/batch")
                            .param("api_key", "pk_test_demo_store")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(body))
                    .andExpect(status().isAccepted());

            assertEquals(1, destination.received.size());
        }
    }
}
