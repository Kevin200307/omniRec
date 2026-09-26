// SPDX-License-Identifier: Apache-2.0
package io.omnirec.core.fake;

import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.EventType;
import io.omnirec.core.model.RecContext;
import io.omnirec.core.model.Recommendation;
import io.omnirec.core.provider.RecommendationProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Popularity-by-click-count fake. Registered by omnirec-web's
 * autoconfiguration only when no real RecommendationProvider bean is
 * present, so a fresh clone of the example app shows *something* instead of
 * an empty carousel before any AWS/Google account is wired up.
 */
public class InMemoryRecommendationProvider implements RecommendationProvider {

    private final Map<String, Integer> clickCounts = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "in-memory";
    }

    @Override
    public void putEvents(List<CanonicalEvent> events) {
        for (CanonicalEvent event : events) {
            if (event.eventType() == EventType.PRODUCT_CLICKED || event.eventType() == EventType.PRODUCT_VIEWED) {
                Object productId = event.payload().get("productId");
                if (productId != null) {
                    clickCounts.merge(productId.toString(), 1, Integer::sum);
                }
            }
        }
    }

    @Override
    public List<Recommendation> getRecommendations(String userId, RecContext ctx) {
        return clickCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(ctx.numResults())
                .map(e -> new Recommendation(e.getKey(), e.getValue(), Map.of()))
                .toList();
    }
}
