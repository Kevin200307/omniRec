package io.omnirec.googlemerchant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omnirec.providers.google-merchant")
public class GoogleMerchantProperties {

    private boolean enabled = false;

    /** "push" (per-item Content API calls) or "scheduled-fetch" (a hosted feed file Google polls). Exactly one of GoogleMerchantCatalogProvider/GoogleMerchantFeedFileProvider is registered based on this. */
    private String mode = "push";

    /** Merchant Center account ID. Required in both modes — push mode calls the API with it; scheduled-fetch mode's file format assumes it implicitly via your Merchant Center feed registration. */
    private String merchantId;

    /** Path to a service account JSON key file with Content API access granted in Merchant Center. Required for push mode and for the feed:diagnose check in either mode. */
    private String credentialsFile;

    private String contentLanguage = "en";
    private String targetCountry = "US";
    private String channel = "online";

    /** CatalogItem carries no currency field (store-wide, not per-item) — ISO 4217 code, e.g. "USD". */
    private String currency = "USD";

    /**
     * Every item needs a landing-page URL and CatalogItem doesn't carry one
     * (it's almost always a per-store URL pattern, not per-item data) — set
     * to e.g. "https://mystore.com/products/{productId}". Required in both
     * modes; if unset, every item is rejected with a config-error reason
     * rather than a fabricated or broken link being sent.
     */
    private String productUrlTemplate;

    /** scheduled-fetch mode only: "tsv" (default, per Google's own spec) or "csv". xml/json are not implemented for this provider — Google's feed spec is delimited text, not either of those. */
    private String feedFormat = "tsv";

    /** scheduled-fetch mode only: local file path the generated feed is written to, for Google to poll (e.g. served over HTTP by your own infra, or synced to a bucket by a separate process — this starter only writes the file). */
    private String feedOutputPath;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getCredentialsFile() { return credentialsFile; }
    public void setCredentialsFile(String credentialsFile) { this.credentialsFile = credentialsFile; }
    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }
    public String getTargetCountry() { return targetCountry; }
    public void setTargetCountry(String targetCountry) { this.targetCountry = targetCountry; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getProductUrlTemplate() { return productUrlTemplate; }
    public void setProductUrlTemplate(String productUrlTemplate) { this.productUrlTemplate = productUrlTemplate; }
    public String getFeedFormat() { return feedFormat; }
    public void setFeedFormat(String feedFormat) { this.feedFormat = feedFormat; }
    public String getFeedOutputPath() { return feedOutputPath; }
    public void setFeedOutputPath(String feedOutputPath) { this.feedOutputPath = feedOutputPath; }
}
