// SPDX-License-Identifier: Apache-2.0
package io.omnirec.googlerecai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omnirec.recommendation.google-rec-ai")
public class GoogleRecAiProperties {

    private boolean enabled = false;
    private String projectNumber;
    private String catalogId = "default_catalog";
    private String eventStoreId = "default_event_store";
    /** Serving config id created for a trained model, used to build the "placement" resource name for Predict. */
    private String servingConfigId;
    /** Path to a service account JSON key file. */
    private String credentialsFile;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProjectNumber() { return projectNumber; }
    public void setProjectNumber(String projectNumber) { this.projectNumber = projectNumber; }
    public String getCatalogId() { return catalogId; }
    public void setCatalogId(String catalogId) { this.catalogId = catalogId; }
    public String getEventStoreId() { return eventStoreId; }
    public void setEventStoreId(String eventStoreId) { this.eventStoreId = eventStoreId; }
    public String getServingConfigId() { return servingConfigId; }
    public void setServingConfigId(String servingConfigId) { this.servingConfigId = servingConfigId; }
    public String getCredentialsFile() { return credentialsFile; }
    public void setCredentialsFile(String credentialsFile) { this.credentialsFile = credentialsFile; }

    String eventStoreParent() {
        return "projects/%s/locations/global/catalogs/%s/eventStores/%s".formatted(projectNumber, catalogId, eventStoreId);
    }

    String placement() {
        return "projects/%s/locations/global/catalogs/%s/servingConfigs/%s".formatted(projectNumber, catalogId, servingConfigId);
    }
}
