// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.catalog;

import java.util.List;
import java.util.Map;

/**
 * An event as the catalog defines it.
 *
 * @param name     canonical wire name
 * @param domain   catalog domain, for example {@code cart}
 * @param version  per-event version, bumped on breaking changes
 * @param kind     {@code standard} for catalog events, {@code custom} for tracking-plan events
 * @param control  control events steer the pipeline and are never delivered to providers
 * @param sources  where the event is expected to come from
 * @param blocks   blocks whose fields the event carries
 * @param aliases  older names that resolve to this event
 * @param required dotted paths that must be present
 * @param fields   fields the event declares or refines, keyed by dotted path
 * @param example  a valid sample payload ({@code commerce}, {@code identity}, ...), or empty
 */
public record EventDefinition(
        String name,
        String domain,
        int version,
        String kind,
        boolean control,
        List<String> sources,
        List<String> blocks,
        List<String> aliases,
        boolean autocapture,
        List<String> required,
        Map<String, FieldDefinition> fields,
        Map<String, Object> example
) {
    public static final String KIND_STANDARD = "standard";
    public static final String KIND_CUSTOM = "custom";

    public EventDefinition {
        sources = sources == null ? List.of() : List.copyOf(sources);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        required = required == null ? List.of() : List.copyOf(required);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        example = example == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(example));
    }

    public boolean isCustom() {
        return KIND_CUSTOM.equals(kind);
    }
}
