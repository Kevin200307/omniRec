// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.trackers;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.Map;

/**
 * Server-side recommendation outcomes.
 *
 * Impressions and clicks belong in the browser — only it knows what was
 * actually rendered. What belongs here is the conversion end of the loop:
 * confirming that a recommended product was genuinely purchased, which is the
 * signal a model is ultimately optimising for and the one a browser cannot
 * confirm.
 */
public class RecommendationTracker {

    private final ServerEventEmitter emitter;

    public RecommendationTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    public void purchased(String recommendationId, String productId, String orderId, String userId, String anonymousId) {
        emitter.emit(
                StandardEventNames.RECOMMENDATION_PURCHASED,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder()
                        .recommendationId(recommendationId)
                        .productId(productId)
                        .orderId(orderId)
                        .build(),
                Map.of(),
                // One attribution per (order, product) — replay-safe.
                orderId + ":" + productId);
    }

    public void addedToCart(String recommendationId, String productId, String cartId, String userId, String anonymousId) {
        emitter.emit(
                StandardEventNames.RECOMMENDATION_ADDED_TO_CART,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder()
                        .recommendationId(recommendationId)
                        .productId(productId)
                        .cartId(cartId)
                        .build(),
                Map.of());
    }
}
