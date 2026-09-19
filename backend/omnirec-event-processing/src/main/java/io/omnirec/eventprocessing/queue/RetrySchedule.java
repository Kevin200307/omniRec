package io.omnirec.eventprocessing.queue;

import java.time.Duration;

/**
 * The delay before each retry attempt: exponential from {@code initial},
 * capped at {@code max}.
 *
 * It is shared between the topology (which gives each retry tier its
 * queue-level TTL) and the consumer (which picks the tier), so the two cannot
 * disagree about what "attempt 3" means.
 */
public record RetrySchedule(int maxRetries, Duration initial, Duration max) {

    public RetrySchedule {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        if (initial == null || initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial retry interval must be positive");
        }
        if (max == null || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max retry interval must be >= the initial interval");
        }
    }

    /** Delay before retry {@code attempt} (1-based): initial, 2x, 4x... capped at max. */
    public Duration delayFor(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt is 1-based");
        long multiplier = 1L << Math.min(attempt - 1, 30);
        long millis = initial.toMillis() * multiplier;
        return millis > max.toMillis() || millis < 0 ? max : Duration.ofMillis(millis);
    }
}
