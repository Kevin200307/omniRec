package io.omnirec.core.provider;

import io.omnirec.core.model.Item;
import io.omnirec.core.model.SearchResult;

import java.util.List;
import java.util.Map;

/** Implemented by AlgoliaSearchProvider and InMemorySearchProvider. */
public interface SearchProvider {

    String id();

    SearchResult search(String query, Map<String, Object> filters);

    void indexItems(List<Item> items);
}
