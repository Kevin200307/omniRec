// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Against a real HTTP server on localhost. */
class WebhookDestinationTest {

    static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    record Received(Map<String, List<String>> headers, byte[] body) {
    }

    private HttpServer server;
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final AtomicInteger status = new AtomicInteger(204);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    private WebhookDestinationProperties.Endpoint endpoint(String... events) {
        WebhookDestinationProperties.Endpoint endpoint = new WebhookDestinationProperties.Endpoint();
        endpoint.setUrl(url());
        endpoint.setSecret("hook_secret");
        endpoint.setEvents(List.of(events));
        endpoint.setTimeout(Duration.ofSeconds(5));
        return endpoint;
    }

    private WebhookDestination destination(WebhookDestinationProperties.Endpoint endpoint) {
        return new WebhookDestination("crm", endpoint, HttpClient.newHttpClient(), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CommerceEvent event(String name, String tenant) {
        return CommerceEvent.builder()
                .eventId("evt_" + name).eventType(name).eventVersion(1).kind(CommerceEvent.KIND_STANDARD)
                .schemaVersion("2.0").source(EventSource.SERVER).timestamp(NOW).receivedAt(NOW).tenantId(tenant)
                .identity(new EventIdentity("a1", "u1", "s1")).context(EventContext.server())
                .data(EventData.of(Map.of("order", Map.of("id", "o1", "total", new BigDecimal("24.00"), "currency", "USD"))))
                .properties(Map.of()).build();
    }

    @Test
    void postsSignedEventsTheReceiverCanVerify() throws Exception {
        destination(endpoint("*")).sendBatch(List.of(event("purchase_completed", "shop"), event("refund_issued", "shop")));

        Received request = received.poll();
        assertNotNull(request);
        String header = request.headers().get("X-omnirec-signature").get(0);
        // The receiver's side, written out: split t and v1, HMAC "t.body", compare.
        String t = header.split(",")[0].substring(2);
        String v1 = header.split(",")[1].substring(3);
        assertEquals(String.valueOf(NOW.getEpochSecond()), t);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("hook_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(request.body())), v1);

        JsonNode body = mapper.readTree(request.body());
        assertEquals(2, body.get("events").size());
        assertEquals("purchase_completed", body.at("/events/0/event").asText());
        assertTrue(new String(request.body(), StandardCharsets.UTF_8).contains("\"total\":24.00"), "money keeps its scale");
        assertEquals("2026-10-01T12:00:00Z", body.at("/events/0/timestamp").asText());
        assertNotNull(request.headers().get("X-omnirec-delivery"));
    }

    @Test
    void serverErrorsAndRateLimitsAreRetryable() {
        for (int code : new int[]{500, 503, 429, 408}) {
            status.set(code);
            DestinationException e = assertThrows(DestinationException.class,
                    () -> destination(endpoint("*")).send(event("purchase_completed", "shop")));
            assertTrue(e.isRetryable(), "status " + code);
            assertEquals("webhook-crm", e.getDestinationId());
        }
    }

    @Test
    void otherClientErrorsArePermanentSoTheyGoStraightToTheDeadLetterQueue() {
        status.set(400);
        DestinationException e = assertThrows(DestinationException.class,
                () -> destination(endpoint("*")).send(event("purchase_completed", "shop")));
        assertFalse(e.isRetryable());
    }

    @Test
    void anUnreachableReceiverIsRetryable() {
        WebhookDestination destination = destination(endpoint("*"));
        server.stop(0);
        DestinationException e = assertThrows(DestinationException.class,
                () -> destination.send(event("purchase_completed", "shop")));
        assertTrue(e.isRetryable());
    }

    @Test
    void filtersByEventNamePrefixAndTenant() {
        WebhookDestinationProperties.Endpoint endpoint = endpoint("purchase_completed", "cart_*");
        endpoint.setTenants(List.of("shop"));
        WebhookDestination destination = destination(endpoint);

        assertTrue(destination.supports(event("purchase_completed", "shop")));
        assertTrue(destination.supports(event("cart_abandoned", "shop")));
        assertFalse(destination.supports(event("page_viewed", "shop")));
        assertFalse(destination.supports(event("purchase_completed", "other-shop")));
        assertFalse(destination.supports(event("identify", "shop")), "control events never leave");
        assertFalse(destination.acceptsUnplanned());
    }

    @Test
    void refusesUnsafeOrIncompleteConfiguration() {
        WebhookDestinationProperties.Endpoint plainHttp = endpoint("*");
        plainHttp.setUrl("http://crm.example.com/hook");
        assertThrows(IllegalStateException.class, () -> destination(plainHttp));

        WebhookDestinationProperties.Endpoint noSecret = endpoint("*");
        noSecret.setSecret(" ");
        assertThrows(IllegalStateException.class, () -> destination(noSecret));

        assertThrows(IllegalStateException.class, () -> destination(endpoint()));
    }

    @Test
    void eachConfiguredEndpointBecomesItsOwnDestinationBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebhookDestinationAutoConfiguration.class))
                .withPropertyValues(
                        "omnirec.destinations.webhook.enabled=true",
                        "omnirec.destinations.webhook.endpoints.crm.url=" + url(),
                        "omnirec.destinations.webhook.endpoints.crm.secret=a",
                        "omnirec.destinations.webhook.endpoints.crm.events[0]=purchase_completed",
                        "omnirec.destinations.webhook.endpoints.etl.url=https://etl.example.com/in",
                        "omnirec.destinations.webhook.endpoints.etl.secret=b",
                        "omnirec.destinations.webhook.endpoints.etl.events[0]=*")
                .run(context -> {
                    List<String> ids = new ArrayList<>();
                    context.getBeansOfType(EventDestination.class).values().forEach(d -> ids.add(d.id()));
                    assertEquals(List.of("webhook-crm", "webhook-etl"), ids.stream().sorted().toList());
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebhookDestinationAutoConfiguration.class))
                .withPropertyValues("omnirec.destinations.webhook.enabled=true",
                        "omnirec.destinations.webhook.endpoints.bad.url=http://insecure.example.com",
                        "omnirec.destinations.webhook.endpoints.bad.secret=a",
                        "omnirec.destinations.webhook.endpoints.bad.events[0]=*")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
