// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.timescale;

import io.omnirec.storage.jdbc.StorageDatabase;
import io.omnirec.storage.postgres.PostgresEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * {@link io.omnirec.commerce.storage.EventStore} on TimescaleDB.
 *
 * TimescaleDB is PostgreSQL, so reads and writes are inherited unchanged: the
 * same insert, the same idempotency ({@code ON CONFLICT DO NOTHING}, against the
 * hypertable's time-inclusive unique key), the same keyset history query. What
 * this adds is only what needs the extension:
 * <ul>
 *   <li>{@code commerce_events} is a hypertable partitioned on
 *       {@code occurred_at} (created by the timescale V4 migration, verified
 *       here at startup);</li>
 *   <li>retention is a TimescaleDB policy that drops whole expired chunks,
 *       instead of the postgres provider's batched {@code DELETE}.</li>
 * </ul>
 */
public class TimescaleEventStore extends PostgresEventStore {

    private static final Logger log = LoggerFactory.getLogger(TimescaleEventStore.class);

    public TimescaleEventStore(StorageDatabase database) {
        super(database);
    }

    /** Fails startup if commerce_events is a plain table, i.e. the timescale migration did not run. */
    public void verifyHypertable() {
        String sql = "SELECT 1 FROM timescaledb_information.hypertables"
                + " WHERE hypertable_schema = ? AND hypertable_name = 'commerce_events'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(schema + ".commerce_events is not a TimescaleDB hypertable. "
                            + "Was this database created with omnirec.storage.provider=postgres?");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not verify the commerce_events hypertable", e);
        }
    }

    /**
     * Makes the database's retention policy match configuration: replaces any
     * existing policy with one dropping chunks older than {@code maxAge}, or
     * removes it when {@code maxAge} is null. Configuration is the source of
     * truth, so turning retention off in config really turns it off.
     *
     * Retention policies are a TimescaleDB Community-licensed feature; on an
     * Apache-2-only build (some hosted offerings) this fails at startup with
     * the database's own explanation, rather than silently keeping everything.
     */
    public void applyRetentionPolicy(Duration maxAge) {
        String relation = table("commerce_events");
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement remove = connection.prepareStatement(
                    "SELECT remove_retention_policy(CAST(? AS regclass), if_exists => TRUE)")) {
                remove.setString(1, relation);
                remove.execute();
            }
            if (maxAge == null) {
                log.info("No retention configured: TimescaleDB keeps historical events indefinitely. "
                        + "Set omnirec.storage.retention.max-age to bound it.");
                return;
            }
            // Days kept as days, so the policy reads "400 days" rather than "9600:00:00".
            try (PreparedStatement add = connection.prepareStatement(
                    "SELECT add_retention_policy(CAST(? AS regclass), drop_after => make_interval(days => ?, secs => ?))")) {
                add.setString(1, relation);
                add.setInt(2, Math.toIntExact(maxAge.toDays()));
                add.setDouble(3, maxAge.minusDays(maxAge.toDays()).toSeconds());
                add.execute();
            }
            log.info("TimescaleDB retention policy: chunks of {} older than {} are dropped", relation, maxAge);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not apply the TimescaleDB retention policy: " + e.getMessage(), e);
        }
    }
}
