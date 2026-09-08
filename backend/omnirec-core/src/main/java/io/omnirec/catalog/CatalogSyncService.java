package io.omnirec.catalog;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The one type calling code depends on — a ProductController/ProductService
 * must never import AlgoliaCatalogProvider or PersonalizeCatalogProvider
 * directly. Which providers actually run is entirely a matter of which
 * CatalogProvider beans are active (provider starter on the classpath +
 * enabled:true in application.yml) — this interface never changes either way.
 *
 * Primary trigger: call sync()/remove() directly from your own product-save
 * code. A returned future that's never awaited is a valid, intended usage —
 * it means "fire and forget, don't block my request thread on 2-3 external
 * API calls." Await it only when you actually need to know sync finished
 * (e.g. the scheduled full re-sync job).
 */
public interface CatalogSyncService {

    /** Upserts items to every currently-enabled CatalogProvider, in parallel. Never completes exceptionally — see CatalogSyncServiceImpl. */
    CompletableFuture<Void> sync(List<CatalogItem> items);

    /** Removes items by productId from every currently-enabled CatalogProvider, in parallel. */
    CompletableFuture<Void> remove(String... productIds);
}
