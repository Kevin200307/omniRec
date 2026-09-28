// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Historical event storage. See docs/event-storage.md.
 *
 * <pre>
 * omnirec:
 *   storage:
 *     enabled: true
 *     provider: postgres        # or timescale
 *     postgres:
 *       url: ${OMNIREC_DATABASE_URL}
 * </pre>
 *
 * Off by default: with {@code enabled=false} none of this module's beans
 * exist and no database connection is ever attempted.
 */
@ConfigurationProperties(prefix = "omnirec.storage")
public class EventStorageProperties {

    public enum Provider {
        /** Any PostgreSQL: local, Neon, RDS, Supabase... The URL decides where. */
        POSTGRES,
        /** TimescaleDB: the same schema as a hypertable, with chunk-based retention. */
        TIMESCALE;

        /** Directory of this provider's own migrations, beside the shared ones. */
        public String migrationDirectory() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private boolean enabled = false;

    private Provider provider = Provider.POSTGRES;

    /**
     * Run the Flyway migrations at startup. When false the schema is only
     * validated, for deployments that migrate as a separate, privileged step.
     */
    private boolean migrateOnStartup = true;

    private final Postgres postgres = new Postgres();
    private final Timescale timescale = new Timescale();
    private final Retention retention = new Retention();
    private final HistoryApi historyApi = new HistoryApi();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    public boolean isMigrateOnStartup() { return migrateOnStartup; }
    public void setMigrateOnStartup(boolean migrateOnStartup) { this.migrateOnStartup = migrateOnStartup; }
    public Postgres getPostgres() { return postgres; }
    public Timescale getTimescale() { return timescale; }
    public Retention getRetention() { return retention; }
    public HistoryApi getHistoryApi() { return historyApi; }

    /** The connection, shared by both providers — TimescaleDB is reached over the PostgreSQL protocol. */
    public static class Postgres {
        /**
         * {@code jdbc:postgresql://host:5432/db}, or the {@code postgresql://user:pass@host/db}
         * form hosted providers such as Neon hand out.
         */
        private String url;
        /** Overrides a user embedded in the URL. */
        private String username;
        /** Overrides a password embedded in the URL. Supply via environment variable, never a committed file. */
        private String password;
        /**
         * Schema holding the storage tables, created if absent. A dedicated
         * schema keeps them clear of anything else in a shared database.
         */
        private String schema = "omnirec";
        /** Pool size. The worker writes one event per consumer thread, so concurrency + a few for reads is plenty. */
        private int maximumPoolSize = 5;
        private Duration connectionTimeout = Duration.ofSeconds(10);

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public Duration getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(Duration connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }

    public static class Timescale {
        /** Hypertable chunk width. Applied when the hypertable is created; changing it later does not re-chunk. */
        private Duration chunkInterval = Duration.ofDays(7);

        public Duration getChunkInterval() { return chunkInterval; }
        public void setChunkInterval(Duration chunkInterval) { this.chunkInterval = chunkInterval; }
    }

    /**
     * How long events are kept, by event time. Unset keeps them indefinitely
     * and logs so at startup; set it deliberately.
     *
     * postgres: a background job deletes expired rows in batches.
     * timescale: a TimescaleDB retention policy drops whole expired chunks.
     */
    public static class Retention {
        private Duration maxAge;
        /** postgres only: how often the purge job runs. */
        private Duration purgeInterval = Duration.ofHours(1);
        /** postgres only: rows deleted per statement, so a purge never holds long locks. */
        private int purgeBatchSize = 5000;

        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }
        public Duration getPurgeInterval() { return purgeInterval; }
        public void setPurgeInterval(Duration purgeInterval) { this.purgeInterval = purgeInterval; }
        public int getPurgeBatchSize() { return purgeBatchSize; }
        public void setPurgeBatchSize(int purgeBatchSize) { this.purgeBatchSize = purgeBatchSize; }
    }

    /**
     * GET /v1/customers/{customerId}/events. Authenticated with a tenant's
     * secret key ({@code omnirec.events.tenants.<id>.secret-key}) or a
     * platform key below — never with a publishable key.
     */
    public static class HistoryApi {
        private boolean enabled = true;
        private int defaultLimit = 50;
        private int maxLimit = 200;
        /**
         * Server-side keys that may read several tenants, for a platform
         * operating many stores. The caller names the tenant per request with
         * {@code X-Omnirec-Tenant}; it must be one of {@code tenants}.
         */
        private Map<String, PlatformKey> platformKeys = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultLimit() { return defaultLimit; }
        public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
        public int getMaxLimit() { return maxLimit; }
        public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
        public Map<String, PlatformKey> getPlatformKeys() { return platformKeys; }
        public void setPlatformKeys(Map<String, PlatformKey> platformKeys) { this.platformKeys = platformKeys; }
    }

    public static class PlatformKey {
        private String key;
        private Set<String> tenants = new LinkedHashSet<>();

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Set<String> getTenants() { return tenants; }
        public void setTenants(Set<String> tenants) { this.tenants = tenants; }
    }
}
