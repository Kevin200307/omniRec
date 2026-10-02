// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.outbox;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.tracker.BatchDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed events from the outbox to the collector. Runs right after a
 * commit (triggered by {@link OutboxEventSender}) and on a fixed interval, so
 * rows left behind by a crash or a collector outage are picked up later.
 *
 * Each pass claims due rows inside its own transaction and attempts one
 * delivery: delivered rows are deleted, rejected rows are deleted and logged
 * (retrying the same bytes cannot help), and the rest are rescheduled with
 * exponential backoff.
 */
public class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final BatchDelivery delivery;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final Duration retryInitial;
    private final Duration retryMax;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    public OutboxRelay(OutboxStore store, BatchDelivery delivery, TransactionTemplate transactions, int batchSize,
                       Duration retryInitial, Duration retryMax, Clock clock) {
        this.store = store;
        this.delivery = delivery;
        this.transactions = transactions;
        this.batchSize = batchSize;
        this.retryInitial = retryInitial;
        this.retryMax = retryMax;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "omnirec-outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts the periodic pass. */
    public void start(Duration interval) {
        executor.scheduleWithFixedDelay(this::runSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Requests a pass soon, on the relay thread. Called after each commit that wrote events. */
    public void trigger() {
        if (!executor.isShutdown()) executor.execute(this::runSafely);
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed; rows stay for the next pass: {}", e.getMessage());
        }
    }

    /** Delivers everything due now. Returns the number of events delivered. */
    public int runOnce() {
        int delivered = 0;
        while (true) {
            Integer batch = transactions.execute(status -> deliverBatch());
            if (batch == null || batch < 0) return delivered;
            delivered += batch;
        }
    }

    /** @return delivered count, or -1 when nothing more is due */
    private int deliverBatch() {
        Instant now = clock.instant();
        List<OutboxStore.Row> rows = store.claimDue(now, batchSize);
        if (rows.isEmpty()) return -1;
        List<CommerceEvent> events = rows.stream().map(OutboxStore.Row::event).toList();
        BatchDelivery.Outcome outcome = delivery.deliverOnce(events);
        List<String> ids = rows.stream().map(OutboxStore.Row::eventId).toList();
        switch (outcome) {
            case DELIVERED -> {
                store.delete(ids);
                return rows.size();
            }
            case REJECTED -> {
                log.error("Collector rejected {} outbox event(s) permanently; removing them: {}", rows.size(), ids);
                store.delete(ids);
                return 0;
            }
            default -> {
                for (OutboxStore.Row row : rows) {
                    int attempts = row.attempts() + 1;
                    store.reschedule(row.eventId(), attempts, now.plus(backoff(attempts)));
                }
                // Stop this pass: the collector is unavailable, retry after the backoff.
                return -1;
            }
        }
    }

    Duration backoff(int attempts) {
        long millis = retryInitial.toMillis() * (1L << Math.min(attempts - 1, 20));
        return Duration.ofMillis(Math.min(millis, retryMax.toMillis()));
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
