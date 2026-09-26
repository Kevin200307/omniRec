// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.model.Platform;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.tracker.trackers.PurchaseTracker.PurchaseCompleted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommerceTrackerTest {

    private static final class RecordingSender implements EventSender {
        final List<CommerceEvent> sent = new ArrayList<>();

        @Override
        public void send(CommerceEvent event) {
            sent.add(event);
        }
    }

    private RecordingSender sender;
    private CommerceTracker commerce;

    @BeforeEach
    void setUp() {
        sender = new RecordingSender();
        commerce = new CommerceTracker(
                new ServerEventEmitter(sender, new EventValidator(), "demo-store", true));
    }

    private CommerceEvent only() {
        assertEquals(1, sender.sent.size(), "expected exactly one event");
        return sender.sent.get(0);
    }

    private PurchaseCompleted.Builder purchase() {
        return PurchaseCompleted.builder()
                .orderId("order_1")
                .userId("customer_123")
                .items(List.of(CommerceItem.of("p1", 2, new BigDecimal("50.00"), "USD")))
                .total(new BigDecimal("100.00"))
                .currency("USD");
    }

    @Nested
    @DisplayName("authoritative purchase events")
    class Purchases {

        @Test
        void producesACanonicalPurchaseEvent() {
            commerce.purchase.completed(purchase().build());

            CommerceEvent event = only();
            assertEquals(EventType.PURCHASE_COMPLETED, event.eventType());
            assertEquals("order_1", event.commerce().orderId());
            assertEquals(new BigDecimal("100.00"), event.commerce().total());
            assertEquals("USD", event.commerce().currency());
            assertEquals(1, event.commerce().items().size());
        }

        @Test
        void marksTheEventAsServerOriginated() {
            commerce.purchase.completed(purchase().build());

            assertEquals(Platform.SERVER, only().context().platform(),
                    "a consumer should be able to tell authoritative events from browser ones");
        }

        @Test
        void carriesTheConfiguredTenant() {
            commerce.purchase.completed(purchase().build());

            assertEquals("demo-store", only().tenantId());
        }

        @Test
        void reportsFailuresCancellationsAndRefunds() {
            commerce.purchase.failed("order_1", "customer_123", "card declined");
            commerce.purchase.orderCancelled("order_2", "customer_123", "out of stock");
            commerce.purchase.orderRefunded("order_3", "customer_123", new BigDecimal("50.00"), "USD");

            assertEquals(List.of(EventType.PURCHASE_FAILED, EventType.ORDER_CANCELLED, EventType.ORDER_REFUNDED),
                    sender.sent.stream().map(CommerceEvent::eventType).toList());
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /**
         * The property that makes a retried webhook safe. Same order reported
         * twice must produce the same eventId, so the pipeline deduplicates it
         * rather than counting the revenue twice.
         */
        @Test
        void reportingTheSameOrderTwiceProducesTheSameEventId() {
            commerce.purchase.completed(purchase().build());
            commerce.purchase.completed(purchase().build());

            assertEquals(sender.sent.get(0).eventId(), sender.sent.get(1).eventId());
        }

        /**
         * Byte-identical to the frontend SDK's businessEventId(), which asserts
         * the same literal. That shared format is what lets a purchase reported
         * from both the browser and the server deduplicate.
         */
        @Test
        void usesTheSameEventIdFormatAsTheFrontendSdk() {
            commerce.purchase.completed(purchase().build());

            assertEquals("evt:purchase_completed:order_1", sender.sent.get(0).eventId());
        }

        @Test
        void differentOrdersProduceDifferentEventIds() {
            commerce.purchase.completed(purchase().build());
            commerce.purchase.completed(purchase().orderId("order_2").build());

            assertNotEquals(sender.sent.get(0).eventId(), sender.sent.get(1).eventId());
        }

        @Test
        void isDeterministicAcrossProcessesNotJustWithinOne() {
            // Two independently constructed trackers stand in for two nodes.
            RecordingSender otherSender = new RecordingSender();
            CommerceTracker otherNode = new CommerceTracker(
                    new ServerEventEmitter(otherSender, new EventValidator(), "demo-store", true));

            commerce.purchase.completed(purchase().build());
            otherNode.purchase.completed(purchase().build());

            assertEquals(sender.sent.get(0).eventId(), otherSender.sent.get(0).eventId(),
                    "two nodes reporting the same order must agree on the eventId");
        }

        @Test
        void aCancellationAndAPurchaseForOneOrderAreDistinctEvents() {
            commerce.purchase.completed(purchase().build());
            commerce.purchase.orderCancelled("order_1", "customer_123", "changed mind");

            assertNotEquals(sender.sent.get(0).eventId(), sender.sent.get(1).eventId(),
                    "the event type is part of the key, so the same orderId can carry both");
        }

        @Test
        void aRepeatedAbandonmentSweepReportsACartOnce() {
            commerce.cart.abandoned("cart_1", "customer_123", "anon_A", List.of());
            commerce.cart.abandoned("cart_1", "customer_123", "anon_A", List.of());

            assertEquals(sender.sent.get(0).eventId(), sender.sent.get(1).eventId(),
                    "a sweeper running every five minutes must not report the same stale cart repeatedly");
        }
    }

    @Nested
    @DisplayName("server-side identity")
    class Identity {

        @Test
        void usesTheSuppliedAnonymousIdSoThePurchaseLinksToBrowsing() {
            commerce.purchase.completed(purchase().anonymousId("anon_A").build());

            assertEquals("anon_A", only().identity().anonymousId());
            assertEquals("customer_123", only().identity().userId());
        }

        @Test
        void derivesAStableAnonymousIdWhenNoneIsSupplied() {
            commerce.purchase.completed(purchase().build());
            commerce.purchase.completed(purchase().orderId("order_2").build());

            String first = sender.sent.get(0).identity().anonymousId();
            assertTrue(first.startsWith("server:"));
            assertEquals(first, sender.sent.get(1).identity().anonymousId(),
                    "one user's server-side events should stay coherent with each other");
        }

        @Test
        void givesDifferentUsersDifferentDerivedIdentities() {
            commerce.purchase.completed(purchase().build());
            commerce.purchase.completed(purchase().orderId("order_2").userId("customer_999").build());

            assertNotEquals(sender.sent.get(0).identity().anonymousId(),
                    sender.sent.get(1).identity().anonymousId());
        }

        @Test
        void refusesAnEventWithNoUserId() {
            assertThrows(IllegalArgumentException.class,
                    () -> commerce.purchase.completed(purchase().userId(null).build()));
        }

        @Test
        void identifyLinksAnonymousToUser() {
            commerce.identify("anon_A", "customer_123");

            CommerceEvent event = only();
            assertEquals(EventType.IDENTIFY, event.eventType());
            assertEquals("anon_A", event.identity().anonymousId());
            assertEquals("customer_123", event.identity().userId());
        }

        @Test
        void identifyRequiresBothIds() {
            assertThrows(IllegalArgumentException.class, () -> commerce.identify(null, "customer_123"));
            assertThrows(IllegalArgumentException.class, () -> commerce.identify("anon_A", null));
        }
    }

    @Nested
    @DisplayName("validation at the call site")
    class Validation {

        /**
         * A backend event is authoritative business data. Dropping a purchase
         * because a field was missing would be far worse than failing loudly
         * while the developer is looking at the call site.
         */
        @Test
        void throwsRatherThanSilentlyDroppingAnIncompletePurchase() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> commerce.purchase.completed(purchase().total(null).build()));

            assertTrue(thrown.getMessage().contains("commerce.total"));
            assertTrue(sender.sent.isEmpty());
        }

        @Test
        void rejectsAPurchaseWithNoItems() {
            assertThrows(IllegalArgumentException.class,
                    () -> commerce.purchase.completed(purchase().items(List.of()).build()));
        }

        @Test
        void rejectsANonIsoCurrency() {
            assertThrows(IllegalArgumentException.class,
                    () -> commerce.purchase.completed(purchase().currency("dollars").build()));
        }

        @Test
        void refusesPaymentCredentialsInProperties() {
            assertThrows(IllegalArgumentException.class,
                    () -> commerce.purchase.completed(
                            purchase().properties(Map.of("cardNumber", "4111111111111111")).build()));
        }

        @Test
        void acceptsSafePaymentMetadata() {
            commerce.checkout.paymentInformationAdded("cart_1", "customer_123", "card");

            assertEquals("card", only().properties().get("paymentMethod"));
        }
    }

    @Nested
    @DisplayName("the other authoritative trackers")
    class OtherTrackers {

        @Test
        void recordsAnAcceptedReview() {
            commerce.product.reviewSubmitted("p1", "customer_123", "review_1", 5);

            assertEquals(EventType.PRODUCT_REVIEW_SUBMITTED, only().eventType());
            assertEquals(5, only().properties().get("rating"));
        }

        @Test
        void recordsRegistrationWithTheBrowsingIdentityThatPrecededIt() {
            commerce.user.registered("customer_123", "anon_A");

            assertEquals(EventType.USER_REGISTERED, only().eventType());
            assertEquals("anon_A", only().identity().anonymousId(),
                    "this is what connects pre-signup browsing to the new account");
        }

        @Test
        void recordsDerivedCartAbandonment() {
            commerce.cart.abandoned("cart_1", "customer_123", "anon_A",
                    List.of(CommerceItem.of("p1", 1, new BigDecimal("10.00"), "USD")));

            assertEquals(EventType.CART_ABANDONED, only().eventType());
            assertEquals("cart_1", only().commerce().cartId());
        }

        @Test
        void recordsCheckoutProgress() {
            commerce.checkout.started("cart_1", "customer_123", "anon_A");
            commerce.checkout.shippingInformationAdded("cart_1", "customer_123", "express");
            commerce.checkout.completed("cart_1", "order_1", "customer_123");

            assertEquals(List.of(EventType.CHECKOUT_STARTED, EventType.SHIPPING_INFORMATION_ADDED,
                            EventType.CHECKOUT_COMPLETED),
                    sender.sent.stream().map(CommerceEvent::eventType).toList());
        }

        @Test
        void attributesARecommendedPurchase() {
            commerce.recommendation.purchased("rec_1", "p1", "order_1", "customer_123", "anon_A");

            CommerceEvent event = only();
            assertEquals(EventType.RECOMMENDATION_PURCHASED, event.eventType());
            assertEquals("rec_1", event.commerce().recommendationId());
            assertEquals("p1", event.commerce().productId());
        }
    }
}
