// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.googleretail;

import com.google.cloud.retail.v2.UserEvent;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GoogleRetailMappingTest {

    private final GoogleRetailEventMapper mapper = new GoogleRetailEventMapper();

    private CommerceEvent.Builder base(EventName type) {
        return CommerceEvent.builder()
                .eventId("evt_1")
                .eventType(type)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .tenantId("demo-store")
                .identity(EventIdentity.anonymous("anon_A", "session_1"))
                .context(new EventContext("https://shop.example/p/1", "/p/1", "https://google.com",
                        Platform.WEB, null, null, null, null, null, null, null, null))
                .commerce(CommerceData.empty())
                .properties(Map.of());
    }

    @Nested
    @DisplayName("identity — the part that differs from Amazon")
    class Identity {

        /**
         * Google's model is two fields, not one. visitorId is the anonymous
         * visitor and must stay anonymous even after login, otherwise the same
         * person looks like two different visitors either side of signing in and
         * Google's own session stitching breaks.
         */
        @Test
        void visitorIdIsAlwaysTheAnonymousId() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build());

            assertEquals("anon_A", mapped.getVisitorId());
        }

        @Test
        void visitorIdStaysAnonymousEvenWhenTheVisitorIsLoggedIn() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build());

            assertEquals("anon_A", mapped.getVisitorId(),
                    "putting userId into visitorId is the classic mistake — it breaks Google's stitching");
            assertEquals("customer_123", mapped.getUserInfo().getUserId(),
                    "the authenticated user rides alongside, in userInfo");
        }

        @Test
        void anAnonymousVisitorCarriesNoUserInfo() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build());

            assertTrue(mapped.getUserInfo().getUserId().isEmpty());
        }

        @Test
        void sessionIdRoundTrips() {
            assertEquals("session_1", mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build()).getSessionId());
        }
    }

    @Nested
    @DisplayName("the closed event vocabulary")
    class Vocabulary {

        @Test
        void mapsEachSupportedTypeToItsRetailCounterpart() {
            assertEquals("detail-page-view", mapper.retailEventType(StandardEventNames.PRODUCT_VIEWED));
            assertEquals("add-to-cart", mapper.retailEventType(StandardEventNames.PRODUCT_ADDED_TO_CART));
            assertEquals("purchase-complete", mapper.retailEventType(StandardEventNames.PURCHASE_COMPLETED));
            assertEquals("search", mapper.retailEventType(StandardEventNames.SEARCH_PERFORMED));
            assertEquals("home-page-view", mapper.retailEventType(StandardEventNames.HOME_PAGE_VIEWED));
            assertEquals("shopping-cart-page-view", mapper.retailEventType(StandardEventNames.CART_VIEWED));
        }

        /**
         * The documented vocabulary is exactly seven types. This used to map
         * removal to "remove-from-cart" and page views to "page-visit" — neither
         * exists, so Retail rejected them and they were dead-lettered.
         */
        @Test
        void emitsOnlyTheSevenDocumentedEventTypes() {
            Set<String> documented = Set.of("home-page-view", "search", "category-page-view",
                    "detail-page-view", "add-to-cart", "shopping-cart-page-view", "purchase-complete");

            for (EventName type : io.omnirec.commerce.catalog.generated.StandardEvents.ALL.stream().map(EventName::of).toList()) {
                String mapped = mapper.retailEventType(type);
                if (mapped != null) {
                    assertTrue(documented.contains(mapped), type + " maps to undocumented type " + mapped);
                }
            }
        }

        @Test
        void dropsRemovalAndPageViewsWhichHaveNoValidRetailType() {
            assertFalse(mapper.supports(StandardEventNames.PRODUCT_REMOVED_FROM_CART));
            assertFalse(mapper.supports(StandardEventNames.PAGE_VIEWED));
        }

        /**
         * A click leads to a detail page that sends its own product_viewed; a
         * recommended add-to-cart is also sent as the add itself; a recommended
         * purchase is also sent as the purchase. Mapping these too would count
         * each interaction twice.
         */
        @Test
        void dropsEventsThatWouldDoubleCountAnotherEvent() {
            for (EventName type : List.of(StandardEventNames.PRODUCT_CLICKED, StandardEventNames.SEARCH_RESULT_CLICKED,
                    StandardEventNames.RECOMMENDATION_CLICKED, StandardEventNames.RECOMMENDATION_ADDED_TO_CART,
                    StandardEventNames.RECOMMENDATION_PURCHASED)) {
                assertFalse(mapper.supports(type), type + " would double-count");
            }
        }

        @Test
        void dropsTypesWithNoGenuineCounterpart() {
            for (EventName type : List.of(StandardEventNames.SESSION_STARTED, StandardEventNames.PRODUCT_WISHLISTED,
                    StandardEventNames.PRODUCT_SHARED, StandardEventNames.CHECKOUT_FAILED, StandardEventNames.ORDER_REFUNDED,
                    StandardEventNames.USER_LOGGED_OUT, StandardEventNames.IDENTIFY, StandardEventNames.CART_ABANDONED)) {
                assertFalse(mapper.supports(type),
                        type + " has no honest Retail counterpart and must be dropped, not coerced");
            }
        }

        @Test
        void skipsADwellTimeUpdateSoAViewIsCountedOnce() {
            CommerceEvent dwellUpdate = base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .properties(Map.of(CommerceEvent.PROPERTY_DWELL_TIME_MS, 4200,
                            CommerceEvent.PROPERTY_VIEW_EVENT_ID, "evt_view"))
                    .build();

            assertFalse(mapper.supports(dwellUpdate));
        }

        @Test
        void skipsEventsMissingFieldsRetailRequires() {
            assertFalse(mapper.supports(base(StandardEventNames.CATEGORY_VIEWED).build()), "no category");
            assertFalse(mapper.supports(base(StandardEventNames.PRODUCT_LIST_VIEWED)
                    .commerce(CommerceData.builder().productIds(List.of("p1")).build()).build()), "no category");
            assertFalse(mapper.supports(base(StandardEventNames.SEARCH_PERFORMED).build()), "no query");
        }

        @Test
        void refusesToMapAnUnsupportedType() {
            CommerceEvent event = base(StandardEventNames.PRODUCT_SHARED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();

            assertThrows(IllegalArgumentException.class, () -> mapper.toUserEvent(event));
        }
    }

    @Nested
    @DisplayName("type-specific required fields")
    class RequiredFields {

        @Test
        void searchCarriesTheQueryRetailDemands() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.SEARCH_PERFORMED)
                    .commerce(CommerceData.builder().searchQuery("gaming laptop").build())
                    .build());

            assertEquals("gaming laptop", mapped.getSearchQuery());
        }

        @Test
        void categoryViewCarriesPageCategories() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.CATEGORY_VIEWED)
                    .commerce(CommerceData.builder().categoryId("laptops").build())
                    .build());

            assertEquals(List.of("laptops"), mapped.getPageCategoriesList());
        }

        @Test
        void purchaseCarriesTheTransactionRetailDemands() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PURCHASE_COMPLETED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder()
                            .orderId("order_1")
                            .items(List.of(CommerceItem.of("p1", 2, new BigDecimal("10.00"), "USD")))
                            .total(new BigDecimal("20.00"))
                            .currency("USD")
                            .build())
                    .build());

            assertEquals("order_1", mapped.getPurchaseTransaction().getId());
            assertEquals(20.0f, mapped.getPurchaseTransaction().getRevenue(), 0.001);
            assertEquals("USD", mapped.getPurchaseTransaction().getCurrencyCode());
        }
    }

    @Nested
    @DisplayName("product details")
    class ProductDetails {

        @Test
        void sendsASingleProduct() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build());

            assertEquals(1, mapped.getProductDetailsCount());
            assertEquals("p1", mapped.getProductDetails(0).getProduct().getId());
        }

        @Test
        void sendsQuantityWithACartAddition() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_ADDED_TO_CART)
                    .commerce(CommerceData.builder().productId("p1").quantity(3).cartId("c1").build())
                    .build());

            assertEquals(3, mapped.getProductDetails(0).getQuantity().getValue());
        }

        @Test
        void sendsEveryLineOfAnOrder() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PURCHASE_COMPLETED)
                    .commerce(CommerceData.builder()
                            .orderId("order_1")
                            .items(List.of(
                                    CommerceItem.of("p1", 1, new BigDecimal("10.00"), "USD"),
                                    CommerceItem.of("p2", 2, new BigDecimal("20.00"), "USD")))
                            .total(new BigDecimal("50.00"))
                            .currency("USD")
                            .build())
                    .build());

            assertEquals(2, mapped.getProductDetailsCount());
        }

        @Test
        void sendsEveryProductOfAListView() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_LIST_VIEWED)
                    .commerce(CommerceData.builder()
                            .productIds(List.of("p1", "p2", "p3"))
                            .categoryId("laptops")
                            .build())
                    .build());

            assertEquals(3, mapped.getProductDetailsCount());
        }
    }

    @Nested
    @DisplayName("attribution and context")
    class AttributionAndContext {

        @Test
        void forwardsAttributionOnlyForARecommendationGoogleServed() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1")
                            .recommendationId("google-token-1").recommendationProvider("google-retail").build())
                    .build());

            assertEquals("google-token-1", mapped.getAttributionToken());
        }

        /**
         * attributionToken must be a token Google returned. Forwarding another
         * engine's id would be invalid attribution data.
         */
        @Test
        void neverForwardsAnotherProvidersRecommendationIdAsAttribution() {
            UserEvent fromAmazon = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1")
                            .recommendationId("rec_1").recommendationProvider("amazon-personalize").build())
                    .build());
            UserEvent unattributed = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").recommendationId("rec_1").build())
                    .build());

            assertTrue(fromAmazon.getAttributionToken().isEmpty());
            assertTrue(unattributed.getAttributionToken().isEmpty());
        }

        @Test
        void carriesTheUrlAndReferrer() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build());

            assertEquals("https://shop.example/p/1", mapped.getUri());
            assertEquals("https://google.com", mapped.getReferrerUri());
        }

        @Test
        void preservesTheEventTime() {
            UserEvent mapped = mapper.toUserEvent(base(StandardEventNames.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build());

            assertEquals(Instant.parse("2026-01-01T00:00:00Z").getEpochSecond(),
                    mapped.getEventTime().getSeconds());
        }
    }
}
