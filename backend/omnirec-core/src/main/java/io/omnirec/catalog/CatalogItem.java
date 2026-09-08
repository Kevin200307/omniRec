package io.omnirec.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * The canonical product shape every CatalogProvider mapper translates from —
 * mirrors CanonicalEvent's role for behavioral signals (see io.omnirec.core.model).
 * attributes is free-form (color, size, brand, ...) since provider-specific
 * facets vary per store; anything structured enough to need its own field
 * belongs above, in attributes otherwise.
 */
public record CatalogItem(
        String productId,
        String title,
        String description,
        BigDecimal price,
        String category,
        String imageUrl,
        Map<String, Object> attributes,
        Instant updatedAt
) {
    public CatalogItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("CatalogItem.productId is required");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
