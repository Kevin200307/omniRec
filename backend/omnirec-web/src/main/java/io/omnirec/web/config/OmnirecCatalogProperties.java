package io.omnirec.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "omnirec.catalog")
public class OmnirecCatalogProperties {

    /** Size of the dedicated thread pool CatalogSyncServiceImpl fans out on — one task per active CatalogProvider runs concurrently on it. */
    private int threadPoolSize = 4;

    /** How often ScheduledFeedPublisher regenerates and republishes every active FeedFileProvider. Accepts ISO-8601 ("PT15M") or simple unit-suffixed ("15m") duration syntax. Only read when omnirec.catalog.feed-sync-enabled=true. */
    private Duration feedRefreshInterval = Duration.ofMinutes(15);

    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
    public Duration getFeedRefreshInterval() { return feedRefreshInterval; }
    public void setFeedRefreshInterval(Duration feedRefreshInterval) { this.feedRefreshInterval = feedRefreshInterval; }
}
