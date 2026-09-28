// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.storage;

import io.omnirec.commerce.model.CommerceEvent;

import java.util.List;

/**
 * One page of a customer's history, newest first.
 *
 * @param nextCursor where the next page starts, or {@code null} if this is the last page
 */
public record CustomerEventPage(String customerId, List<CommerceEvent> events, EventCursor nextCursor) {

    public CustomerEventPage {
        events = List.copyOf(events);
    }

    public static CustomerEventPage empty(String customerId) {
        return new CustomerEventPage(customerId, List.of(), null);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }
}
