package io.omnirec.openaifeed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omnirec.providers.openai-feed")
public class OpenAIFeedProperties {

    private boolean enabled = false;

    /** Merchant-specific URL issued after OpenAI's approval process — there is no fixed public endpoint to default to. */
    private String endpoint;

    /** csv | tsv | xml | json */
    private String format = "json";

    /** Sent as "Authorization: Bearer <apiKey>" if set. Not in the original spec — added because a real merchant-pushed endpoint virtually always requires auth; unset by default so nothing is silently sent. */
    private String apiKey;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
