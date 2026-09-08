package io.omnirec.catalog.providers.algolia;

import com.algolia.api.SearchClient;
import io.omnirec.algolia.AlgoliaProperties;
import io.omnirec.catalog.CatalogItem;
import io.omnirec.catalog.CatalogProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog sync counterpart to AlgoliaSearchProvider — same SearchClient
 * bean and same index (catalog sync writes the records; search reads them
 * back), so no separate Algolia config is needed beyond what
 * AlgoliaProperties already holds for search.
 */
public class AlgoliaCatalogProvider implements CatalogProvider {

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
    public void upsertItems(List<CatalogItem> items) {
        for (List<CatalogItem> chunk : partition(items, BATCH_SIZE)) {
            List<Map<String, Object>> records = chunk.stream().map(this::toAlgoliaRecord).toList();
            client.saveObjects(properties.getIndexName(), records);
        }
    }

    @Override
    public void removeItems(List<String> productIds) {
        for (List<String> chunk : partition(productIds, BATCH_SIZE)) {
            client.deleteObjects(properties.getIndexName(), chunk);
        }
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
