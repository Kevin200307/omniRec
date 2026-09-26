// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.recentlyviewed;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.redis.RedisCacheProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Against a real Redis, read back through the serving side's own
 * RedisCacheProvider: the proof that what this writes is what
 * /v1/recently-viewed returns.
 */
@Testcontainers(disabledWithoutDocker = true)
class RealRedisRecentlyViewedTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisCacheProvider servingCache;
    private String user;

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        servingCache = new RedisCacheProvider(redis);
        user = "u_" + UUID.randomUUID();
    }

    @AfterEach
    void close() {
        factory.destroy();
    }

    private List<Object> servedList() {
        return servingCache.getList("recently-viewed:" + user);
    }

    @Test
    void theServingSideReadsWhatThePipelineWrote() {
        RecentlyViewedDestination destination = new RecentlyViewedDestination(redis, new RecentlyViewedProperties());
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p1", T0));
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p2", T0.plusSeconds(1)));

        assertEquals(List.of("p2", "p1"), servedList());
    }

    @Test
    void aReViewedProductMovesToTheFrontWithoutAppearingTwice() {
        RecentlyViewedDestination destination = new RecentlyViewedDestination(redis, new RecentlyViewedProperties());
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p1", T0));
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p2", T0.plusSeconds(1)));
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p1", T0.plusSeconds(2)));

        assertEquals(List.of("p1", "p2"), servedList());
    }

    @Test
    void aLateRetryOfAnOlderViewDoesNotJumpAheadOfANewerOne() {
        RecentlyViewedDestination destination = new RecentlyViewedDestination(redis, new RecentlyViewedProperties());
        CommerceEvent olderP1 = RecentlyViewedDestinationTest.view("t", user, "p1", T0);
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p1", T0.plusSeconds(10)));
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p2", T0.plusSeconds(20)));

        destination.send(olderP1);     // arrives late, from a retry tier

        assertEquals(List.of("p2", "p1"), servedList());
    }

    @Test
    void redeliveringTheSameViewChangesNothing() {
        RecentlyViewedDestination destination = new RecentlyViewedDestination(redis, new RecentlyViewedProperties());
        CommerceEvent p1 = RecentlyViewedDestinationTest.view("t", user, "p1", T0);
        destination.send(p1);
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p2", T0.plusSeconds(1)));
        destination.send(p1);

        assertEquals(List.of("p2", "p1"), servedList());
    }

    @Test
    void theListIsCappedKeepingTheNewest() {
        RecentlyViewedProperties p = new RecentlyViewedProperties();
        p.setMaxItems(3);
        RecentlyViewedDestination destination = new RecentlyViewedDestination(redis, p);
        for (int i = 1; i <= 5; i++) {
            destination.send(RecentlyViewedDestinationTest.view("t", user, "p" + i, T0.plusSeconds(i)));
        }
        // An old view arriving after the list is full must not evict a newer one.
        destination.send(RecentlyViewedDestinationTest.view("t", user, "p0", T0));

        assertEquals(List.of("p5", "p4", "p3"), servedList());
    }

    @Test
    void bothKeysExpire() {
        RecentlyViewedProperties p = new RecentlyViewedProperties();
        p.setTtl(Duration.ofMinutes(5));
        new RecentlyViewedDestination(redis, p).send(RecentlyViewedDestinationTest.view("t", user, "p1", T0));

        long listTtl = redis.getExpire("recently-viewed:" + user);
        long indexTtl = redis.getExpire("recently-viewed:" + user + RecentlyViewedDestination.INDEX_SUFFIX);
        assertTrue(listTtl > 0 && listTtl <= 300, "list ttl " + listTtl);
        assertTrue(indexTtl > 0 && indexTtl <= 300, "index ttl " + indexTtl);
    }
}
