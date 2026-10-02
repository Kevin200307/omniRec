// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small path language of webhook mappings: dot-separated names with
 * optional array indexes, {@code order.lines[0].sku}. An empty path or
 * {@code $} is the node itself. Deliberately not full JSONPath: mappings stay
 * readable, and there is nothing to evaluate.
 */
public final class JsonPaths {

    private static final Pattern SEGMENT = Pattern.compile("([^\\[\\]]*)((?:\\[\\d+])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");

    private JsonPaths() {
    }

    /** The node at {@code path}, or null when any step is missing. JSON null counts as missing. */
    public static JsonNode read(JsonNode root, String path) {
        if (root == null) return null;
        if (path == null || path.isBlank() || path.equals("$")) return root;
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            Matcher m = SEGMENT.matcher(segment);
            if (!m.matches()) return null;
            if (!m.group(1).isEmpty()) current = current.get(m.group(1));
            Matcher index = INDEX.matcher(m.group(2));
            while (current != null && index.find()) {
                current = current.get(Integer.parseInt(index.group(1)));
            }
            if (current == null || current.isNull() || current.isMissingNode()) return null;
        }
        return current;
    }

    /** The text at {@code path}, or null. Numbers are returned as their text. */
    public static String text(JsonNode root, String path) {
        JsonNode node = read(root, path);
        if (node == null || node.isContainerNode()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * A timestamp at {@code path}: ISO-8601 text, or epoch seconds, or epoch
     * milliseconds (values above 10^11 are taken as milliseconds). Null when
     * missing or unreadable.
     */
    public static Instant instant(JsonNode root, String path) {
        JsonNode node = read(root, path);
        if (node == null) return null;
        if (node.isNumber()) {
            long value = node.asLong();
            return value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(node.asText()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
