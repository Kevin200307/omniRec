// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.commerce.validation.ValidationResult;
import io.omnirec.derived.rules.CartAbandonedRule;
import io.omnirec.derived.rules.CheckoutAbandonedRule;
import io.omnirec.derived.rules.PurchaseHistoryRule;
import io.omnirec.derived.rules.ReturnVisitRule;
import io.omnirec.derived.store.InMemoryDerivedStateStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DerivedRulesTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    /** A clock the test moves by hand. */
    static final class MovableClock extends Clock {
        Instant now = T0;

        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final MovableClock clock = new MovableClock();
    final InMemoryDerivedStateStore store = new InMemoryDerivedStateStore(clock);
    final List<CommerceEvent> emitted = new ArrayList<>();

    DerivedEventsEngine engine(DerivedEventRule... rules) {
        return new DerivedEventsEngine(List.of(rules), store, emitted::add, clock);
    }

    static int seq;

    CommerceEvent event(String name, String anonymousId, String userId, String sessionId, Map<String, ?> data) {
        return CommerceEvent.builder()
                .eventId("e" + (++seq))
                .eventType(name)
                .eventVersion(1)
                .kind(CommerceEvent.KIND_STANDARD)
                .schemaVersion("2.0")
                .source(EventSource.BROWSER)
                .timestamp(clock.instant())
                .receivedAt(clock.instant())
                .tenantId("shop")
                .identity(new EventIdentity(anonymousId, userId, sessionId))
                .context(EventContext.empty())
                .data(EventData.of(data))
                .properties(Map.of())
                .build();
    }

    CommerceEvent addToCart(String anon) {
        return event("product_added_to_cart", anon, null, "s1",
                Map.of("product", Map.of("id", "p1", "quantity", 1), "cart", Map.of("id", "cart_" + anon)));
    }

    static void assertValid(CommerceEvent event) {
        ValidationResult result = new EventValidator(EventRegistry.standard(), ValidationMode.STRICT).validate(event);
        assertTrue(result.valid(), event.eventType() + ": " + result.describe());
        assertEquals(EventSource.DERIVED, event.source());
    }

    @Nested
    class CartAbandoned {
        final DerivedEventsEngine engine = engine(new CartAbandonedRule(Duration.ofMinutes(60)));

        @Test
        void firesOnceAfterTheTimeout() {
            engine.send(addToCart("a1"));

            clock.advance(Duration.ofMinutes(59));
            assertEquals(0, engine.fireDue());
            clock.advance(Duration.ofMinutes(1));
            assertEquals(1, engine.fireDue());
            assertEquals(0, engine.fireDue(), "fires once");

            CommerceEvent abandoned = emitted.get(0);
            assertEquals("cart_abandoned", abandoned.eventType().wireName());
            assertEquals("cart_a1", abandoned.data().cart().id());
            assertEquals("a1", abandoned.identity().anonymousId());
            assertEquals("shop", abandoned.tenantId());
            assertEquals(T0.plus(Duration.ofMinutes(60)), abandoned.timestamp());
            assertEquals(60L, abandoned.properties().get("abandonedAfterMinutes"));
            assertTrue(abandoned.eventId().startsWith("derived:cart_abandoned:shop:"));
            assertValid(abandoned);
        }

        @Test
        void isCancelledByCheckoutStarted() {
            engine.send(addToCart("a1"));
            clock.advance(Duration.ofMinutes(10));
            engine.send(event("checkout_started", "a1", "u1", "s1", Map.of("cart", Map.of("id", "cart_a1"))));
            clock.advance(Duration.ofHours(2));
            assertEquals(0, engine.fireDue());
            assertTrue(emitted.isEmpty());
        }

        @Test
        void eachAddPushesTheTimerBack() {
            engine.send(addToCart("a1"));
            clock.advance(Duration.ofMinutes(50));
            engine.send(addToCart("a1"));
            clock.advance(Duration.ofMinutes(50));
            assertEquals(0, engine.fireDue());
            clock.advance(Duration.ofMinutes(10));
            assertEquals(1, engine.fireDue());
        }

        @Test
        void visitorsAreIndependent() {
            engine.send(addToCart("a1"));
            engine.send(addToCart("a2"));
            engine.send(event("purchase_completed", "a2", null, "s1", Map.of("order", Map.of("id", "o1"))));
            clock.advance(Duration.ofHours(1));
            engine.fireDue();
            assertEquals(List.of("a1"), emitted.stream().map(e -> e.identity().anonymousId()).toList());
        }

        @Test
        void anAddDeliveredAfterTheCheckoutItPrecededDoesNotArmTheTimer() {
            CommerceEvent add = addToCart("a1");
            clock.advance(Duration.ofMinutes(1));
            engine.send(event("checkout_started", "a1", null, "s1", Map.of("cart", Map.of("id", "cart_a1"))));
            engine.send(add); // the queue reordered them
            clock.advance(Duration.ofHours(2));
            assertEquals(0, engine.fireDue());
        }

        @Test
        void aCartWithoutAnIdIsAttributedToTheVisitor() {
            engine.send(event("product_added_to_cart", "a9", null, "s1", Map.of("product", Map.of("id", "p1", "quantity", 1))));
            clock.advance(Duration.ofHours(1));
            engine.fireDue();
            assertEquals("visitor_a9", emitted.get(0).data().cart().id());
            assertEquals(true, emitted.get(0).properties().get("cartIdInferred"));
            assertValid(emitted.get(0));
        }
    }

    @Test
    void checkoutAbandonedFollowsTheSameShape() {
        DerivedEventsEngine engine = engine(new CheckoutAbandonedRule(Duration.ofMinutes(30)));
        engine.send(event("checkout_started", "a1", "u1", "s1", Map.of("cart", Map.of("id", "c1"))));
        engine.send(event("checkout_started", "a2", null, "s2", Map.of("cart", Map.of("id", "c2"))));
        engine.send(event("checkout_completed", "a2", null, "s2", Map.of("cart", Map.of("id", "c2"))));
        clock.advance(Duration.ofMinutes(30));
        assertEquals(1, engine.fireDue());
        assertEquals("checkout_abandoned", emitted.get(0).eventType().wireName());
        assertEquals("u1", emitted.get(0).identity().userId());
        assertValid(emitted.get(0));
    }

    @Nested
    class ReturnVisit {
        final DerivedEventsEngine engine = engine(new ReturnVisitRule(Duration.ofMinutes(30)));

        CommerceEvent session(String anon, String sessionId) {
            return event("session_started", anon, null, sessionId, Map.of());
        }

        @Test
        void aFirstSessionIsNotAReturnButALaterOneIs() {
            engine.send(session("a1", "s1"));
            assertTrue(emitted.isEmpty());

            clock.advance(Duration.ofDays(2));
            engine.send(session("a1", "s2"));
            assertEquals(1, emitted.size());
            CommerceEvent visit = emitted.get(0);
            assertEquals("return_visit", visit.eventType().wireName());
            assertEquals("s1", visit.properties().get("previousSessionId"));
            assertEquals(Duration.ofDays(2).toMinutes(), visit.properties().get("minutesSinceLastVisit"));
            assertEquals("derived:return_visit:shop:s2", visit.eventId());
            assertValid(visit);
        }

        @Test
        void quickNewSessionsRedeliveriesAndLateEventsAreNotReturns() {
            CommerceEvent first = session("a1", "s1");
            engine.send(first);
            clock.advance(Duration.ofMinutes(10));
            engine.send(session("a1", "s2")); // under the minimum gap
            engine.send(session("a1", "s2")); // redelivered
            engine.send(first);               // arrives late
            assertTrue(emitted.isEmpty());
        }
    }

    @Nested
    class PurchaseHistory {
        final DerivedEventsEngine engine = engine(new PurchaseHistoryRule());

        CommerceEvent purchase(String userId, String orderId) {
            return event("purchase_completed", "anon_" + userId, userId, "s1", Map.of("order", Map.of(
                    "id", orderId, "total", new BigDecimal("49.90"), "currency", "USD",
                    "items", List.of(Map.of("productId", "p1", "quantity", 1)))));
        }

        @Test
        void theFirstOrderIsNewAndLaterOnesAreRepeats() {
            engine.send(purchase("u1", "o1"));
            engine.send(purchase("u1", "o2"));
            engine.send(purchase("u2", "o3"));

            assertEquals(List.of("new_customer_purchase", "repeat_purchase", "new_customer_purchase"),
                    emitted.stream().map(e -> e.eventType().wireName()).toList());
            assertEquals(2L, emitted.get(1).properties().get("purchaseNumber"));
            assertEquals(0, new BigDecimal("49.90").compareTo(emitted.get(1).data().order().total()));
            emitted.forEach(DerivedRulesTest::assertValid);
        }

        @Test
        void anOrderIsCountedOnceEvenWhenSentTwice() {
            engine.send(purchase("u1", "o1"));
            engine.send(purchase("u1", "o1")); // browser and server both reported it, or a redelivery
            engine.send(purchase("u1", "o2"));

            assertEquals(3, emitted.size());
            assertEquals(emitted.get(0).eventId(), emitted.get(1).eventId(), "the repeat is a duplicate downstream");
            assertEquals("new_customer_purchase", emitted.get(1).eventType().wireName());
            assertEquals(2L, emitted.get(2).properties().get("purchaseNumber"));
        }
    }

    @Nested
    class Engine {

        @Test
        void neverFeedsItselfAndOnlyWantsSubscribedEvents() {
            DerivedEventsEngine engine = engine(new CartAbandonedRule(Duration.ofMinutes(1)));
            CommerceEvent derived = addToCart("a1").toBuilder().source(EventSource.DERIVED).build();
            assertFalse(engine.supports(derived));
            assertFalse(engine.supports(event("page_viewed", "a1", null, "s1", Map.of())));
            assertTrue(engine.supports(addToCart("a1")));
            assertEquals("derived-events", engine.id());
        }

        @Test
        void aFailingTimerIsRetriedThenDropped() {
            AtomicInteger calls = new AtomicInteger();
            DerivedEventRule flaky = new DerivedEventRule() {
                @Override public String name() { return "flaky"; }
                @Override public Set<String> subscribesTo() { return Set.of("page_viewed"); }
                @Override public void onEvent(CommerceEvent event, RuleContext context) {
                    context.schedule("k", context.now(), Map.of());
                }
                @Override public void onTimer(Timer timer, RuleContext context) {
                    calls.incrementAndGet();
                    throw new IllegalStateException("downstream unavailable");
                }
            };
            DerivedEventsEngine engine = engine(flaky);
            engine.send(event("page_viewed", "a1", null, "s1", Map.of()));

            for (int i = 0; i < 10; i++) {
                engine.fireDue();
                clock.advance(DerivedEventsEngine.TIMER_RETRY_DELAY);
            }
            assertEquals(DerivedEventsEngine.MAX_TIMER_ATTEMPTS, calls.get());
            assertEquals(0, store.pendingTimers());
        }

        @Test
        void ruleNamesMustBeUnique() {
            assertThrows(IllegalStateException.class,
                    () -> engine(new PurchaseHistoryRule(), new PurchaseHistoryRule()));
        }
    }
}
