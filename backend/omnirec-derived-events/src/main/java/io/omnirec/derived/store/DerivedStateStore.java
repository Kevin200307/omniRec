// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.store;

import io.omnirec.derived.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Timers and small facts for derived-event rules. Implementations must make
 * {@link #claimDue} exclusive: with several instances polling, each timer is
 * handed to exactly one of them.
 */
public interface DerivedStateStore {

    /** Adds the timer, replacing any timer with the same {@link Timer#id()}. */
    void schedule(Timer timer);

    /** Removes the timer with this id. */
    void cancel(String timerId);

    /** Removes and returns up to {@code limit} timers due at {@code now}. A returned timer is no longer stored anywhere. */
    List<Timer> claimDue(Instant now, int limit);

    /** True if the key was absent (and is now set for {@code ttl}). */
    boolean setIfAbsent(String key, Duration ttl);

    long increment(String key);

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
