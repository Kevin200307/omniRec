// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore.SaveOutcome;
import io.omnirec.storage.config.EventStorageProperties;
import io.omnirec.storage.config.EventStorageProperties.Provider;
import io.omnirec.storage.jdbc.StorageDatabase;
import io.omnirec.storage.jdbc.StorageSchemaMigrator;
import io.omnirec.storage.timescale.TimescaleEventStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.omnirec.storage.StorageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TimescaleEventStore against a real TimescaleDB server: the timescale
 * migrations, the hypertable, idempotency on its time-inclusive key, the
 * inherited history query, and the retention policy.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RealTimescaleEventStoreTest {

    @Container
    static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));

    private static StorageDatabase database;
    private static TimescaleEventStore store;

    @BeforeAll
    static void migrate() {
        EventStorageProperties.Postgres config = new EventStorageProperties.Postgres();
        config.setUrl(TIMESCALE.getJdbcUrl());
        config.setUsername(TIMESCALE.getUsername());
        config.setPassword(TIMESCALE.getPassword());
        database = StorageDatabase.connect(config, null);
        StorageSchemaMigrator.migrate(database, Provider.TIMESCALE, Duration.ofDays(1));
        store = new TimescaleEventStore(database);
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

    @Test
    void commerceEventsIsAHypertableOnOccurredAt() throws SQLException {
        assertDoesNotThrow(store::verifyHypertable);

        List<String> dimension = strings("SELECT column_name FROM timescaledb_information.dimensions "
                + "WHERE hypertable_schema = 'omnirec' AND hypertable_name = 'commerce_events'");
        assertEquals(List.of("occurred_at"), dimension);
    }

    @Test
    void appliesTheCommonAndTimescaleMigrationsOnly() throws SQLException {
        List<String> applied = strings("SELECT version || ':' || description FROM omnirec."
                + StorageSchemaMigrator.HISTORY_TABLE + " WHERE success AND version IS NOT NULL ORDER BY installed_rank");

        assertEquals(List.of("1:create commerce events", "2:create identity links",
                "3:create customer history indexes", "4:timescale hypertable"), applied);
    }

    @Test
    void persistsEventsIntoChunksAndReadsThemBack() throws SQLException {
        String tenant = tenant();
        CommerceEvent original = fullyPopulatedPurchase(tenant);

        assertEquals(SaveOutcome.STORED, store.save(original));

        assertEquals(original, store.findCustomerEvents(tenant, "user_full", EventQuery.firstPage(10)).events().get(0));
        assertFalse(strings("SELECT chunk_name FROM timescaledb_information.chunks "
                + "WHERE hypertable_name = 'commerce_events'").isEmpty(), "the row must live in a chunk");
    }

    @Test
    void aRedeliveredEventIsNotInsertedTwice() throws SQLException {
        String tenant = tenant();
        CommerceEvent event = anonymousView(tenant, "anon_1", "p1", T0);

        assertEquals(SaveOutcome.STORED, store.save(event));
        assertEquals(SaveOutcome.DUPLICATE, store.save(event));

        assertEquals(List.of("1"), strings("SELECT count(*) FROM omnirec.commerce_events WHERE tenant_id = ?", tenant));
    }

    @Test
    void customerHistoryWithLinkedAnonymousEventsWorksUnchanged() {
        String tenant = tenant();
        store.save(anonymousView(tenant, "anon_t", "p1", T0));
        store.save(identify(tenant, "anon_t", "user_t", T0.plusSeconds(1)));
        store.save(userView(tenant, "anon_t", "user_t", "p2", T0.plusSeconds(2)));
        // Spread across several one-day chunks, so paging crosses chunk boundaries.
        for (int i = 1; i <= 6; i++) {
            store.save(userView(tenant, "anon_t", "user_t", "old" + i, T0.minus(Duration.ofDays(i))));
        }

        List<CommerceEvent> all = allPages(store, tenant, "user_t", EventQuery.firstPage(4));

        assertEquals(9, all.size());
        assertEquals("p2", all.get(0).commerce().productId());
        assertEquals("old6", all.get(8).commerce().productId());
    }

    @Test
    void appliesAndRemovesTheRetentionPolicyFromConfiguration() throws SQLException {
        String jobs = "SELECT config::text FROM timescaledb_information.jobs "
                + "WHERE proc_name = 'policy_retention' AND hypertable_name = 'commerce_events'";

        store.applyRetentionPolicy(Duration.ofDays(400));
        List<String> configured = strings(jobs);
        assertEquals(1, configured.size());
        assertTrue(configured.get(0).contains("400 days"), configured.get(0));

        store.applyRetentionPolicy(Duration.ofDays(30));
        assertEquals(1, strings(jobs).size(), "re-applying replaces the policy rather than adding a second");

        store.applyRetentionPolicy(null);
        assertTrue(strings(jobs).isEmpty(), "retention unset in config must remove the policy");
    }
}
