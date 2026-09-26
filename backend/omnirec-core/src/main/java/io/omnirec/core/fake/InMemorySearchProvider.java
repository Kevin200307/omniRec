// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.fake;

import io.omnirec.core.model.Item;
import io.omnirec.core.model.SearchResult;
import io.omnirec.core.provider.SearchProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Naive substring match over indexed items — fine for local dev, not meant to compete with a real search engine. */
public class InMemorySearchProvider implements SearchProvider {

    private final Map<String, Item> items = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "in-memory";
    }

    @Override
    public SearchResult search(String query, Map<String, Object> filters) {
        String needle = query == null ? "" : query.toLowerCase();
        List<Map<String, Object>> hits = items.values().stream()
                .filter(item -> matches(item, needle))
                .map(this::toHit)
                .toList();
        return new SearchResult(hits, hits.size());
    }

    @Override
    public void indexItems(List<Item> newItems) {
        for (Item item : newItems) {
            items.put(item.id(), item);
        }
    }

    private boolean matches(Item item, String needle) {
        if (needle.isEmpty()) return true;
        Object title = item.attributes().get("title");
        return title != null && title.toString().toLowerCase().contains(needle);
    }

    private Map<String, Object> toHit(Item item) {
        Map<String, Object> hit = new java.util.LinkedHashMap<>(item.attributes());
        hit.put("id", item.id());
        return hit;
    }
}
