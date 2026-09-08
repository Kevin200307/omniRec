package io.omnirec.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omnirec.catalog")
public class OmnirecCatalogProperties {

    /** Size of the dedicated thread pool CatalogSyncServiceImpl fans out on — one task per active CatalogProvider runs concurrently on it. */
    private int threadPoolSize = 4;

    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
}
