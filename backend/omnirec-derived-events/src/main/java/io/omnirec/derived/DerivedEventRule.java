// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived;

import io.omnirec.commerce.model.CommerceEvent;

import java.util.Set;

/**
 * Computes events from other events. Register an implementation as a bean and
 * the engine runs it next to the built-in rules.
 *
 * Rules are called with at-least-once delivery, like any destination, so
 * {@link #onEvent} must be idempotent: scheduling a timer under the same key
 * replaces it, and an emitted event should carry an id derived from what
 * caused it (see {@link RuleContext#derivedEventId}) so a repeat is dropped as
 * a duplicate downstream.
 */
public interface DerivedEventRule {

    /** Stable rule name, used to namespace timer and state keys. */
    String name();

    /** Canonical names of the events this rule wants. */
    Set<String> subscribesTo();

    /** An event the rule subscribes to. Throwing makes the pipeline retry it. */
    void onEvent(CommerceEvent event, RuleContext context);

    /** A timer this rule scheduled has come due. It fires once, on one instance. */
    default void onTimer(Timer timer, RuleContext context) {
    }
}
