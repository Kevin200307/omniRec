// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventData;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OmnirecClientTest {

    private HttpServer server;
    private String endpoint;
    private final List<JsonNode> bodies = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final Deque<Integer> statuses = new ArrayDeque<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events/batch", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int status = statuses.isEmpty() ? 202 : statuses.poll();
            synchronized (bodies) {
                if (status < 300) {
                    bodies.add(json.readTree(body));
                    keys.add(exchange.getRequestHeaders().getFirst("X-Omnirec-Key"));
                }
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OmnirecClient client(String apiKey) {
        return OmnirecClient.builder().endpoint(endpoint).apiKey(apiKey).synchronous().build();
    }

    private JsonNode onlyEvent() {
        assertEquals(1, bodies.size());
        return bodies.get(0).path("events").get(0);
    }

    @Test
    void sendsAV2ServerEvent() {
        client(null).track(StandardEvents.PURCHASE_COMPLETED,
                Map.of("order", Map.of("id", "o1", "total", "24.00", "currency", "USD",
                        "items", List.of(Map.of("productId", "P1", "quantity", 2)))),
                new ServerIdentity("anon_A", "c_1", "s1"), Map.of("channel", "web"), "o1");

        JsonNode event = onlyEvent();
        assertEquals("purchase_completed", event.path("event").asText());
        assertEquals("evt:purchase_completed:o1", event.path("eventId").asText());
        assertEquals("2.0", event.path("schemaVersion").asText());
        assertEquals("server", event.path("source").asText());
        assertEquals("24.00", event.path("data").path("order").path("total").asText());
        assertEquals("anon_A", event.path("identity").path("anonymousId").asText());
        assertEquals("s1", event.path("identity").path("sessionId").asText());
        assertEquals("web", event.path("properties").path("channel").asText());
        assertNull(keys.get(0), "no key header without an apiKey");
    }

    @Test
    void sendsTheKeyWhenConfigured() {
        client("sk_server").track(StandardEvents.PAGE_VIEWED, Map.of(), ServerIdentity.ofUser("c_1"));
        assertEquals("sk_server", keys.get(0));
    }

    @Test
    void acceptsAnAnonymousVisitorWithoutAUserId() {
        client(null).track(StandardEvents.PRODUCT_ADDED_TO_CART,
                Map.of("product", Map.of("id", "P1", "quantity", 1)), new ServerIdentity("anon_A", null, null));
        JsonNode identity = onlyEvent().path("identity");
        assertEquals("anon_A", identity.path("anonymousId").asText());
        assertTrue(identity.path("userId").isNull() || identity.path("userId").isMissingNode());
        assertTrue(identity.path("sessionId").asText().startsWith("server:"));
    }

    @Test
    void refusesAnEventWithNoIdentityAtAll() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client(null).track(StandardEvents.PAGE_VIEWED, Map.of(), new ServerIdentity(null, null, null)));
        assertTrue(error.getMessage().contains("omnirec_anonymous_id"));
    }

    @Test
    void throwsAtTheCallSiteForAnInvalidStandardEvent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client(null).track(StandardEvents.ORDER_CANCELLED, Map.of(), ServerIdentity.ofUser("c_1")));
        assertTrue(error.getMessage().contains("data.order.id"));
        assertTrue(bodies.isEmpty());
    }

    @Test
    void letsCustomEventsThroughForTheCollectorToCheckAgainstThePlan() {
        client(null).track("loyalty_points_redeemed", Map.of("points", 100), ServerIdentity.ofUser("c_1"));
        assertEquals("loyalty_points_redeemed", onlyEvent().path("event").asText());
    }

    @Test
    void retriesATransientFailure() {
        statuses.add(503);
        statuses.add(503);
        OmnirecClient client = OmnirecClient.builder().endpoint(endpoint).synchronous()
                .sender(new JdkHttpEventSender(JdkHttpEventSender.Options.defaults(endpoint, null).synchronous()
                        .withRetries(3, Duration.ofMillis(1))))
                .build();
        client.track(StandardEvents.PAGE_VIEWED, Map.of(), ServerIdentity.ofUser("c_1"));
        assertEquals(1, bodies.size());
    }

    @Test
    void reportsEachOutcomeOfASingleAttempt() {
        JdkHttpEventSender sender = new JdkHttpEventSender(JdkHttpEventSender.Options.defaults(endpoint, null).synchronous());
        CommerceEvent event = CommerceEvent.builder().eventId("e1").eventType(StandardEvents.PAGE_VIEWED)
                .data(EventData.empty()).build();
        assertEquals(BatchDelivery.Outcome.DELIVERED, sender.deliverOnce(List.of(event)));
        statuses.add(400);
        assertEquals(BatchDelivery.Outcome.REJECTED, sender.deliverOnce(List.of(event)));
        statuses.add(503);
        assertEquals(BatchDelivery.Outcome.RETRYABLE, sender.deliverOnce(List.of(event)));
        server.stop(0);
        assertEquals(BatchDelivery.Outcome.RETRYABLE, sender.deliverOnce(List.of(event)));
    }

    @Test
    void sendsQueuedEventsOnCloseWhenAsync() throws Exception {
        OmnirecClient client = OmnirecClient.builder().endpoint(endpoint).build();
        for (int i = 0; i < 3; i++) client.track(StandardEvents.PAGE_VIEWED, Map.of(), ServerIdentity.ofUser("c_1"));
        client.close();
        int total = bodies.stream().mapToInt(b -> b.path("events").size()).sum();
        assertEquals(3, total);
    }

    @Test
    void refusesARelativeEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> OmnirecClient.builder().endpoint("/omnirec").build());
    }
}
