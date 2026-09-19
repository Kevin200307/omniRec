package io.omnirec.destination.personalize;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side configuration for the Personalize destination.
 *
 * Note there is no accessKey/secretKey pair here. Static keys in configuration
 * are the thing this architecture exists to avoid, so credentials are resolved
 * through the AWS SDK's default provider chain instead: an IAM role in
 * production, environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
 * for local development. Nothing here ever reaches the browser.
 */
@ConfigurationProperties(prefix = "omnirec.destinations.amazon-personalize")
public class AmazonPersonalizeProperties {

    private boolean enabled = false;

    /** AWS region, e.g. "us-east-1". Falls back to the SDK's default region chain when unset. */
    private String region;

    /** Event Tracker ID from the Personalize dataset group. Required. */
    private String trackingId;

    /**
     * Override the Personalize Events endpoint. For LocalStack or a capture
     * server in integration tests; leave unset in production so the SDK
     * resolves the regional endpoint itself.
     */
    private String endpointOverride;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public String getEndpointOverride() { return endpointOverride; }
    public void setEndpointOverride(String endpointOverride) { this.endpointOverride = endpointOverride; }
    /**
     * Keys to send in the Personalize {@code properties} map. Each must be a
     * field of your Item interactions dataset schema (camelCase), and the
     * reserved names userId, sessionId, eventType, timestamp,
     * recommendationId and impression are refused at startup. Empty by
     * default: sending keys the schema doesn't define gets calls rejected.
     */
    private java.util.Set<String> propertyKeys = java.util.Set.of();

    public java.util.Set<String> getPropertyKeys() { return propertyKeys; }
    public void setPropertyKeys(java.util.Set<String> propertyKeys) { this.propertyKeys = propertyKeys; }
}
