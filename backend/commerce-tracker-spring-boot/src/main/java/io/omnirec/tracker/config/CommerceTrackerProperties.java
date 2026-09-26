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

    /** Key for the Event API. May be a publishable key — this SDK only writes events. */
    private String apiKey;

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
