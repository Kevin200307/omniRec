package io.omnirec.catalog;

import java.util.List;

/**
 * Returned by every CatalogProvider and FeedFileProvider call — replaces
 * the earlier void-returning design, which silently swallowed per-item
 * validation failures (e.g. a channel rejecting an item for a missing
 * GTIN). "Synced" and "silently dropped" must never be indistinguishable.
 *
 * Convention: a RejectedItem with productId "*" represents a whole-batch or
 * whole-provider failure (e.g. the provider was unreachable after all retry
 * attempts, or an operation isn't supported at all) rather than a specific
 * item's validation failure — see CatalogSyncServiceImpl.runWithRetry and
 * PersonalizeCatalogProvider.removeItems for the two cases that produce it.
 */
public record SyncResult(
        String providerName,
        int accepted,
        int rejected,
        List<RejectedItem> rejections
) {
    public SyncResult {
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }
}
