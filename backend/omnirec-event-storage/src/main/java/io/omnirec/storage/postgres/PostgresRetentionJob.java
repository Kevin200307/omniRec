// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.postgres;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Retention for the postgres provider: periodically deletes events whose event
 * time is older than {@code maxAge}, in small batches.
 *
 * Runs on its own single thread rather than relying on {@code @EnableScheduling},
 * so it works in any application that includes storage. Running on several
 * instances at once is harmless — they delete disjoint or already-deleted rows.
 *
 * TimescaleDB does not use this; it drops whole chunks through its own policy.
 */
public class PostgresRetentionJob implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PostgresRetentionJob.class);

    private final PostgresEventStore store;
    private final Duration maxAge;
    private final Duration interval;
    private final int batchSize;
    private final Clock clock;
    private final Map<String, Duration> tenantMaxAge;

    private ScheduledExecutorService executor;

    public PostgresRetentionJob(PostgresEventStore store, Duration maxAge, Duration interval, int batchSize) {
        this(store, maxAge, Map.of(), interval, batchSize, Clock.systemUTC());
    }

    public PostgresRetentionJob(PostgresEventStore store, Duration maxAge, Map<String, Duration> tenantMaxAge,
                                Duration interval, int batchSize) {
        this(store, maxAge, tenantMaxAge, interval, batchSize, Clock.systemUTC());
    }

    PostgresRetentionJob(PostgresEventStore store, Duration maxAge, Duration interval, int batchSize, Clock clock) {
        this(store, maxAge, Map.of(), interval, batchSize, clock);
    }

    /**
     * @param maxAge       null: no global purge
     * @param tenantMaxAge per-tenant overrides; those tenants are skipped by the global purge
     */
    PostgresRetentionJob(PostgresEventStore store, Duration maxAge, Map<String, Duration> tenantMaxAge,
                         Duration interval, int batchSize, Clock clock) {
        if (maxAge != null && (maxAge.isNegative() || maxAge.isZero())) {
            throw new IllegalArgumentException("omnirec.storage.retention.max-age must be positive");
        }
        Map<String, Duration> overrides = tenantMaxAge == null ? Map.of() : Map.copyOf(tenantMaxAge);
        overrides.forEach((tenant, age) -> {
            if (age.isNegative() || age.isZero()) {
                throw new IllegalArgumentException("omnirec.storage.retention.tenants." + tenant + " must be positive");
            }
        });
        this.tenantMaxAge = overrides;
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("omnirec.storage.retention.purge-interval must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("omnirec.storage.retention.purge-batch-size must be at least 1");
        }
        this.store = store;
        this.maxAge = maxAge;
        this.interval = interval;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return maxAge != null || !tenantMaxAge.isEmpty();
    }

    /** One purge pass: each tenant override, then the global max-age for everyone else. */
    public long purgeNow() {
        if (!isEnabled()) return 0;
        Instant now = clock.instant();
        long total = 0;
        for (Map.Entry<String, Duration> tenant : tenantMaxAge.entrySet()) {
            long deleted = store.deleteEventsOccurredBefore(now.minus(tenant.getValue()), batchSize, tenant.getKey(), List.of());
            if (deleted > 0) {
                log.info("Retention: deleted {} event(s) of tenant {} older than {}", deleted, tenant.getKey(), tenant.getValue());
            }
            total += deleted;
        }
        if (maxAge != null) {
            long deleted = store.deleteEventsOccurredBefore(now.minus(maxAge), batchSize, null, tenantMaxAge.keySet());
            if (deleted > 0) {
                log.info("Retention: deleted {} historical event(s) older than {}", deleted, maxAge);
            }
            total += deleted;
        }
        return total;
    }

    private void runSafely() {
        try {
            purgeNow();
        } catch (RuntimeException e) {
            // Keep the schedule alive; the next pass picks up where this one stopped.
            log.warn("Retention purge failed, will retry in {}: {}", interval, e.getMessage());
        }
    }

    @Override
    public synchronized void start() {
        if (executor != null || !isEnabled()) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "omnirec-storage-retention");
            thread.setDaemon(true);
            return thread;
        });
        // First pass soon after startup, not a full interval later.
        long initialDelay = Math.min(interval.toMillis(), Duration.ofMinutes(1).toMillis());
        executor.scheduleWithFixedDelay(this::runSafely, initialDelay, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Retention: events older than {} are purged every {}{}", maxAge, interval,
                tenantMaxAge.isEmpty() ? "" : " (per tenant: " + tenantMaxAge + ")");
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
