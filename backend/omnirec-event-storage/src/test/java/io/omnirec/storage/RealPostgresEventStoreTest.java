// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.storage.CustomerEventPage;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore.SaveOutcome;
import io.omnirec.commerce.storage.EventStoreException;
import io.omnirec.storage.config.EventStorageProperties;
import io.omnirec.storage.config.EventStorageProperties.Provider;
import io.omnirec.storage.jdbc.StorageDatabase;
import io.omnirec.storage.jdbc.StorageSchemaMigrator;
import io.omnirec.storage.postgres.PostgresEventStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.omnirec.storage.StorageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgresEventStore against a real PostgreSQL, migrated by the real Flyway
 * scripts. Uses the stock postgres image — no extension of any kind — which is
 * what makes it representative of Neon, RDS or Supabase as well as localhost.
 *
 * Tests share one database; each uses its own tenant, which is also a standing
 * check that nothing leaks between tenants.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RealPostgresEventStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static StorageDatabase database;
    private static PostgresEventStore store;

    @BeforeAll
    static void migrate() {
        EventStorageProperties.Postgres config = new EventStorageProperties.Postgres();
        config.setUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        database = StorageDatabase.connect(config, null);
        StorageSchemaMigrator.migrate(database, Provider.POSTGRES, Duration.ofDays(7));
        store = new PostgresEventStore(database);
    }

    @AfterAll
    static void close() {
        if (database != null) database.close();
    }

    private static List<String> strings(String sql, Object... binds) throws SQLException {
        try (Connection c = database.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        }
    }

    private static int count(String sql, Object... binds) throws SQLException {
        return Integer.parseInt(strings(sql, binds).get(0));
    }

    private static EventQuery page(int limit) {
        return EventQuery.firstPage(limit);
    }

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        void migrationsCreateBothTablesInTheStorageSchema() throws SQLException {
            List<String> tables = strings("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'omnirec' ORDER BY table_name");

            assertTrue(tables.containsAll(List.of("commerce_events", "identity_links",
                    StorageSchemaMigrator.HISTORY_TABLE)), tables.toString());
        }

        @Test
        void appliesTheCommonAndPostgresMigrationsOnly() throws SQLException {
            List<String> applied = strings("SELECT version || ':' || description FROM omnirec."
                    // version IS NULL is Flyway's own "created the schema" marker row.
                    + StorageSchemaMigrator.HISTORY_TABLE + " WHERE success AND version IS NOT NULL ORDER BY installed_rank");

            assertEquals(List.of(
                    "1:create commerce events",
                    "2:create identity links",
                    "3:create customer history indexes",
                    "4:postgres event key and retention index", "5:v2 envelope columns", "6:erasure tombstones"), applied);
        }

        @Test
        void createsTheIndexesTheQueriesUse() throws SQLException {
            Set<String> indexes = new HashSet<>(strings(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'omnirec'"));

            assertTrue(indexes.containsAll(Set.of(
                    "commerce_events_pkey",
                    "commerce_events_user_history_idx",
                    "commerce_events_anonymous_history_idx",
                    "commerce_events_occurred_at_brin",
                    "identity_links_pkey",
                    "identity_links_user_idx")), indexes.toString());
        }

        @Test
        void theEventKeyIsTenantScoped() throws SQLException {
            String definition = strings("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'commerce_events_pkey'").get(0);

            assertEquals("PRIMARY KEY (tenant_id, event_id)", definition);
        }

        @Test
        void migratingAgainIsANoOp() {
            assertDoesNotThrow(() -> StorageSchemaMigrator.migrate(database, Provider.POSTGRES, Duration.ofDays(7)));
        }

        /**
         * The URL form hosted providers such as Neon hand out, with credentials
         * embedded, against a real server. Not a Neon test: Neon is PostgreSQL,
         * and this is the only part of connecting to it that differs from localhost.
         */
        @Test
        void connectsWithAHostedStyleLibpqUrl() {
            EventStorageProperties.Postgres config = new EventStorageProperties.Postgres();
            config.setUrl("postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
                    + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName()
                    + "?sslmode=disable");
            config.setSchema("omnirec_libpq_url");

            try (StorageDatabase viaLibpqUrl = StorageDatabase.connect(config, null)) {
                StorageSchemaMigrator.migrate(viaLibpqUrl, Provider.POSTGRES, Duration.ofDays(7));
                PostgresEventStore other = new PostgresEventStore(viaLibpqUrl);
                String tenant = tenant();
                assertEquals(SaveOutcome.STORED, other.save(userView(tenant, "a", "u", "p1", T0)));
                assertEquals(1, other.findCustomerEvents(tenant, "u", page(10)).events().size());
            }
        }

        /** A database created by one provider must not be silently opened by the other. */
        @Test
        void refusesToOpenAPostgresDatabaseAsTimescale() {
            assertThrows(RuntimeException.class,
                    () -> StorageSchemaMigrator.validate(database, Provider.TIMESCALE, Duration.ofDays(7)));
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        void persistsTheCanonicalEventWithoutLosingAnything() {
            String tenant = tenant();
            CommerceEvent original = fullyPopulatedPurchase(tenant);

            assertEquals(SaveOutcome.STORED, store.save(original));

            CommerceEvent stored = store.findCustomerEvents(tenant, "user_full", page(10)).events().get(0);
            assertEquals(original, stored, "the event read back must equal the event written");
        }

        @Test
        void aRedeliveredEventIsNotInsertedTwice() throws SQLException {
            String tenant = tenant();
            CommerceEvent event = anonymousView(tenant, "anon_1", "p1", T0);

            assertEquals(SaveOutcome.STORED, store.save(event));
            assertEquals(SaveOutcome.DUPLICATE, store.save(event));
            assertEquals(SaveOutcome.DUPLICATE, store.save(event));

            assertEquals(1, count("SELECT count(*) FROM omnirec.commerce_events WHERE tenant_id = ?", tenant));
        }

        /**
         * eventIds are client-supplied. A globally unique key would let one
         * tenant suppress another's event just by reusing its id.
         */
        @Test
        void theSameEventIdInTwoTenantsIsTwoEvents() throws SQLException {
            String tenantA = tenant();
            String tenantB = tenant();
            CommerceEvent a = anonymousView(tenantA, "anon_1", "p1", T0);
            CommerceEvent b = CommerceEvent.builder().eventId(a.eventId()).eventType(a.eventType())
                    .timestamp(T0).tenantId(tenantB).identity(a.identity()).build();

            assertEquals(SaveOutcome.STORED, store.save(a));
            assertEquals(SaveOutcome.STORED, store.save(b));
            assertEquals(2, count("SELECT count(*) FROM omnirec.commerce_events WHERE event_id = ?", a.eventId()));
        }

        @Test
        void anEventWithoutATenantIsRefusedPermanently() {
            CommerceEvent orphan = anonymousView(null, "anon_1", "p1", T0);

            EventStoreException e = assertThrows(EventStoreException.class, () -> store.save(orphan));
            assertFalse(e.isRetryable(), "retrying cannot give it a tenant");
        }

        /** PostgreSQL's JSONB cannot hold U+0000. That row will fail every time, so it must not be retried. */
        @Test
        void aRowTheDatabaseCanNeverAcceptIsAPermanentFailure() throws SQLException {
            String tenant = tenant();
            CommerceEvent poisoned = CommerceEvent.builder()
                    .eventId(eventId()).eventType(StandardEventNames.PRODUCT_VIEWED).timestamp(T0).tenantId(tenant)
                    .identity(EventIdentity.anonymous("anon_1", "s1"))
                    .properties(Map.of("note", "nul\u0000byte"))
                    .build();

            EventStoreException e = assertThrows(EventStoreException.class, () -> store.save(poisoned));
            assertFalse(e.isRetryable());
            assertEquals(0, count("SELECT count(*) FROM omnirec.commerce_events WHERE tenant_id = ?", tenant),
                    "nothing may be half-written");
        }
    }

    @Nested
    @DisplayName("identity links")
    class IdentityLinks {

        @Test
        void anIdentifyCreatesATenantScopedLink() throws SQLException {
            String tenant = tenant();

            store.save(identify(tenant, "anon_123", "user_456", T0));

            assertEquals(List.of("user_456"), strings("SELECT user_id FROM omnirec.identity_links "
                    + "WHERE tenant_id = ? AND anonymous_id = ?", tenant, "anon_123"));
        }

        @Test
        void identifyingDoesNotRewriteEarlierAnonymousEvents() throws SQLException {
            String tenant = tenant();
            CommerceEvent anonymous = anonymousView(tenant, "anon_123", "p1", T0);
            store.save(anonymous);

            store.save(identify(tenant, "anon_123", "user_456", T0.plusSeconds(60)));

            assertEquals(List.of("null"), strings("SELECT coalesce(user_id, 'null') FROM omnirec.commerce_events "
                    + "WHERE tenant_id = ? AND event_id = ?", tenant, anonymous.eventId()),
                    "the row must keep the identity it was captured with");
        }

        @Test
        void theCustomerJourneyIncludesLinkedAnonymousHistory() {
            String tenant = tenant();
            CommerceEvent browsing = anonymousView(tenant, "anon_123", "p1", T0);
            CommerceEvent identify = identify(tenant, "anon_123", "user_456", T0.plusSeconds(60));
            CommerceEvent afterLogin = userView(tenant, "anon_123", "user_456", "p2", T0.plusSeconds(120));
            store.save(browsing);
            store.save(identify);
            store.save(afterLogin);

            List<CommerceEvent> journey = store.findCustomerEvents(tenant, "user_456", page(50)).events();

            assertEquals(List.of(afterLogin.eventId(), identify.eventId(), browsing.eventId()),
                    journey.stream().map(CommerceEvent::eventId).toList(), "newest first, both halves merged");
            assertNull(journey.get(2).identity().userId(), "returned as captured, not rewritten on the way out");
        }

        @Test
        void aCustomerWithTwoDevicesSeesBoth() {
            String tenant = tenant();
            store.save(anonymousView(tenant, "anon_laptop", "p1", T0));
            store.save(anonymousView(tenant, "anon_phone", "p2", T0.plusSeconds(1)));
            store.save(identify(tenant, "anon_laptop", "user_1", T0.plusSeconds(2)));
            store.save(identify(tenant, "anon_phone", "user_1", T0.plusSeconds(3)));

            assertEquals(4, store.findCustomerEvents(tenant, "user_1", page(50)).events().size());
        }

        @Test
        void unlinkedDevicesAndOtherCustomersEventsAreExcluded() {
            String tenant = tenant();
            store.save(identify(tenant, "anon_mine", "user_1", T0));
            store.save(anonymousView(tenant, "anon_mine", "p1", T0.plusSeconds(1)));
            store.save(anonymousView(tenant, "anon_stranger", "p2", T0.plusSeconds(2)));
            // Someone else signed in on my device later: their events carry their userId.
            store.save(userView(tenant, "anon_other_device", "user_2", "p3", T0.plusSeconds(3)));

            List<String> products = store.findCustomerEvents(tenant, "user_1", page(50)).events().stream()
                    .map(e -> e.commerce().productId()).filter(p -> p != null).toList();

            assertEquals(List.of("p1"), products);
        }

        @Test
        void aDeviceRelinkedToAnotherCustomerFollowsTheMostRecentLink() throws SQLException {
            String tenant = tenant();
            store.save(identify(tenant, "anon_shared", "user_1", T0));
            store.save(identify(tenant, "anon_shared", "user_2", T0.plusSeconds(60)));

            assertEquals(List.of("user_2"), strings("SELECT user_id FROM omnirec.identity_links "
                    + "WHERE tenant_id = ? AND anonymous_id = ?", tenant, "anon_shared"));
        }

        /** Retries deliver out of order: an old login arriving late must not undo a newer one. */
        @Test
        void aLateArrivingOlderLinkDoesNotOverrideANewerOne() throws SQLException {
            String tenant = tenant();
            store.save(identify(tenant, "anon_shared", "user_2", T0.plusSeconds(60)));
            store.save(identify(tenant, "anon_shared", "user_1", T0));

            assertEquals(List.of("user_2"), strings("SELECT user_id FROM omnirec.identity_links "
                    + "WHERE tenant_id = ? AND anonymous_id = ?", tenant, "anon_shared"));
            assertEquals(List.of(T0.toString()), strings("SELECT to_char(first_seen_at AT TIME ZONE 'UTC', "
                    + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM omnirec.identity_links WHERE tenant_id = ?", tenant));
        }
    }

    @Nested
    @DisplayName("tenant isolation")
    class TenantIsolation {

        @Test
        void eachTenantSeesOnlyItsOwnEventsForTheSameCustomerId() {
            String tenantA = tenant();
            String tenantB = tenant();
            CommerceEvent a = userView(tenantA, "anon_1", "user_456", "pA", T0);
            CommerceEvent b = userView(tenantB, "anon_1", "user_456", "pB", T0);
            store.save(a);
            store.save(b);

            assertEquals(List.of(a.eventId()), store.findCustomerEvents(tenantA, "user_456", page(50))
                    .events().stream().map(CommerceEvent::eventId).toList());
            assertEquals(List.of(b.eventId()), store.findCustomerEvents(tenantB, "user_456", page(50))
                    .events().stream().map(CommerceEvent::eventId).toList());
        }

        /** tenant_A: anon_123 -> user_456 must not affect tenant_B's anon_123. */
        @Test
        void aLinkInOneTenantDoesNotAttributeAnotherTenantsVisitor() {
            String tenantA = tenant();
            String tenantB = tenant();
            store.save(anonymousView(tenantB, "anon_123", "pB", T0));
            store.save(identify(tenantA, "anon_123", "user_456", T0.plusSeconds(1)));

            CustomerEventPage tenantBHistory = store.findCustomerEvents(tenantB, "user_456", page(50));
            assertTrue(tenantBHistory.events().isEmpty(), "tenant B never linked anon_123 to anyone");

            List<CommerceEvent> tenantAHistory = store.findCustomerEvents(tenantA, "user_456", page(50)).events();
            assertEquals(1, tenantAHistory.size(), "just the identify: tenant B's browsing is not tenant A's");
            assertEquals(tenantA, tenantAHistory.get(0).tenantId());
        }
    }

    @Nested
    @DisplayName("pagination and filters")
    class Pagination {

        @Test
        void anUnknownCustomerHasAnEmptyHistoryAndNoCursor() {
            CustomerEventPage page = store.findCustomerEvents(tenant(), "nobody", page(50));

            assertTrue(page.events().isEmpty());
            assertFalse(page.hasMore());
        }

        /**
         * Most events share timestamps with others here, which is exactly the
         * case a time-only cursor gets wrong at a page boundary.
         */
        @Test
        void walksEveryPageWithoutDuplicatesOrGapsEvenWithIdenticalTimestamps() {
            String tenant = tenant();
            List<String> expected = new ArrayList<>();
            store.save(identify(tenant, "anon_p", "user_p", T0.minusSeconds(3600)));
            for (int i = 0; i < 23; i++) {
                Instant at = T0.plusSeconds(i / 4);   // groups of four events per second
                CommerceEvent e = i % 3 == 0
                        ? anonymousView(tenant, "anon_p", "p" + i, at)
                        : userView(tenant, "anon_p", "user_p", "p" + i, at);
                store.save(e);
                expected.add(e.eventId());
            }

            List<CommerceEvent> all = allPages(store, tenant, "user_p", page(5)).stream()
                    .filter(e -> !e.eventType().equals(StandardEventNames.IDENTIFY)).toList();

            List<String> ids = all.stream().map(CommerceEvent::eventId).toList();
            assertEquals(new HashSet<>(ids).size(), ids.size(), "no event may appear on two pages");
            assertEquals(new HashSet<>(expected), new HashSet<>(ids), "no event may be skipped");
            for (int i = 1; i < all.size(); i++) {
                CommerceEvent prev = all.get(i - 1);
                CommerceEvent next = all.get(i);
                int byTime = next.timestamp().compareTo(prev.timestamp());
                assertTrue(byTime < 0 || (byTime == 0 && next.eventId().compareTo(prev.eventId()) < 0),
                        "order must be (occurredAt DESC, eventId DESC) across page boundaries");
            }
        }

        @Test
        void theFirstPageSaysThereIsASecondAndTheLastSaysThereIsNot() {
            String tenant = tenant();
            for (int i = 0; i < 3; i++) store.save(userView(tenant, "a", "u", "p" + i, T0.plusSeconds(i)));

            CustomerEventPage first = store.findCustomerEvents(tenant, "u", page(2));
            CustomerEventPage second = store.findCustomerEvents(tenant, "u", page(2).after(first.nextCursor()));

            assertEquals(2, first.events().size());
            assertTrue(first.hasMore());
            assertEquals(1, second.events().size());
            assertFalse(second.hasMore());
            assertEquals("p0", second.events().get(0).commerce().productId());
        }

        @Test
        void filtersByEventType() {
            String tenant = tenant();
            store.save(userView(tenant, "a", "u", "p1", T0));
            store.save(event(tenant, StandardEventNames.PRODUCT_ADDED_TO_CART, EventIdentity.authenticated("a", "u", "s"), "p2", T0));
            store.save(event(tenant, StandardEventNames.SEARCH_PERFORMED, EventIdentity.authenticated("a", "u", "s"), null, T0));

            List<EventName> types = store.findCustomerEvents(tenant, "u",
                            new EventQuery(50, null, null, null, Set.of(StandardEventNames.PRODUCT_VIEWED, StandardEventNames.PRODUCT_ADDED_TO_CART)))
                    .events().stream().map(CommerceEvent::eventType).toList();

            assertEquals(Set.of(StandardEventNames.PRODUCT_VIEWED, StandardEventNames.PRODUCT_ADDED_TO_CART), new HashSet<>(types));
            assertEquals(2, types.size());
        }

        @Test
        void filtersByATimeRangeWithAnInclusiveStartAndExclusiveEnd() {
            String tenant = tenant();
            for (int i = 0; i < 5; i++) store.save(userView(tenant, "a", "u", "p" + i, T0.plusSeconds(i * 60L)));

            List<String> products = store.findCustomerEvents(tenant, "u",
                            new EventQuery(50, null, T0.plusSeconds(60), T0.plusSeconds(180), Set.of()))
                    .events().stream().map(e -> e.commerce().productId()).toList();

            assertEquals(List.of("p2", "p1"), products);
        }

        @Test
        void filtersApplyToLinkedAnonymousHistoryToo() {
            String tenant = tenant();
            store.save(anonymousView(tenant, "anon_f", "p_old", T0.minusSeconds(3600)));
            store.save(anonymousView(tenant, "anon_f", "p_new", T0));
            store.save(identify(tenant, "anon_f", "user_f", T0.plusSeconds(1)));

            List<String> products = store.findCustomerEvents(tenant, "user_f",
                            new EventQuery(50, null, T0.minusSeconds(60), null, Set.of(StandardEventNames.PRODUCT_VIEWED)))
                    .events().stream().map(e -> e.commerce().productId()).toList();

            assertEquals(List.of("p_new"), products);
        }
    }

    @Nested
    @DisplayName("retention")
    class Retention {

        @Test
        void purgesEventsOlderThanTheCutoffInBatches() throws SQLException {
            String tenant = tenant();
            for (int i = 0; i < 7; i++) store.save(userView(tenant, "a", "u", "old" + i, T0.minus(Duration.ofDays(500))));
            store.save(userView(tenant, "a", "u", "fresh", T0));

            long deleted = store.deleteEventsOccurredBefore(T0.minus(Duration.ofDays(400)), 3);

            assertTrue(deleted >= 7, "other tests' rows are all newer, so at least these seven go");
            List<String> remaining = store.findCustomerEvents(tenant, "u", page(50)).events().stream()
                    .map(e -> e.commerce().productId()).toList();
            assertEquals(List.of("fresh"), remaining);
            assertEquals(1, count("SELECT count(*) FROM omnirec.identity_links WHERE tenant_id = ?", tenant),
                    "links outlive events; they are not part of the purge");
        }

        @Test
        void tenantsWithTheirOwnRetentionArePrunedIndependently() {
            Instant now = Instant.now();
            String shortLived = tenant(), longLived = tenant(), standard = tenant();
            store.save(userView(shortLived, "a", "u", "60d", now.minus(Duration.ofDays(60))));
            store.save(userView(shortLived, "a", "u", "1d", now.minus(Duration.ofDays(1))));
            store.save(userView(longLived, "a", "u", "500d", now.minus(Duration.ofDays(500))));
            store.save(userView(standard, "a", "u", "500d", now.minus(Duration.ofDays(500))));
            store.save(userView(standard, "a", "u", "1d", now.minus(Duration.ofDays(1))));

            io.omnirec.storage.postgres.PostgresRetentionJob job = new io.omnirec.storage.postgres.PostgresRetentionJob(
                    store, Duration.ofDays(400), java.util.Map.of(shortLived, Duration.ofDays(30), longLived, Duration.ofDays(1000)),
                    Duration.ofHours(1), 2);
            assertTrue(job.isEnabled());
            job.purgeNow();

            java.util.function.Function<String, List<String>> left = t -> store.findCustomerEvents(t, "u", page(50))
                    .events().stream().map(e -> e.commerce().productId()).toList();
            assertEquals(List.of("1d"), left.apply(shortLived), "a shorter tenant retention wins");
            assertEquals(List.of("500d"), left.apply(longLived), "a longer one is not cut by the global max-age");
            assertEquals(List.of("1d"), left.apply(standard), "everyone else gets the global max-age");
        }

        @Test
        void perTenantRetentionAloneEnablesTheJob() {
            var job = new io.omnirec.storage.postgres.PostgresRetentionJob(store, null,
                    java.util.Map.of("t", Duration.ofDays(1)), Duration.ofHours(1), 10);
            assertTrue(job.isEnabled());
            assertThrows(IllegalArgumentException.class, () -> new io.omnirec.storage.postgres.PostgresRetentionJob(
                    store, null, java.util.Map.of("t", Duration.ZERO), Duration.ofHours(1), 10));
        }
    }
}
