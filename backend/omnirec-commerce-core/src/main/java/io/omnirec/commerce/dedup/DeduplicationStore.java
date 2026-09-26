// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.dedup;

import java.time.Duration;

/**
 * Idempotency for the pipeline.
 *
 * Duplicates are routine, not exceptional: the SDK retries after a timeout
 * without knowing whether the first attempt landed, confirmation pages get
 * reloaded, and RabbitMQ is at-least-once, so a consumer that dies after
 * sending but before acking sees the message again. Delivering a duplicate
 * {@code purchase_completed} teaches a recommender an order happened twice.
 *
 * <h2>Lease, then complete</h2>
 *
 * A naive "claim the key for 24 hours, then do the work" has a crash window:
 * if the process dies after claiming and before the work lands, the key says
 * "done" for a day and every redelivery is dropped as a duplicate. The event is
 * silently lost. So a claim is a short <em>lease</em>:
 *
 * <pre>
 *   claim(key, lease)     -> CLAIMED            do the work, then complete(key, window)
 *                                               or, if the work failed, release(key)
 *                         -> ALREADY_COMPLETED  a genuine duplicate: skip it
 *                         -> IN_PROGRESS        someone else holds the lease (or crashed
 *                                               holding it): try again later; the lease
 *                                               expires, so a crashed holder can't block
 *                                               the event for longer than the lease
 * </pre>
 *
 * The claim must be atomic (a compare-and-set). A get-then-set lets two
 * consumers on different nodes both see "absent" and both deliver.
 */
public interface DeduplicationStore {

    enum ClaimResult {
        /** This caller holds the lease and should do the work. */
        CLAIMED,
        /** The work already completed; this is a duplicate. */
        ALREADY_COMPLETED,
        /** Another caller holds an unexpired lease; retry later. */
        IN_PROGRESS
    }

    /** Atomically takes a lease on {@code key} unless it is leased or completed. */
    ClaimResult claim(String key, Duration lease);

    /** Marks the work done, replacing the lease, and remembers it for {@code ttl}. */
    void complete(String key, Duration ttl);

    /** Gives up a lease after the work failed, so a retry can claim it immediately. */
    void release(String key);

    /** True once {@link #complete} has been called and has not expired. */
    boolean isCompleted(String key);

    /**
     * Claim-and-complete in one step, for work with no failure window between
     * the two (or for callers that genuinely want at-most-once).
     */
    default boolean markProcessed(String key, Duration ttl) {
        if (claim(key, ttl) != ClaimResult.CLAIMED) return false;
        complete(key, ttl);
        return true;
    }

    /** Alias kept for readability at call sites that only check. */
    default boolean isProcessed(String key) {
        return isCompleted(key);
    }

    /**
     * Builds the deduplication key. Scoped per tenant and per stage, so the same
     * event being deduplicated at ingestion doesn't prevent it being
     * deduplicated again per destination.
     */
    static String key(String stage, String tenantId, String eventId) {
        return "dedup:" + stage + ":" + (tenantId == null ? "_" : tenantId) + ":" + eventId;
    }
}
