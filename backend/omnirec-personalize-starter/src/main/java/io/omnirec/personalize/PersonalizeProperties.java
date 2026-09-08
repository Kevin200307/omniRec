package io.omnirec.personalize;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omnirec.recommendation.aws-personalize")
public class PersonalizeProperties {

    private boolean enabled = false;
    private String accessKey;
    private String secretKey;
    private String region;
    /** ARN of the deployed campaign, used for getRecommendations. */
    private String campaignArn;
    /** Event Tracker ID from the Personalize dataset group, used for putEvents. */
    private String trackingId;
    /** ARN of the Items dataset specifically (not the dataset *group* ARN) — used for catalog sync via PutItems. A dataset group has separate Users/Items/Interactions datasets, each with its own ARN. */
    private String itemsDatasetArn;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCampaignArn() { return campaignArn; }
    public void setCampaignArn(String campaignArn) { this.campaignArn = campaignArn; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public String getItemsDatasetArn() { return itemsDatasetArn; }
    public void setItemsDatasetArn(String itemsDatasetArn) { this.itemsDatasetArn = itemsDatasetArn; }
}
