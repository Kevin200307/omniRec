package io.omnirec.catalog.providers.algolia;

import com.algolia.api.SearchClient;
import io.omnirec.algolia.AlgoliaProperties;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.CatalogProvider;
import io.omnirec.catalog.RejectedItem;
import io.omnirec.catalog.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog sync counterpart to AlgoliaSearchProvider — same SearchClient
 * bean and same index (catalog sync writes the records; search reads them
 * back), so no separate Algolia config is needed beyond what
 * AlgoliaProperties already holds for search.
 *
 * Algolia is schema-less, so there's no per-item validation feedback the
 * way Google's Content API has (e.g. "missing GTIN") — a chunk either
 * saves or it throws. Each chunk's exception is caught here and reported
 * as a single RejectedItem for that chunk (productId "*", per SyncResult's
 * whole-batch convention) rather than propagated, so one bad chunk doesn't
 * cost the accepted count of chunks that already succeeded, and doesn't
 * trigger CatalogSyncServiceImpl's retry for what's usually a permanent
 * failure (bad credentials, malformed index name) rather than a transient
 * one.
 */
public class AlgoliaCatalogProvider implements CatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(AlgoliaCatalogProvider.class);

    /**
     * Algolia's documented per-batch limit is 1000 records (also capped at
     * 10MB per request) — verify against current Algolia API limits before
     * relying on this in production.
     */
    private static final int BATCH_SIZE = 1000;

    private final SearchClient client;
    private final AlgoliaProperties properties;

    public AlgoliaCatalogProvider(SearchClient client, AlgoliaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String getProviderName() {
        return "algolia";
    }

    @Override
    public SyncResult upsertItems(List<CatalogItem> items) {
        int accepted = 0;
        List<RejectedItem> rejections = new ArrayList<>();

        for (List<CatalogItem> chunk : partition(items, BATCH_SIZE)) {
            try {
                List<Map<String, Object>> records = chunk.stream().map(this::toAlgoliaRecord).toList();
                client.saveObjects(properties.getIndexName(), records);
                accepted += chunk.size();
            } catch (Exception e) {
                log.warn("Algolia saveObjects failed for a chunk of {} item(s): {}", chunk.size(), e.getMessage());
                rejections.add(new RejectedItem("*", "chunk of " + chunk.size() + " item(s) failed: " + e.getMessage()));
            }
        }

        return new SyncResult(getProviderName(), accepted, items.size() - accepted, rejections);
    }

    @Override
    public SyncResult removeItems(List<String> productIds) {
        int accepted = 0;
        List<RejectedItem> rejections = new ArrayList<>();

        for (List<String> chunk : partition(productIds, BATCH_SIZE)) {
            try {
                client.deleteObjects(properties.getIndexName(), chunk);
                accepted += chunk.size();
            } catch (Exception e) {
                log.warn("Algolia deleteObjects failed for a chunk of {} id(s): {}", chunk.size(), e.getMessage());
                rejections.add(new RejectedItem("*", "chunk of " + chunk.size() + " id(s) failed: " + e.getMessage()));
            }
        }

        return new SyncResult(getProviderName(), accepted, productIds.size() - accepted, rejections);
    }

    /**
     * Pure CatalogItem → Algolia record mapping, kept separate from the
     * network call so it's unit-testable without a live Algolia client —
     * same pattern as AlgoliaSearchProvider's normalizeHit/toAlgoliaRecord.
     */
    public Map<String, Object> toAlgoliaRecord(CatalogItem item) {
        Map<String, Object> record = new LinkedHashMap<>(item.attributes());
        record.put("objectID", item.productId());
        record.put("title", item.title());
        record.put("description", item.description());
        record.put("price", item.price());
        record.put("category", item.category());
        record.put("imageUrl", item.imageUrl());
        if (item.updatedAt() != null) {
            record.put("updatedAt", item.updatedAt().toString());
        }
        return record;
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }
}
