// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.jdbc;

import io.omnirec.storage.config.EventStorageProperties.Provider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

/**
 * Applies the storage schema with Flyway.
 *
 * Run programmatically against the storage pool, not through Spring Boot's
 * Flyway auto-configuration, which would migrate the application's primary
 * database. Its history table is {@value #HISTORY_TABLE} inside the storage
 * schema, so it cannot collide with an application's own Flyway history even
 * in a shared database.
 *
 * <pre>
 *   db/omnirec-storage/common/     V1 commerce_events, V2 identity_links, V3 indexes
 *   db/omnirec-storage/postgres/   V4 primary key + BRIN index for retention
 *   db/omnirec-storage/timescale/  V4 extension, time-inclusive unique key, hypertable
 * </pre>
 */
public final class StorageSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(StorageSchemaMigrator.class);

    public static final String HISTORY_TABLE = "omnirec_storage_schema_history";
    private static final String LOCATION_ROOT = "classpath:db/omnirec-storage/";

    private StorageSchemaMigrator() {
    }

    public static void migrate(StorageDatabase database, Provider provider, Duration chunkInterval) {
        if (provider == Provider.TIMESCALE) {
            requireTimescaleAvailable(database);
        }
        MigrateResult result = flyway(database, provider, chunkInterval).migrate();
        log.info("Historical event storage schema {} is at version {} ({} migration(s) applied, provider {})",
                database.schema(), result.targetSchemaVersion == null ? "current" : result.targetSchemaVersion,
                result.migrationsExecuted, provider.migrationDirectory());
    }

    /** For {@code migrate-on-startup=false}: fail startup if the schema is missing or behind. */
    public static void validate(StorageDatabase database, Provider provider, Duration chunkInterval) {
        flyway(database, provider, chunkInterval).validate();
    }

    private static Flyway flyway(StorageDatabase database, Provider provider, Duration chunkInterval) {
        return Flyway.configure(StorageSchemaMigrator.class.getClassLoader())
                .dataSource(database.dataSource())
                .schemas(database.schema())
                .defaultSchema(database.schema())
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(LOCATION_ROOT + "common", LOCATION_ROOT + provider.migrationDirectory())
                // Only the timescale migrations reference it; Flyway ignores unused placeholders.
                .placeholders(Map.of("chunkTimeInterval", Math.max(1, chunkInterval.toSeconds()) + " seconds"))
                .load();
    }

    /**
     * TimescaleDB must be installed on the server for its extension to be
     * created. Without this check the failure would surface as an obscure
     * error halfway through a migration.
     */
    private static void requireTimescaleAvailable(StorageDatabase database) {
        try (Connection connection = database.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb'");
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("omnirec.storage.provider=timescale, but the database server has "
                        + "no timescaledb extension available. Use a TimescaleDB server (see docker-compose.yml, "
                        + "profile 'timescale') or set omnirec.storage.provider=postgres.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check for the timescaledb extension", e);
        }
    }
}
