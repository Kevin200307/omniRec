// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.CommerceEventFixtures;
import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.FieldDefinition;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The validator reads per-event rules from the catalog. These tests drive it
 * with every catalog example, so a new catalog event is covered automatically.
 */
class CatalogDrivenValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EventValidator validator = new EventValidator();

    /** Builds an event from a catalog example: its data blocks and, if present, identity.userId. */
    private static CommerceEvent fromExample(EventDefinition definition, Map<String, Object> example) {
        CommerceEvent.Builder builder = CommerceEventFixtures.base(EventName.of(definition.name()));
        Map<String, Object> data = new LinkedHashMap<>(example);
        data.remove("identity");
        builder.data(EventData.of(data));
        Object identity = example.get("identity");
        if (identity instanceof Map<?, ?> map && map.get("userId") != null) {
            builder.identity(EventIdentity.authenticated(CommerceEventFixtures.ANON, (String) map.get("userId"),
                    CommerceEventFixtures.SESSION));
        }
        return builder.build();
    }

    /** A deep copy of the example with one dotted path removed. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> without(Map<String, Object> example, String path) {
        Map<String, Object> copy = MAPPER.convertValue(example, Map.class);
        String[] parts = path.split("\\.");
        Map<String, Object> current = copy;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) return copy;
            current = (Map<String, Object>) next;
        }
        current.remove(parts[parts.length - 1]);
        return copy;
    }

    private static List<String> fields(ValidationResult result) {
        return result.errors().stream().map(ValidationResult.ValidationError::field).toList();
    }

    @TestFactory
    @DisplayName("every catalog example is valid, and fails when any required field is removed")
    Stream<DynamicTest> catalogExamples() {
        List<DynamicTest> tests = new ArrayList<>();
        for (EventDefinition definition : EventRegistry.standard().events()) {
            if (definition.required().isEmpty()) {
                tests.add(DynamicTest.dynamicTest(definition.name() + " needs nothing beyond the envelope", () ->
                        assertTrue(validator.validate(CommerceEventFixtures.base(EventName.of(definition.name())).build())
                                .valid())));
                continue;
            }
            assertFalse(definition.example().isEmpty(), definition.name() + " needs an example in the catalog");
            tests.add(DynamicTest.dynamicTest(definition.name() + " example is valid", () -> {
                ValidationResult result = validator.validate(fromExample(definition, definition.example()));
                assertTrue(result.valid(), result.describe());
            }));
            for (String path : definition.required()) {
                tests.add(DynamicTest.dynamicTest(definition.name() + " without " + path, () -> {
                    ValidationResult result = validator.validate(fromExample(definition, without(definition.example(), path)));
                    assertFalse(result.valid());
                    String reported = path.startsWith("identity.") ? path : "data." + path;
                    assertTrue(fields(result).contains(reported), reported + " not reported in " + fields(result));
                }));
            }
        }
        return tests.stream();
    }

    @Nested
    @DisplayName("validation modes")
    class Modes {

        private CommerceEvent unknown() {
            return CommerceEventFixtures.base(EventName.of("action_x_clicked")).build();
        }

        @Test
        void strictRejectsUnknownEvents() {
            ValidationResult result = new EventValidator(EventRegistry.standard(), ValidationMode.STRICT).validate(unknown());
            assertFalse(result.valid());
            assertEquals(List.of("eventType"), fields(result));
        }

        @Test
        void permissiveAcceptsUnknownEventsAndFlagsThem() {
            ValidationResult result = new EventValidator(EventRegistry.standard(), ValidationMode.PERMISSIVE).validate(unknown());
            assertTrue(result.valid());
            assertTrue(result.unplanned());
        }

        @Test
        void permissiveStillRejectsAnInvalidKnownEvent() {
            ValidationResult result = new EventValidator(EventRegistry.standard(), ValidationMode.PERMISSIVE)
                    .validate(CommerceEventFixtures.base(StandardEventNames.PRODUCT_VIEWED).build());
            assertFalse(result.valid());
            assertFalse(result.unplanned());
        }

        @Test
        void permissiveStillAppliesTheSensitiveFieldBackstop() {
            CommerceEvent event = CommerceEventFixtures.base(EventName.of("action_x_clicked"))
                    .properties(Map.of("cardNumber", "4111")).build();
            assertFalse(new EventValidator(EventRegistry.standard(), ValidationMode.PERMISSIVE).validate(event).valid());
        }

        @Test
        void knownEventsAreNeverFlaggedUnplanned() {
            assertFalse(new EventValidator(EventRegistry.standard(), ValidationMode.PERMISSIVE)
                    .validate(CommerceEventFixtures.productViewed()).unplanned());
        }

        @Test
        void theDefaultValidatorIsStrict() {
            assertEquals(ValidationMode.STRICT, new EventValidator().mode());
        }
    }

    @Nested
    @DisplayName("custom events from a tracking plan")
    class CustomEvents {

        private final EventRegistry withPlan = EventRegistry.standard().withEvents(List.of(
                new EventDefinition("action_x_clicked", "engagement", 1, EventDefinition.KIND_CUSTOM, false,
                        List.of("browser"), List.of(), List.of(), false, List.of("properties.variant"),
                        Map.of("properties.variant", new FieldDefinition("enum", true, null, null, null, null, null,
                                "variant", null, Map.of())), Map.of())),
                Map.of("variant", List.of("a", "b")));
        private final EventValidator validator = new EventValidator(withPlan, ValidationMode.STRICT);

        private CommerceEvent clicked(Map<String, Object> properties) {
            return CommerceEventFixtures.base(EventName.of("action_x_clicked")).properties(properties).build();
        }

        @Test
        void acceptsAValidCustomEvent() {
            assertTrue(validator.validate(clicked(Map.of("variant", "a"))).valid());
        }

        @Test
        void enforcesRequiredFieldsAndVocabularies() {
            assertEquals(List.of("properties.variant"), fields(validator.validate(clicked(Map.of()))));
            assertEquals(List.of("properties.variant"), fields(validator.validate(clicked(Map.of("variant", "c")))));
        }
    }

    @Nested
    @DisplayName("constraint messages")
    class Messages {

        @Test
        void describeTheRuleNotTheValue() {
            CommerceData commerce = new CommerceData(null, null, null, null, null, null, "usd", null, "o1", null,
                    null, null, null, List.of(), new BigDecimal("-1"));
            ValidationResult result = validator.validate(
                    CommerceEventFixtures.base(StandardEventNames.PURCHASE_COMPLETED).commerce(commerce).build());
            Map<String, String> byField = new LinkedHashMap<>();
            result.errors().forEach(e -> byField.put(e.field(), e.message()));
            assertEquals("currency must be a 3-letter ISO 4217 code", byField.get("data.order.currency"));
            assertEquals("total must be a non-negative number", byField.get("data.order.total"));
            assertEquals("items is required and must be non-empty", byField.get("data.order.items"));
            assertFalse(result.describe().contains("usd"));
        }
    }
}
