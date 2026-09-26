// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.trackers;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.EventType;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.util.Map;

/**
 * Checkout outcomes the server is authoritative for.
 *
 * Note what {@link #paymentInformationAdded} accepts: a payment <em>method</em>
 * string and nothing else. There is deliberately no parameter that could carry
 * a card number, CVV, expiry, or gateway token, so the unsafe call simply
 * cannot be written. The validator would reject such an event anyway, but an
 * API that makes the mistake impossible beats one that catches it afterwards.
 */
public class CheckoutTracker {

    private final ServerEventEmitter emitter;

    public CheckoutTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    public void started(String cartId, String userId, String anonymousId) {
        emitter.emit(
                EventType.CHECKOUT_STARTED,
                ServerIdentity.of(anonymousId, userId),
                CommerceData.builder().cartId(cartId).build(),
                Map.of());
    }

    public void shippingInformationAdded(String cartId, String userId, String shippingMethod) {
        emitter.emit(
                EventType.SHIPPING_INFORMATION_ADDED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().cartId(cartId).build(),
                shippingMethod == null ? Map.of() : Map.of("shippingMethod", shippingMethod));
    }

    /**
     * @param paymentMethod safe metadata only — "card", "paypal", "apple_pay".
     *                      Never a number, token, or anything that could be replayed.
     */
    public void paymentInformationAdded(String cartId, String userId, String paymentMethod) {
        emitter.emit(
                EventType.PAYMENT_INFORMATION_ADDED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().cartId(cartId).build(),
                paymentMethod == null ? Map.of() : Map.of("paymentMethod", paymentMethod));
    }

    public void completed(String cartId, String orderId, String userId) {
        emitter.emit(
                EventType.CHECKOUT_COMPLETED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().cartId(cartId).orderId(orderId).build(),
                Map.of(),
                orderId);
    }

    public void failed(String cartId, String userId, String reason) {
        emitter.emit(
                EventType.CHECKOUT_FAILED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().cartId(cartId).build(),
                reason == null ? Map.of() : Map.of("reason", reason));
    }
}
