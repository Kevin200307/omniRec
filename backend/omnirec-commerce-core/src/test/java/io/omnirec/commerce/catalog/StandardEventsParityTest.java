// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Until Phase 2 replaces the {@link EventType} enum with a registry, the enum
 * and the generated catalog must describe exactly the same taxonomy. These
 * tests are the bridge: they fail if either side changes without the other.
 */
class StandardEventsParityTest {

    private static JsonNode runtimeCatalog() throws IOException {
        try (InputStream in = StandardEventsParityTest.class.getClassLoader()
                .getResourceAsStream(StandardEvents.CATALOG_RESOURCE)) {
            assertNotNull(in, StandardEvents.CATALOG_RESOURCE + " is not on the classpath");
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("StandardEvents and the EventType enum list the same event names")
    void sameNames() {
        Set<String> generated = new TreeSet<>(StandardEvents.ALL);
        Set<String> enumNames = new TreeSet<>();
        for (EventType type : EventType.values()) enumNames.add(type.wireName());

        assertEquals(enumNames, generated);
        assertEquals(StandardEvents.ALL.size(), generated.size(), "StandardEvents.ALL contains duplicates");
    }

    @Test
    @DisplayName("every enum constant has a StandardEvents constant with the same name and value")
    void constantsMatchEnum() throws ReflectiveOperationException {
        for (EventType type : EventType.values()) {
            Object value = StandardEvents.class.getField(type.name()).get(null);
            assertEquals(type.wireName(), value, "StandardEvents." + type.name());
        }
    }

    @Test
    @DisplayName("the runtime catalog agrees with the enum on domain and control events")
    void runtimeCatalogMatchesEnum() throws IOException {
        JsonNode catalog = runtimeCatalog();
        assertEquals(StandardEvents.CATALOG_VERSION, catalog.path("catalogVersion").asInt());

        Map<String, JsonNode> byName = new HashMap<>();
        catalog.path("events").forEach(event -> byName.put(event.path("name").asText(), event));
        assertEquals(EventType.values().length, byName.size());

        for (EventType type : EventType.values()) {
            JsonNode event = byName.get(type.wireName());
            assertNotNull(event, type.wireName() + " is missing from " + StandardEvents.CATALOG_RESOURCE);
            assertEquals(type.category().name().toLowerCase(), event.path("domain").asText(),
                    "domain of " + type.wireName());
            assertEquals(type.isControlEvent(), event.path("control").asBoolean(),
                    "control flag of " + type.wireName());
        }
        assertEquals(Set.of(StandardEvents.IDENTIFY), StandardEvents.CONTROL);
    }
}
