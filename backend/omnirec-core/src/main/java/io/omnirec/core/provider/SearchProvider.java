// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.provider;

import io.omnirec.core.model.Item;
import io.omnirec.core.model.SearchResult;

import java.util.List;
import java.util.Map;

/** Implemented by InMemorySearchProvider, plus any search starter a deployment adds. */
public interface SearchProvider {

    String id();

    SearchResult search(String query, Map<String, Object> filters);

    void indexItems(List<Item> items);
}
