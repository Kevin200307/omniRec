package io.omnirec.commerce.dedup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link DeduplicationStore}.
 *
 * Atomic within one JVM via {@link ConcurrentHashMap#compute}, which is enough
 * for tests and a single-node deployment. It is <strong>not</strong> enough for
 * more than one instance: each node keeps its own map, so the same event can
 * pass both. Use {@code omnirec-redis-state} anywhere with more than one
 * consumer — the auto-configuration logs a warning when this store is active.
 *
 * Expired entries are cleaned lazily on access and swept on write, so a
 * long-running process doesn't grow without bound.
 */
public class InMemoryDeduplicationStore implements DeduplicationStore {

    private record Entry(boolean completed, Instant expiresAt) {
        boolean isLive(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int sweepThreshold;

    public InMemoryDeduplicationStore() {
        this(Clock.systemUTC(), 10_000);
    }

    public InMemoryDeduplicationStore(Clock clock, int sweepThreshold) {
        this.clock = clock;
        this.sweepThreshold = sweepThreshold;
    }

    @Override
    public ClaimResult claim(String key, Duration lease) {
        Instant now = clock.instant();
        ClaimResult[] result = new ClaimResult[1];

        entries.compute(key, (k, existing) -> {
            if (existing != null && existing.isLive(now)) {
                result[0] = existing.completed() ? ClaimResult.ALREADY_COMPLETED : ClaimResult.IN_PROGRESS;
                return existing;
            }
            result[0] = ClaimResult.CLAIMED;
            return new Entry(false, now.plus(lease));
        });

        if (result[0] == ClaimResult.CLAIMED && entries.size() > sweepThreshold) {
            sweepExpired(now);
        }
        return result[0];
    }

    @Override
    public void complete(String key, Duration ttl) {
        entries.put(key, new Entry(true, clock.instant().plus(ttl)));
    }

    @Override
    public void release(String key) {
        // Only a lease is released; a completed entry is a fact and stays.
        entries.computeIfPresent(key, (k, existing) -> existing.completed() ? existing : null);
    }

    @Override
    public boolean isCompleted(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return false;
        if (!entry.isLive(clock.instant())) {
            entries.remove(key, entry);
            return false;
        }
        return entry.completed();
    }

    public int size() {
        return entries.size();
    }

    private void sweepExpired(Instant now) {
        entries.entrySet().removeIf(e -> !e.getValue().isLive(now));
    }
}
