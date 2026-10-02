// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code data} object of an event: catalog blocks ({@code product},
 * {@code order}, ...) plus any inline fields a tracking plan declares.
 *
 * Stored as an immutable tree of maps, lists, strings, booleans and numbers, so
 * custom events need no Java type. Typed views such as {@link #product()} and
 * {@link #order()} give destinations convenient access to the standard blocks.
 *
 * Every non-integral number is held as {@link BigDecimal}: money summed in binary
 * floating point drifts by a cent.
 */
@JsonDeserialize(using = EventDataDeserializer.class)
public final class EventData {

    private static final EventData EMPTY = new EventData(Map.of());

    private final Map<String, Object> values;

    private EventData(Map<String, Object> values) {
        this.values = values;
    }

    public static EventData empty() {
        return EMPTY;
    }

    public static EventData of(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return EMPTY;
        @SuppressWarnings("unchecked")
        Map<String, Object> normalised = (Map<String, Object>) normalise(values);
        return normalised.isEmpty() ? EMPTY : new EventData(normalised);
    }

    /** Deep, immutable copy with nulls dropped and numbers made exact. */
    private static Object normalise(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object child = normalise(entry.getValue());
                if (child != null) out.put(String.valueOf(entry.getKey()), child);
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            for (Object element : collection) out.add(normalise(element));
            return Collections.unmodifiableList(out);
        }
        if (value instanceof Double d) return BigDecimal.valueOf(d);
        if (value instanceof Float f) return new BigDecimal(f.toString());
        if (value instanceof BigInteger big) return new BigDecimal(big);
        if (value instanceof Record || value instanceof Enum<?>) {
            throw new IllegalArgumentException("EventData holds plain values only, not " + value.getClass().getSimpleName());
        }
        return value;
    }

    @JsonValue
    public Map<String, Object> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Value at a dotted path such as {@code product.id}, or {@code null}. */
    public Object get(String path) {
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    public Optional<String> string(String path) {
        Object value = get(path);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    /** Numeric value at a path, accepting numbers and decimal strings. {@code null} when absent or not numeric. */
    public BigDecimal decimal(String path) {
        Object value = get(path);
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Integer integer(String path) {
        BigDecimal decimal = decimal(path);
        if (decimal == null) return null;
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    public List<String> strings(String path) {
        Object value = get(path);
        if (!(value instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) out.add(element == null ? null : element.toString());
        return List.copyOf(out.stream().filter(Objects::nonNull).toList());
    }

    /** A copy with {@code value} set at {@code path}, creating parent objects as needed. A null value removes the path. */
    public EventData with(String path, Object value) {
        Map<String, Object> root = mutableCopy(values);
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            Map<String, Object> child = next instanceof Map<?, ?> map ? mutableCopy(map) : new LinkedHashMap<>();
            current.put(parts[i], child);
            current = child;
        }
        if (value == null) current.remove(parts[parts.length - 1]);
        else current.put(parts[parts.length - 1], value);
        return of(root);
    }

    private static Map<String, Object> mutableCopy(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    // --- typed views of the standard blocks ------------------------------------

    public ProductData product() {
        return new ProductData(str("product.id"), str("product.variantId"), str("product.name"), str("product.brand"),
                decimal("product.price"), str("product.currency"), integer("product.quantity"));
    }

    public CategoryData category() {
        return new CategoryData(str("category.id"), str("category.name"));
    }

    public ListData list() {
        return new ListData(str("list.id"), str("list.name"), integer("list.position"), strings("list.productIds"));
    }

    public SearchData search() {
        return new SearchData(str("search.query"), integer("search.resultsCount"));
    }

    public CartData cart() {
        return new CartData(str("cart.id"), decimal("cart.total"), str("cart.currency"), items("cart.items"));
    }

    public OrderData order() {
        return new OrderData(str("order.id"), decimal("order.total"), str("order.currency"), items("order.items"));
    }

    public RecommendationData recommendation() {
        return new RecommendationData(str("recommendation.id"), str("recommendation.provider"));
    }

    private String str(String path) {
        return string(path).orElse(null);
    }

    private List<CommerceItem> items(String path) {
        Object value = get(path);
        if (!(value instanceof List<?> list)) return null;
        List<CommerceItem> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> line) {
                EventData item = of(mutableCopy(line));
                out.add(new CommerceItem(item.str("productId"), item.integer("quantity"), item.decimal("price"),
                        item.str("currency"), item.str("categoryId")));
            } else {
                out.add(null);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The {@code product} block. Every field is null when absent. */
    public record ProductData(String id, String variantId, String name, String brand, BigDecimal price,
                              String currency, Integer quantity) {
    }

    public record CategoryData(String id, String name) {
    }

    public record ListData(String id, String name, Integer position, List<String> productIds) {
    }

    public record SearchData(String query, Integer resultsCount) {
    }

    public record CartData(String id, BigDecimal total, String currency, List<CommerceItem> items) {
    }

    public record OrderData(String id, BigDecimal total, String currency, List<CommerceItem> items) {
    }

    public record RecommendationData(String id, String provider) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EventData data && values.equals(data.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "EventData" + values;
    }
}
