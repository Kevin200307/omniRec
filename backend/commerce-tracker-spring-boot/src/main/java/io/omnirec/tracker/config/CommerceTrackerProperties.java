// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the backend SDK.
 *
 * Note this holds an {@code apiKey} for the Event API and nothing else. There
 * are no AWS or Google credentials here: a merchant's application talks only to
 * the Event API, and the Event API talks to providers. That separation is what
 * lets a merchant adopt tracking without handing their own application any
 * provider secrets at all.
 */
@ConfigurationProperties(prefix = "omnirec.tracker")
public class CommerceTrackerProperties {

    private boolean enabled = true;

    /** Base URL of the Event API, e.g. "https://events.example.com". */
    private String endpoint;

    /** Key for the Event API, only needed when it runs in keys mode. A publishable key is enough: this SDK only writes. */
    private String apiKey;

    /**
     * How unknown event names are treated before sending. {@code permissive}
     * (default) lets custom tracking-plan events through for the collector to
     * check; known events are always validated in full.
     */
    private io.omnirec.commerce.validation.ValidationMode validationMode =
            io.omnirec.commerce.validation.ValidationMode.PERMISSIVE;

    private final IdentityFilter identityFilter = new IdentityFilter();
    private final Outbox outbox = new Outbox();

    public io.omnirec.commerce.validation.ValidationMode getValidationMode() { return validationMode; }
    public void setValidationMode(io.omnirec.commerce.validation.ValidationMode validationMode) { this.validationMode = validationMode; }
    public IdentityFilter getIdentityFilter() { return identityFilter; }
    public Outbox getOutbox() { return outbox; }

    /** Reads the browser's omnirec_* cookies so tracked events join the visitor's session. */
    public static class IdentityFilter {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Transactional outbox (PostgreSQL). Events tracked inside a transaction are
     * written with it and sent after commit, so they are never lost and never
     * sent for rolled-back work.
     */
    public static class Outbox {
        private boolean enabled = false;
        private String table = "omnirec_outbox";
        /** How often leftover rows are retried. */
        private java.time.Duration relayInterval = java.time.Duration.ofSeconds(5);
        private int batchSize = 100;
        private java.time.Duration retryInitialInterval = java.time.Duration.ofSeconds(1);
        private java.time.Duration retryMaxInterval = java.time.Duration.ofMinutes(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public java.time.Duration getRelayInterval() { return relayInterval; }
        public void setRelayInterval(java.time.Duration relayInterval) { this.relayInterval = relayInterval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public java.time.Duration getRetryInitialInterval() { return retryInitialInterval; }
        public void setRetryInitialInterval(java.time.Duration retryInitialInterval) { this.retryInitialInterval = retryInitialInterval; }
        public java.time.Duration getRetryMaxInterval() { return retryMaxInterval; }
        public void setRetryMaxInterval(java.time.Duration retryMaxInterval) { this.retryMaxInterval = retryMaxInterval; }
    }

    private String tenantId;

    /**
     * Deliver on a background thread. Keep this on: synchronous delivery puts
     * an HTTP call to the Event API inside the merchant's own business
     * transaction, so our latency becomes their latency.
     */
    private boolean async = true;

    /** Bounded so an Event API outage can't exhaust the merchant's heap. */
    private int queueCapacity = 10_000;

    private int maxBatchSize = 50;

    /** Validate before sending. Leave on: a backend event is authoritative data. */
    private boolean validateEvents = true;

    /**
     * Retries for a transient failure (5xx, 408, 429, network). With the
     * defaults a batch is retried for roughly three minutes before being
     * dropped and counted.
     */
    private int maxRetries = 8;
    private java.time.Duration retryInitialInterval = java.time.Duration.ofMillis(500);
    private java.time.Duration retryMaxInterval = java.time.Duration.ofSeconds(30);

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public java.time.Duration getRetryInitialInterval() { return retryInitialInterval; }
    public void setRetryInitialInterval(java.time.Duration retryInitialInterval) { this.retryInitialInterval = retryInitialInterval; }
    public java.time.Duration getRetryMaxInterval() { return retryMaxInterval; }
    public void setRetryMaxInterval(java.time.Duration retryMaxInterval) { this.retryMaxInterval = retryMaxInterval; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public boolean isValidateEvents() { return validateEvents; }
    public void setValidateEvents(boolean validateEvents) { this.validateEvents = validateEvents; }
}
