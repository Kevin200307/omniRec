// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived;

import io.omnirec.commerce.model.CommerceEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** What a rule may do. Time comes from {@link #now()}, never the system clock, so rules are testable. */
public interface RuleContext {

    Instant now();

    /** Sends a derived event into the pipeline, to every destination that wants it. */
    void emit(CommerceEvent event);

    /** Schedules, or reschedules, the timer {@code key} of the calling rule. */
    void schedule(String key, Instant dueAt, Map<String, String> payload);

    /** Cancels the calling rule's timer {@code key}; nothing happens when there is none. */
    void cancel(String key);

    /** True the first time it is called for {@code key} within {@code ttl}; false after. */
    boolean firstTime(String key, Duration ttl);

    /** Adds one to a counter and returns the new value. */
    long increment(String key);

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    /**
     * A stable id for a derived event: {@code derived:<event>:<tenant>:<cause>}.
     * The same cause always gives the same id, so a repeat is a duplicate.
     */
    static String derivedEventId(String event, String tenantId, String cause) {
        return "derived:" + event + ":" + tenantId + ":" + cause;
    }
}
