package io.omnirec.catalog;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The one type calling code depends on — a ProductController/ProductService
 * must never import AlgoliaCatalogProvider, PersonalizeCatalogProvider, or
 * any other adapter directly. Which providers actually run is entirely a
 * matter of which CatalogProvider beans are active (provider starter on the
 * classpath + enabled:true in application.yml) — this interface never
 * changes either way.
 *
 * Fans out ONLY to CatalogProvider beans (item-based channels: Algolia,
 * Personalize, Google Merchant in push mode) — never to FeedFileProvider
 * beans, which are driven on their own schedule by ScheduledFeedPublisher
 * instead. See CatalogProvider vs. FeedFileProvider javadoc for why these
 * are two different triggers, not one.
 *
 * Primary trigger: call sync()/remove() directly from your own product-save
 * code. A returned future that's never awaited is a valid, intended usage —
 * it means "fire and forget, don't block my request thread on 2-3 external
 * API calls." Await it only when you actually need the per-provider
 * SyncResult (e.g. to surface rejections to an admin UI).
 */
public interface CatalogSyncService {

    /** Upserts items to every currently-enabled CatalogProvider, in parallel. One SyncResult per provider; never completes exceptionally — see CatalogSyncServiceImpl. */
    CompletableFuture<List<SyncResult>> sync(List<CatalogItem> items);

    /** Removes items by productId from every currently-enabled CatalogProvider, in parallel. */
    CompletableFuture<List<SyncResult>> remove(String... productIds);
}
