// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.storage.CustomerEventPage;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.commerce.storage.EventStoreException;
import io.omnirec.eventprocessing.queue.QueueTopology;
import io.omnirec.storage.worker.EventStorageDestination;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Historical storage end to end, on real infrastructure:
 *
 * <pre>
 *   HTTP -> Event API -> RabbitMQ -> omnirec.events.event-storage -> storage worker
 *        -> EventStore -> PostgreSQL           ... GET /v1/customers/{id}/events
 * </pre>
 *
 * Everything is real except the thin wrapper around the EventStore, which
 * fails on command so the worker's failure paths can be driven through the
 * real broker.
 *
 * Deliberately flat, without {@code @Nested} classes: Spring caches a separate
 * application context for the enclosing class and for nested classes, and two
 * live contexts on one broker are two storage workers competing for the same
 * queue — which made the failure-path tests observe the wrong consumer.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=true",
        // Two retries at 1s and 2s keeps exhaustion under five seconds.
        "omnirec.processing.max-retries=2",
        "omnirec.processing.concurrency=1",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.events.tenants.tenant-a.api-key=pk_test_tenant_a",
        "omnirec.events.tenants.tenant-a.secret-key=sk_test_tenant_a_secret",
        "omnirec.events.tenants.tenant-b.api-key=pk_test_tenant_b",
        "omnirec.events.tenants.tenant-b.secret-key=sk_test_tenant_b_secret",
        "omnirec.storage.history-api.platform-keys.ops.key=sk_test_platform_secret",
        "omnirec.storage.history-api.platform-keys.ops.tenants=tenant-a,tenant-b",
        "omnirec.storage.enabled=true",
        "omnirec.storage.provider=postgres"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealStoragePipelineTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("omnirec.storage.postgres.url", POSTGRES::getJdbcUrl);
        registry.add("omnirec.storage.postgres.username", POSTGRES::getUsername);
        registry.add("omnirec.storage.postgres.password", POSTGRES::getPassword);
    }

    private static final String STORAGE_QUEUE = QueueTopology.queueName(EventStorageDestination.ID);

    /**
     * Wraps the real store. The product id scripts a failure:
     * "storage-fail-once" fails its first save, "storage-always-fail" every save.
     */
    static class ScriptedFailureStore implements EventStore {
        private final EventStore delegate;
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        ScriptedFailureStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public SaveOutcome save(CommerceEvent event) {
            int attempt = attempts.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
            String script = event.commerce().productId();
            if (("storage-fail-once".equals(script) && attempt == 1) || "storage-always-fail".equals(script)) {
                throw new EventStoreException("database unavailable (scripted)", null, true);
            }
            return delegate.save(event);
        }

        @Override
        public CustomerEventPage findCustomerEvents(String tenantId, String customerId, EventQuery query) {
            return delegate.findCustomerEvents(tenantId, customerId, query);
        }

        int attemptsFor(String eventId) {
            AtomicInteger count = attempts.get(eventId);
            return count == null ? 0 : count.get();
        }
    }

    @TestConfiguration
    static class FailureInjection {
        @Bean
        static BeanPostProcessor scriptedFailureStore() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof EventStore store && !(bean instanceof ScriptedFailureStore)
                            ? new ScriptedFailureStore(store)
                            : bean;
                }
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private EventStore eventStore;

    // ------------------------------------------------------------ helpers

    /** Within the normalizer's clock-skew window, so timestamps are kept as sent. */
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(6));

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> event(String eventId, String type, String anonymousId, String userId,
                                             String productId, Instant at) {
        Map<String, Object> identity = userId == null
                ? Map.of("anonymousId", anonymousId, "sessionId", "s_" + anonymousId)
                : Map.of("anonymousId", anonymousId, "userId", userId, "sessionId", "s_" + anonymousId);
        Map<String, Object> commerce = productId == null ? Map.of() : Map.of("productId", productId);
        return Map.of("eventId", eventId, "eventType", type, "schemaVersion", "1.0", "timestamp", at.toString(),
                "identity", identity, "context", Map.of("platform", "web"), "commerce", commerce,
                "properties", Map.of("source", "test"));
    }

    private void send(String publishableKey, Map<String, Object> event) throws Exception {
        mockMvc.perform(post("/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", publishableKey)
                        .content(objectMapper.writeValueAsString(Map.of("events", List.of(event)))))
                .andExpect(status().isAccepted());
    }

    private static Connection db() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int storedRows(String tenant, String eventId) throws Exception {
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM omnirec.commerce_events WHERE tenant_id = ? AND event_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String storedUserId(String tenant, String eventId) throws Exception {
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM omnirec.commerce_events WHERE tenant_id = ? AND event_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "row expected");
                return rs.getString(1);
            }
        }
    }

    private static void awaitStored(String tenant, String... eventIds) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (String eventId : eventIds) assertEquals(1, storedRows(tenant, eventId), eventId);
        });
    }

    private JsonNode history(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static MockHttpServletRequestBuilder historyOf(String customerId, String secretKey) {
        return get("/v1/customers/{customerId}/events", customerId).header("Authorization", "Bearer " + secretKey);
    }

    private static List<String> eventIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.get("events").forEach(e -> ids.add(e.get("eventId").asText()));
        return ids;
    }

    /** Queue counters from the management API: ready and delivered-but-unacknowledged. */
    private static JsonNode managementQueue(String queue) throws Exception {
        String auth = Base64.getEncoder().encodeToString(
                (RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + "/api/queues/%2F/" + queue))
                        .header("Authorization", "Basic " + auth).build(),
                HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(response.body());
    }

    // -------------------------------------------------------------- tests

    // ================================================================ the storage worker

    @Test
    void exactlyOneStorageWorkerConsumesTheStorageQueue() throws Exception {
        // concurrency=1 in this test: one consumer. A second would mean a second
        // worker (or application context) competing for storage messages.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(1, managementQueue(STORAGE_QUEUE).path("consumers").asInt(-1)));
    }

    @Test
    void hasItsOwnQueueRetryTiersAndDeadLetterQueue() {
        String id = EventStorageDestination.ID;
        assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.queueName(id)));
        assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.retryQueueName(id, 1)));
        assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.retryQueueName(id, 2)));
        assertNotNull(amqpAdmin.getQueueInfo(QueueTopology.deadLetterQueueName(id)));
    }

    @Test
    void anAcceptedEventReachesTheDatabaseAsynchronouslyAndTheMessageIsAcked() throws Exception {
        String eventId = unique("evt");
        send("pk_test_tenant_a", event(eventId, "product_viewed", unique("anon"), unique("user"), "p1", BASE));

        awaitStored("tenant-a", eventId);

        // Only acked once the row is committed: nothing may be left ready or
        // unacknowledged on the storage queue. (Management stats refresh
        // every few seconds, hence the wait.)
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode queue = managementQueue(STORAGE_QUEUE);
            assertEquals(0, queue.path("messages_ready").asInt(-1), queue.toString());
            assertEquals(0, queue.path("messages_unacknowledged").asInt(-1), queue.toString());
        });
    }

    /** Nothing in our code moves the message back: only the broker's retry-tier TTL can. */
    @Test
    void aFailedWriteIsRetriedThroughTheBrokerNotAckedAway() throws Exception {
        String eventId = unique("evt");
        send("pk_test_tenant_a", event(eventId, "product_viewed", unique("anon"), null, "storage-fail-once", BASE));

        awaitStored("tenant-a", eventId);

        assertEquals(2, ((ScriptedFailureStore) eventStore).attemptsFor(eventId),
                "one failed write, then one successful retry");
        assertEquals(1, storedRows("tenant-a", eventId));
    }

    @Test
    void aWriteThatKeepsFailingIsParkedOnTheStorageDeadLetterQueue() throws Exception {
        String eventId = unique("evt");
        send("pk_test_tenant_a", event(eventId, "product_viewed", unique("anon"), null, "storage-always-fail", BASE));

        // Initial attempt + 2 retries, then dead-lettered.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertEquals(3, ((ScriptedFailureStore) eventStore).attemptsFor(eventId)));

        String dlq = QueueTopology.deadLetterQueueName(EventStorageDestination.ID);
        CommerceEvent[] parked = new CommerceEvent[1];
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message message;
            while (parked[0] == null && (message = rabbitTemplate.receive(dlq, 200)) != null) {
                CommerceEvent e = objectMapper.readValue(message.getBody(), CommerceEvent.class);
                if (e.eventId().equals(eventId)) parked[0] = e;
            }
            assertNotNull(parked[0], "an event that could not be stored must be parked, never dropped");
        });
        assertEquals(0, storedRows("tenant-a", eventId));
    }

    /**
     * A broker redelivery, simulated by republishing the same event straight
     * onto the storage queue. Delivery-stage dedup normally catches it first;
     * the database's unique key is the backstop when that state is gone
     * (restart, eviction) — proven directly in RealPostgresEventStoreTest.
     * Either way: one row.
     */
    @Test
    void aRedeliveredMessageDoesNotCreateASecondRow() throws Exception {
        String eventId = unique("evt");
        String user = unique("user");
        send("pk_test_tenant_a", event(eventId, "product_viewed", unique("anon"), user, "p1", BASE));
        awaitStored("tenant-a", eventId);

        CommerceEvent stored = eventStore.findCustomerEvents("tenant-a", user, EventQuery.firstPage(10)).events().get(0);
        rabbitTemplate.send(QueueTopology.EXCHANGE, QueueTopology.routingKey(EventStorageDestination.ID),
                new Message(objectMapper.writeValueAsBytes(stored)));

        Thread.sleep(1500);
        assertEquals(1, storedRows("tenant-a", eventId));
    }

    // ================================================================ identity

    @Test
    void identifyLinksEarlierAnonymousBrowsingIntoTheJourneyWithoutRewritingIt() throws Exception {
        String anon = unique("anon");
        String user = unique("user");
        String first = unique("evt");
        String second = unique("evt");
        send("pk_test_tenant_a", event(first, "product_viewed", anon, null, "p1", BASE));
        send("pk_test_tenant_a", event(second, "product_viewed", anon, null, "p2", BASE.plusSeconds(60)));
        awaitStored("tenant-a", first, second);

        mockMvc.perform(post("/v1/identify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_test_tenant_a")
                        .content(objectMapper.writeValueAsString(Map.of("anonymousId", anon, "userId", user))))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode journey = history(historyOf(user, "sk_test_tenant_a_secret"), 200);
            List<String> ids = eventIds(journey);
            assertEquals(3, ids.size(), journey.toString());
            assertEquals("identify", journey.get("events").get(0).get("eventType").asText());
            assertEquals(List.of(second, first), ids.subList(1, 3));
        });

        assertNull(storedUserId("tenant-a", first), "the anonymous row keeps the identity it was captured with");
        assertNull(storedUserId("tenant-a", second));
    }

    // ================================================================ tenant isolation

    @Test
    void eachTenantKeyReadsOnlyItsOwnTenant() throws Exception {
        String customer = unique("user");
        String a = unique("evt");
        String b = unique("evt");
        send("pk_test_tenant_a", event(a, "product_viewed", unique("anon"), customer, "pA", BASE));
        send("pk_test_tenant_b", event(b, "product_viewed", unique("anon"), customer, "pB", BASE));
        awaitStored("tenant-a", a);
        awaitStored("tenant-b", b);

        assertEquals(List.of(a), eventIds(history(historyOf(customer, "sk_test_tenant_a_secret"), 200)));
        assertEquals(List.of(b), eventIds(history(historyOf(customer, "sk_test_tenant_b_secret"), 200)));
    }

    @Test
    void tenantAsKeyCannotReadTenantBByNamingIt() throws Exception {
        history(historyOf("anyone", "sk_test_tenant_a_secret").header("X-Omnirec-Tenant", "tenant-b"), 403);
    }

    @Test
    void aTenantIdInTheQueryStringIsIgnored() throws Exception {
        String customer = unique("user");
        String b = unique("evt");
        send("pk_test_tenant_b", event(b, "product_viewed", unique("anon"), customer, "pB", BASE));
        awaitStored("tenant-b", b);

        JsonNode response = history(historyOf(customer, "sk_test_tenant_a_secret").param("tenantId", "tenant-b"), 200);
        assertTrue(eventIds(response).isEmpty(), "the tenant comes from the key, never from the request");
    }

    @Test
    void thePublishableKeyCannotReadHistory() throws Exception {
        history(historyOf("anyone", "pk_test_tenant_a"), 401);
        history(get("/v1/customers/anyone/events").header("X-Omnirec-Key", "pk_test_tenant_a"), 401);
    }

    @Test
    void anEncodedPathCannotSlipPastAuthentication() throws Exception {
        // A raw URI: a string template would re-encode the '%'.
        history(get(URI.create("/v1/%63ustomers/anyone/events")), 401);
    }

    @Test
    void noKeyOrAWrongKeyIsRefused() throws Exception {
        history(get("/v1/customers/anyone/events"), 401);
        history(historyOf("anyone", "sk_guess"), 401);
        history(get("/v1/customers/anyone/events").param("api_key", "sk_test_tenant_a_secret"), 401);
    }

    @Test
    void aPlatformKeyMustNameOneOfItsTenants() throws Exception {
        String customer = unique("user");
        String b = unique("evt");
        send("pk_test_tenant_b", event(b, "product_viewed", unique("anon"), customer, "pB", BASE));
        awaitStored("tenant-b", b);

        history(historyOf(customer, "sk_test_platform_secret"), 400);
        history(historyOf(customer, "sk_test_platform_secret").header("X-Omnirec-Tenant", "demo-store"), 403);
        assertEquals(List.of(b), eventIds(history(
                historyOf(customer, "sk_test_platform_secret").header("X-Omnirec-Tenant", "tenant-b"), 200)));
    }

    // ================================================================ the history API

    @Test
    void returnsACleanDto() throws Exception {
        String customer = unique("user");
        String eventId = unique("evt");
        send("pk_test_tenant_a", event(eventId, "product_viewed", unique("anon"), customer, "P999", BASE));
        awaitStored("tenant-a", eventId);

        JsonNode response = history(historyOf(customer, "sk_test_tenant_a_secret"), 200);

        assertEquals(customer, response.get("customerId").asText());
        JsonNode event = response.get("events").get(0);
        assertEquals(eventId, event.get("eventId").asText());
        assertEquals("product_viewed", event.get("eventType").asText());
        assertEquals(BASE.toString(), event.get("occurredAt").asText());
        assertEquals("P999", event.get("productId").asText());
        assertEquals("test", event.get("properties").get("source").asText());
        assertFalse(event.has("tenantId"), "the storage row is not the API shape");
        assertFalse(response.has("nextCursor"), "one event: no next page");
    }

    @Test
    void pagesThroughTheWholeHistoryWithTheOpaqueCursor() throws Exception {
        String customer = unique("user");
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String id = unique("evt");
            // Pairs share a timestamp, to exercise the tie-breaker at page boundaries.
            send("pk_test_tenant_a", event(id, "product_viewed", "anon_pages", customer, "p" + i,
                    BASE.plusSeconds(i / 2)));
            sent.add(id);
        }
        awaitStored("tenant-a", sent.toArray(String[]::new));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request = historyOf(customer, "sk_test_tenant_a_secret").param("limit", "3");
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = history(request, 200);
            seen.addAll(eventIds(page));
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(3, pages);
        assertEquals(seen.size(), new HashSet<>(seen).size(), "no duplicates across pages");
        assertEquals(new HashSet<>(sent), new HashSet<>(seen), "nothing skipped");
    }

    @Test
    void filtersByEventTypeAndTimeRange() throws Exception {
        String customer = unique("user");
        String anon = unique("anon");
        String early = unique("evt");
        String view = unique("evt");
        String cart = unique("evt");
        send("pk_test_tenant_a", event(early, "product_viewed", anon, customer, "p0", BASE.minusSeconds(3600)));
        send("pk_test_tenant_a", event(view, "product_viewed", anon, customer, "p1", BASE));
        Map<String, Object> addToCart = new java.util.HashMap<>(event(cart, "product_added_to_cart", anon, customer,
                "p1", BASE.plusSeconds(10)));
        addToCart.put("commerce", Map.of("productId", "p1", "cartId", "c1", "quantity", 1));
        send("pk_test_tenant_a", addToCart);
        awaitStored("tenant-a", early, view, cart);

        assertEquals(List.of(view, early), eventIds(history(
                historyOf(customer, "sk_test_tenant_a_secret").param("eventType", "product_viewed"), 200)));
        assertEquals(List.of(cart, view), eventIds(history(historyOf(customer, "sk_test_tenant_a_secret")
                .param("from", BASE.toString()).param("to", BASE.plusSeconds(60).toString()), 200)));
        assertEquals(List.of(cart), eventIds(history(historyOf(customer, "sk_test_tenant_a_secret")
                .param("eventType", "product_added_to_cart,purchase_completed"), 200)));
    }

    @Test
    void anUnknownCustomerHasAnEmptyHistory() throws Exception {
        JsonNode response = history(historyOf(unique("nobody"), "sk_test_tenant_a_secret"), 200);

        assertEquals(0, response.get("events").size());
        assertFalse(response.has("nextCursor"));
    }

    @Test
    void rejectsInvalidInputWithoutEchoingIt() throws Exception {
        String key = "sk_test_tenant_a_secret";
        history(historyOf("x".repeat(257), key), 400);
        history(historyOf("user_1", key).param("cursor", "not-a-cursor"), 400);
        history(historyOf("user_1", key).param("limit", "0"), 400);
        history(historyOf("user_1", key).param("limit", "100000"), 400);
        history(historyOf("user_1", key).param("limit", "abc"), 400);
        history(historyOf("user_1", key).param("eventType", "not_a_type"), 400);
        history(historyOf("user_1", key).param("from", "yesterday"), 400);
        JsonNode inverted = history(historyOf("user_1", key)
                .param("from", BASE.toString()).param("to", BASE.minusSeconds(1).toString()), 400);
        assertEquals(400, inverted.get("status").asInt());
    }
}
