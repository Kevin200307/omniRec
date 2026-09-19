package io.omnirec.redis.state;

import io.omnirec.commerce.dedup.DeduplicationStore.ClaimResult;
import io.omnirec.commerce.identity.IdentityLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * These stores are thin wrappers over Redis commands, so the tests assert the
 * one thing that actually matters: <em>which</em> command is used. Using
 * {@code SET NX} rather than a get-then-set is the whole reason this class
 * exists, and that distinction is invisible to a behavioural test against a
 * single-threaded fake.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisStateStoresTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SetOperations<String, String> setOps;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
    }

    @Nested
    @DisplayName("deduplication")
    class Deduplication {

        private RedisDeduplicationStore store() {
            return new RedisDeduplicationStore(redis);
        }

        /**
         * The atomicity guarantee. A GET followed by a SET would let two nodes
         * both observe "absent" and both deliver — the duplicate purchase this
         * store exists to prevent.
         */
        @Test
        void claimsWithASingleAtomicSetIfAbsentHoldingAShortLease() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

            assertEquals(ClaimResult.CLAIMED, store().claim("dedup:ingest:t:evt_1", Duration.ofSeconds(30)));

            verify(valueOps).setIfAbsent("dedup:ingest:t:evt_1", RedisDeduplicationStore.PENDING, Duration.ofSeconds(30));
            verify(valueOps, never()).get(anyString());
        }

        @Test
        void reportsACompletedKeyAsADuplicate() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
            when(valueOps.get("k")).thenReturn(RedisDeduplicationStore.DONE);

            assertEquals(ClaimResult.ALREADY_COMPLETED, store().claim("k", Duration.ofSeconds(30)));
        }

        @Test
        void reportsALeasedKeyAsInProgressNotAsADuplicate() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
            when(valueOps.get("k")).thenReturn(RedisDeduplicationStore.PENDING);

            assertEquals(ClaimResult.IN_PROGRESS, store().claim("k", Duration.ofSeconds(30)));
        }

        /** The key expired between SET NX and GET: it is simply claimable now. */
        @Test
        void retriesTheClaimWhenTheKeyExpiresInBetween() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false, true);
            when(valueOps.get("k")).thenReturn(null);

            assertEquals(ClaimResult.CLAIMED, store().claim("k", Duration.ofSeconds(30)));
        }

        /**
         * A null reply means the command didn't complete. Proceeding on an
         * unconfirmed claim risks a duplicate purchase, so report "try later".
         */
        @Test
        void treatsAnUnconfirmedReplyAsInProgress() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

            assertEquals(ClaimResult.IN_PROGRESS, store().claim("k", Duration.ofSeconds(30)));
        }

        @Test
        void completesByOverwritingTheLeaseWithDone() {
            store().complete("k", Duration.ofHours(24));

            verify(valueOps).set("k", RedisDeduplicationStore.DONE, Duration.ofHours(24));
        }

        @Test
        void substitutesADefaultForANonPositiveTtlRedisWouldReject() {
            store().complete("k", Duration.ZERO);

            verify(valueOps).set("k", RedisDeduplicationStore.DONE, Duration.ofHours(24));
        }

        /** Release must be conditional (a script), or it could delete a completion. */
        @Test
        @SuppressWarnings("unchecked")
        void releasesWithAConditionalScriptNotAPlainDelete() {
            store().release("k");

            verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                    eq(List.of("k")), eq(RedisDeduplicationStore.PENDING));
            verify(redis, never()).delete(anyString());
        }

        @Test
        void isCompletedOnlyForDone() {
            when(valueOps.get("done")).thenReturn(RedisDeduplicationStore.DONE);
            when(valueOps.get("pending")).thenReturn(RedisDeduplicationStore.PENDING);

            assertTrue(store().isCompleted("done"));
            assertFalse(store().isCompleted("pending"));
        }
    }

    @Nested
    @DisplayName("identity links")
    class IdentityLinks {

        private RedisIdentityLinkStore store() {
            return new RedisIdentityLinkStore(redis, Duration.ofDays(365));
        }

        @Test
        void writesBothDirectionsOfTheLink() {
            store().link(IdentityLink.of("demo-store", "anon_A", "customer_123"));

            verify(valueOps).set(eq("identity:anon:demo-store:anon_A"), eq("customer_123"), any(Duration.class));
            verify(setOps).add("identity:user:demo-store:customer_123", "anon_A");
        }

        @Test
        void resolvesAnAnonymousIdToItsUser() {
            when(valueOps.get("identity:anon:demo-store:anon_A")).thenReturn("customer_123");

            assertEquals(Optional.of("customer_123"), store().resolveUserId("demo-store", "anon_A"));
        }

        @Test
        void returnsEmptyForAnUnknownAnonymousId() {
            when(valueOps.get(anyString())).thenReturn(null);

            assertTrue(store().resolveUserId("demo-store", "unknown").isEmpty());
        }

        @Test
        void returnsEveryDeviceKnownForAUser() {
            when(setOps.members("identity:user:demo-store:customer_123"))
                    .thenReturn(Set.of("anon_A", "anon_B"));

            List<String> ids = store().anonymousIdsFor("demo-store", "customer_123");

            assertEquals(2, ids.size());
            assertTrue(ids.containsAll(List.of("anon_A", "anon_B")));
        }

        @Test
        void returnsNothingForAnUnknownUser() {
            when(setOps.members(anyString())).thenReturn(null);

            assertEquals(List.of(), store().anonymousIdsFor("demo-store", "nobody"));
        }

        /**
         * A plain SET means the latest link overwrites the previous one, which
         * is the documented "most recent wins" rule for a shared device.
         */
        @Test
        void overwritesTheForwardLinkSoTheMostRecentUserWins() {
            RedisIdentityLinkStore store = store();

            store.link(IdentityLink.of("demo-store", "anon_shared", "user_first"));
            store.link(IdentityLink.of("demo-store", "anon_shared", "user_second"));

            verify(valueOps).set(eq("identity:anon:demo-store:anon_shared"), eq("user_first"), any(Duration.class));
            verify(valueOps).set(eq("identity:anon:demo-store:anon_shared"), eq("user_second"), any(Duration.class));
        }

        @Test
        void namespacesKeysPerTenant() {
            store().link(IdentityLink.of("tenant-a", "anon_A", "user_1"));

            verify(valueOps).set(eq("identity:anon:tenant-a:anon_A"), anyString(), any(Duration.class));
            verify(setOps).add(eq("identity:user:tenant-a:user_1"), anyString());
        }

        @Test
        void handlesASingleTenantDeploymentWithNoTenantId() {
            store().link(IdentityLink.of(null, "anon_A", "user_1"));

            verify(valueOps).set(eq("identity:anon:_:anon_A"), anyString(), any(Duration.class));
        }

        /**
         * A Redis set has no per-member expiry, so the key's TTL is refreshed on
         * every link. An active customer's links never expire; a dormant
         * device's eventually do.
         */
        @Test
        void refreshesTheSetTtlOnEveryLink() {
            store().link(IdentityLink.of("demo-store", "anon_A", "customer_123"));

            verify(redis).expire("identity:user:demo-store:customer_123", Duration.ofDays(365));
        }

        @Test
        void fallsBackToALongTtlWhenConfiguredWithANonsensicalOne() {
            new RedisIdentityLinkStore(redis, Duration.ZERO)
                    .link(IdentityLink.of("demo-store", "anon_A", "customer_123"));

            verify(redis).expire(anyString(), eq(Duration.ofDays(365)));
        }
    }
}
