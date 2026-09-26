// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.validation;

import io.omnirec.commerce.CommerceEventFixtures;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventValidatorTest {

    private final EventValidator validator = new EventValidator();

    private boolean isValid(CommerceEvent event) {
        return validator.validate(event).valid();
    }

    private List<String> fieldsOf(CommerceEvent event) {
        return validator.validate(event).errors().stream()
                .map(ValidationResult.ValidationError::field)
                .toList();
    }

    @Nested
    @DisplayName("universal rules")
    class Universal {

        @Test
        void acceptsAWellFormedEvent() {
            assertTrue(isValid(CommerceEventFixtures.productViewed()));
        }

        @Test
        void rejectsABlankEventId() {
            CommerceEvent event = CommerceEventFixtures.base(EventType.PAGE_VIEWED).eventId("  ").build();

            assertTrue(fieldsOf(event).contains("eventId"));
        }

        @Test
        void rejectsAMissingAnonymousId() {
            CommerceEvent event = CommerceEventFixtures.base(EventType.PAGE_VIEWED)
                    .identity(EventIdentity.anonymous(null, "s1"))
                    .build();

            assertTrue(fieldsOf(event).contains("identity.anonymousId"));
        }

        @Test
        void rejectsAMissingSessionId() {
            CommerceEvent event = CommerceEventFixtures.base(EventType.PAGE_VIEWED)
                    .identity(EventIdentity.anonymous("anon_A", null))
                    .build();

            assertTrue(fieldsOf(event).contains("identity.sessionId"));
        }

        @Test
        void doesNotDemandCommerceFieldsForSessionEvents() {
            assertTrue(isValid(CommerceEventFixtures.base(EventType.SESSION_STARTED).build()));
            assertTrue(isValid(CommerceEventFixtures.base(EventType.PAGE_VIEWED).build()));
            assertTrue(isValid(CommerceEventFixtures.base(EventType.HOME_PAGE_VIEWED).build()));
        }

        @Test
        void collectsEveryProblemRatherThanStoppingAtTheFirst() {
            CommerceEvent event = CommerceEventFixtures.base(EventType.PRODUCT_ADDED_TO_CART).build();

            assertEquals(2, validator.validate(event).errors().size());
        }

        @Test
        void describesFailuresWithoutLeakingValues() {
            CommerceEvent event = CommerceEventFixtures.base(EventType.PRODUCT_VIEWED).build();

            String description = validator.validate(event).describe();

            assertTrue(description.contains("commerce.productId"));
            assertFalse(description.contains("anon_A"), "diagnostics must never echo identity values");
        }
    }

    @Nested
    @DisplayName("product and discovery rules")
    class Discovery {

        @Test
        void productViewedRequiresProductId() {
            assertFalse(isValid(CommerceEventFixtures.base(EventType.PRODUCT_VIEWED).build()));
        }

        @Test
        void categoryViewedRequiresCategoryId() {
            assertFalse(isValid(CommerceEventFixtures.base(EventType.CATEGORY_VIEWED).build()));
            assertTrue(isValid(CommerceEventFixtures.base(EventType.CATEGORY_VIEWED)
                    .commerce(CommerceData.builder().categoryId("c1").build()).build()));
        }

        @Test
        void productListViewedRequiresANonEmptyProductIdList() {
            assertFalse(isValid(CommerceEventFixtures.base(EventType.PRODUCT_LIST_VIEWED)
                    .commerce(CommerceData.builder().productIds(List.of()).build()).build()));
            assertTrue(isValid(CommerceEventFixtures.base(EventType.PRODUCT_LIST_VIEWED)
                    .commerce(CommerceData.builder().productIds(List.of("p1")).build()).build()));
        }

        @Test
        void searchPerformedRequiresAQuery() {
            assertFalse(isValid(CommerceEventFixtures.base(EventType.SEARCH_PERFORMED).build()));
            assertTrue(isValid(CommerceEventFixtures.searchPerformed()));
        }
    }

    @Nested
    @DisplayName("cart rules")
    class Cart {

        @Test
        void acceptsAValidAddToCart() {
            assertTrue(isValid(CommerceEventFixtures.addedToCart()));
        }

        @Test
        void requiresProductIdAndQuantity() {
            List<String> fields = fieldsOf(CommerceEventFixtures.base(EventType.PRODUCT_ADDED_TO_CART).build());

            assertTrue(fields.contains("commerce.productId"));
            assertTrue(fields.contains("commerce.quantity"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100})
        void rejectsANonPositiveQuantity(int quantity) {
            CommerceEvent event = CommerceEventFixtures.base(EventType.PRODUCT_ADDED_TO_CART)
                    .commerce(CommerceData.builder().productId("p1").quantity(quantity).build())
                    .build();

            assertFalse(isValid(event));
        }

        @Test
        void cartViewedAndAbandonedRequireACartId() {
            assertFalse(isValid(CommerceEventFixtures.base(EventType.CART_VIEWED).build()));
            assertFalse(isValid(CommerceEventFixtures.base(EventType.CART_ABANDONED).build()));
        }
    }

    @Nested
    @DisplayName("purchase rules")
    class Purchase {

        private CommerceData.Builder validPurchase() {
            return CommerceData.builder()
                    .orderId("order_1")
                    .items(List.of(CommerceItem.of("p1", 1, new BigDecimal("10.00"), "USD")))
                    .total(new BigDecimal("10.00"))
                    .currency("USD");
        }

        private CommerceEvent purchaseWith(CommerceData commerce) {
            return CommerceEventFixtures.base(EventType.PURCHASE_COMPLETED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "s1"))
                    .commerce(commerce)
                    .build();
        }

        @Test
        void acceptsACompletePurchase() {
            assertTrue(isValid(CommerceEventFixtures.purchaseCompleted()));
        }

        @Test
        void requiresOrderId() {
            assertTrue(fieldsOf(purchaseWith(validPurchase().orderId(null).build()))
                    .contains("commerce.orderId"));
        }

        @Test
        void requiresANonEmptyItemList() {
            assertTrue(fieldsOf(purchaseWith(validPurchase().items(List.of()).build()))
                    .contains("commerce.items"));
        }

        @Test
        void requiresCurrency() {
            assertTrue(fieldsOf(purchaseWith(validPurchase().currency(null).build()))
                    .contains("commerce.currency"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"dollars", "US", "usd1", "$"})
        void rejectsACurrencyThatIsNotIso4217(String currency) {
            assertFalse(isValid(purchaseWith(validPurchase().currency(currency).build())));
        }

        @Test
        void requiresTotal() {
            assertTrue(fieldsOf(purchaseWith(validPurchase().total(null).build()))
                    .contains("commerce.total"));
        }

        @Test
        void rejectsANegativeTotal() {
            assertTrue(fieldsOf(purchaseWith(validPurchase().total(new BigDecimal("-5.00")).build()))
                    .contains("commerce.total"));
        }

        @Test
        void acceptsAZeroTotalForAFullyDiscountedOrder() {
            assertTrue(isValid(purchaseWith(validPurchase().total(BigDecimal.ZERO).build())));
        }

        @Test
        void reportsTheOffendingItemByIndex() {
            CommerceData commerce = validPurchase()
                    .items(List.of(
                            CommerceItem.of("p1", 1, BigDecimal.ONE, "USD"),
                            new CommerceItem("", 1, BigDecimal.ONE, "USD", null)))
                    .build();

            assertTrue(fieldsOf(purchaseWith(commerce)).contains("commerce.items[1].productId"));
        }

        @Test
        void rejectsANonPositiveItemQuantity() {
            CommerceData commerce = validPurchase()
                    .items(List.of(new CommerceItem("p1", 0, BigDecimal.ONE, "USD", null)))
                    .build();

            assertTrue(fieldsOf(purchaseWith(commerce)).contains("commerce.items[0].quantity"));
        }
    }

    @Nested
    @DisplayName("recommendation and user rules")
    class RecommendationAndUser {

        @Test
        void impressionRequiresRecommendationIdAndProducts() {
            List<String> fields = fieldsOf(CommerceEventFixtures.base(EventType.RECOMMENDATION_IMPRESSION).build());

            assertTrue(fields.contains("commerce.recommendationId"));
            assertTrue(fields.contains("commerce.productIds"));
        }

        @Test
        void acceptsAValidImpression() {
            assertTrue(isValid(CommerceEventFixtures.recommendationImpression()));
        }

        @Test
        void identityBearingEventsRequireAUserId() {
            for (EventType type : List.of(EventType.USER_REGISTERED, EventType.USER_LOGGED_IN,
                    EventType.USER_PROFILE_UPDATED, EventType.IDENTIFY)) {
                assertFalse(isValid(CommerceEventFixtures.base(type).build()), type + " without userId");
            }
        }

        @Test
        void loggingOutDoesNotRequireAUserId() {
            assertTrue(isValid(CommerceEventFixtures.base(EventType.USER_LOGGED_OUT).build()));
        }
    }

    @Nested
    @DisplayName("sensitive data is refused outright")
    class SensitiveData {

        private CommerceEvent withProperties(Map<String, Object> properties) {
            return CommerceEventFixtures.base(EventType.PAYMENT_INFORMATION_ADDED)
                    .commerce(CommerceData.builder().cartId("cart_1").build())
                    .properties(properties)
                    .build();
        }

        @Test
        void allowsSafePaymentMetadata() {
            assertTrue(isValid(withProperties(Map.of("paymentMethod", "card"))));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "cardNumber", "card_number", "CardNumber", "cvv", "CVC", "securityCode",
                "pan", "expiryMonth", "password", "ssn", "iban", "secretKey", "privateKey", "accessToken"
        })
        void rejectsAnEventCarryingSensitiveData(String field) {
            assertFalse(isValid(withProperties(Map.of(field, "whatever"))), field + " must be refused");
        }

        @Test
        void findsSensitiveFieldsNestedDeepInsideTheProperties() {
            CommerceEvent event = withProperties(Map.of(
                    "checkout", Map.of("billing", Map.of("cardNumber", "4111111111111111"))));

            assertEquals(List.of("properties.checkout.billing.cardNumber"), fieldsOf(event));
        }

        @Test
        void findsSensitiveFieldsInsideALIst() {
            CommerceEvent event = withProperties(Map.of("methods", List.of(Map.of("cvv", "123"))));

            assertEquals(List.of("properties.methods[0].cvv"), fieldsOf(event));
        }

        @Test
        void doesNotPruneDistinctButEqualSiblingMaps() {
            // Both entries are equal by value; an equals-based visited set would
            // skip the second and miss the violation inside it.
            CommerceEvent event = withProperties(Map.of(
                    "a", Map.of("cvv", "123"),
                    "b", Map.of("cvv", "123")));

            assertEquals(2, validator.validate(event).errors().size());
        }

        @Test
        void exposesTheBlockedNamesSoTheContractTestCanCompareThem() {
            assertTrue(EventValidator.sensitiveFieldNames().contains("cvv"));
            assertTrue(EventValidator.sensitiveFieldNames().contains("cardnumber"));
        }
    }
}
