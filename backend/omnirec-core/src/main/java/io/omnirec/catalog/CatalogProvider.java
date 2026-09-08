package io.omnirec.catalog;

import java.util.List;

/**
 * Implemented by AlgoliaCatalogProvider, PersonalizeCatalogProvider, and —
 * following the same pattern — a future GoogleRecAiCatalogProvider. Each
 * implementation owns its own batch-size chunking against its provider's
 * actual API limits; CatalogSyncServiceImpl never chunks on their behalf,
 * it just hands over the full list for a given sync() call.
 *
 * Implementations should treat upsertItems/removeItems as idempotent —
 * CatalogSyncServiceImpl retries a failed call by resending the whole list,
 * which is safe (a no-op for already-synced items) but not free, so an
 * implementation with expensive per-item work should chunk small enough
 * that a retry's blast radius stays reasonable.
 */
public interface CatalogProvider {

    void upsertItems(List<CatalogItem> items);

    void removeItems(List<String> productIds);

    /** Provider id used in logs — e.g. "algolia", "aws-personalize". */
    String getProviderName();
}
