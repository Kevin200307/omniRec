// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.storage.config.EventStorageAutoConfiguration;
import io.omnirec.storage.jdbc.StorageDatabase;
import io.omnirec.storage.postgres.PostgresEventStore;
import io.omnirec.storage.postgres.PostgresRetentionJob;
import io.omnirec.storage.timescale.TimescaleEventStore;
import io.omnirec.storage.worker.EventStorageDestination;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the auto-configuration creates for each setting of omnirec.storage.*.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventStorageAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventStorageAutoConfiguration.class));

    private static String[] connection(PostgreSQLContainer<?> db) {
        return new String[]{
                "omnirec.storage.postgres.url=" + db.getJdbcUrl(),
                "omnirec.storage.postgres.username=" + db.getUsername(),
                "omnirec.storage.postgres.password=" + db.getPassword()};
    }

    @Test
    void disabledByDefault_contributesNothingAndNeedsNoDatabase() {
        runner.run(context -> {
            assertFalse(context.containsBean("omnirecStorageDatabase"));
            assertEquals(0, context.getBeanNamesForType(EventStore.class).length);
            assertEquals(0, context.getBeanNamesForType(EventDestination.class).length);
        });
    }

    /** Explicitly disabled, with a URL that would fail if anything tried to connect. */
    @Test
    void disabledNeverTouchesTheConfiguredDatabase() {
        runner.withPropertyValues("omnirec.storage.enabled=false",
                        "omnirec.storage.postgres.url=jdbc:postgresql://unreachable.invalid:5432/none")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeanNamesForType(StorageDatabase.class).length);
                });
    }

    @Test
    void postgresProviderCreatesTheGenericStoreAndTheWorker() {
        runner.withPropertyValues("omnirec.storage.enabled=true", "omnirec.storage.provider=postgres")
                .withPropertyValues(connection(POSTGRES))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    EventStore store = context.getBean(EventStore.class);
                    assertEquals(PostgresEventStore.class, store.getClass(), "exactly the generic implementation");
                    assertEquals(EventStorageDestination.ID, context.getBean(EventDestination.class).id());
                    assertFalse(context.getBean(PostgresRetentionJob.class).isEnabled(),
                            "no max-age configured: nothing is purged");
                });
    }

    @Test
    void postgresRetentionIsSwitchedOnByMaxAge() {
        runner.withPropertyValues("omnirec.storage.enabled=true", "omnirec.storage.retention.max-age=400d")
                .withPropertyValues(connection(POSTGRES))
                .run(context -> assertTrue(context.getBean(PostgresRetentionJob.class).isEnabled()));
    }

    @Test
    void anEmptyMaxAgeFromAnUnsetEnvironmentVariableMeansNoRetention() {
        runner.withPropertyValues("omnirec.storage.enabled=true", "omnirec.storage.retention.max-age=")
                .withPropertyValues(connection(POSTGRES))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(PostgresRetentionJob.class).isEnabled());
                });
    }

    @Test
    void timescaleProviderCreatesTheTimescaleStoreWithItsRetentionPolicy() {
        runner.withPropertyValues("omnirec.storage.enabled=true", "omnirec.storage.provider=timescale",
                        "omnirec.storage.retention.max-age=400d")
                .withPropertyValues(connection(TIMESCALE))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(TimescaleEventStore.class, context.getBean(EventStore.class));
                    assertEquals(0, context.getBeanNamesForType(PostgresRetentionJob.class).length,
                            "TimescaleDB drops chunks itself; the row-deleting job must not run");
                });
    }

    /** Do not assume TimescaleDB is installed: say so clearly when it isn't. */
    @Test
    void timescaleProviderAgainstPlainPostgresFailsWithAClearMessage() {
        runner.withPropertyValues("omnirec.storage.enabled=true", "omnirec.storage.provider=timescale",
                        "omnirec.storage.postgres.schema=omnirec_ts_check")
                .withPropertyValues(connection(POSTGRES))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    Throwable root = failure;
                    while (root.getCause() != null) root = root.getCause();
                    assertTrue(root.getMessage().contains("no timescaledb extension available"), root.getMessage());
                });
    }

    @Test
    void anInvalidSchemaNameIsRefusedBeforeItCanReachSql() {
        runner.withPropertyValues("omnirec.storage.enabled=true", "omnirec.storage.postgres.schema=x; DROP TABLE y")
                .withPropertyValues(connection(POSTGRES))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
