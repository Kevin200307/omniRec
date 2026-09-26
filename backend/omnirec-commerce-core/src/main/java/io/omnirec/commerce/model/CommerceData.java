// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * The commerce payload.
 *
 * Every field is nullable here on purpose. What is actually required depends
 * entirely on the event type — {@code productId} is mandatory for
 * {@code product_viewed} and meaningless for {@code session_started} — so
 * requiredness is expressed by {@link io.omnirec.commerce.validation.EventValidator},
 * not by the type.
 *
 * Money is {@link BigDecimal}: a double cannot represent 0.1 exactly, and
 * summing order totals in binary floating point produces off-by-a-cent errors
 * that are miserable to trace back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommerceData(
        String productId,
        List<String> productIds,
        String categoryId,
        String category,
        Integer quantity,
        BigDecimal price,
        String currency,
        String cartId,
        String orderId,
        String searchQuery,
        String recommendationId,
        /**
         * Who served the recommendation list — e.g. "amazon-personalize",
         * "google-retail", or the merchant's own engine. Provider-neutral: it
         * names the source, it carries no provider-specific structure. Adapters
         * use it to decide whether {@code recommendationId} is *their* token and
         * can be forwarded as attribution, or is someone else's and must not be.
         */
        String recommendationProvider,
        String listId,
        List<CommerceItem> items,
        BigDecimal total
) {

    public CommerceData {
        productIds = productIds == null ? null : List.copyOf(productIds);
        items = items == null ? null : List.copyOf(items);
    }

    public static CommerceData empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String productId;
        private List<String> productIds;
        private String categoryId;
        private String category;
        private Integer quantity;
        private BigDecimal price;
        private String currency;
        private String cartId;
        private String orderId;
        private String searchQuery;
        private String recommendationId;
        private String recommendationProvider;
        private String listId;
        private List<CommerceItem> items;
        private BigDecimal total;

        public Builder productId(String productId) { this.productId = productId; return this; }
        public Builder productIds(List<String> productIds) { this.productIds = productIds; return this; }
        public Builder categoryId(String categoryId) { this.categoryId = categoryId; return this; }
        public Builder category(String category) { this.category = category; return this; }
        public Builder quantity(Integer quantity) { this.quantity = quantity; return this; }
        public Builder price(BigDecimal price) { this.price = price; return this; }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder cartId(String cartId) { this.cartId = cartId; return this; }
        public Builder orderId(String orderId) { this.orderId = orderId; return this; }
        public Builder searchQuery(String searchQuery) { this.searchQuery = searchQuery; return this; }
        public Builder recommendationId(String recommendationId) { this.recommendationId = recommendationId; return this; }
        public Builder recommendationProvider(String recommendationProvider) { this.recommendationProvider = recommendationProvider; return this; }
        public Builder listId(String listId) { this.listId = listId; return this; }
        public Builder items(List<CommerceItem> items) { this.items = items; return this; }
        public Builder total(BigDecimal total) { this.total = total; return this; }

        public CommerceData build() {
            return new CommerceData(productId, productIds, categoryId, category, quantity, price,
                    currency, cartId, orderId, searchQuery, recommendationId, recommendationProvider, listId, items, total);
        }
    }
}
