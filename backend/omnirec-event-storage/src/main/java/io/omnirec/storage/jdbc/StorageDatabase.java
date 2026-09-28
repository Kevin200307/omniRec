// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnirec.storage.config.EventStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.regex.Pattern;

/**
 * The storage subsystem's own connection pool.
 *
 * Held here rather than registered as a {@code DataSource} bean on purpose. A
 * {@code DataSource} bean would be picked up as the application's primary
 * database — making Spring Boot's own DataSource back off, and pointing an
 * application's JPA or Flyway at the event store. Keeping it private means
 * enabling storage changes nothing else about the application.
 */
public final class StorageDatabase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StorageDatabase.class);

    /**
     * The schema name is spliced into SQL (identifiers cannot be bound as
     * parameters), so it is restricted to a plain unquoted identifier.
     */
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final HikariDataSource dataSource;
    private final String schema;

    private StorageDatabase(HikariDataSource dataSource, String schema) {
        this.dataSource = dataSource;
        this.schema = schema;
    }

    public static StorageDatabase connect(EventStorageProperties.Postgres config, MeterRegistry meterRegistry) {
        String schema = validSchema(config.getSchema());
        PostgresConnectionUrl.Resolved url =
                PostgresConnectionUrl.resolve(config.getUrl(), config.getUsername(), config.getPassword());

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("omnirec-storage");
        hikari.setJdbcUrl(url.jdbcUrl());
        hikari.setUsername(url.username());
        hikari.setPassword(url.password());
        hikari.setMaximumPoolSize(config.getMaximumPoolSize());
        hikari.setConnectionTimeout(config.getConnectionTimeout().toMillis());
        // A server error's "Detail:" line can quote the offending row values.
        // Exception messages end up in logs and in the dead-letter reason
        // header, so keep them to the error itself.
        hikari.addDataSourceProperty("logServerErrorDetail", "false");
        if (meterRegistry != null) {
            hikari.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        }

        // Host only: the URL may carry query parameters, and never the password.
        log.info("Connecting historical event storage to PostgreSQL at {} (schema {})", url.host(), schema);
        return new StorageDatabase(new HikariDataSource(hikari), schema);
    }

    /** For tests that bring their own DataSource. */
    public static StorageDatabase wrap(HikariDataSource dataSource, String schema) {
        return new StorageDatabase(dataSource, validSchema(schema));
    }

    public static String validSchema(String schema) {
        if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalStateException("omnirec.storage.postgres.schema must be a lowercase SQL identifier "
                    + "([a-z_][a-z0-9_]*, at most 63 characters)");
        }
        return schema;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String schema() {
        return schema;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
