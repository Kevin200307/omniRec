package io.omnirec.eventapi.config;

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
     * Allows unauthenticated writes. Development only — it disables tenant
     * isolation entirely, so startup logs a warning and refuses it outside
     * the "dev" and "test" profiles.
     */
    private boolean allowAnonymousIngestion = false;

    /** Tenant assigned to unauthenticated events when the above is on. */
    private String defaultTenantId = "default";

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
    public boolean isAllowAnonymousIngestion() { return allowAnonymousIngestion; }
    public void setAllowAnonymousIngestion(boolean allowAnonymousIngestion) { this.allowAnonymousIngestion = allowAnonymousIngestion; }
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
        private boolean enabled = true;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
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
