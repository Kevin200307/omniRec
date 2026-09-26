// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.googleretail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side configuration for the Google Retail destination.
 *
 * No credentials field: authentication uses Application Default Credentials, so
 * production runs on workload identity and local development on
 * GOOGLE_APPLICATION_CREDENTIALS, with no key material in configuration.
 */
@ConfigurationProperties(prefix = "omnirec.destinations.google-retail")
public class GoogleRetailProperties {

    private boolean enabled = false;

    /** GCP project number (not the project id). Required. */
    private String projectNumber;

    private String location = "global";
    private String catalogId = "default_catalog";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProjectNumber() { return projectNumber; }
    public void setProjectNumber(String projectNumber) { this.projectNumber = projectNumber; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getCatalogId() { return catalogId; }
    public void setCatalogId(String catalogId) { this.catalogId = catalogId; }

    /** The resource name WriteUserEvent expects as its parent. */
    public String catalogParent() {
        return "projects/%s/locations/%s/catalogs/%s".formatted(projectNumber, location, catalogId);
    }
}
