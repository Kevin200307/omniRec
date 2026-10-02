// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived;

import java.time.Instant;
import java.util.Map;

/**
 * A scheduled callback to a rule.
 *
 * @param rule    the rule that scheduled it
 * @param key     unique per rule; scheduling the same key again replaces the timer
 * @param dueAt   when it fires
 * @param payload what the rule needs when it fires, for example the visitor and cart
 */
public record Timer(String rule, String key, Instant dueAt, Map<String, String> payload) {

    public Timer {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** The key under which the store keeps it, unique across rules. */
    public String id() {
        return rule + ":" + key;
    }

    public String get(String name) {
        return payload.get(name);
    }
}
