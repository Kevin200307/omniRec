// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.normalize;

import io.omnirec.commerce.catalog.EventDefinition;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.eventapi.dto.EventDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The collector's edge conversion: whatever the client sent, the pipeline sees envelope v2. */
class V1UpconversionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private final EventNormalizer normalizer = new EventNormalizer(false);
    private final EventNormalizer.RequestMetadata request =
            new EventNormalizer.RequestMetadata("203.0.113.9", "LK", "Mozilla/5.0", NOW);
    private final EventIdentity identity = EventIdentity.anonymous("anon_A", "session_1");

    private EventDto v1(EventName type, CommerceData commerce, EventContext context) {
        return new EventDto("evt_1", type, "1.0", NOW, identity, context, commerce, Map.of());
    }

    @Test
    void convertsTheV1PayloadToBlocks() {
        CommerceEvent event = normalizer.normalize(v1(StandardEventNames.PRODUCT_ADDED_TO_CART,
                CommerceData.builder().productId("p1").quantity(2).price(new BigDecimal("9.99")).currency("USD")
                        .cartId("c1").build(), EventContext.empty()), "demo", request);

        assertEquals(StandardEventNames.PRODUCT_ADDED_TO_CART, event.eventType());
        assertEquals(CommerceEvent.CURRENT_SCHEMA_VERSION, event.schemaVersion());
        assertEquals("p1", event.data().product().id());
        assertEquals(2, event.data().product().quantity());
        assertEquals("USD", event.data().product().currency());
        assertEquals("c1", event.data().cart().id());
        assertEquals(CommerceEvent.KIND_STANDARD, event.kind());
        assertEquals(1, event.eventVersion());
    }

    @Test
    void infersTheSourceFromThePlatformForV1() {
        EventContext server = EventContext.server();
        assertEquals(EventSource.SERVER, normalizer.normalize(v1(StandardEventNames.ORDER_CANCELLED,
                CommerceData.builder().orderId("o1").build(), server), "demo", request).source());
        assertEquals(EventSource.BROWSER, normalizer.normalize(v1(StandardEventNames.PAGE_VIEWED,
                CommerceData.empty(), EventContext.empty()), "demo", request).source());
    }

    @Test
    void prefersV2DataAndTheV2NameWhenBothArePresent() {
        EventDto dto = new EventDto("evt_2", StandardEventNames.PRODUCT_VIEWED, StandardEventNames.PRODUCT_CLICKED,
                null, "2.0", EventSource.BROWSER, NOW, identity, EventContext.empty(),
                EventData.of(Map.of("product", Map.of("id", "v2"))),
                CommerceData.builder().productId("v1").build(), Map.of());
        CommerceEvent event = normalizer.normalize(dto, "demo", request);
        assertEquals(StandardEventNames.PRODUCT_VIEWED, event.eventType());
        assertEquals("v2", event.data().product().id());
        assertEquals(EventSource.BROWSER, event.source());
    }

    @Test
    void rewritesAnAliasToItsCanonicalNameAndVersion() {
        EventRegistry registry = EventRegistry.standard().withEvents(List.of(new EventDefinition(
                "add_to_cart_v2", "cart", 3, EventDefinition.KIND_STANDARD, false, List.of("browser"),
                List.of("product"), List.of("legacy_add"), false, List.of(), Map.of(), Map.of())), Map.of());
        CommerceEvent event = new EventNormalizer(false, registry).normalize(
                v1(EventName.of("legacy_add"), CommerceData.builder().productId("p1").build(), EventContext.empty()),
                "demo", request);
        assertEquals(EventName.of("add_to_cart_v2"), event.eventType());
        assertEquals(3, event.eventVersion());
    }

    @Test
    void marksNamesTheRegistryDoesNotKnowAsCustom() {
        CommerceEvent event = normalizer.normalize(EventDto.v2("evt_3", EventName.of("action_x_clicked"),
                EventSource.BROWSER, NOW, identity, EventContext.empty(), EventData.empty(), Map.of()), "demo", request);
        assertEquals(CommerceEvent.KIND_CUSTOM, event.kind());
    }

    @Test
    void refusesAnEventWithNoName() {
        EventDto dto = EventDto.v2("evt_4", null, null, NOW, identity, EventContext.empty(), EventData.empty(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(dto, "demo", request));
    }
}
