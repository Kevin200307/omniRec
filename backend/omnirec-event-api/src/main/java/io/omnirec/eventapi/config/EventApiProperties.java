// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.config;

import io.omnirec.commerce.validation.ValidationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the Event API. See docs/configuration.md.
 *
 * Note what is <em>not</em> here: provider credentials. Those belong to each
 * destination starter, are read from environment variables or a secret manager,
 * and are never reachable from anything the browser talks to.
 */
@ConfigurationProperties(prefix = "omnirec.events")
public class EventApiProperties {

    /** Publishable key -> tenant. Keyed by tenant id. */
    private Map<String, Tenant> tenants = new LinkedHashMap<>();

    /**
     * How event writes are authenticated.
     * <ul>
     *   <li>{@code auto} (default): {@code keys} when any tenant has an api-key, otherwise {@code open};</li>
     *   <li>{@code open}: no key needed; keyless traffic goes to {@link #defaultTenantId}. Browser
     *       requests must come from an allowed origin or the collector's own origin;</li>
     *   <li>{@code keys}: every write needs a valid publishable key.</li>
     * </ul>
     */
    private AuthMode authMode = AuthMode.AUTO;

    /**
     * @deprecated use {@code auth-mode: open}. Kept so existing configuration
     * keeps working; {@code true} means open mode.
     */
    @Deprecated
    private boolean allowAnonymousIngestion = false;

    /** Tenant assigned to keyless events in open mode. */
    private String defaultTenantId = "default";

    /** Where tenants come from: {@code file} (these properties) or {@code jdbc} (table omnirec_tenant). */
    private TenantSource tenantSource = TenantSource.FILE;

    /** Validation mode for tenants that do not set one. */
    private ValidationMode defaultValidationMode = ValidationMode.PERMISSIVE;

    /** Tracking plans for the default tenant in open mode, when it is not configured explicitly. */
    private List<String> defaultPlanPaths = List.of();

    private final Jdbc jdbc = new Jdbc();

    /** Hard cap on events per batch request. Anything larger is rejected with 413. */
    private int maxBatchSize = 500;

    /** Hard cap on request body bytes. Also enforced by the servlet container. */
    private int maxPayloadBytes = 1024 * 1024;

    /** How long an eventId is remembered for deduplication. */
    private Duration deduplicationWindow = Duration.ofHours(24);

    /** Drop the client IP after using it for geo lookup. */
    private boolean retainIpAddress = false;

    private final RateLimit rateLimit = new RateLimit();
    private final Cors cors = new Cors();

    public Map<String, Tenant> getTenants() { return tenants; }
    public void setTenants(Map<String, Tenant> tenants) { this.tenants = tenants; }
    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }
    @Deprecated public boolean isAllowAnonymousIngestion() { return allowAnonymousIngestion; }
    @Deprecated public void setAllowAnonymousIngestion(boolean allowAnonymousIngestion) { this.allowAnonymousIngestion = allowAnonymousIngestion; }
    public TenantSource getTenantSource() { return tenantSource; }
    public void setTenantSource(TenantSource tenantSource) { this.tenantSource = tenantSource; }
    public ValidationMode getDefaultValidationMode() { return defaultValidationMode; }
    public void setDefaultValidationMode(ValidationMode defaultValidationMode) { this.defaultValidationMode = defaultValidationMode; }
    public List<String> getDefaultPlanPaths() { return defaultPlanPaths; }
    public void setDefaultPlanPaths(List<String> defaultPlanPaths) { this.defaultPlanPaths = defaultPlanPaths; }
    public Jdbc getJdbc() { return jdbc; }
    public String getDefaultTenantId() { return defaultTenantId; }
    public void setDefaultTenantId(String defaultTenantId) { this.defaultTenantId = defaultTenantId; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public int getMaxPayloadBytes() { return maxPayloadBytes; }
    public void setMaxPayloadBytes(int maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
    public Duration getDeduplicationWindow() { return deduplicationWindow; }
    public void setDeduplicationWindow(Duration deduplicationWindow) { this.deduplicationWindow = deduplicationWindow; }
    public boolean isRetainIpAddress() { return retainIpAddress; }
    public void setRetainIpAddress(boolean retainIpAddress) { this.retainIpAddress = retainIpAddress; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Cors getCors() { return cors; }

    public static class Tenant {
        /** Publishable key, e.g. "pk_live_...". Safe to embed in frontend code. */
        private String apiKey;
        /**
         * Secret, server-side key, e.g. "sk_live_...". Reads this tenant's
         * historical events (omnirec-event-storage). Never put it in frontend
         * code; it must differ from every publishable key, or startup fails.
         */
        private String secretKey;
        private boolean enabled = true;

        /** Browser origins allowed to send events for this tenant, in addition to the global CORS list. */
        private List<String> allowedOrigins = List.of();

        /** Unknown events: {@code permissive} accepts and flags them, {@code strict} rejects them. Defaults to default-validation-mode. */
        private ValidationMode validationMode;

        /** Tracking plan files, as Spring resource locations (classpath:..., file:...). */
        private List<String> planPaths = List.of();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public ValidationMode getValidationMode() { return validationMode; }
        public void setValidationMode(ValidationMode validationMode) { this.validationMode = validationMode; }
        public List<String> getPlanPaths() { return planPaths; }
        public void setPlanPaths(List<String> planPaths) { this.planPaths = planPaths; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public enum AuthMode { AUTO, OPEN, KEYS }

    public enum TenantSource { FILE, JDBC }

    /** Connection for {@code tenant-source: jdbc}. Keys are stored as SHA-256 hashes. */
    public static class Jdbc {
        private String url;
        private String username;
        private String password;
        private String table = "omnirec_tenant";
        /** How often tenant changes (new keys, rotated keys, plans) are picked up. */
        private Duration refreshInterval = Duration.ofSeconds(30);

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public Duration getRefreshInterval() { return refreshInterval; }
        public void setRefreshInterval(Duration refreshInterval) { this.refreshInterval = refreshInterval; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** Requests per window, per tenant per client IP. */
        private int requestsPerWindow = 300;
        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRequestsPerWindow() { return requestsPerWindow; }
        public void setRequestsPerWindow(int requestsPerWindow) { this.requestsPerWindow = requestsPerWindow; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class Cors {
        /** Storefront origins allowed to POST events from the browser. */
        private List<String> allowedOrigins = List.of();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
