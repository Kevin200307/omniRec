// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.catalog;

import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.EventName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EventRegistryTest {

    private final EventRegistry standard = EventRegistry.standard();

    private static EventRegistry parse(String json) throws IOException {
        return EventRegistry.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static EventDefinition custom(String name, String... aliases) {
        return new EventDefinition(name, "custom", 1, EventDefinition.KIND_CUSTOM, false,
                List.of("browser"), List.of(), List.of(aliases), false, List.of(), Map.of(), Map.of());
    }

    @Nested
    @DisplayName("the standard catalog")
    class Standard {

        @Test
        void resolvesEveryGeneratedName() {
            assertEquals(StandardEvents.ALL.size(), standard.events().size());
            for (String name : StandardEvents.ALL) {
                assertTrue(standard.isKnown(name), name);
                assertEquals(name, standard.find(name).orElseThrow().name());
            }
            Set<String> registryNames = standard.events().stream().map(EventDefinition::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(new TreeSet<>(StandardEvents.ALL), registryNames);
        }

        @Test
        void matchesTheGeneratedVersionAndControlEvents() {
            assertEquals(StandardEvents.CATALOG_VERSION, standard.catalogVersion());
            Set<String> control = standard.events().stream().filter(EventDefinition::control)
                    .map(EventDefinition::name).collect(Collectors.toSet());
            assertEquals(StandardEvents.CONTROL, control);
        }

        @Test
        void carriesRequiredFieldsAndConstraints() {
            EventDefinition added = standard.find(StandardEvents.PRODUCT_ADDED_TO_CART).orElseThrow();
            assertEquals("cart_checkout", added.domain());
            assertEquals(List.of("product.id", "product.quantity"), added.required());
            FieldDefinition quantity = added.fields().get("product.quantity");
            assertTrue(quantity.required());
            assertEquals(0, quantity.minimum().compareTo(BigDecimal.ONE));

            FieldDefinition items = standard.find(StandardEvents.PURCHASE_COMPLETED).orElseThrow()
                    .fields().get("order.items");
            assertTrue(items.isArray());
            assertEquals(1, items.minItems());
            assertTrue(items.items().fields().get("productId").required());
        }

        @Test
        void doesNotKnowUnknownNames() {
            assertFalse(standard.isKnown("definitely_not_an_event"));
            assertTrue(standard.find("definitely_not_an_event").isEmpty());
            assertFalse(standard.isKnown(null));
            assertEquals("definitely_not_an_event", standard.canonicalName("definitely_not_an_event"));
        }

        @Test
        void isLoadedOnce() {
            assertSame(standard, EventRegistry.standard());
        }
    }

    @Nested
    @DisplayName("loading and extending")
    class Loading {

        private static final String DOC = """
                {"catalogVersion": 7,
                 "vocabularies": {"channel": ["email", "sms"]},
                 "events": [
                   {"name": "thing_done", "domain": "misc", "version": 2, "kind": "standard", "control": false,
                    "sources": ["server"], "blocks": [], "aliases": ["thing_did"], "autocapture": false,
                    "required": ["channel"],
                    "fields": {"channel": {"type": "enum", "required": true, "vocabulary": "channel"}}}
                 ]}
                """;

        @Test
        void resolvesAliasesToTheCanonicalEvent() throws IOException {
            EventRegistry registry = parse(DOC);
            assertEquals(7, registry.catalogVersion());
            assertEquals("thing_done", registry.find("thing_did").orElseThrow().name());
            assertEquals("thing_done", registry.canonicalName("thing_did"));
            assertEquals(List.of("email", "sms"), registry.vocabularies().get("channel"));
            assertEquals("channel", registry.find("thing_done").orElseThrow().fields().get("channel").vocabulary());
        }

        @Test
        void addsCustomEventsWithoutChangingTheOriginal() {
            EventRegistry extended = standard.withEvents(List.of(custom("action_x_clicked")), Map.of());
            assertTrue(extended.isKnown("action_x_clicked"));
            assertTrue(extended.find("action_x_clicked").orElseThrow().isCustom());
            assertFalse(standard.isKnown("action_x_clicked"));
            assertEquals(standard.events().size() + 1, extended.events().size());
        }

        @Test
        void rejectsACustomEventThatReusesAStandardName() {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> standard.withEvents(List.of(custom(StandardEvents.PRODUCT_VIEWED)), Map.of()));
            assertTrue(error.getMessage().contains("product_viewed"));
        }

        @Test
        void rejectsAnAliasThatReusesAName() {
            assertThrows(IllegalArgumentException.class,
                    () -> standard.withEvents(List.of(custom("my_event", StandardEvents.CART_VIEWED)), Map.of()));
        }

        @Test
        void rejectsAVocabularyThatAlreadyExists() throws IOException {
            EventRegistry registry = parse(DOC);
            assertThrows(IllegalArgumentException.class,
                    () -> registry.withEvents(List.of(), Map.of("channel", List.of("x"))));
        }
    }

    @Nested
    @DisplayName("EventName")
    class Names {

        @Test
        void acceptsWellFormedNamesAndRejectsOthers() {
            assertEquals("product_viewed", EventName.of("product_viewed").wireName());
            assertEquals("action_x_clicked", EventName.of("action_x_clicked").toString());
            for (String bad : new String[]{"", "ab", "Product_viewed", "1st_event", "has-dash", "has space"}) {
                assertThrows(IllegalArgumentException.class, () -> EventName.of(bad), bad);
                assertTrue(EventName.parse(bad).isEmpty(), bad);
            }
            assertThrows(IllegalArgumentException.class, () -> EventName.of(null));
        }

        @Test
        void comparesByValue() {
            assertEquals(StandardEventNames.PRODUCT_VIEWED, EventName.of("product_viewed"));
            assertTrue(EventName.of("product_viewed").is(StandardEvents.PRODUCT_VIEWED));
        }

        @Test
        void knowsControlEventsAndDomainsFromTheCatalog() {
            assertTrue(StandardEventNames.IDENTIFY.isControlEvent());
            assertFalse(StandardEventNames.PRODUCT_VIEWED.isControlEvent());
            assertFalse(EventName.of("custom_thing").isControlEvent());
            assertEquals("product_page", StandardEventNames.PRODUCT_VIEWED.domain().orElseThrow());
            assertTrue(EventName.of("custom_thing").domain().isEmpty());
        }
    }
}
