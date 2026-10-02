// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.config;

import io.omnirec.commerce.identity.IdentityLinkStore;
import io.omnirec.commerce.privacy.ErasureRegistry;
import io.omnirec.storage.api.CustomerErasureController;
import io.omnirec.storage.postgres.JdbcErasureRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnirec.commerce.storage.EventStore;
import io.omnirec.eventapi.config.EventApiProperties;
import io.omnirec.storage.api.CustomerHistoryAuthFilter;
import io.omnirec.storage.api.CustomerHistoryController;
import io.omnirec.storage.api.CustomerHistoryExceptionHandler;
import io.omnirec.storage.api.HistoryAccessAuthenticator;
import io.omnirec.storage.config.EventStorageProperties.Provider;
import io.omnirec.storage.jdbc.StorageDatabase;
import io.omnirec.storage.jdbc.StorageSchemaMigrator;
import io.omnirec.storage.metrics.StorageMetrics;
import io.omnirec.storage.postgres.PostgresEventStore;
import io.omnirec.storage.postgres.PostgresRetentionJob;
import io.omnirec.storage.timescale.TimescaleEventStore;
import io.omnirec.storage.worker.EventStorageDestination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Historical event storage. Everything here is behind
 * {@code omnirec.storage.enabled=true}; with it off (the default) this module
 * contributes no bean, no queue, and no database connection, and the pipeline
 * is exactly what it was without it.
 *
 * With it on:
 * <pre>
 *   StorageDatabase     private connection pool + Flyway migrations
 *   EventStore          PostgresEventStore | TimescaleEventStore, by provider
 *   event-storage       an EventDestination, so the pipeline gives it its own
 *                       queue, retry tiers, and dead-letter queue
 *   retention           batched purge (postgres) | retention policy (timescale)
 *   history API         GET /v1/customers/{customerId}/events
 * </pre>
 */
@AutoConfiguration(afterName = {
        // Strings, not class literals: actuator is optional.
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
})
@ConditionalOnProperty(prefix = "omnirec.storage", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EventStorageProperties.class)
public class EventStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventStorageAutoConfiguration.class);

    /**
     * Connects and brings the schema up to date before anything can consume:
     * the storage listener starts only after the context has refreshed, and
     * this bean is created during refresh. A database that is unreachable or
     * cannot be migrated fails startup — with storage enabled, a silent
     * start that drops every event into retries would be worse.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StorageDatabase omnirecStorageDatabase(EventStorageProperties properties,
                                                  ObjectProvider<MeterRegistry> meterRegistry) {
        StorageDatabase database = StorageDatabase.connect(properties.getPostgres(), meterRegistry.getIfAvailable());
        try {
            if (properties.isMigrateOnStartup()) {
                StorageSchemaMigrator.migrate(database, properties.getProvider(),
                        properties.getTimescale().getChunkInterval());
            } else {
                StorageSchemaMigrator.validate(database, properties.getProvider(),
                        properties.getTimescale().getChunkInterval());
            }
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
        return database;
    }

    @Bean
    @ConditionalOnMissingBean(EventStore.class)
    public EventStore omnirecEventStore(StorageDatabase database, EventStorageProperties properties) {
        if (properties.getProvider() == Provider.TIMESCALE) {
            if (!properties.getRetention().getTenants().isEmpty()) {
                throw new IllegalStateException("omnirec.storage.retention.tenants is supported by the postgres "
                        + "provider only: TimescaleDB retention drops whole chunks across all tenants");
            }
            TimescaleEventStore store = new TimescaleEventStore(database);
            store.verifyHypertable();
            store.applyRetentionPolicy(properties.getRetention().getMaxAge());
            return store;
        }
        if (properties.getRetention().getMaxAge() == null) {
            log.warn("omnirec.storage.retention.max-age is not set: historical events are kept indefinitely. "
                    + "Set it deliberately (for example 400d) to bound storage.");
        }
        return new PostgresEventStore(database);
    }

    @Bean
    @ConditionalOnMissingBean
    public StorageMetrics omnirecStorageMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new StorageMetrics(meterRegistry.getIfAvailable());
    }

    /**
     * Tombstones of deleted customers, in the storage database. Ingestion and
     * the storage worker both consult it, so a deleted customer's late events
     * are dropped.
     */
    @Bean
    @ConditionalOnMissingBean(ErasureRegistry.class)
    public ErasureRegistry omnirecErasureRegistry(StorageDatabase database, EventStorageProperties properties) {
        return new JdbcErasureRegistry(database, properties.getErasureRefreshInterval());
    }

    /** The storage worker. Being an EventDestination is all it takes to get a durable queue. */
    @Bean
    @ConditionalOnMissingBean
    public EventStorageDestination omnirecEventStorageDestination(EventStore store, StorageMetrics metrics,
                                                                  ErasureRegistry erasures) {
        log.info("Historical event storage enabled: events are queued to destination '{}' and written "
                + "asynchronously by the storage worker", EventStorageDestination.ID);
        return new EventStorageDestination(store, metrics, erasures);
    }

    /** postgres provider only; TimescaleDB enforces retention itself through its policy. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "omnirec.storage", name = "provider", havingValue = "postgres", matchIfMissing = true)
    static class PostgresRetentionConfiguration {

        /** Inert when retention.max-age is unset (an empty environment variable included). */
        @Bean
        @ConditionalOnMissingBean
        public PostgresRetentionJob omnirecStorageRetentionJob(EventStore store, EventStorageProperties properties) {
            boolean anyRetention = properties.getRetention().getMaxAge() != null
                    || !properties.getRetention().getTenants().isEmpty();
            if (anyRetention && !(store instanceof PostgresEventStore)) {
                throw new IllegalStateException("omnirec.storage.retention.max-age with the postgres provider "
                        + "requires the PostgresEventStore, but a custom EventStore bean is defined");
            }
            EventStorageProperties.Retention retention = properties.getRetention();
            return new PostgresRetentionJob(store instanceof PostgresEventStore postgres ? postgres : null,
                    retention.getMaxAge(), retention.getTenants(), retention.getPurgeInterval(),
                    retention.getPurgeBatchSize());
        }
    }

    /**
     * GET /v1/customers/{customerId}/events. Server-to-server only: there is no
     * CORS mapping, because the key it takes must never be in a browser.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "omnirec.storage.history-api", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class HistoryApiConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public HistoryAccessAuthenticator omnirecHistoryAccessAuthenticator(
                EventApiProperties eventApiProperties, EventStorageProperties properties,
                org.springframework.beans.factory.ObjectProvider<io.omnirec.eventapi.tenant.TenantRegistry> tenants) {
            HistoryAccessAuthenticator authenticator = new HistoryAccessAuthenticator(
                    eventApiProperties, properties.getHistoryApi(), tenants.getIfAvailable());
            if (!authenticator.hasAnyKeyConfigured()) {
                log.warn("No history read keys are configured — every GET /v1/customers/{id}/events request "
                        + "will be rejected with 401. Set omnirec.events.tenants.<id>.secret-key.");
            }
            return authenticator;
        }

        @Bean
        public FilterRegistrationBean<CustomerHistoryAuthFilter> omnirecCustomerHistoryAuthFilter(
                HistoryAccessAuthenticator authenticator) {
            FilterRegistrationBean<CustomerHistoryAuthFilter> registration =
                    new FilterRegistrationBean<>(new CustomerHistoryAuthFilter(authenticator));
            registration.addUrlPatterns("/v1/customers/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
            return registration;
        }

        @Bean
        @ConditionalOnMissingBean
        public CustomerHistoryController omnirecCustomerHistoryController(
                EventStore store, EventStorageProperties properties, StorageMetrics metrics) {
            return new CustomerHistoryController(store, properties.getHistoryApi(), metrics);
        }

        /** DELETE /v1/customers/{customerId}, behind the same secret-key filter. */
        @Bean
        @ConditionalOnMissingBean
        public CustomerErasureController omnirecCustomerErasureController(
                EventStore store, ErasureRegistry erasures, ObjectProvider<IdentityLinkStore> linkStore) {
            return new CustomerErasureController(store, erasures, linkStore.getIfAvailable(), java.time.Clock.systemUTC());
        }

        @Bean
        @ConditionalOnMissingBean
        public CustomerHistoryExceptionHandler omnirecCustomerHistoryExceptionHandler() {
            return new CustomerHistoryExceptionHandler();
        }
    }
}
