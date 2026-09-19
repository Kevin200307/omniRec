package io.omnirec.redis.state;

import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.identity.IdentityLink;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Redis stores against a real Redis.
 *
 * The mocked tests prove the right commands are issued; this proves the
 * property those commands exist for. Separate connection factories stand in
 * for separate Event API instances — the whole reason this module exists is
 * that the in-memory store cannot make that guarantee across processes.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RealRedisStateTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();

    /** A new connection factory per call: each one is a separate "node". */
    private StringRedisTemplate node() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }

    @AfterEach
    void closeConnections() {
        factories.forEach(LettuceConnectionFactory::destroy);
        factories.clear();
    }

    private String key() {
        return DeduplicationStore.key("ingest", "demo-store", "evt_" + UUID.randomUUID());
    }

    @Test
    void aClaimOnOneNodeIsVisibleToAnother() {
        RedisDeduplicationStore nodeA = new RedisDeduplicationStore(node());
        RedisDeduplicationStore nodeB = new RedisDeduplicationStore(node());
        String key = key();

        assertTrue(nodeA.markProcessed(key, Duration.ofMinutes(5)));
        assertFalse(nodeB.markProcessed(key, Duration.ofMinutes(5)),
                "the in-memory store would say true here — that is the duplicate this module prevents");
    }

    /**
     * Many threads across several nodes race on one eventId. Exactly one may
     * win, or a purchase reaches a provider more than once.
     */
    @Test
    void exactlyOneClaimantWinsAcrossNodesUnderContention() throws Exception {
        List<RedisDeduplicationStore> nodes = List.of(
                new RedisDeduplicationStore(node()),
                new RedisDeduplicationStore(node()),
                new RedisDeduplicationStore(node()));
        String key = key();
        int threads = 30;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                RedisDeduplicationStore store = nodes.get(i % nodes.size());
                pool.submit(() -> {
                    startLine.await();
                    if (store.markProcessed(key, Duration.ofMinutes(5))) winners.incrementAndGet();
                    return null;
                });
            }
            startLine.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get());
    }

    @Test
    void aClaimExpiresAfterItsTtl() throws Exception {
        RedisDeduplicationStore store = new RedisDeduplicationStore(node());
        String key = key();

        store.markProcessed(key, Duration.ofSeconds(1));
        assertTrue(store.isProcessed(key));

        Thread.sleep(1500);
        assertFalse(store.isProcessed(key), "the window is finite so Redis memory doesn't grow forever");
    }

    /**
     * A node that claimed a lease and crashed must not make the event look
     * delivered: another node sees IN_PROGRESS until the lease expires, then
     * claims it.
     */
    @Test
    void aCrashedNodesLeaseExpiresAndAnotherNodeTakesOver() throws Exception {
        RedisDeduplicationStore crashedNode = new RedisDeduplicationStore(node());
        RedisDeduplicationStore survivor = new RedisDeduplicationStore(node());
        String key = key();

        assertEquals(DeduplicationStore.ClaimResult.CLAIMED, crashedNode.claim(key, Duration.ofSeconds(1)));
        assertEquals(DeduplicationStore.ClaimResult.IN_PROGRESS, survivor.claim(key, Duration.ofSeconds(1)));
        assertFalse(survivor.isCompleted(key), "a lease is not proof of delivery");

        Thread.sleep(1500);

        assertEquals(DeduplicationStore.ClaimResult.CLAIMED, survivor.claim(key, Duration.ofSeconds(1)));
    }

    @Test
    void aCompletionIsSeenByEveryNodeAndCannotBeReleased() {
        RedisDeduplicationStore nodeA = new RedisDeduplicationStore(node());
        RedisDeduplicationStore nodeB = new RedisDeduplicationStore(node());
        String key = key();

        nodeA.claim(key, Duration.ofSeconds(30));
        nodeA.complete(key, Duration.ofMinutes(5));
        nodeB.release(key); // a late, confused release from another node

        assertEquals(DeduplicationStore.ClaimResult.ALREADY_COMPLETED, nodeB.claim(key, Duration.ofSeconds(30)));
    }

    @Test
    void aReleasedLeaseIsClaimableAtOnce() {
        RedisDeduplicationStore store = new RedisDeduplicationStore(node());
        String key = key();

        store.claim(key, Duration.ofMinutes(5));
        store.release(key);

        assertEquals(DeduplicationStore.ClaimResult.CLAIMED, store.claim(key, Duration.ofMinutes(5)));
    }

    @Test
    void anIdentityLinkWrittenByOneNodeResolvesOnAnother() {
        RedisIdentityLinkStore nodeA = new RedisIdentityLinkStore(node(), Duration.ofDays(1));
        RedisIdentityLinkStore nodeB = new RedisIdentityLinkStore(node(), Duration.ofDays(1));
        String anon = "anon_" + UUID.randomUUID();

        nodeA.link(IdentityLink.of("demo-store", anon, "customer_123"));

        assertEquals(Optional.of("customer_123"), nodeB.resolveUserId("demo-store", anon),
                "a login handled by one instance must be known to every other");
    }

    @Test
    void collectsEveryDeviceForAUserAndIsIdempotentUnderRedelivery() {
        RedisIdentityLinkStore store = new RedisIdentityLinkStore(node(), Duration.ofDays(1));
        String user = "customer_" + UUID.randomUUID();

        store.link(IdentityLink.of("demo-store", "anon_laptop", user));
        store.link(IdentityLink.of("demo-store", "anon_phone", user));
        store.link(IdentityLink.of("demo-store", "anon_phone", user)); // redelivered identify

        List<String> devices = store.anonymousIdsFor("demo-store", user);
        assertEquals(2, devices.size());
        assertTrue(devices.containsAll(List.of("anon_laptop", "anon_phone")));
    }

    @Test
    void theMostRecentLoginOnASharedDeviceWins() {
        RedisIdentityLinkStore store = new RedisIdentityLinkStore(node(), Duration.ofDays(1));
        String anon = "anon_" + UUID.randomUUID();

        store.link(IdentityLink.of("demo-store", anon, "user_first"));
        store.link(IdentityLink.of("demo-store", anon, "user_second"));

        assertEquals(Optional.of("user_second"), store.resolveUserId("demo-store", anon));
    }

    @Test
    void tenantsNeverSeeEachOthersLinks() {
        RedisIdentityLinkStore store = new RedisIdentityLinkStore(node(), Duration.ofDays(1));
        String anon = "anon_" + UUID.randomUUID();

        store.link(IdentityLink.of("tenant-a", anon, "user_a"));

        assertTrue(store.resolveUserId("tenant-b", anon).isEmpty());
    }
}
