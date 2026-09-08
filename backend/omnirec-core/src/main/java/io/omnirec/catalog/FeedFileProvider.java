package io.omnirec.catalog;

import java.util.List;

/**
 * For channels that don't accept per-item calls at all — they expect a
 * full catalog file, generated and published on a schedule, not a
 * reaction to a single product edit. GoogleMerchantFeedFileProvider
 * (scheduled-fetch mode) and OpenAIProductFeedProvider implement this;
 * CatalogProvider is the wrong shape for either of them — see that
 * interface's javadoc for why.
 *
 * Driven exclusively by ScheduledFeedPublisher, on its own cadence — never
 * by CatalogSyncService.sync(), which only fans out to CatalogProvider
 * beans. A FeedFileProvider always receives the *entire* current catalog,
 * never a diff, because that's the unit these channels actually consume.
 */
public interface FeedFileProvider {

    SyncResult generateAndPublish(List<CatalogItem> fullCatalogSnapshot);

    /** Provider id used in logs — e.g. "google-merchant", "openai-feed". */
    String getProviderName();
}
