// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.destination;

import io.omnirec.commerce.model.CommerceEvent;

import java.util.List;

/**
 * Where a canonical event goes. The single extension point of the whole system:
 * adding Azure means writing one implementation of this interface and
 * registering it as a bean. Nothing in the core, the API, or either SDK changes.
 *
 * Implementations must:
 * <ul>
 *   <li>be <strong>idempotent</strong> — the dispatcher deduplicates, but
 *       at-least-once delivery means an implementation may still see a repeat;</li>
 *   <li><strong>throw</strong> on a transient failure, so the consumer can retry
 *       and eventually dead-letter, rather than swallowing it and silently
 *       losing the event;</li>
 *   <li>return normally for a permanent rejection they've decided not to retry,
 *       having logged why — retrying "this event type isn't supported" forever
 *       helps nobody;</li>
 *   <li>never mutate the event. It is shared, concurrently, with every other
 *       destination.</li>
 * </ul>
 */
public interface EventDestination {

    /** Stable id used in configuration, logs, and metrics — e.g. "amazon-personalize". */
    String id();

    /**
     * Delivers one event.
     *
     * @throws DestinationException when delivery failed in a way worth retrying
     */
    void send(CommerceEvent event);

    /**
     * Delivers a batch. The default sends one at a time; override where the
     * provider has a real batch API, since one call of 100 beats 100 calls.
     */
    default void sendBatch(List<CommerceEvent> events) {
        for (CommerceEvent event : events) {
            send(event);
        }
    }

    /**
     * Whether this destination wants the event at all. Lets a destination skip
     * types its provider has no concept of, before any mapping work happens.
     */
    default boolean supports(CommerceEvent event) {
        return !event.eventType().isControlEvent();
    }

    /**
     * Whether this destination wants events that are in neither the catalog nor
     * the tenant's tracking plan (accepted in permissive mode and flagged
     * {@code unplanned}). Off by default, so a typo in an event name never
     * reaches a recommendation model. Storage turns it on, so unplanned events
     * can be found and added to the plan.
     */
    default boolean acceptsUnplanned() {
        return false;
    }
}
