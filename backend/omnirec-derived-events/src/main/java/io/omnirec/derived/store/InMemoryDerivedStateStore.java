// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.store;

import io.omnirec.derived.Timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single-instance store. Timers and counters are lost on restart and not
 * shared between instances; use {@link RedisDerivedStateStore} for anything
 * that runs more than one instance or must survive a deploy.
 */
public class InMemoryDerivedStateStore implements DerivedStateStore {

    private record Value(String value, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<String, Timer> timers = new HashMap<>();
    private final Map<String, Value> values = new HashMap<>();
    private final Map<String, Long> counters = new HashMap<>();

    public InMemoryDerivedStateStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void schedule(Timer timer) {
        timers.put(timer.id(), timer);
    }

    @Override
    public synchronized void cancel(String timerId) {
        timers.remove(timerId);
    }

    @Override
    public synchronized List<Timer> claimDue(Instant now, int limit) {
        List<Timer> due = new ArrayList<>();
        timers.values().stream()
                .filter(t -> !t.dueAt().isAfter(now))
                .sorted(Comparator.comparing(Timer::dueAt))
                .limit(limit)
                .forEach(due::add);
        due.forEach(t -> timers.remove(t.id()));
        return due;
    }

    @Override
    public synchronized boolean setIfAbsent(String key, Duration ttl) {
        if (live(key) != null) return false;
        values.put(key, new Value("1", clock.instant().plus(ttl)));
        return true;
    }

    @Override
    public synchronized long increment(String key) {
        return counters.merge(key, 1L, Long::sum);
    }

    @Override
    public synchronized Optional<String> get(String key) {
        Value value = live(key);
        return value == null ? Optional.empty() : Optional.of(value.value());
    }

    @Override
    public synchronized void put(String key, String value, Duration ttl) {
        values.put(key, new Value(value, clock.instant().plus(ttl)));
    }

    /** Pending timers, for tests and diagnostics. */
    public synchronized int pendingTimers() {
        return timers.size();
    }

    private Value live(String key) {
        Value value = values.get(key);
        if (value != null && !value.expiresAt().isAfter(clock.instant())) {
            values.remove(key);
            return null;
        }
        return value;
    }
}
