package io.omnirec.catalog;

import java.util.List;

/**
 * For channels that accept real per-item API calls — AlgoliaCatalogProvider,
 * PersonalizeCatalogProvider, GoogleMerchantCatalogProvider (push mode).
 * Reacts to changes: one product edit → one call. Contrast with
 * FeedFileProvider, for channels that only accept a full catalog file on a
 * schedule (Google Merchant scheduled-fetch mode, OpenAI Product Feed) —
 * those never implement this interface, because there is no per-item call
 * to make.
 *
 * Each implementation owns its own batch-size chunking against its
 * provider's actual API limits; CatalogSyncServiceImpl never chunks on
 * their behalf, it just hands over the full list for a given sync() call.
 * Each implementation is also responsible for catching its own per-chunk
 * exceptions and reporting them as RejectedItems in the returned
 * SyncResult, rather than letting them propagate — CatalogSyncServiceImpl's
 * retry only fires on a thrown exception (a transient failure), never on a
 * returned SyncResult with rejections (a permanent, already-decided one;
 * retrying "missing GTIN" doesn't fix it).
 *
 * Implementations should treat upsertItems/removeItems as idempotent —
 * CatalogSyncServiceImpl retries a failed call by resending the whole list,
 * which is safe (a no-op for already-synced items) but not free, so an
 * implementation with expensive per-item work should chunk small enough
 * that a retry's blast radius stays reasonable.
 */
public interface CatalogProvider {

    SyncResult upsertItems(List<CatalogItem> items);

    SyncResult removeItems(List<String> productIds);

    /** Provider id used in logs — e.g. "algolia", "aws-personalize". */
    String getProviderName();
}
