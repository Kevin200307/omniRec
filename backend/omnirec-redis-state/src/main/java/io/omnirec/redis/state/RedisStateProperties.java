package io.omnirec.redis.state;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection details for the pipeline's shared state. Separate from
 * omnirec.cache.redis (the serving-side cache) because the two may point at
 * different instances with different durability requirements — losing a cached
 * recently-viewed list is harmless, losing identity links is not.
 */
@ConfigurationProperties(prefix = "omnirec.state.redis")
public class RedisStateProperties {

    private boolean enabled = false;
    private String host = "localhost";
    private int port = 6379;
    private String password;
    private Integer database;

    /** How long an identity link survives without being refreshed. */
    private Duration identityLinkTtl = Duration.ofDays(365);

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
    public Duration getIdentityLinkTtl() { return identityLinkTtl; }
    public void setIdentityLinkTtl(Duration identityLinkTtl) { this.identityLinkTtl = identityLinkTtl; }
}
