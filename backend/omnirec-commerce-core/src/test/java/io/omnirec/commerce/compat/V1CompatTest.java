// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.omnirec.commerce.CommerceEventFixtures;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
class V1CompatTest {

    private static CommerceData.Builder v1() {
        return CommerceData.builder();
    }

    @Nested
    @DisplayName("v1 to v2")
    class ToData {

        @Test
        void productFieldsGoToTheProductBlock() {
            EventData d = V1Compat.toData(v1().productId("p1").price(new BigDecimal("89.99")).currency("USD")
                    .quantity(2).cartId("c1").build());
            assertEquals("p1", d.product().id());
            assertEquals(new BigDecimal("89.99"), d.product().price());
            assertEquals("USD", d.product().currency());
            assertEquals(2, d.product().quantity());
            assertEquals("c1", d.cart().id());
            assertNull(d.cart().currency(), "the cart has no amount, so no currency");
        }

        @Test
        void totalsItemsAndCurrencyBelongToTheOrderWhenThereIsAnOrderId() {
            EventData d = V1Compat.toData(v1().orderId("o1").total(new BigDecimal("179.98")).currency("USD")
                    .items(List.of(CommerceItem.of("p1", 2, new BigDecimal("89.99"), "USD"))).build());
            assertEquals("o1", d.order().id());
            assertEquals(new BigDecimal("179.98"), d.order().total());
            assertEquals("USD", d.order().currency());
            assertEquals("p1", d.order().items().get(0).productId());
            assertNull(d.get("product"));
        }

        @Test
        void totalsBelongToTheCartWhenThereIsOnlyACartId() {
            EventData d = V1Compat.toData(v1().cartId("c1").total(new BigDecimal("10")).currency("EUR").build());
            assertEquals(new BigDecimal("10"), d.cart().total());
            assertEquals("EUR", d.cart().currency());
            assertNull(d.get("order"));
        }

        @Test
        void listsSearchesCategoriesAndRecommendationsGetTheirOwnBlocks() {
            EventData d = V1Compat.toData(v1().listId("home").productIds(List.of("a", "b")).searchQuery("shoes")
                    .categoryId("c9").category("Shoes").recommendationId("r1").recommendationProvider("google-retail")
                    .build());
            assertEquals(List.of("a", "b"), d.list().productIds());
            assertEquals("home", d.list().id());
            assertEquals("shoes", d.search().query());
            assertEquals("c9", d.category().id());
            assertEquals("Shoes", d.category().name());
            assertEquals("r1", d.recommendation().id());
            assertEquals("google-retail", d.recommendation().provider());
        }

        @Test
        void anEmptyPayloadIsEmptyData() {
            assertTrue(V1Compat.toData(CommerceData.empty()).isEmpty());
            assertTrue(V1Compat.toData(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void everyFixtureSurvivesV1ToV2AndBack() {
            for (CommerceData original : List.of(
                    CommerceEventFixtures.productViewed().commerce(),
                    CommerceEventFixtures.addedToCart().commerce(),
                    CommerceEventFixtures.purchaseCompleted().commerce(),
                    CommerceEventFixtures.searchPerformed().commerce(),
                    CommerceEventFixtures.recommendationImpression().commerce(),
                    v1().cartId("c1").total(new BigDecimal("5")).currency("GBP").build(),
                    v1().productId("p").price(BigDecimal.ONE).currency("USD").orderId("o").total(BigDecimal.TEN).build(),
                    v1().currency("JPY").build())) {
                assertEquals(original, V1Compat.toCommerce(V1Compat.toData(original)), original.toString());
            }
        }
    }

    @Nested
    @DisplayName("EventData")
    class Data {

        @Test
        void keepsMoneyExact() {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("order", Map.of("total", 0.3d));
            assertEquals(new BigDecimal("0.3"), EventData.of(raw).decimal("order.total"));
            assertEquals(new BigDecimal("12.50"), EventData.of(Map.of("order", Map.of("total", "12.50"))).decimal("order.total"));
        }

        @Test
        void isImmutableAndDropsNulls() {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("id", "p1");
            product.put("name", null);
            EventData d = EventData.of(Map.of("product", product));
            assertEquals(Map.of("product", Map.of("id", "p1")), d.asMap());
            assertThrows(UnsupportedOperationException.class, () -> d.asMap().put("x", 1));
        }

        @Test
        void withSetsNestedPathsWithoutChangingTheOriginal() {
            EventData base = EventData.of(Map.of("product", Map.of("id", "p1")));
            EventData changed = base.with("product.price", new BigDecimal("2")).with("position", 3);
            assertNull(base.get("product.price"));
            assertEquals(new BigDecimal("2"), changed.decimal("product.price"));
            assertEquals("p1", changed.product().id());
            assertEquals(3, changed.integer("position"));
            assertNull(changed.with("position", null).get("position"));
        }

        @Test
        void typedViewsAreEmptyWhenABlockIsAbsent() {
            assertNull(EventData.empty().product().id());
            assertNull(EventData.empty().order().items());
        }
    }

    @Nested
    @DisplayName("JSON")
    class Json {

        private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        @Test
        void serialisesTheV2EnvelopeAndReadsItBack() throws Exception {
            CommerceEvent event = CommerceEventFixtures.purchaseCompleted().toBuilder().source(EventSource.SERVER).build();
            JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

            assertEquals("purchase_completed", json.path("event").asText());
            assertFalse(json.has("eventType"));
            assertFalse(json.has("commerce"), "the v1 view is never serialised");
            assertEquals("2.0", json.path("schemaVersion").asText());
            assertEquals("server", json.path("source").asText());
            assertEquals(1, json.path("eventVersion").asInt());
            assertEquals("standard", json.path("kind").asText());
            assertEquals(event.data().order().id(), json.path("data").path("order").path("id").asText());
            assertFalse(json.has("unplanned"));

            CommerceEvent back = mapper.readValue(mapper.writeValueAsString(event), CommerceEvent.class);
            assertEquals(event, back);
            assertEquals(StandardEventNames.PURCHASE_COMPLETED, back.eventType());
        }
    }
}
