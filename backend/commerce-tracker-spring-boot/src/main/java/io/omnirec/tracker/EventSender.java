// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.model.CommerceEvent;

/**
 * Transport for backend events.
 *
 * Two implementations ship:
 * <ul>
 *   <li>{@link io.omnirec.tracker.transport.HttpEventSender} — POSTs to the
 *       Event API. The default, and what a merchant embedding this SDK uses.</li>
 *   <li>{@link io.omnirec.tracker.transport.InProcessEventSender} — calls the
 *       ingestion service directly, for when the Event API runs inside the same
 *       application and a network hop would be pointless.</li>
 * </ul>
 */
public interface EventSender {

    void send(CommerceEvent event);

    /** No-op for synchronous senders; meaningful once buffering is involved. */
    default void flush() {
    }
}
