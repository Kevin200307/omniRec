// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.omnirec.commerce.catalog.EventRegistry;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The name of an event, as it appears on the wire ({@code "product_viewed"}).
 *
 * This replaces the closed {@code EventType} enum. A name only has to be well
 * formed to exist; whether the pipeline knows it is a question for the
 * {@link EventRegistry}, answered by the validator. That is what lets tenants
 * send custom events from a tracking plan without a code change here.
 *
 * Serialises as the plain string.
 */
public record EventName(String value) {

    /** Same rule as the catalog: lowercase, starts with a letter, 3 to 64 characters. */
    public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    public EventName {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid event name: " + (value == null ? "null" : "\"" + value + "\""));
        }
    }

    @JsonCreator
    public static EventName of(String value) {
        return new EventName(value);
    }

    /** Parses a name, or empty when it is not well formed. */
    public static Optional<EventName> parse(String value) {
        return value != null && PATTERN.matcher(value).matches() ? Optional.of(new EventName(value)) : Optional.empty();
    }

    @JsonValue
    public String wireName() {
        return value;
    }

    public boolean is(String name) {
        return value.equals(name);
    }

    /** Control events steer the pipeline and are never delivered to providers. Only catalog events can be control events. */
    public boolean isControlEvent() {
        return EventRegistry.standard().isControl(value);
    }

    /** Catalog domain of a standard event, or empty for unknown and custom events. */
    public Optional<String> domain() {
        return EventRegistry.standard().find(value).map(d -> d.domain());
    }

    @Override
    public String toString() {
        return value;
    }
}
