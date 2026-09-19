package io.omnirec.commerce.identity;

import io.omnirec.commerce.CommerceEventFixtures;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdentityResolverTest {

    private InMemoryIdentityLinkStore store;
    private IdentityResolver resolver;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdentityLinkStore();
        resolver = new IdentityResolver(store);
    }

    @Nested
    @DisplayName("recording links")
    class RecordingLinks {

        @Test
        void anIdentifyEventEstablishesTheAnonymousToUserLink() {
            resolver.process(CommerceEventFixtures.identify());

            assertEquals(java.util.Optional.of(CommerceEventFixtures.USER),
                    store.resolveUserId(CommerceEventFixtures.TENANT, CommerceEventFixtures.ANON));
        }

        @Test
        void anIdentifyEventIsAbsorbedAndNeverForwarded() {
            assertNull(resolver.process(CommerceEventFixtures.identify()),
                    "identify is a control event — forwarding it to a provider would be meaningless");
        }

        @Test
        void anOrdinaryAuthenticatedEventAlsoEstablishesTheLink() {
            // A merchant who only ever calls user.loggedIn() still gets linking.
            resolver.process(CommerceEventFixtures.productViewedByUser());

            assertTrue(store.resolveUserId(CommerceEventFixtures.TENANT, CommerceEventFixtures.ANON).isPresent());
        }

        @Test
        void anAnonymousEventEstablishesNothing() {
            resolver.process(CommerceEventFixtures.productViewed());

            assertTrue(store.resolveUserId(CommerceEventFixtures.TENANT, CommerceEventFixtures.ANON).isEmpty());
        }

        @Test
        void linkingIsIdempotentUnderRedelivery() {
            for (int i = 0; i < 5; i++) {
                resolver.process(CommerceEventFixtures.identify());
            }

            assertEquals(List.of(CommerceEventFixtures.ANON),
                    store.anonymousIdsFor(CommerceEventFixtures.TENANT, CommerceEventFixtures.USER));
        }
    }

    @Nested
    @DisplayName("resolving historical anonymous behaviour")
    class Resolving {

        @Test
        void anAnonymousEventGainsTheUserIdOnceALinkExists() {
            resolver.process(CommerceEventFixtures.identify());

            CommerceEvent resolved = resolver.process(CommerceEventFixtures.productViewed());

            assertNotNull(resolved);
            assertEquals(CommerceEventFixtures.USER, resolved.identity().userId());
            assertEquals(CommerceEventFixtures.ANON, resolved.identity().anonymousId(),
                    "the anonymousId must survive resolution — it is how the device stays recognisable");
        }

        @Test
        void anEventThatAlreadyNamesAUserIsLeftAlone() {
            store.link(IdentityLink.of(CommerceEventFixtures.TENANT, CommerceEventFixtures.ANON, "stale_user"));

            CommerceEvent resolved = resolver.process(CommerceEventFixtures.productViewedByUser());

            assertEquals(CommerceEventFixtures.USER, resolved.identity().userId(),
                    "a live login beats a historical link");
        }

        @Test
        void anEventWithNoLinkStaysAnonymous() {
            CommerceEvent resolved = resolver.process(CommerceEventFixtures.productViewed());

            assertNull(resolved.identity().userId());
        }

        @Test
        void resolutionIsScopedPerTenant() {
            store.link(IdentityLink.of("tenant-a", CommerceEventFixtures.ANON, "user-a"));

            CommerceEvent forTenantB = CommerceEventFixtures.productViewed().withTenantId("tenant-b");

            assertNull(resolver.process(forTenantB).identity().userId(),
                    "one tenant's identity graph must never leak into another's");
        }
    }

    @Nested
    @DisplayName("multiple devices, one shopper")
    class MultipleDevices {

        @Test
        void twoAnonymousIdsCanLinkToTheSameUser() {
            store.link(IdentityLink.of(CommerceEventFixtures.TENANT, "anon_A", CommerceEventFixtures.USER));
            store.link(IdentityLink.of(CommerceEventFixtures.TENANT, "anon_B", CommerceEventFixtures.USER));

            List<String> anonymousIds = store.anonymousIdsFor(CommerceEventFixtures.TENANT, CommerceEventFixtures.USER);

            assertEquals(2, anonymousIds.size());
            assertTrue(anonymousIds.containsAll(List.of("anon_A", "anon_B")));
        }

        @Test
        void bothDevicesResolveToTheSameUser() {
            store.link(IdentityLink.of(CommerceEventFixtures.TENANT, "anon_A", CommerceEventFixtures.USER));
            store.link(IdentityLink.of(CommerceEventFixtures.TENANT, "anon_B", CommerceEventFixtures.USER));

            assertEquals(store.resolveUserId(CommerceEventFixtures.TENANT, "anon_A"),
                    store.resolveUserId(CommerceEventFixtures.TENANT, "anon_B"));
        }

        @Test
        void aSharedDeviceResolvesToWhoeverLoggedInMostRecently() {
            store.link(new IdentityLink(CommerceEventFixtures.TENANT, "anon_shared", "user_first",
                    Instant.parse("2026-01-01T00:00:00Z")));
            store.link(new IdentityLink(CommerceEventFixtures.TENANT, "anon_shared", "user_second",
                    Instant.parse("2026-02-01T00:00:00Z")));

            assertEquals(java.util.Optional.of("user_second"),
                    store.resolveUserId(CommerceEventFixtures.TENANT, "anon_shared"));
        }
    }

    @Nested
    @DisplayName("the full day-1 / day-2 / login journey")
    class Journey {

        @Test
        void historicalAnonymousEventsBecomeAttributableAfterLogin() {
            // Day 1 and day 2: anonymous browsing in two different sessions.
            CommerceEvent day1 = CommerceEventFixtures.productViewed()
                    .withIdentity(EventIdentity.anonymous("anon_A", "session_1"));
            CommerceEvent day2 = CommerceEventFixtures.productViewed()
                    .withIdentity(EventIdentity.anonymous("anon_A", "session_2"));

            assertNull(resolver.process(day1).identity().userId());
            assertNull(resolver.process(day2).identity().userId());

            // Day 3: the visitor logs in.
            CommerceEvent login = CommerceEventFixtures.identify()
                    .withIdentity(EventIdentity.authenticated("anon_A", "customer_123", "session_3"));
            resolver.process(login);

            // The stored events keep their original anonymous identity...
            assertNull(day1.identity().userId(), "history must not be rewritten in place");
            assertNull(day2.identity().userId());

            // ...but the link makes them attributable to the user.
            assertEquals(java.util.Optional.of("customer_123"),
                    store.resolveUserId(CommerceEventFixtures.TENANT, "anon_A"));

            // And everything after the link carries the userId directly.
            CommerceEvent day3 = CommerceEventFixtures.productViewed()
                    .withIdentity(EventIdentity.anonymous("anon_A", "session_3"));
            assertEquals("customer_123", resolver.process(day3).identity().userId());
        }
    }

    @Nested
    @DisplayName("store behaviour")
    class StoreBehaviour {

        @Test
        void resolvingAnUnknownAnonymousIdReturnsEmpty() {
            assertTrue(store.resolveUserId(CommerceEventFixtures.TENANT, "never-seen").isEmpty());
        }

        @Test
        void resolvingNullIsSafe() {
            assertTrue(store.resolveUserId(CommerceEventFixtures.TENANT, null).isEmpty());
        }

        @Test
        void anUnknownUserHasNoAnonymousIds() {
            assertEquals(List.of(), store.anonymousIdsFor(CommerceEventFixtures.TENANT, "never-seen"));
        }

        @Test
        void aNullTenantIsAValidSingleTenantKey() {
            store.link(IdentityLink.of(null, "anon_A", "user_1"));

            assertEquals(java.util.Optional.of("user_1"), store.resolveUserId(null, "anon_A"));
        }

        @Test
        void linkRequiresBothIds() {
            assertThrows(NullPointerException.class, () -> IdentityLink.of("t", null, "user"));
            assertThrows(NullPointerException.class, () -> IdentityLink.of("t", "anon", null));
        }
    }

    @Test
    void logoutDoesNotDestroyTheLink() {
        resolver.process(CommerceEventFixtures.identify());

        CommerceEvent loggedOut = CommerceEventFixtures.base(EventType.USER_LOGGED_OUT)
                .identity(EventIdentity.anonymous(CommerceEventFixtures.ANON, "session_after_logout"))
                .build();
        resolver.process(loggedOut);

        // The device is still known to belong to that shopper — which is exactly
        // what lets a returning visitor get personalised results before logging in again.
        assertEquals(java.util.Optional.of(CommerceEventFixtures.USER),
                store.resolveUserId(CommerceEventFixtures.TENANT, CommerceEventFixtures.ANON));
    }
}
