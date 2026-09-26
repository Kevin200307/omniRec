// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.recentlyviewed;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the serving side's recently-viewed lists live. This is the Redis that
 * {@code omnirec.cache.redis} points the serving app at, which is not
 * necessarily the one {@code omnirec.state.redis} holds pipeline state in.
 */
@ConfigurationProperties(prefix = "omnirec.destinations.recently-viewed")
public class RecentlyViewedProperties {

    private boolean enabled = false;
    private String host = "localhost";
    private int port = 6379;
    private String password;
    private Integer database;

    /**
     * Only this tenant's events are written. The serving app's keys carry no
     * tenant, so a multi-tenant Event API feeding one storefront's cache must
     * set this, or two tenants' users with the same id would share a list.
     */
    private String tenantId;

    /** Matches the serving side's list length. */
    private int maxItems = 20;

    /** Matches the serving side's retention. */
    private Duration ttl = Duration.ofDays(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getDatabase() { return database; }
    public void setDatabase(Integer database) { this.database = database; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId; }
    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
}
