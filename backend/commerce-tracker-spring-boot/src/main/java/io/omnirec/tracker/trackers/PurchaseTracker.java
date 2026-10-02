// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.trackers;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.tracker.ServerEventEmitter;
import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Authoritative purchase events. This is the tracker that matters most: the
 * frontend equivalents exist for completeness, but revenue data should come
 * from here, where the payment result is actually known.
 *
 * Every method derives its eventId from the orderId, so reporting the same
 * order twice — a retry, a redelivered webhook, or the frontend also firing —
 * is deduplicated rather than double-counted.
 */
public class PurchaseTracker {

    private final ServerEventEmitter emitter;

    public PurchaseTracker(ServerEventEmitter emitter) {
        this.emitter = emitter;
    }

    public void completed(PurchaseCompleted purchase) {
        emitter.emit(
                StandardEventNames.PURCHASE_COMPLETED,
                purchase.identity(),
                CommerceData.builder()
                        .orderId(purchase.orderId())
                        .cartId(purchase.cartId())
                        .items(purchase.items())
                        .total(purchase.total())
                        .currency(purchase.currency())
                        .build(),
                purchase.properties(),
                purchase.orderId());
    }

    public void failed(String orderId, String userId, String reason) {
        emitter.emit(
                StandardEventNames.PURCHASE_FAILED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().orderId(orderId).build(),
                reason == null ? Map.of() : Map.of("reason", reason),
                orderId);
    }

    public void orderCancelled(String orderId, String userId, String reason) {
        emitter.emit(
                StandardEventNames.ORDER_CANCELLED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().orderId(orderId).build(),
                reason == null ? Map.of() : Map.of("reason", reason),
                orderId);
    }

    public void orderRefunded(String orderId, String userId, BigDecimal amount, String currency) {
        emitter.emit(
                StandardEventNames.ORDER_REFUNDED,
                ServerIdentity.ofUser(userId),
                CommerceData.builder().orderId(orderId).total(amount).currency(currency).build(),
                Map.of(),
                orderId);
    }

    /**
     * A builder rather than a long parameter list: a purchase has six-plus
     * fields, several of them String, and a positional call is one careless
     * edit away from swapping orderId and cartId with no compiler complaint.
     */
    public record PurchaseCompleted(
            ServerIdentity identity,
            String orderId,
            String cartId,
            List<CommerceItem> items,
            BigDecimal total,
            String currency,
            Map<String, Object> properties
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String anonymousId;
            private String userId;
            private String sessionId;
            private String orderId;
            private String cartId;
            private List<CommerceItem> items = List.of();
            private BigDecimal total;
            private String currency;
            private Map<String, Object> properties = Map.of();

            /**
             * The browser identity captured at checkout. Strongly recommended:
             * it is what links this purchase to the anonymous browsing that led
             * to it. Without it the purchase is still attributed to the user,
             * but the path that produced it is lost.
             */
            public Builder anonymousId(String anonymousId) { this.anonymousId = anonymousId; return this; }
            public Builder userId(String userId) { this.userId = userId; return this; }
            public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
            public Builder orderId(String orderId) { this.orderId = orderId; return this; }
            public Builder cartId(String cartId) { this.cartId = cartId; return this; }
            public Builder items(List<CommerceItem> items) { this.items = items; return this; }
            public Builder total(BigDecimal total) { this.total = total; return this; }
            public Builder currency(String currency) { this.currency = currency; return this; }
            public Builder properties(Map<String, Object> properties) { this.properties = properties; return this; }

            public PurchaseCompleted build() {
                return new PurchaseCompleted(
                        new ServerIdentity(anonymousId, userId, sessionId),
                        orderId, cartId, items, total, currency, properties);
            }
        }
    }
}
