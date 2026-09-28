// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.storage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * A position in newest-first history: "everything strictly older than this".
 *
 * History is ordered by {@code (occurredAt DESC, eventId DESC)}. The eventId
 * is the tie-breaker that makes the order total — many events share a
 * timestamp (a batch flushed at once, a server clock with millisecond
 * resolution), and a cursor on time alone would skip or repeat them at a page
 * boundary. Keyset rather than OFFSET, so page 1000 costs what page 1 does and
 * events arriving meanwhile don't shift the pages.
 *
 * On the wire it is an opaque token. Clients must not build or parse it; the
 * encoding is versioned so it can change.
 */
public record EventCursor(Instant occurredAt, String eventId) {

    private static final String VERSION = "1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public EventCursor {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventId, "eventId");
    }

    public String encode() {
        String raw = VERSION + "|" + occurredAt.getEpochSecond() + "|" + occurredAt.getNano() + "|" + eventId;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException if the token was not produced by {@link #encode()} */
    public static EventCursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("empty cursor");
        }
        try {
            String raw = new String(DECODER.decode(token), StandardCharsets.UTF_8);
            // The eventId is last and may itself contain '|', so split at most 4 ways.
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || !VERSION.equals(parts[0]) || parts[3].isEmpty()) {
                throw new IllegalArgumentException("malformed cursor");
            }
            Instant occurredAt = Instant.ofEpochSecond(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
            return new EventCursor(occurredAt, parts[3]);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            // NumberFormatException is an IllegalArgumentException too.
            throw new IllegalArgumentException("malformed cursor", e);
        }
    }
}
