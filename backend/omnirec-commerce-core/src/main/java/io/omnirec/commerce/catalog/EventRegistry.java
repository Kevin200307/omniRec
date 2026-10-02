// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.catalog.generated.StandardEvents;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The set of events the pipeline knows, loaded from the generated runtime
 * catalog ({@code omnirec/catalog.json}). Lookups accept a canonical name or an
 * alias.
 *
 * Immutable. {@link #withEvents} returns a new registry with extra events, which
 * is how a tenant's tracking plan is layered on top of the standard catalog.
 */
public final class EventRegistry {

    private static volatile EventRegistry standard;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> EXAMPLE_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() { };

    private final int catalogVersion;
    private final Map<String, EventDefinition> byName;
    private final Map<String, EventDefinition> byNameOrAlias;
    private final Map<String, List<String>> vocabularies;
    private final Map<String, Map<String, FieldDefinition>> blocks;

    private EventRegistry(int catalogVersion, Collection<EventDefinition> events, Map<String, List<String>> vocabularies,
                          Map<String, Map<String, FieldDefinition>> blocks) {
        this.catalogVersion = catalogVersion;
        Map<String, EventDefinition> names = new LinkedHashMap<>();
        Map<String, EventDefinition> lookup = new LinkedHashMap<>();
        for (EventDefinition event : events) {
            claim(lookup, event.name(), event);
            names.put(event.name(), event);
            for (String alias : event.aliases()) {
                claim(lookup, alias, event);
            }
        }
        this.byName = Collections.unmodifiableMap(names);
        this.byNameOrAlias = Collections.unmodifiableMap(lookup);
        this.vocabularies = Map.copyOf(vocabularies);
        this.blocks = Map.copyOf(blocks);
    }

    private static void claim(Map<String, EventDefinition> lookup, String name, EventDefinition owner) {
        EventDefinition existing = lookup.putIfAbsent(name, owner);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "event name \"" + name + "\" is defined by both " + existing.name() + " and " + owner.name());
        }
    }

    /** The standard catalog shipped in this jar. Loaded once. */
    public static EventRegistry standard() {
        EventRegistry local = standard;
        if (local == null) {
            synchronized (EventRegistry.class) {
                local = standard;
                if (local == null) {
                    standard = local = loadResource(StandardEvents.CATALOG_RESOURCE);
                }
            }
        }
        return local;
    }

    static EventRegistry loadResource(String resource) {
        try (InputStream in = EventRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath; run npm run catalog:generate");
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }

    /** Parses a runtime catalog document. */
    public static EventRegistry load(InputStream json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        List<EventDefinition> events = new ArrayList<>();
        for (JsonNode node : root.path("events")) {
            events.add(parseEvent(node));
        }
        Map<String, List<String>> vocabularies = new LinkedHashMap<>();
        root.path("vocabularies").fields().forEachRemaining(entry -> {
            List<String> values = new ArrayList<>();
            entry.getValue().forEach(v -> values.add(v.asText()));
            vocabularies.put(entry.getKey(), List.copyOf(values));
        });
        Map<String, Map<String, FieldDefinition>> blocks = new LinkedHashMap<>();
        root.path("blocks").fields().forEachRemaining(block -> {
            Map<String, FieldDefinition> fields = new LinkedHashMap<>();
            block.getValue().fields().forEachRemaining(f -> fields.put(f.getKey(), parseField(f.getValue())));
            blocks.put(block.getKey(), Map.copyOf(fields));
        });
        return new EventRegistry(root.path("catalogVersion").asInt(), events, vocabularies, blocks);
    }

    private static EventDefinition parseEvent(JsonNode node) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        node.path("fields").fields().forEachRemaining(e -> fields.put(e.getKey(), parseField(e.getValue())));
        return new EventDefinition(
                node.path("name").asText(),
                node.path("domain").asText(),
                node.path("version").asInt(1),
                node.path("kind").asText(EventDefinition.KIND_STANDARD),
                node.path("control").asBoolean(false),
                strings(node.path("sources")),
                strings(node.path("blocks")),
                strings(node.path("aliases")),
                node.path("autocapture").asBoolean(false),
                strings(node.path("required")),
                fields,
                node.has("example") ? MAPPER.convertValue(node.get("example"), EXAMPLE_TYPE) : Map.of());
    }

    /** Parses one field definition in the runtime-catalog JSON shape. Also used for tracking plans. */
    public static FieldDefinition parseField(JsonNode node) {
        Map<String, FieldDefinition> nested = new LinkedHashMap<>();
        node.path("fields").fields().forEachRemaining(e -> nested.put(e.getKey(), parseField(e.getValue())));
        return new FieldDefinition(
                node.path("type").asText(),
                node.path("required").asBoolean(false),
                decimal(node.get("minimum")),
                decimal(node.get("maximum")),
                node.hasNonNull("minItems") ? node.get("minItems").asInt() : null,
                node.hasNonNull("maxLength") ? node.get("maxLength").asInt() : null,
                node.hasNonNull("pattern") ? node.get("pattern").asText() : null,
                node.hasNonNull("vocabulary") ? node.get("vocabulary").asText() : null,
                node.hasNonNull("items") ? parseField(node.get("items")) : null,
                nested);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    /** The definition for a canonical name or alias. */
    public Optional<EventDefinition> find(String nameOrAlias) {
        return Optional.ofNullable(nameOrAlias).map(byNameOrAlias::get);
    }

    public boolean isKnown(String nameOrAlias) {
        return nameOrAlias != null && byNameOrAlias.containsKey(nameOrAlias);
    }

    /** The canonical name for a name or alias; unknown names are returned unchanged. */
    public String canonicalName(String nameOrAlias) {
        return find(nameOrAlias).map(EventDefinition::name).orElse(nameOrAlias);
    }

    public boolean isControl(String nameOrAlias) {
        return find(nameOrAlias).map(EventDefinition::control).orElse(false);
    }

    /** Every event, in catalog order. */
    public Collection<EventDefinition> events() {
        return byName.values();
    }

    public int catalogVersion() {
        return catalogVersion;
    }

    /** Field definitions of every catalog block, keyed by block name, then field name. */
    public Map<String, Map<String, FieldDefinition>> blocks() {
        return blocks;
    }

    /** Allowed values per vocabulary name. */
    public Map<String, List<String>> vocabularies() {
        return vocabularies;
    }

    /**
     * A new registry with extra events added, for example a tenant's custom
     * events. Fails if any extra name or alias clashes with an existing one.
     */
    public EventRegistry withEvents(Collection<EventDefinition> extra, Map<String, List<String>> extraVocabularies) {
        List<EventDefinition> all = new ArrayList<>(byName.values());
        all.addAll(extra);
        Map<String, List<String>> vocabs = new LinkedHashMap<>(vocabularies);
        extraVocabularies.forEach((name, values) -> {
            if (vocabs.containsKey(name)) {
                throw new IllegalArgumentException("vocabulary \"" + name + "\" is already defined by the catalog");
            }
            vocabs.put(name, values);
        });
        return new EventRegistry(catalogVersion, all, vocabs, blocks);
    }
}
