// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.compat;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between the v1 flat {@code commerce} payload and v2 {@code data}
 * blocks. The rule is fixed and shared with the catalog conversion, the browser
 * SDK's upconversion and the docs:
 *
 * <ul>
 *   <li>{@code productId, price, quantity} go to {@code product};</li>
 *   <li>{@code categoryId, category} go to {@code category.id, category.name};</li>
 *   <li>{@code listId, productIds} go to {@code list};</li>
 *   <li>{@code searchQuery} goes to {@code search.query};</li>
 *   <li>{@code cartId, orderId} go to {@code cart.id, order.id};</li>
 *   <li>{@code recommendationId, recommendationProvider} go to {@code recommendation};</li>
 *   <li>{@code total} and {@code items} belong to the order when there is an
 *       {@code orderId}, otherwise to the cart when there is a {@code cartId},
 *       otherwise to the order;</li>
 *   <li>{@code currency} goes to every block that holds an amount: the product
 *       when there is a {@code price}, the order or cart when there is a total or
 *       items. With no amount at all it goes to the order, the cart or the
 *       product, in that order of preference by which id is present.</li>
 * </ul>
 *
 * Converting back takes the first currency found on the order, then the cart,
 * then the product, so {@code toCommerce(toData(c))} returns {@code c}.
 */
public final class V1Compat {

    private V1Compat() {
    }

    public static EventData toData(CommerceData c) {
        if (c == null) return EventData.empty();
        Map<String, Map<String, Object>> blocks = new LinkedHashMap<>();
        put(blocks, "product", "id", c.productId());
        put(blocks, "product", "price", c.price());
        put(blocks, "product", "quantity", c.quantity());
        put(blocks, "category", "id", c.categoryId());
        put(blocks, "category", "name", c.category());
        put(blocks, "list", "id", c.listId());
        put(blocks, "list", "productIds", c.productIds());
        put(blocks, "search", "query", c.searchQuery());
        put(blocks, "cart", "id", c.cartId());
        put(blocks, "order", "id", c.orderId());
        put(blocks, "recommendation", "id", c.recommendationId());
        put(blocks, "recommendation", "provider", c.recommendationProvider());

        String owner = c.orderId() != null ? "order" : c.cartId() != null ? "cart" : "order";
        boolean hasItems = c.items() != null && !c.items().isEmpty();
        put(blocks, owner, "total", c.total());
        if (hasItems) put(blocks, owner, "items", items(c.items()));

        if (c.currency() != null) {
            if (c.price() != null) put(blocks, "product", "currency", c.currency());
            if (c.total() != null || hasItems) put(blocks, owner, "currency", c.currency());
            if (c.price() == null && c.total() == null && !hasItems) {
                String target = c.orderId() != null ? "order" : c.cartId() != null ? "cart" : "product";
                put(blocks, target, "currency", c.currency());
            }
        }
        return EventData.of(blocks);
    }

    public static CommerceData toCommerce(EventData d) {
        if (d == null || d.isEmpty()) return CommerceData.empty();
        EventData.OrderData order = d.order();
        EventData.CartData cart = d.cart();
        EventData.ProductData product = d.product();
        return CommerceData.builder()
                .productId(product.id())
                .productIds(d.list().productIds())
                .categoryId(d.category().id())
                .category(d.category().name())
                .quantity(product.quantity())
                .price(product.price())
                .currency(first(order.currency(), cart.currency(), product.currency()))
                .cartId(cart.id())
                .orderId(order.id())
                .searchQuery(d.search().query())
                .recommendationId(d.recommendation().id())
                .recommendationProvider(d.recommendation().provider())
                .listId(d.list().id())
                .items(order.items() != null ? order.items() : cart.items())
                .total(order.total() != null ? order.total() : cart.total())
                .build();
    }

    private static List<Map<String, Object>> items(List<CommerceItem> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (CommerceItem item : items) {
            Map<String, Object> line = new LinkedHashMap<>();
            if (item != null) {
                if (item.productId() != null) line.put("productId", item.productId());
                if (item.quantity() != null) line.put("quantity", item.quantity());
                if (item.price() != null) line.put("price", item.price());
                if (item.currency() != null) line.put("currency", item.currency());
                if (item.categoryId() != null) line.put("categoryId", item.categoryId());
            }
            out.add(line);
        }
        return out;
    }

    private static void put(Map<String, Map<String, Object>> blocks, String block, String field, Object value) {
        if (value == null) return;
        blocks.computeIfAbsent(block, b -> new LinkedHashMap<>()).put(field, value);
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }
}
