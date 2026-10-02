// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.catalog.generated.StandardEvents;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;
import io.omnirec.tracker.test.OmnirecTestRecorder;
import io.omnirec.tracker.web.OmnirecIdentityFilter;
import io.omnirec.tracker.web.OmnirecRequestIdentity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IdentityAndTrackerTest {

    private final OmnirecTestRecorder recorder = new OmnirecTestRecorder();
    private final OmnirecTracker tracker = new OmnirecTracker(new ServerEventEmitter(recorder,
            new EventValidator(EventRegistry.standard(), ValidationMode.PERMISSIVE), "demo", true));

    private static MockHttpServletRequest requestWith(Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cart");
        request.setCookies(cookies);
        return request;
    }

    @Nested
    class Filter {

        @Test
        void exposesTheBrowserIdentityForTheRequestOnly() throws Exception {
            AtomicReference<ServerIdentity> seen = new AtomicReference<>();
            new OmnirecIdentityFilter().doFilter(
                    requestWith(new Cookie("omnirec_anonymous_id", "anon_A"), new Cookie("omnirec_session_id", "s-1")),
                    new MockHttpServletResponse(),
                    new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    }, (req, res, chain) -> seen.set(OmnirecRequestIdentity.current().orElse(null))));

            assertEquals(new ServerIdentity("anon_A", null, "s-1"), seen.get());
            assertTrue(OmnirecRequestIdentity.current().isEmpty(), "cleared after the request");
        }

        @Test
        void ignoresCookieValuesThatAreNotIds() throws Exception {
            AtomicReference<ServerIdentity> seen = new AtomicReference<>();
            new OmnirecIdentityFilter().doFilter(
                    requestWith(new Cookie("omnirec_anonymous_id", "<script>alert(1)</script>")),
                    new MockHttpServletResponse(),
                    new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    }, (req, res, chain) -> seen.set(OmnirecRequestIdentity.current().orElse(null))));
            assertNull(seen.get());
        }
    }

    @Nested
    class Tracker {

        @Test
        void usesTheRequestIdentityAutomatically() {
            OmnirecRequestIdentity.runAs(new ServerIdentity("anon_A", null, "s-1"), () ->
                    tracker.track(StandardEvents.PRODUCT_ADDED_TO_CART, Map.of("product", Map.of("id", "P1", "quantity", 1))));

            recorder.assertThat("product_added_to_cart")
                    .withAnonymousId("anon_A")
                    .withData("product.id", "P1")
                    .withData("product.quantity", 1);
            assertEquals("s-1", recorder.events().get(0).identity().sessionId());
            assertEquals("server", recorder.events().get(0).source().wireName());
        }

        @Test
        void addsTheUserToTheRequestIdentityAndDerivesTheEventIdFromABusinessKey() {
            OmnirecRequestIdentity.runAs(new ServerIdentity("anon_A", null, "s-1"), () ->
                    tracker.track(StandardEvents.ORDER_CANCELLED, Map.of("order", Map.of("id", "o9")), "c_1", "o9"));
            recorder.assertThat("order_cancelled")
                    .withUserId("c_1")
                    .withAnonymousId("anon_A")
                    .withEventId("evt:order_cancelled:o9");
        }

        @Test
        void failsLoudlyOutsideARequestWithoutAUser() {
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.track(StandardEvents.PAGE_VIEWED, Map.of()));
        }

        @Test
        void identifyLinksTheRequestVisitor() {
            OmnirecRequestIdentity.runAs(new ServerIdentity("anon_A", null, null), () -> tracker.identify("c_1"));
            CommerceEvent identify = recorder.assertThat("identify").withUserId("c_1").event();
            assertEquals("anon_A", identify.identity().anonymousId());
            assertThrows(IllegalStateException.class, () -> tracker.identify("c_1"));
        }

        @Test
        void recorderAssertionsExplainFailures() {
            tracker.track(StandardEvents.PAGE_VIEWED, Map.of(), ServerIdentity.ofUser("c_1"), Map.of(), null);
            AssertionError missing = assertThrows(AssertionError.class, () -> recorder.assertThat("home_page_viewed"));
            assertTrue(missing.getMessage().contains("page_viewed"));
            AssertionError wrongData = assertThrows(AssertionError.class,
                    () -> recorder.assertThat("page_viewed").withData("product.id", "P1"));
            assertTrue(wrongData.getMessage().contains("data.product.id"));
            assertEquals(List.of(), recorder.events("home_page_viewed"));
        }
    }
}
