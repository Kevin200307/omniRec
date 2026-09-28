// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EventCursorTest {

    @Test
    void roundTripsExactly() {
        EventCursor cursor = new EventCursor(Instant.parse("2026-09-28T08:15:20.123456Z"), "evt_1");

        assertEquals(cursor, EventCursor.decode(cursor.encode()));
    }

    /** eventIds are client-supplied, so they may contain the separator. */
    @Test
    void survivesAnEventIdContainingTheSeparator() {
        EventCursor cursor = new EventCursor(Instant.parse("2026-01-01T00:00:00Z"), "a|b|c");

        assertEquals(cursor, EventCursor.decode(cursor.encode()));
    }

    @Test
    void isOpaqueUrlSafeText() {
        String token = new EventCursor(Instant.parse("2026-01-01T00:00:00Z"), "evt/+?=").encode();

        assertTrue(token.matches("[A-Za-z0-9_-]+"), "a cursor must be usable in a query string unescaped");
    }

    @Test
    void rejectsATokenItDidNotProduce() {
        assertThrows(IllegalArgumentException.class, () -> EventCursor.decode(""));
        assertThrows(IllegalArgumentException.class, () -> EventCursor.decode("not base64!"));
        for (String raw : new String[]{"2|1|0|evt", "1|x|0|evt", "1|1|0|", "1|1"}) {
            String token = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> EventCursor.decode(token), raw);
        }
    }

    @Test
    void aQueryRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> EventQuery.firstPage(0));
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new EventQuery(10, null, t, t, Set.of()));
    }
}
