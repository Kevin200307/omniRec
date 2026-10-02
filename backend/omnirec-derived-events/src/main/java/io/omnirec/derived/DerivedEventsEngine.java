// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.derived.store.DerivedStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs the derived-event rules as a pipeline destination: it receives events
 * from its own queue (with the usual retry tiers and dead-letter queue), hands
 * each to the rules that subscribe to it, and fires due timers when
 * {@link #fireDue()} is called by the poller.
 *
 * Derived events go back into the pipeline through the emitter and reach every
 * destination that wants them. The engine ignores events whose source is
 * {@code derived}, so a rule can never feed itself.
 */
public class DerivedEventsEngine implements EventDestination {

    public static final String ID = "derived-events";

    private static final Logger log = LoggerFactory.getLogger(DerivedEventsEngine.class);

    /** A timer whose rule throws is retried this many times, a minute apart, then dropped. */
    static final int MAX_TIMER_ATTEMPTS = 5;
    static final Duration TIMER_RETRY_DELAY = Duration.ofMinutes(1);
    private static final String ATTEMPT = "_attempt";

    private final Map<String, List<DerivedEventRule>> bySubscription = new HashMap<>();
    private final Map<String, DerivedEventRule> byName = new LinkedHashMap<>();
    private final DerivedStateStore store;
    private final Consumer<CommerceEvent> emitter;
    private final Clock clock;

    public DerivedEventsEngine(List<DerivedEventRule> rules, DerivedStateStore store, Consumer<CommerceEvent> emitter,
                               Clock clock) {
        for (DerivedEventRule rule : rules) {
            if (byName.put(rule.name(), rule) != null) {
                throw new IllegalStateException("Two derived-event rules are named '" + rule.name() + "'");
            }
            for (String event : rule.subscribesTo()) {
                bySubscription.computeIfAbsent(event, e -> new ArrayList<>()).add(rule);
            }
        }
        this.store = store;
        this.emitter = emitter;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(CommerceEvent event) {
        return event.source() != EventSource.DERIVED
                && bySubscription.containsKey(event.eventType().wireName());
    }

    @Override
    public void send(CommerceEvent event) {
        for (DerivedEventRule rule : bySubscription.getOrDefault(event.eventType().wireName(), List.of())) {
            rule.onEvent(event, contextFor(rule));
        }
    }

    /** Fires every due timer, in batches. Returns how many fired. */
    public int fireDue() {
        return fireDue(100);
    }

    public int fireDue(int batchSize) {
        int fired = 0;
        while (true) {
            List<Timer> due = store.claimDue(clock.instant(), batchSize);
            for (Timer timer : due) {
                fire(timer);
                fired++;
            }
            if (due.size() < batchSize) return fired;
        }
    }

    private void fire(Timer timer) {
        DerivedEventRule rule = byName.get(timer.rule());
        if (rule == null) {
            log.warn("Dropping timer {}: no rule named '{}' is active", timer.id(), timer.rule());
            return;
        }
        try {
            rule.onTimer(timer, contextFor(rule));
        } catch (RuntimeException e) {
            int attempt = Integer.parseInt(timer.payload().getOrDefault(ATTEMPT, "0")) + 1;
            if (attempt >= MAX_TIMER_ATTEMPTS) {
                log.error("Rule {} failed timer {} {} times; dropping it", rule.name(), timer.key(), attempt, e);
                return;
            }
            log.warn("Rule {} failed timer {} (attempt {}); retrying in {}", rule.name(), timer.key(), attempt,
                    TIMER_RETRY_DELAY, e);
            Map<String, String> payload = new HashMap<>(timer.payload());
            payload.put(ATTEMPT, String.valueOf(attempt));
            store.schedule(new Timer(timer.rule(), timer.key(), clock.instant().plus(TIMER_RETRY_DELAY), payload));
        }
    }

    /** The active rules, for logs and tests. */
    public List<String> ruleNames() {
        return List.copyOf(byName.keySet());
    }

    private RuleContext contextFor(DerivedEventRule rule) {
        String ns = rule.name() + ":";
        return new RuleContext() {
            @Override public Instant now() { return clock.instant(); }

            @Override public void emit(CommerceEvent event) { emitter.accept(event); }

            @Override
            public void schedule(String key, Instant dueAt, Map<String, String> payload) {
                store.schedule(new Timer(rule.name(), key, dueAt, payload));
            }

            @Override public void cancel(String key) { store.cancel(rule.name() + ":" + key); }

            @Override public boolean firstTime(String key, Duration ttl) { return store.setIfAbsent(ns + key, ttl); }

            @Override public long increment(String key) { return store.increment(ns + key); }

            @Override public Optional<String> get(String key) { return store.get(ns + key); }

            @Override public void put(String key, String value, Duration ttl) { store.put(ns + key, value, ttl); }
        };
    }
}
