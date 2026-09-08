package io.omnirec.catalog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives every FeedFileProvider bean (Google Merchant in scheduled-fetch
 * mode, OpenAI Product Feed) on a fixed interval — not on individual
 * product writes, which is what CatalogSyncService.sync() is for. See
 * FeedFileProvider's javadoc for why these channels need a different
 * trigger entirely.
 *
 * No retry here, deliberately: unlike CatalogSyncServiceImpl (which retries
 * because a developer's single product edit deserves an in-band attempt
 * before giving up), a transient failure in a periodic job resolves itself
 * on the next scheduled run — "wait for the next cycle" already is the
 * retry. One provider throwing doesn't stop the others in the same run.
 *
 * publishFeeds() is public and takes no timer to invoke — that's what
 * makes this class testable with a plain constructor call and no Spring
 * context, exactly like CatalogSyncServiceImpl.
 */
public class ScheduledFeedPublisher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFeedPublisher.class);

    private final List<FeedFileProvider> feedProviders;
    private final CatalogSource catalogSource;
    private final Duration refreshInterval;
    private ScheduledExecutorService scheduler;

    public ScheduledFeedPublisher(List<FeedFileProvider> feedProviders, CatalogSource catalogSource, Duration refreshInterval) {
        this.feedProviders = feedProviders;
        this.catalogSource = catalogSource;
        this.refreshInterval = refreshInterval;
    }

    /** Spring lifecycle hook — starts the timer once this bean is constructed. Never fires in a plain unit test that just calls `new ScheduledFeedPublisher(...)`, since @PostConstruct only runs under a Spring container. */
    @PostConstruct
    public void start() {
        if (feedProviders.isEmpty()) {
            log.debug("No FeedFileProvider beans active — scheduled feed publishing will not run");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "omnirec-feed-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishFeeds, 0, refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One full run: fetch the catalog once, hand the same snapshot to every FeedFileProvider independently. Returns one SyncResult per provider, and logs a summary of each — including every rejection, not just a count. */
    public List<SyncResult> publishFeeds() {
        List<CatalogItem> catalog = catalogSource.fetchAll();
        List<SyncResult> results = new ArrayList<>();

        for (FeedFileProvider provider : feedProviders) {
            SyncResult result;
            try {
                result = provider.generateAndPublish(catalog);
            } catch (Exception e) {
                log.error("Feed publish failed entirely for provider [{}]", provider.getProviderName(), e);
                result = new SyncResult(
                        provider.getProviderName(), 0, catalog.size(),
                        List.of(new RejectedItem("*", "feed publish failed: " + e.getMessage()))
                );
            }
            results.add(result);
            logSummary(result);
        }
        return results;
    }

    private void logSummary(SyncResult result) {
        log.info("Feed published to [{}]: {} accepted, {} rejected", result.providerName(), result.accepted(), result.rejected());
        for (RejectedItem rejection : result.rejections()) {
            log.warn("  [{}] rejected {}: {}", result.providerName(), rejection.productId(), rejection.reason());
        }
    }
}
