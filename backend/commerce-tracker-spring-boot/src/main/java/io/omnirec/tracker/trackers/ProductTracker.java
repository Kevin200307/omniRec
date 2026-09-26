// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.trackers;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.EventType;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.Map;

/**
 * Product events the server is authoritative for.
 *
 * Only the review events, really: a review is worth recording once it has been
 * accepted and moderated, which the browser cannot know. Views and clicks are
 * browser concerns and stay in the frontend SDK.
 */
public class ProductTracker {

    private final ServerEventEmitter emitter;

    public ProductTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    public void reviewSubmitted(String productId, String userId, String reviewId, Integer rating) {
        emitter.emit(
                EventType.PRODUCT_REVIEW_SUBMITTED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().productId(productId).build(),
                rating == null ? Map.of() : Map.of("rating", rating),
                // One accepted review per id — safe to replay.
                reviewId);
    }

    public void wishlisted(String productId, String userId, String anonymousId) {
        emitter.emit(
                EventType.PRODUCT_WISHLISTED,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder().productId(productId).build(),
                Map.of());
    }
}
