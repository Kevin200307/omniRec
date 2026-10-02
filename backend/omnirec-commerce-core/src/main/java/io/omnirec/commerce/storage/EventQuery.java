// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.storage;

import io.omnirec.commerce.model.EventName;

import java.time.Instant;
import java.util.Set;

/**
 * Filters and position for one page of history.
 *
 * @param limit      page size, at least 1
 * @param after      continue after this position; {@code null} for the first page
 * @param from       inclusive lower bound on the event time, or {@code null}
 * @param to         exclusive upper bound on the event time, or {@code null}
 * @param eventTypes only these types; empty for all
 */
public record EventQuery(int limit, EventCursor after, Instant from, Instant to, Set<EventName> eventTypes) {

    public EventQuery {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
    }

    public static EventQuery firstPage(int limit) {
        return new EventQuery(limit, null, null, null, Set.of());
    }

    public EventQuery after(EventCursor cursor) {
        return new EventQuery(limit, cursor, from, to, eventTypes);
    }
}
