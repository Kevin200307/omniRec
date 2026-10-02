// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.recentlyviewed;

import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RecentlyViewedDestinationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final AtomicInteger IDS = new AtomicInteger();

    static CommerceEvent view(String tenant, String userId, String productId, Instant at) {
        EventIdentity identity = userId == null
                ? EventIdentity.anonymous("anon_1", "s1")
                : EventIdentity.authenticated("anon_1", userId, "s1");
        return CommerceEvent.builder()
                .eventId("evt_" + IDS.incrementAndGet())
                .eventType(StandardEventNames.PRODUCT_VIEWED)
                .timestamp(at)
                .tenantId(tenant)
                .identity(identity)
                .commerce(CommerceData.builder().productId(productId).build())
                .build();
    }

    static RecentlyViewedProperties props() {
        return new RecentlyViewedProperties();
    }

    @Nested
    class WhatItAccepts {

        private final RecentlyViewedDestination destination =
                new RecentlyViewedDestination(mock(StringRedisTemplate.class), props());

        @Test
        void aSignedInProductViewIsAccepted() {
            assertTrue(destination.supports(view("t", "u1", "p1", T0)));
        }

        @Test
        void anAnonymousViewIsSkippedBecauseListsAreLookedUpByUser() {
            assertFalse(destination.supports(view("t", null, "p1", T0)));
        }

        @Test
        void aDwellFollowUpIsNotASecondView() {
            CommerceEvent view = view("t", "u1", "p1", T0);
            CommerceEvent dwell = CommerceEvent.builder()
                    .eventId("evt_dwell").eventType(StandardEventNames.PRODUCT_VIEWED).timestamp(T0).tenantId("t")
                    .identity(view.identity()).commerce(view.commerce())
                    .properties(Map.of(CommerceEvent.PROPERTY_VIEW_EVENT_ID, view.eventId(),
                            CommerceEvent.PROPERTY_DWELL_TIME_MS, 4000))
                    .build();
            assertFalse(destination.supports(dwell));
        }

        @Test
        void otherEventTypesAreSkipped() {
            CommerceEvent cart = CommerceEvent.builder()
                    .eventId("evt_cart").eventType(StandardEventNames.PRODUCT_ADDED_TO_CART).tenantId("t")
                    .identity(EventIdentity.authenticated("a", "u1", "s"))
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();
            assertFalse(destination.supports(cart));
        }

        @Test
        void withATenantConfiguredOtherTenantsAreSkipped() {
            RecentlyViewedProperties p = props();
            p.setTenantId("store-a");
            RecentlyViewedDestination scoped = new RecentlyViewedDestination(mock(StringRedisTemplate.class), p);
            assertTrue(scoped.supports(view("store-a", "u1", "p1", T0)));
            assertFalse(scoped.supports(view("store-b", "u1", "p1", T0)));
        }

        @Test
        void aBlankTenantSettingMeansEveryTenant() {
            RecentlyViewedProperties p = props();
            p.setTenantId("");
            RecentlyViewedDestination unscoped = new RecentlyViewedDestination(mock(StringRedisTemplate.class), p);
            assertTrue(unscoped.supports(view("store-b", "u1", "p1", T0)));
        }

        @Test
        @SuppressWarnings("unchecked")
        void aRedisFailureIsThrownSoTheQueueRetries() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                    .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
            RecentlyViewedDestination failing = new RecentlyViewedDestination(redis, props());

            DestinationException e = assertThrows(DestinationException.class,
                    () -> failing.send(view("t", "u1", "p1", T0)));
            assertTrue(e.isRetryable());
        }

        @Test
        void aZeroLengthListIsRefusedAtStartup() {
            RecentlyViewedProperties p = props();
            p.setMaxItems(0);
            assertThrows(IllegalArgumentException.class,
                    () -> new RecentlyViewedDestination(mock(StringRedisTemplate.class), p));
        }
    }
}
