// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Which kind of producer sent an event. Matches the catalog's {@code sources} values. */
public enum EventSource {
    BROWSER, SERVER, WEBHOOK, DERIVED, IMPORT;

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unknown values map to {@code null} rather than failing the batch; the collector infers a source instead. */
    @JsonCreator
    public static EventSource fromWireName(String value) {
        if (value == null) return null;
        for (EventSource source : values()) {
            if (source.wireName().equals(value)) return source;
        }
        return null;
    }
}
