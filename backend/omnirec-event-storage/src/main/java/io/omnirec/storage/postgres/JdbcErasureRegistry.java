// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.postgres;

import io.omnirec.commerce.privacy.ErasureRegistry;
import io.omnirec.commerce.privacy.InMemoryErasureRegistry;
import io.omnirec.storage.jdbc.SqlFailures;
import io.omnirec.storage.jdbc.StorageDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tombstones in the {@code erasure_tombstones} table, checked from memory:
 * every fingerprint is loaded at startup and refreshed periodically, so a
 * deletion made through another instance takes effect here within the refresh
 * interval, and checking an event costs a hash lookup rather than a query.
 * A deletion made through this instance takes effect immediately.
 */
public class JdbcErasureRegistry extends InMemoryErasureRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JdbcErasureRegistry.class);

    private final DataSource dataSource;
    private final String table;
    private final Duration refreshInterval;
    private ScheduledExecutorService executor;

    public JdbcErasureRegistry(StorageDatabase database, Duration refreshInterval) {
        this(database.dataSource(), database.schema(), refreshInterval);
    }

    public JdbcErasureRegistry(DataSource dataSource, String schema, Duration refreshInterval) {
        this.dataSource = dataSource;
        this.table = StorageDatabase.validSchema(schema) + ".erasure_tombstones";
        this.refreshInterval = refreshInterval;
        refresh();
    }

    /** Writes the tombstones before anything is deleted, so no event slips in between. */
    @Override
    public void record(String tenantId, String userId, Collection<String> anonymousIds) {
        List<String> fingerprints = new ArrayList<>();
        if (userId != null && !userId.isBlank()) fingerprints.add(ErasureRegistry.fingerprint(tenantId, userId));
        for (String id : anonymousIds) {
            if (id != null && !id.isBlank()) fingerprints.add(ErasureRegistry.fingerprint(tenantId, id));
        }
        String sql = "INSERT INTO " + table + " (tenant_id, fingerprint) VALUES (?, ?) ON CONFLICT DO NOTHING";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            for (String fingerprint : fingerprints) {
                insert.setString(1, tenantId);
                insert.setString(2, fingerprint);
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException e) {
            throw SqlFailures.translate("could not record erasure tombstones", e);
        }
        addFingerprints(fingerprints);
    }

    /** Loads every tombstone. Tombstones are never removed, so loading only adds. */
    public void refresh() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT fingerprint FROM " + table);
             ResultSet rs = select.executeQuery()) {
            List<String> loaded = new ArrayList<>();
            while (rs.next()) loaded.add(rs.getString(1));
            addFingerprints(loaded);
        } catch (SQLException e) {
            throw SqlFailures.translate("could not load erasure tombstones", e);
        }
    }

    @Override
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "omnirec-erasure-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("Could not refresh erasure tombstones: {}", e.getMessage());
            }
        }, refreshInterval.toMillis(), refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
