package io.omnirec.eventapi.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.destination.recentlyviewed.RecentlyViewedDestination;
import io.omnirec.redis.state.RedisDeduplicationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real app with both of its Redis features on, as a production deployment
 * would run it: shared pipeline state <em>and</em> the recently-viewed feed.
 *
 * Every module test passed while this configuration refused to start — two
 * {@code RedisConnectionFactory} beans left Spring Boot's own Redis
 * auto-configuration unable to choose — so this boots the whole context rather
 * than trusting the modules separately.
 *
 * It then checks the feed through the full pipeline over HTTP, including
 * identity stitching: a view sent with only an anonymous id lands in the
 * user's list once that device is linked, and views from before the login do
 * not.
 */
@SpringBootTest(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.rate-limit.enabled=false",
        "omnirec.state.redis.enabled=true",
        "omnirec.destinations.recently-viewed.enabled=true",
        "omnirec.destinations.recently-viewed.tenant-id=demo-store"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RecentlyViewedFeedTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("omnirec.state.redis.host", REDIS::getHost);
        registry.add("omnirec.state.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("omnirec.destinations.recently-viewed.host", REDIS::getHost);
        registry.add("omnirec.destinations.recently-viewed.port", () -> REDIS.getMappedPort(6379));
        // Separate databases stand in for separate instances: the serving cache
        // and the pipeline state need not be the same Redis.
        registry.add("omnirec.destinations.recently-viewed.database", () -> 1);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationContext context;

    @Test
    void bothRedisFeaturesStartTogether() {
        assertEquals(1, context.getBeansOfType(RecentlyViewedDestination.class).size());
        assertInstanceOf(RedisDeduplicationStore.class,
                context.getBean(io.omnirec.commerce.dedup.DeduplicationStore.class));
    }

    @Test
    void viewsAfterLoginReachTheServedListAndEarlierAnonymousViewsDoNot() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String anon = "anon_" + run;
        String user = "customer_" + run;

        send(event("v1_" + run, "product_viewed", anonymous(anon), "p1", "2026-09-01T10:00:00Z"));
        send(event("id_" + run, "identify", identified(anon, user), null, "2026-09-01T10:01:00Z"));
        // Sent as the browser would after login, and again with only the anonymous
        // id: the pipeline's identity link must attribute it to the user.
        send(event("v2_" + run, "product_viewed", identified(anon, user), "p2", "2026-09-01T10:02:00Z"));
        send(event("v3_" + run, "product_viewed", anonymous(anon), "p3", "2026-09-01T10:03:00Z"));

        assertEquals(List.of("\"p3\"", "\"p2\""), servedList(user),
                "newest first, only views attributable to the user");
    }

    /** Read the way RedisCacheProvider reads it, from the serving cache's database. */
    private List<String> servedList(String user) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.setDatabase(1);
        factory.afterPropertiesSet();
        try {
            List<String> raw = new StringRedisTemplate(factory).opsForList().range("recently-viewed:" + user, 0, -1);
            return raw == null ? List.of() : raw;
        } finally {
            factory.destroy();
        }
    }

    private void send(Map<String, Object> event) throws Exception {
        mockMvc.perform(post("/v1/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Omnirec-Key", "pk_test_demo_store")
                        .content(objectMapper.writeValueAsString(Map.of("events", List.of(event)))))
                .andExpect(status().isAccepted());
    }

    private static Map<String, Object> anonymous(String anon) {
        Map<String, Object> identity = new HashMap<>();
        identity.put("anonymousId", anon);
        identity.put("sessionId", "s1");
        identity.put("userId", null);
        return identity;
    }

    private static Map<String, Object> identified(String anon, String user) {
        return Map.of("anonymousId", anon, "userId", user, "sessionId", "s1");
    }

    private static Map<String, Object> event(String id, String type, Map<String, Object> identity,
                                             String productId, String timestamp) {
        return Map.of(
                "eventId", "evt_" + id,
                "eventType", type,
                "schemaVersion", "1.0",
                "timestamp", timestamp,
                "identity", identity,
                "context", Map.of("platform", "web", "url", "https://shop.example/p"),
                "commerce", productId == null ? Map.of() : Map.of("productId", productId),
                "properties", Map.of());
    }
}
