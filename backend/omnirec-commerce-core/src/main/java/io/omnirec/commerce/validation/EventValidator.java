// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.validation;

import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.FieldDefinition;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.validation.ValidationResult.ValidationError;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Server-side validation.
 *
 * Per-event rules come from the event catalog: each event's required fields and
 * the constraints it declares are read from its {@link EventDefinition}, so
 * adding an event to {@code catalog/} needs no code here. Rules shared by every
 * event (identity, timestamp, the sensitive-field backstop) stay in code.
 *
 * This runs even though the SDK already validated, because a client-side check
 * is a developer convenience, not a guarantee: anyone can POST to the Event API
 * directly. The SDK's copy exists so mistakes surface in the console during
 * development; this copy exists because it is the actual boundary.
 *
 * Only constraints an event declares or refines are enforced for it. A block
 * constraint such as the currency pattern is therefore checked on
 * {@code purchase_completed}, which refines currency, but not on events that
 * merely carry the field. That matches the v1 behaviour exactly.
 */
public class EventValidator {

    /**
     * Field names that must never appear in an event, at any depth. Matching is
     * on a normalised name (lowercased, non-alphanumerics stripped), so
     * {@code card_number}, {@code cardNumber} and {@code CardNumber} all match.
     *
     * This is a hard rejection, not a redaction: quietly stripping the field
     * would leave the merchant believing the data was accepted, and they'd
     * never fix the call site.
     */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "cardnumber", "cardno", "pan", "cvv", "cvc", "cvv2",
            "securitycode", "cardsecuritycode", "expirymonth", "expiryyear", "cardexpiry",
            "password", "passwd", "pin", "ssn", "socialsecuritynumber",
            "accesstoken", "refreshtoken", "apikey", "apisecret", "secretkey",
            "privatekey", "authorization", "creditcard", "iban"
    );

    /** Friendlier wording for well-known patterns. Anything else gets a generic message. */
    private static final Map<String, String> PATTERN_MESSAGES = Map.of(
            "^[A-Z]{3}$", "must be a 3-letter ISO 4217 code");

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final int MAX_WALK_DEPTH = 12;

    private final EventRegistry registry;
    private final ValidationMode mode;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    /** Validates against the standard catalog and rejects unknown events. */
    public EventValidator() {
        this(EventRegistry.standard(), ValidationMode.STRICT);
    }

    public EventValidator(EventRegistry registry, ValidationMode mode) {
        this.registry = registry;
        this.mode = mode;
    }

    public EventRegistry registry() {
        return registry;
    }

    public ValidationMode mode() {
        return mode;
    }

    public ValidationResult validate(CommerceEvent event) {
        List<ValidationError> errors = new ArrayList<>();

        validateUniversal(event, errors);
        Optional<EventDefinition> definition = event.eventType() == null
                ? Optional.empty()
                : registry.find(event.eventType().wireName());
        boolean unplanned = false;
        if (definition.isPresent()) {
            validateAgainst(definition.get(), event, errors);
        } else if (event.eventType() != null) {
            if (mode == ValidationMode.STRICT) {
                errors.add(new ValidationError("eventType",
                        "unknown event type \"" + event.eventType().wireName() + "\""));
            } else {
                unplanned = true;
            }
        }
        assertNoSensitiveFields(event, errors);

        if (!errors.isEmpty()) return ValidationResult.invalid(errors);
        return unplanned ? ValidationResult.okUnplanned() : ValidationResult.ok();
    }

    private void validateUniversal(CommerceEvent event, List<ValidationError> errors) {
        if (isBlank(event.eventId())) {
            errors.add(new ValidationError("eventId", "eventId is required"));
        }
        if (event.eventType() == null) {
            errors.add(new ValidationError("eventType", "eventType is required"));
        }
        if (isBlank(event.schemaVersion())) {
            errors.add(new ValidationError("schemaVersion", "schemaVersion is required"));
        }
        if (event.timestamp() == null) {
            errors.add(new ValidationError("timestamp", "timestamp must be a valid ISO-8601 date-time"));
        }
        if (event.identity() == null || isBlank(event.identity().anonymousId())) {
            errors.add(new ValidationError("identity.anonymousId", "anonymousId is required"));
        }
        if (event.identity() == null || isBlank(event.identity().sessionId())) {
            errors.add(new ValidationError("identity.sessionId", "sessionId is required"));
        }
    }

    // --- catalog-driven rules ------------------------------------------------

    private void validateAgainst(EventDefinition definition, CommerceEvent event, List<ValidationError> errors) {
        // Catalog paths are relative to data (product.id), except the envelope
        // paths identity.*, context.* and properties.*.
        Map<String, Object> root = new LinkedHashMap<>(event.data().asMap());
        root.put("identity", event.identity());
        root.put("context", event.context());
        root.put("properties", event.properties());

        for (Map.Entry<String, FieldDefinition> entry : definition.fields().entrySet()) {
            String path = entry.getKey();
            FieldDefinition field = entry.getValue();
            Object value = valueAt(root, path);
            if (isMissing(value)) {
                if (field.required()) errors.add(new ValidationError(wirePath(path), requiredMessage(path, field)));
                continue;
            }
            checkValue(wirePath(path), value, field, errors);
        }
        // A required path the definition lists but does not describe (defensive:
        // the generator always emits both, but a hand-built registry might not).
        for (String path : definition.required()) {
            if (!definition.fields().containsKey(path) && isMissing(valueAt(root, path))) {
                errors.add(new ValidationError(wirePath(path), leaf(path) + " is required"));
            }
        }
    }

    private void checkValue(String path, Object value, FieldDefinition field, List<ValidationError> errors) {
        String leaf = leaf(path);
        if (value instanceof Number || value instanceof CharSequence && isNumeric(field)) {
            BigDecimal number = toDecimal(value);
            if (number == null) {
                errors.add(new ValidationError(path, leaf + " must be a number"));
                return;
            }
            if (field.minimum() != null && number.compareTo(field.minimum()) < 0) {
                errors.add(new ValidationError(path, minimumMessage(leaf, field)));
            }
            if (field.maximum() != null && number.compareTo(field.maximum()) > 0) {
                errors.add(new ValidationError(path, leaf + " must be at most " + field.maximum().toPlainString()));
            }
            return;
        }
        if (value instanceof CharSequence text) {
            String string = text.toString();
            if (field.maxLength() != null && string.length() > field.maxLength()) {
                errors.add(new ValidationError(path, leaf + " must be at most " + field.maxLength() + " characters"));
            }
            if (field.pattern() != null && !pattern(field.pattern()).matcher(string).matches()) {
                errors.add(new ValidationError(path,
                        leaf + " " + PATTERN_MESSAGES.getOrDefault(field.pattern(), "has an invalid format")));
            }
            if (field.vocabulary() != null) {
                List<String> allowed = registry.vocabularies().getOrDefault(field.vocabulary(), List.of());
                if (!allowed.contains(string)) {
                    errors.add(new ValidationError(path, leaf + " must be one of " + String.join(", ", allowed)));
                }
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (field.minItems() != null && collection.size() < field.minItems()) {
                errors.add(new ValidationError(path, leaf + " must have at least " + field.minItems() + " item(s)"));
            }
            if (field.items() != null) {
                int index = 0;
                for (Object element : collection) {
                    checkElement(path + "[" + index++ + "]", element, field.items(), errors);
                }
            }
            return;
        }
        if (!field.fields().isEmpty()) {
            checkObject(path, value, field, errors);
        }
    }

    private void checkElement(String path, Object element, FieldDefinition spec, List<ValidationError> errors) {
        if (element == null) {
            if (!spec.fields().isEmpty()) {
                // A null line in an order is missing every required field.
                spec.fields().forEach((name, child) -> {
                    if (child.required()) {
                        errors.add(new ValidationError(path + "." + name, requiredMessage(name, child)));
                    }
                });
            }
            return;
        }
        checkValue(path, element, spec, errors);
    }

    private void checkObject(String path, Object value, FieldDefinition field, List<ValidationError> errors) {
        for (Map.Entry<String, FieldDefinition> child : field.fields().entrySet()) {
            String childPath = path + "." + child.getKey();
            Object childValue = property(value, child.getKey());
            if (isMissing(childValue)) {
                if (child.getValue().required()) {
                    errors.add(new ValidationError(childPath, requiredMessage(childPath, child.getValue())));
                }
                continue;
            }
            checkValue(childPath, childValue, child.getValue(), errors);
        }
    }

    /** Where the field sits in the v2 wire format, as reported to the client. */
    static String wirePath(String catalogPath) {
        return catalogPath.startsWith("identity.") || catalogPath.startsWith("context.")
                || catalogPath.startsWith("properties.") ? catalogPath : "data." + catalogPath;
    }

    private static String requiredMessage(String path, FieldDefinition field) {
        String leaf = leaf(path);
        if (path.endsWith("identity.userId")) return "userId is required for this event type";
        if (field.isArray()) return leaf + " is required and must be non-empty";
        return leaf + " is required";
    }

    private static String minimumMessage(String leaf, FieldDefinition field) {
        BigDecimal min = field.minimum();
        if (min.signum() == 0) return leaf + " must be a non-negative number";
        if ("integer".equals(field.type()) && min.compareTo(BigDecimal.ONE) == 0) return leaf + " must be greater than 0";
        return leaf + " must be at least " + min.toPlainString();
    }

    private Pattern pattern(String regex) {
        return patterns.computeIfAbsent(regex, Pattern::compile);
    }

    private static boolean isNumeric(FieldDefinition field) {
        return "integer".equals(field.type()) || "number".equals(field.type()) || "money".equals(field.type());
    }

    private static BigDecimal toDecimal(Object value) {
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- path resolution -----------------------------------------------------

    /** Resolves a dotted path through maps and records. */
    static Object valueAt(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            current = property(current, part);
        }
        return current;
    }

    private static final Map<Class<?>, Map<String, RecordComponent>> COMPONENTS = new ConcurrentHashMap<>();

    static Object property(Object target, String name) {
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (target instanceof io.omnirec.commerce.model.EventData data) {
            return data.asMap().get(name);
        }
        if (target instanceof Record) {
            RecordComponent component = COMPONENTS
                    .computeIfAbsent(target.getClass(), EventValidator::componentsOf)
                    .get(name);
            if (component == null) return null;
            try {
                return component.getAccessor().invoke(target);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read " + name + " from " + target.getClass().getSimpleName(), e);
            }
        }
        return null;
    }

    private static Map<String, RecordComponent> componentsOf(Class<?> type) {
        Map<String, RecordComponent> byName = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            byName.put(component.getName(), component);
        }
        return byName;
    }

    private static boolean isMissing(Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence text) return text.toString().isBlank();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        return false;
    }

    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        String tail = dot < 0 ? path : path.substring(dot + 1);
        int bracket = tail.indexOf('[');
        return bracket < 0 ? tail : tail.substring(0, bracket);
    }

    // --- sensitive-data backstop -----------------------------------------

    private void assertNoSensitiveFields(CommerceEvent event, List<ValidationError> errors) {
        // Identity-based visited set: a merchant's payload can contain equal-but-
        // distinct maps legitimately, and equals() would wrongly prune those.
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        walk(event.properties(), "properties", 0, visited, errors);
    }

    private void walk(Object value, String path, int depth, Set<Object> visited, List<ValidationError> errors) {
        if (value == null || depth > MAX_WALK_DEPTH) return;
        if (value instanceof Map<?, ?> map) {
            if (!visited.add(map)) return;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (FORBIDDEN_FIELDS.contains(normalise(key))) {
                    errors.add(new ValidationError(childPath,
                            "\"" + key + "\" looks like sensitive data and must never be tracked"));
                }
                walk(entry.getValue(), childPath, depth + 1, visited, errors);
            }
        } else if (value instanceof Collection<?> collection) {
            if (!visited.add(collection)) return;
            int index = 0;
            for (Object entry : collection) {
                walk(entry, path + "[" + index++ + "]", depth + 1, visited, errors);
            }
        }
    }

    private static String normalise(String key) {
        return NON_ALPHANUMERIC.matcher(key.toLowerCase()).replaceAll("");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static Set<String> sensitiveFieldNames() {
        return FORBIDDEN_FIELDS;
    }
}
