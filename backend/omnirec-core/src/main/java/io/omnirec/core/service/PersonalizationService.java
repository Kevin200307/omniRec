// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.service;

import io.omnirec.core.model.*;
import io.omnirec.core.provider.CacheProvider;
import io.omnirec.core.provider.RecommendationProvider;
import io.omnirec.core.provider.SearchProvider;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one class application code depends on. It never contains
 * provider-specific logic — only dispatch. Adding a new RecommendationProvider
 * bean (e.g. Google Rec AI alongside Personalize) requires zero changes here;
 * that invariant is enforced by omnirec-contract-tests.
 */
public class PersonalizationService {

    private static final String RECENTLY_VIEWED_PREFIX = "recently-viewed:";
    private static final Duration RECENTLY_VIEWED_TTL = Duration.ofDays(30);
    private static final int RECENTLY_VIEWED_MAX = 20;

    private final List<RecommendationProvider> recommendationProviders;
    private final List<SearchProvider> searchProviders;
    private final List<CacheProvider> cacheProviders;

    public PersonalizationService(
            List<RecommendationProvider> recommendationProviders,
            List<SearchProvider> searchProviders,
            List<CacheProvider> cacheProviders
    ) {
        this.recommendationProviders = recommendationProviders;
        this.searchProviders = searchProviders;
        this.cacheProviders = cacheProviders;
    }

    /** Fans out to every active RecommendationProvider — Personalize and Google Rec AI both get every event if both are configured. */
    public void trackEvent(CanonicalEvent event) {
        for (RecommendationProvider provider : recommendationProviders) {
            provider.putEvents(List.of(event));
        }
        if (event.eventType() == EventType.PRODUCT_VIEWED) {
            recordRecentlyViewed(event);
        }
    }

    /**
     * Queries every active provider and merges by score, deduping by
     * productId (first occurrence wins — providers are in config-declared
     * priority order). With one provider configured this is just that
     * provider's list; with two, it's a naive blend — good enough for v1,
     * real A/B/ranking logic is post-v1 scope per the implementation plan.
     */
    public List<Recommendation> getRecommendations(String userId, RecContext ctx) {
        Map<String, Recommendation> byProductId = recommendationProviders.stream()
                .flatMap(p -> p.getRecommendations(userId, ctx).stream())
                .collect(Collectors.toMap(Recommendation::productId, r -> r, (first, second) -> first, java.util.LinkedHashMap::new));

        return byProductId.values().stream()
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed())
                .limit(ctx.numResults())
                .collect(Collectors.toList());
    }

    /** Uses the first configured SearchProvider — search isn't blended across providers the way recommendations are. */
    public SearchResult search(String query, Map<String, Object> filters) {
        if (searchProviders.isEmpty()) {
            return new SearchResult(List.of(), 0);
        }
        return searchProviders.get(0).search(query, filters);
    }

    public void indexItems(List<Item> items) {
        for (SearchProvider provider : searchProviders) {
            provider.indexItems(items);
        }
    }

    private void recordRecentlyViewed(CanonicalEvent event) {
        if (event.userId() == null || cacheProviders.isEmpty()) return;
        Object productId = event.payload().get("productId");
        if (productId == null) return;
        String key = RECENTLY_VIEWED_PREFIX + event.userId();
        cacheProviders.get(0).pushCapped(key, productId, RECENTLY_VIEWED_MAX, RECENTLY_VIEWED_TTL);
    }

    public List<Object> getRecentlyViewed(String userId) {
        if (cacheProviders.isEmpty()) return List.of();
        return cacheProviders.get(0).getList(RECENTLY_VIEWED_PREFIX + userId);
    }
}
