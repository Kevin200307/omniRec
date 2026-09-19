package io.omnirec.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.validation.EventValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canonical event is defined three times — once per language that has to
 * understand it:
 *
 * <ol>
 *   <li>{@code packages/commerce-web/src/events/types.ts} (the frontend SDK)</li>
 *   <li>{@code io.omnirec.commerce.model.EventType} (everything on the JVM)</li>
 *   <li>{@code schema/commerce-event.schema.json} (the documented wire contract)</li>
 * </ol>
 *
 * Three definitions is a deliberate trade: generating two from one would mean a
 * codegen step in every build, and the shape changes rarely. What is not
 * acceptable is letting them drift silently — a taxonomy entry added to the
 * frontend but missing from the Java enum means the API rejects an event the
 * SDK happily sends, and nobody finds out until production.
 *
 * So this test parses the other two definitions and compares them to the Java
 * one. It fails the build on drift, which is what makes three copies safe.
 */
class CanonicalSchemaContractTest {

    /** Walk up from the module directory to the repo root. */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("schema/commerce-event.schema.json"))) {
            current = current.getParent();
        }
        assertNotNull(current, "could not locate the repository root from " + Path.of("").toAbsolutePath());
        return current;
    }

    private static String read(String relativePath) throws IOException {
        Path path = repoRoot().resolve(relativePath);
        assertTrue(Files.exists(path), relativePath + " is missing — the contract has no second opinion to check against");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Set<String> javaEventTypes() {
        Set<String> names = new TreeSet<>();
        for (EventType type : EventType.values()) {
            names.add(type.wireName());
        }
        return names;
    }

    private static Set<String> schemaEventTypes() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(read("schema/commerce-event.schema.json"));
        JsonNode enumNode = schema.path("properties").path("eventType").path("enum");
        assertTrue(enumNode.isArray(), "schema is missing properties.eventType.enum");

        Set<String> names = new TreeSet<>();
        enumNode.forEach(node -> names.add(node.asText()));
        return names;
    }

    /**
     * Reads the EVENT_TYPES array from the TypeScript source. A regex rather
     * than a TS parser: the array is a flat list of string literals in a file we
     * control, and adding a JS toolchain to the Java build to read it would cost
     * far more than it saves.
     */
    private static Set<String> typescriptEventTypes() throws IOException {
        String source = read("packages/commerce-web/src/events/types.ts");

        Matcher block = Pattern
                .compile("EVENT_TYPES:\\s*readonly EventType\\[\\]\\s*=\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(source);
        assertTrue(block.find(), "could not find the EVENT_TYPES array in types.ts");

        Set<String> names = new TreeSet<>();
        Matcher literal = Pattern.compile("\"([a-z_]+)\"").matcher(block.group(1));
        while (literal.find()) {
            names.add(literal.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("the Java enum and the JSON schema define the same event taxonomy")
    void javaAndSchemaAgree() throws IOException {
        assertEquals(javaEventTypes(), schemaEventTypes(),
                "EventType and commerce-event.schema.json have drifted apart");
    }

    @Test
    @DisplayName("the TypeScript SDK and the Java enum define the same event taxonomy")
    void typescriptAndJavaAgree() throws IOException {
        Set<String> typescript = typescriptEventTypes();
        Set<String> java = javaEventTypes();

        Set<String> onlyInTypescript = new LinkedHashSet<>(typescript);
        onlyInTypescript.removeAll(java);
        Set<String> onlyInJava = new LinkedHashSet<>(java);
        onlyInJava.removeAll(typescript);

        assertTrue(onlyInTypescript.isEmpty(),
                "the frontend SDK can send event types the API would reject: " + onlyInTypescript);
        assertTrue(onlyInJava.isEmpty(),
                "the API accepts event types no frontend tracker produces: " + onlyInJava);
    }

    @Test
    @DisplayName("every taxonomy entry from the specification is present")
    void theFullTaxonomyIsImplemented() {
        // Spelled out rather than derived, so an accidental rename or deletion
        // has to be an explicit edit here too.
        List<String> required = List.of(
                "session_started", "session_ended", "page_viewed", "home_page_viewed",
                "search_performed", "search_result_clicked", "product_list_viewed", "category_viewed",
                "product_viewed", "product_clicked",
                "product_wishlisted", "product_shared", "product_compared",
                "product_review_viewed", "product_review_submitted",
                "cart_viewed", "product_added_to_cart", "product_removed_from_cart",
                "cart_quantity_updated", "cart_abandoned",
                "checkout_started", "shipping_information_added", "payment_information_added",
                "checkout_completed", "checkout_failed",
                "purchase_completed", "purchase_failed", "order_cancelled", "order_refunded",
                "recommendation_impression", "recommendation_clicked",
                "recommendation_added_to_cart", "recommendation_purchased",
                "user_registered", "user_logged_in", "user_logged_out", "user_profile_updated");

        Set<String> implemented = javaEventTypes();
        List<String> missing = new ArrayList<>();
        for (String eventType : required) {
            if (!implemented.contains(eventType)) missing.add(eventType);
        }

        assertTrue(missing.isEmpty(), "event types from the taxonomy are missing: " + missing);
    }

    @Test
    @DisplayName("both validators block the same sensitive field names")
    void sensitiveFieldListsAgree() throws IOException {
        String typescript = read("packages/commerce-web/src/validation/validator.ts");

        Matcher block = Pattern
                .compile("const FORBIDDEN_FIELDS = \\[(.*?)];", Pattern.DOTALL)
                .matcher(typescript);
        assertTrue(block.find(), "could not find FORBIDDEN_FIELDS in the TypeScript validator");

        Set<String> frontend = new TreeSet<>();
        Matcher literal = Pattern.compile("\"([a-z0-9]+)\"").matcher(block.group(1));
        while (literal.find()) {
            frontend.add(literal.group(1));
        }

        Set<String> backend = new TreeSet<>(EventValidator.sensitiveFieldNames());

        // A field blocked on one side but not the other is the dangerous case:
        // it reads as protected while a direct POST sails straight past.
        assertEquals(backend, frontend,
                "the frontend and backend sensitive-field lists have drifted apart");
    }

    /**
     * A commerce field added to one definition but not the others is silently
     * dropped somewhere: Jackson ignores it, or the schema rejects it. This is
     * how recommendationProvider would have gone missing from the schema.
     */
    @Test
    @DisplayName("TypeScript, Java and the schema define the same commerce fields")
    void commerceFieldsAgree() throws IOException {
        Set<String> java = new TreeSet<>();
        for (var component : io.omnirec.commerce.model.CommerceData.class.getRecordComponents()) {
            java.add(component.getName());
        }

        JsonNode schema = new ObjectMapper().readTree(read("schema/commerce-event.schema.json"));
        Set<String> schemaFields = new TreeSet<>();
        schema.path("properties").path("commerce").path("properties").fieldNames().forEachRemaining(schemaFields::add);

        Matcher block = Pattern.compile("export interface CommerceData \\{(.*?)\\n}", Pattern.DOTALL)
                .matcher(read("packages/commerce-web/src/events/types.ts"));
        assertTrue(block.find(), "could not find CommerceData in types.ts");
        Set<String> typescript = new TreeSet<>();
        Matcher field = Pattern.compile("^\\s*(\\w+)\\??:", Pattern.MULTILINE).matcher(block.group(1));
        while (field.find()) {
            typescript.add(field.group(1));
        }

        assertEquals(java, schemaFields, "CommerceData (Java) and the JSON schema have drifted apart");
        assertEquals(java, typescript, "CommerceData (Java) and types.ts have drifted apart");
    }

    @Test
    @DisplayName("the SDK and the API scrub the same URL parameters")
    void urlDenylistsAgree() throws IOException {
        Matcher block = Pattern.compile("const SENSITIVE_PARAMS = \\[(.*?)];", Pattern.DOTALL)
                .matcher(read("packages/commerce-web/src/context/sanitizeUrl.ts"));
        assertTrue(block.find(), "could not find SENSITIVE_PARAMS in sanitizeUrl.ts");
        Set<String> frontend = new TreeSet<>();
        Matcher literal = Pattern.compile("\"([a-z_-]+)\"").matcher(block.group(1));
        while (literal.find()) {
            frontend.add(literal.group(1));
        }

        assertEquals(new TreeSet<>(io.omnirec.eventapi.normalize.UrlSanitizer.SENSITIVE_PARAMS), frontend,
                "a parameter scrubbed on one side but not the other leaks through direct API calls");
    }

    @Test
    @DisplayName("identify is the only control event, and it is never delivered")
    void controlEventsAreMarkedAsSuch() {
        List<EventType> controlEvents = java.util.Arrays.stream(EventType.values())
                .filter(EventType::isControlEvent)
                .toList();

        assertEquals(List.of(EventType.IDENTIFY), controlEvents);
    }

    @Test
    @DisplayName("the schema declares the fields the pipeline depends on")
    void schemaDeclaresRequiredFields() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(read("schema/commerce-event.schema.json"));

        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));

        assertTrue(required.contains("eventId"), "deduplication depends on eventId");
        assertTrue(required.contains("eventType"), "routing depends on eventType");
        assertTrue(required.contains("identity"), "attribution depends on identity");

        Set<String> identityRequired = new LinkedHashSet<>();
        schema.path("properties").path("identity").path("required")
                .forEach(node -> identityRequired.add(node.asText()));

        assertTrue(identityRequired.contains("anonymousId"),
                "every event must be attributable to a device, even before login");
        assertTrue(identityRequired.contains("sessionId"));
    }
}
