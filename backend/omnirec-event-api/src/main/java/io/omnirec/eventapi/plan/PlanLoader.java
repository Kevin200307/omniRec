// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.FieldDefinition;
import io.omnirec.commerce.model.EventName;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads tracking plans: the custom events a store defines on top of the
 * standard catalog. Same event format as {@code catalog/events/*.yaml}, keyed
 * by event name:
 *
 * <pre>
 * vocabularies:
 *   variant: [a, b]
 * events:
 *   action_x_clicked:
 *     description: "Shopper clicked the X button."
 *     sources: [browser]
 *     blocks: [product]
 *     properties:
 *       variant: { type: enum, vocabulary: variant, required: true }   # inline field in data
 *       product.id: { required: true }                                 # refines a catalog block
 * </pre>
 *
 * Every problem in every file is reported at once, with the file it came from.
 */
public class PlanLoader {

    private static final Pattern NAME = EventName.PATTERN;
    private static final Pattern FIELD_NAME = Pattern.compile("^[a-z][A-Za-z0-9]*$");
    private static final Set<String> TYPES = Set.of(
            "string", "integer", "number", "boolean", "timestamp", "money", "enum", "array", "object");
    private static final Set<String> EVENT_KEYS = Set.of(
            "domain", "version", "sources", "description", "blocks", "properties", "aliases", "example");
    private static final Set<String> FIELD_KEYS = Set.of(
            "type", "description", "vocabulary", "items", "fields", "required", "minimum", "maximum",
            "minItems", "maxLength", "pattern");
    private static final Set<String> CONSTRAINT_KEYS = Set.of(
            "required", "minimum", "maximum", "minItems", "maxLength", "pattern", "description");
    private static final Set<String> RESERVED = Set.of("identity", "context", "properties");
    private static final Set<String> SOURCES = Set.of("browser", "server", "webhook", "derived", "import");

    private final YAMLMapper yaml = new YAMLMapper();
    private final ResourceLoader resources;

    public PlanLoader() {
        this(new DefaultResourceLoader());
    }

    public PlanLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    /** The custom events and vocabularies a set of plan files adds. */
    public record Plan(List<EventDefinition> events, Map<String, List<String>> vocabularies) {
        public static final Plan EMPTY = new Plan(List.of(), Map.of());
    }

    /**
     * Loads the plans and returns the standard registry extended with them.
     *
     * @throws PlanException listing every problem found
     */
    public EventRegistry extend(EventRegistry standard, List<String> locations) {
        Plan plan = load(standard, locations);
        try {
            return standard.withEvents(plan.events(), plan.vocabularies());
        } catch (IllegalArgumentException e) {
            throw new PlanException(List.of(String.join(", ", locations) + ": " + e.getMessage()));
        }
    }

    public Plan load(EventRegistry standard, List<String> locations) {
        if (locations == null || locations.isEmpty()) return Plan.EMPTY;
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> vocabularies = new LinkedHashMap<>();
        List<JsonNode> documents = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        for (String location : locations) {
            JsonNode document = read(location, problems);
            if (document == null) continue;
            documents.add(document);
            sources.add(location);
            readVocabularies(location, document.path("vocabularies"), standard, vocabularies, problems);
        }

        List<EventDefinition> events = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < documents.size(); i++) {
            String location = sources.get(i);
            JsonNode document = documents.get(i);
            for (Iterator<String> it = document.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!key.equals("events") && !key.equals("vocabularies")) {
                    problems.add(location + ": unknown top-level key \"" + key + "\" (expected events, vocabularies)");
                }
            }
            document.path("events").fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                String where = location + ": event " + name;
                if (!NAME.matcher(name).matches()) {
                    problems.add(where + ": name must match " + NAME.pattern());
                    return;
                }
                if (!seen.add(name)) {
                    problems.add(where + ": defined more than once");
                    return;
                }
                if (standard.isKnown(name)) {
                    problems.add(where + ": is a standard catalog event; custom events need their own name");
                    return;
                }
                EventDefinition event = readEvent(name, entry.getValue(), where, standard, vocabularies, problems);
                if (event != null) events.add(event);
            });
        }

        if (!problems.isEmpty()) throw new PlanException(problems);
        return new Plan(List.copyOf(events), Map.copyOf(vocabularies));
    }

    private JsonNode read(String location, List<String> problems) {
        Resource resource = resources.getResource(location);
        if (!resource.exists()) {
            problems.add(location + ": file not found");
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode node = yaml.readTree(in);
            if (node == null || node.isMissingNode() || node.isNull()) return yaml.createObjectNode();
            if (!node.isObject()) {
                problems.add(location + ": a plan must be a YAML mapping");
                return null;
            }
            return node;
        } catch (IOException e) {
            // Jackson's YAML errors carry the line and column.
            problems.add(location + ": " + e.getMessage().split("\n")[0]
                    + (e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe && jpe.getLocation() != null
                    ? " (line " + jpe.getLocation().getLineNr() + ", column " + jpe.getLocation().getColumnNr() + ")"
                    : ""));
            return null;
        }
    }

    private void readVocabularies(String location, JsonNode node, EventRegistry standard,
                                  Map<String, List<String>> out, List<String> problems) {
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            String where = location + ": vocabulary " + name;
            if (!NAME.matcher(name).matches()) {
                problems.add(where + ": name must match " + NAME.pattern());
                return;
            }
            if (standard.vocabularies().containsKey(name) || out.containsKey(name)) {
                problems.add(where + ": already defined");
                return;
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : entry.getValue()) {
                values.add(value.isObject() ? value.path("value").asText() : value.asText());
            }
            if (values.isEmpty()) problems.add(where + ": needs at least one value");
            out.put(name, List.copyOf(values));
        });
    }

    private EventDefinition readEvent(String name, JsonNode node, String where, EventRegistry standard,
                                      Map<String, List<String>> planVocabularies, List<String> problems) {
        int before = problems.size();
        if (!node.isObject()) {
            problems.add(where + ": must be a mapping");
            return null;
        }
        node.fieldNames().forEachRemaining(key -> {
            if (!EVENT_KEYS.contains(key)) problems.add(where + ": unknown key \"" + key + "\"");
        });

        String domain = node.path("domain").asText("custom");
        if (!NAME.matcher(domain).matches()) problems.add(where + ": domain must match " + NAME.pattern());

        List<String> sources = strings(node.path("sources"));
        if (sources.isEmpty()) sources = List.of("browser", "server");
        for (String source : sources) {
            if (!SOURCES.contains(source)) problems.add(where + ": unknown source \"" + source + "\"");
        }

        List<String> blocks = strings(node.path("blocks"));
        for (String block : blocks) {
            if (!standard.blocks().containsKey(block)) problems.add(where + ": block \"" + block + "\" does not exist");
        }

        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        node.path("properties").fields().forEachRemaining(entry -> {
            String path = entry.getKey();
            JsonNode spec = entry.getValue();
            String at = where + ": property " + path;
            spec.fieldNames().forEachRemaining(key -> {
                if (!FIELD_KEYS.contains(key)) problems.add(at + ": unknown key \"" + key + "\"");
            });
            FieldDefinition field;
            if (path.contains(".")) {
                FieldDefinition base = refinedBase(path, blocks, standard, at, problems);
                if (base == null) return;
                spec.fieldNames().forEachRemaining(key -> {
                    if (!CONSTRAINT_KEYS.contains(key)) {
                        problems.add(at + ": refines an existing field and may only set constraints, not " + key);
                    }
                });
                field = merge(base, spec);
            } else {
                if (!FIELD_NAME.matcher(path).matches()) {
                    problems.add(at + ": field names are lowerCamelCase");
                    return;
                }
                if (RESERVED.contains(path) || blocks.contains(path) || standard.blocks().containsKey(path)) {
                    problems.add(at + ": \"" + path + "\" is reserved for a block or the envelope");
                    return;
                }
                if (!spec.hasNonNull("type")) {
                    problems.add(at + ": inline fields need a type");
                    return;
                }
                field = EventRegistry.parseField(spec);
                checkField(field, at, standard, planVocabularies, problems);
            }
            constraintProblem(field, at, problems);
            fields.put(path, field);
            if (field.required()) required.add(path);
        });

        if (problems.size() > before) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> example = node.has("example") ? yaml.convertValue(node.get("example"), Map.class) : Map.of();
        return new EventDefinition(name, domain, node.path("version").asInt(1), EventDefinition.KIND_CUSTOM, false,
                sources, blocks, strings(node.path("aliases")), false, required, fields, example);
    }

    private static FieldDefinition refinedBase(String path, List<String> blocks, EventRegistry standard, String at,
                                               List<String> problems) {
        if (path.equals("identity.userId")) {
            return new FieldDefinition("string", false, null, null, null, null, null, null, null, Map.of());
        }
        String[] parts = path.split("\\.", 2);
        if (!blocks.contains(parts[0])) {
            problems.add(at + ": refines block \"" + parts[0] + "\", which this event does not list under blocks");
            return null;
        }
        FieldDefinition base = standard.blocks().getOrDefault(parts[0], Map.of()).get(parts[1]);
        if (base == null) problems.add(at + ": \"" + parts[1] + "\" is not a field of block \"" + parts[0] + "\"");
        return base;
    }

    private static FieldDefinition merge(FieldDefinition base, JsonNode spec) {
        return new FieldDefinition(
                base.type(),
                spec.path("required").asBoolean(base.required()),
                spec.hasNonNull("minimum") ? spec.get("minimum").decimalValue() : base.minimum(),
                spec.hasNonNull("maximum") ? spec.get("maximum").decimalValue() : base.maximum(),
                spec.hasNonNull("minItems") ? Integer.valueOf(spec.get("minItems").asInt()) : base.minItems(),
                spec.hasNonNull("maxLength") ? Integer.valueOf(spec.get("maxLength").asInt()) : base.maxLength(),
                spec.hasNonNull("pattern") ? spec.get("pattern").asText() : base.pattern(),
                base.vocabulary(), base.items(), base.fields());
    }

    private static void checkField(FieldDefinition field, String at, EventRegistry standard,
                                   Map<String, List<String>> planVocabularies, List<String> problems) {
        if (!TYPES.contains(field.type())) {
            problems.add(at + ": unknown type \"" + field.type() + "\"");
            return;
        }
        if ("enum".equals(field.type())) {
            String vocabulary = field.vocabulary();
            if (vocabulary == null) problems.add(at + ": enum fields need a vocabulary");
            else if (!planVocabularies.containsKey(vocabulary) && !standard.vocabularies().containsKey(vocabulary)) {
                problems.add(at + ": vocabulary \"" + vocabulary + "\" does not exist");
            }
        }
        if ("array".equals(field.type()) && field.items() == null) problems.add(at + ": arrays need items");
        if (field.items() != null) checkField(field.items(), at + "[]", standard, planVocabularies, problems);
        field.fields().forEach((name, child) -> checkField(child, at + "." + name, standard, planVocabularies, problems));
    }

    private static void constraintProblem(FieldDefinition field, String at, List<String> problems) {
        boolean numeric = Set.of("integer", "number", "money").contains(field.type());
        if ((field.minimum() != null || field.maximum() != null) && !numeric) {
            problems.add(at + ": minimum/maximum only apply to integer, number or money");
        }
        if (field.minimum() != null && field.maximum() != null && field.minimum().compareTo(field.maximum()) > 0) {
            problems.add(at + ": minimum is greater than maximum");
        }
        if (field.minItems() != null && !field.isArray()) problems.add(at + ": minItems only applies to arrays");
        if ((field.maxLength() != null || field.pattern() != null) && !"string".equals(field.type())) {
            problems.add(at + ": maxLength/pattern only apply to strings");
        }
        if (field.pattern() != null) {
            try {
                Pattern.compile(field.pattern());
            } catch (PatternSyntaxException e) {
                problems.add(at + ": pattern is not a valid regular expression");
            }
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) array.forEach(v -> out.add(v.asText()));
        return out;
    }
}
