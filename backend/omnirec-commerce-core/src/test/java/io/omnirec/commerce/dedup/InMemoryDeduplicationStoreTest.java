// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.dedup;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDeduplicationStoreTest {

    /** Lets expiry be tested without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    @Test
    void theFirstCallClaimsTheKey() {
        DeduplicationStore store = new InMemoryDeduplicationStore();

        assertTrue(store.markProcessed("evt_123", Duration.ofHours(1)));
    }

    @Test
    void aSecondCallIsRefused() {
        DeduplicationStore store = new InMemoryDeduplicationStore();
        store.markProcessed("evt_123", Duration.ofHours(1));

        assertFalse(store.markProcessed("evt_123", Duration.ofHours(1)));
    }

    @Test
    void repeatedSubmissionsOfTheSameEventPassExactlyOnce() {
        DeduplicationStore store = new InMemoryDeduplicationStore();
        int accepted = 0;

        for (int i = 0; i < 10; i++) {
            if (store.markProcessed("evt_123", Duration.ofHours(1))) accepted++;
        }

        assertEquals(1, accepted);
    }

    @Test
    void differentEventsAreIndependent() {
        DeduplicationStore store = new InMemoryDeduplicationStore();

        assertTrue(store.markProcessed("evt_1", Duration.ofHours(1)));
        assertTrue(store.markProcessed("evt_2", Duration.ofHours(1)));
    }

    @Test
    void aKeyBecomesClaimableAgainAfterItsTtl() {
        MutableClock clock = new MutableClock();
        DeduplicationStore store = new InMemoryDeduplicationStore(clock, 10_000);
        store.markProcessed("evt_123", Duration.ofHours(1));

        clock.advance(Duration.ofHours(2));

        assertTrue(store.markProcessed("evt_123", Duration.ofHours(1)),
                "the window is finite by design — we can't retain every eventId forever");
    }

    @Test
    void aKeyStaysClaimedWithinItsTtl() {
        MutableClock clock = new MutableClock();
        DeduplicationStore store = new InMemoryDeduplicationStore(clock, 10_000);
        store.markProcessed("evt_123", Duration.ofHours(24));

        clock.advance(Duration.ofHours(23));

        assertFalse(store.markProcessed("evt_123", Duration.ofHours(24)));
    }

    @Test
    void isProcessedReflectsExpiry() {
        MutableClock clock = new MutableClock();
        DeduplicationStore store = new InMemoryDeduplicationStore(clock, 10_000);
        store.markProcessed("evt_123", Duration.ofMinutes(10));

        assertTrue(store.isProcessed("evt_123"));
        clock.advance(Duration.ofMinutes(11));
        assertFalse(store.isProcessed("evt_123"));
    }

    @Test
    void unknownKeysAreNotProcessed() {
        assertFalse(new InMemoryDeduplicationStore().isProcessed("never-seen"));
    }

    /**
     * The property that actually matters: with many consumers racing on the same
     * eventId, exactly one may proceed. A get-then-put implementation passes
     * every other test here and fails this one.
     */
    @Test
    void exactlyOneOfManyConcurrentClaimantsWins() throws Exception {
        DeduplicationStore store = new InMemoryDeduplicationStore();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    startLine.await();
                    if (store.markProcessed("evt_contended", Duration.ofHours(1))) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            startLine.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "a duplicate purchase must never reach a provider twice");
    }

    @Test
    void keysAreScopedByTenantAndStage() {
        assertNotEquals(
                DeduplicationStore.key("ingest", "tenant-a", "evt_1"),
                DeduplicationStore.key("ingest", "tenant-b", "evt_1"));
        assertNotEquals(
                DeduplicationStore.key("ingest", "tenant-a", "evt_1"),
                DeduplicationStore.key("deliver:amazon", "tenant-a", "evt_1"));
    }

    @Test
    void expiredEntriesAreSweptSoMemoryDoesNotGrowWithoutBound() {
        MutableClock clock = new MutableClock();
        InMemoryDeduplicationStore store = new InMemoryDeduplicationStore(clock, 10);

        for (int i = 0; i < 10; i++) {
            store.markProcessed("evt_" + i, Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofMinutes(5));
        // Claiming past the sweep threshold triggers the sweep.
        for (int i = 10; i < 22; i++) {
            store.markProcessed("evt_" + i, Duration.ofMinutes(1));
        }

        assertTrue(store.size() < 22, "expired keys should not accumulate forever");
    }
    // --- lease -> complete protocol --------------------------------------

    @Test
    void aFreshKeyIsClaimed() {
        assertEquals(DeduplicationStore.ClaimResult.CLAIMED,
                new InMemoryDeduplicationStore().claim("k", Duration.ofSeconds(30)));
    }

    @Test
    void aLeasedKeyReportsInProgressNotDuplicate() {
        DeduplicationStore store = new InMemoryDeduplicationStore();
        store.claim("k", Duration.ofSeconds(30));

        assertEquals(DeduplicationStore.ClaimResult.IN_PROGRESS, store.claim("k", Duration.ofSeconds(30)),
                "a lease is not proof the work happened");
        assertFalse(store.isCompleted("k"));
    }

    @Test
    void aCompletedKeyReportsAlreadyCompleted() {
        DeduplicationStore store = new InMemoryDeduplicationStore();
        store.claim("k", Duration.ofSeconds(30));
        store.complete("k", Duration.ofHours(24));

        assertEquals(DeduplicationStore.ClaimResult.ALREADY_COMPLETED, store.claim("k", Duration.ofSeconds(30)));
        assertTrue(store.isCompleted("k"));
    }

    /** The whole point of the lease: a crash leaves the event claimable again soon, not "done" for a day. */
    @Test
    void anAbandonedLeaseExpiresAndTheKeyCanBeClaimedAgain() {
        MutableClock clock = new MutableClock();
        DeduplicationStore store = new InMemoryDeduplicationStore(clock, 10_000);
        store.claim("k", Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(31));

        assertEquals(DeduplicationStore.ClaimResult.CLAIMED, store.claim("k", Duration.ofSeconds(30)));
    }

    @Test
    void completionOutlivesTheLease() {
        MutableClock clock = new MutableClock();
        DeduplicationStore store = new InMemoryDeduplicationStore(clock, 10_000);
        store.claim("k", Duration.ofSeconds(30));
        store.complete("k", Duration.ofHours(24));

        clock.advance(Duration.ofHours(1));

        assertEquals(DeduplicationStore.ClaimResult.ALREADY_COMPLETED, store.claim("k", Duration.ofSeconds(30)));
    }

    @Test
    void releaseFreesALeaseButNeverUndoesACompletion() {
        DeduplicationStore store = new InMemoryDeduplicationStore();
        store.claim("leased", Duration.ofSeconds(30));
        store.claim("done", Duration.ofSeconds(30));
        store.complete("done", Duration.ofHours(24));

        store.release("leased");
        store.release("done");

        assertEquals(DeduplicationStore.ClaimResult.CLAIMED, store.claim("leased", Duration.ofSeconds(30)));
        assertEquals(DeduplicationStore.ClaimResult.ALREADY_COMPLETED, store.claim("done", Duration.ofSeconds(30)));
    }
}
