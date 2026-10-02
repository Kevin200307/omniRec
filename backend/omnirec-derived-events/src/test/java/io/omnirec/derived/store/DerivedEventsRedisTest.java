// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.store;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.derived.DerivedEventsEngine;
import io.omnirec.derived.Timer;
import io.omnirec.derived.rules.CartAbandonedRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Against a real Redis: timers survive a restart and two instances never fire the same one. */
@Testcontainers(disabledWithoutDocker = true)
class DerivedEventsRedisTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "test:" + UUID.randomUUID();
    }

    @AfterEach
    void close() {
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    /** A fresh connection, as a new process or another instance would have. */
    private RedisDerivedStateStore newInstance() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factories.add(factory);
        return new RedisDerivedStateStore(new StringRedisTemplate(factory), prefix);
    }

    @Test
    void timersSurviveARestartWithTheirPayload() {
        RedisDerivedStateStore before = newInstance();
        before.schedule(new Timer("cart_abandoned", "shop:a1", T0.plusSeconds(60), Map.of("cartId", "c1")));
        before.schedule(new Timer("cart_abandoned", "shop:a2", T0.plusSeconds(600), Map.of()));
        factories.get(0).destroy();

        RedisDerivedStateStore after = newInstance();
        assertEquals(List.of(), after.claimDue(T0, 10));
        List<Timer> due = after.claimDue(T0.plusSeconds(60), 10);
        assertEquals(1, due.size());
        assertEquals("shop:a1", due.get(0).key());
        assertEquals("c1", due.get(0).get("cartId"));
        assertEquals(T0.plusSeconds(60), due.get(0).dueAt());
        assertEquals(List.of(), after.claimDue(T0.plusSeconds(60), 10), "claimed timers are gone");
    }

    @Test
    void reschedulingReplacesAndCancelRemoves() {
        RedisDerivedStateStore store = newInstance();
        store.schedule(new Timer("r", "k", T0, Map.of("v", "1")));
        store.schedule(new Timer("r", "k", T0.plusSeconds(30), Map.of("v", "2")));
        assertEquals(List.of(), store.claimDue(T0, 10));
        assertEquals("2", store.claimDue(T0.plusSeconds(30), 10).get(0).get("v"));

        store.schedule(new Timer("r", "gone", T0, Map.of()));
        store.cancel("r:gone");
        assertEquals(List.of(), store.claimDue(T0.plusSeconds(60), 10));
    }

    @Test
    void twoInstancesPollingAtOnceFireEachTimerExactlyOnce() throws Exception {
        RedisDerivedStateStore seed = newInstance();
        for (int i = 0; i < 300; i++) seed.schedule(new Timer("r", "k" + i, T0, Map.of()));

        RedisDerivedStateStore a = newInstance();
        RedisDerivedStateStore b = newInstance();
        List<String> claimed = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> pollers = new ArrayList<>();
            for (RedisDerivedStateStore store : List.of(a, b, a, b)) {
                pollers.add(() -> {
                    List<Timer> batch;
                    while (!(batch = store.claimDue(T0, 7)).isEmpty()) batch.forEach(t -> claimed.add(t.key()));
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(pollers)) f.get();
        } finally {
            pool.shutdown();
        }
        assertEquals(300, claimed.size(), "no timer fired twice");
        assertEquals(300, new HashSet<>(claimed).size());
    }

    @Test
    void factsAndCountersAreShared() {
        RedisDerivedStateStore a = newInstance();
        RedisDerivedStateStore b = newInstance();
        assertTrue(a.setIfAbsent("seen:o1", Duration.ofMinutes(5)));
        assertFalse(b.setIfAbsent("seen:o1", Duration.ofMinutes(5)));
        assertEquals(1, a.increment("orders:u1"));
        assertEquals(2, b.increment("orders:u1"));
        b.put("last:a1", "s1|123", Duration.ofMinutes(5));
        assertEquals("s1|123", a.get("last:a1").orElseThrow());
    }

    @Test
    void anAbandonedCartFiresOnceAcrossTwoEngines() {
        List<CommerceEvent> emitted = Collections.synchronizedList(new ArrayList<>());
        Clock early = Clock.fixed(T0, ZoneOffset.UTC);
        Clock late = Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC);
        DerivedEventsEngine first = new DerivedEventsEngine(List.of(new CartAbandonedRule(Duration.ofMinutes(60))),
                newInstance(), emitted::add, early);
        first.send(CommerceEvent.builder()
                .eventId("add_1").eventType("product_added_to_cart").eventVersion(1)
                .kind(CommerceEvent.KIND_STANDARD).schemaVersion("2.0").source(EventSource.BROWSER)
                .timestamp(T0).receivedAt(T0).tenantId("shop")
                .identity(new EventIdentity("a1", null, "s1")).context(EventContext.empty())
                .data(EventData.of(Map.of("product", Map.of("id", "p1", "quantity", 1), "cart", Map.of("id", "c1"))))
                .properties(Map.of()).build());

        DerivedEventsEngine instanceA = new DerivedEventsEngine(List.of(new CartAbandonedRule(Duration.ofMinutes(60))),
                newInstance(), emitted::add, late);
        DerivedEventsEngine instanceB = new DerivedEventsEngine(List.of(new CartAbandonedRule(Duration.ofMinutes(60))),
                newInstance(), emitted::add, late);
        assertEquals(1, instanceA.fireDue() + instanceB.fireDue());
        Set<String> names = new HashSet<>();
        emitted.forEach(e -> names.add(e.eventType().wireName()));
        assertEquals(Set.of("cart_abandoned"), names);
        assertEquals("c1", emitted.get(0).data().cart().id());
    }
}
