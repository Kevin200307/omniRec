package io.omnirec.core.provider;

import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.RecContext;
import io.omnirec.core.model.Recommendation;

import java.util.List;

/**
 * Implemented by AmazonPersonalizeProvider, GoogleRecommendationsAiProvider,
 * and InMemoryRecommendationProvider (the dev/test fake). Callers only ever
 * depend on this interface, via PersonalizationService — never on a
 * concrete implementation. See the design doc's non-negotiables.
 */
public interface RecommendationProvider {

    /** Provider id used in logs/metrics, e.g. "aws-personalize", "google-rec-ai". */
    String id();

    void putEvents(List<CanonicalEvent> events);

    List<Recommendation> getRecommendations(String userId, RecContext ctx);
}
