package io.omnirec.algolia;

import com.algolia.api.SearchClient;
import com.algolia.model.search.SearchParamsObject;
import com.algolia.model.search.SearchResponse;
import io.omnirec.core.model.Item;
import io.omnirec.core.model.SearchResult;
import io.omnirec.core.provider.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps CanonicalEvent-adjacent search queries onto Algolia's SearchClient
 * and normalizes its "objectID" convention back to the "id" field
 * @omnirec/react-ui's SearchHit expects — that rename is the entire
 * translation layer for search.
 */
public class AlgoliaSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(AlgoliaSearchProvider.class);

    private final SearchClient client;
    private final AlgoliaProperties properties;

    public AlgoliaSearchProvider(SearchClient client, AlgoliaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "algolia";
    }

    @SuppressWarnings("unchecked")
    @Override
    public SearchResult search(String query, Map<String, Object> filters) {
        try {
            SearchResponse<Map> response = client.searchSingleIndex(
                    properties.getIndexName(),
                    new SearchParamsObject().setQuery(query),
                    Map.class
            );

            List<Map<String, Object>> hits = response.getHits().stream()
                    .map(hit -> normalizeHit((Map<String, Object>) hit))
                    .toList();

            return new SearchResult(hits, response.getNbHits() != null ? response.getNbHits() : hits.size());
        } catch (Exception e) {
            log.warn("Algolia search failed for query '{}': {}", query, e.getMessage());
            return new SearchResult(List.of(), 0);
        }
    }

    @Override
    public void indexItems(List<Item> items) {
        List<Map<String, Object>> records = items.stream().map(this::toAlgoliaRecord).toList();
        client.saveObjects(properties.getIndexName(), records);
    }

    private Map<String, Object> normalizeHit(Map<String, Object> raw) {
        Map<String, Object> hit = new LinkedHashMap<>(raw);
        Object objectId = hit.remove("objectID");
        if (objectId != null) {
            hit.put("id", objectId);
        }
        return hit;
    }

    private Map<String, Object> toAlgoliaRecord(Item item) {
        Map<String, Object> record = new LinkedHashMap<>(item.attributes());
        record.put("objectID", item.id());
        return record;
    }
}
