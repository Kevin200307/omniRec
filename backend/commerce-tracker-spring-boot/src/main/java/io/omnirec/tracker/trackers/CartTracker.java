// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.trackers;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.List;
import java.util.Map;

/**
 * Cart events the server owns — principally {@code cart_abandoned}.
 *
 * <h2>Why abandonment is generated here and not in the browser</h2>
 *
 * Closing a tab is not abandoning a cart. Neither is navigating away, losing
 * connectivity, or switching to another device to finish the purchase. The
 * browser can observe all of those and tell none of them apart.
 *
 * Abandonment is a <em>derived</em> fact, and only the backend has the state to
 * derive it:
 *
 * <pre>
 *   the cart has items
 *   AND no order exists for it
 *   AND nothing has touched it for longer than the configured threshold
 * </pre>
 *
 * So the merchant runs a scheduled job over their own cart table and calls
 * {@link #abandoned}. The SDK deliberately provides no timer of its own: it
 * doesn't know what a cart is, when an order settled, or what threshold suits
 * a business where people routinely take three days to decide.
 */
public class CartTracker {

    private final ServerEventEmitter emitter;

    public CartTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * Reports a cart the merchant has determined to be abandoned.
     *
     * The eventId derives from the cartId, so a sweeper job that runs every
     * five minutes and keeps seeing the same stale cart reports it once, not
     * once per run.
     */
    public void abandoned(String cartId, String userId, String anonymousId, List<CommerceItem> items) {
        emitter.emit(
                StandardEventNames.CART_ABANDONED,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder().cartId(cartId).items(items).build(),
                Map.of(),
                cartId);
    }

    public void productAdded(String cartId, String productId, int quantity, String userId, String anonymousId) {
        emitter.emit(
                StandardEventNames.PRODUCT_ADDED_TO_CART,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder().cartId(cartId).productId(productId).quantity(quantity).build(),
                Map.of());
    }

    public void productRemoved(String cartId, String productId, String userId, String anonymousId) {
        emitter.emit(
                StandardEventNames.PRODUCT_REMOVED_FROM_CART,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder().cartId(cartId).productId(productId).build(),
                Map.of());
    }
}
