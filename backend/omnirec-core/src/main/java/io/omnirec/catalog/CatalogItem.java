package io.omnirec.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * The canonical product shape every CatalogProvider/FeedFileProvider mapper
 * translates from — mirrors CanonicalEvent's role for behavioral signals
 * (see io.omnirec.core.model). attributes is free-form (color, size, ...)
 * since provider-specific facets vary per store; anything structured enough
 * to need its own field belongs above, in attributes otherwise.
 *
 * The seven fields after updatedAt (gtin, brand, condition, availability,
 * shippingWeight, productUrl, currency) exist only for feed-distribution
 * channels (Google Merchant, OpenAI Product Feed) that require them for a
 * valid listing — Algolia and AWS Personalize ignore them entirely. All
 * seven are optional; two constructor overloads below exist so item-based
 * providers and existing callers never need to pass nulls for fields
 * they'll never use.
 *
 * productUrl and currency are deliberately per-item, not store-only config:
 * omnirec.providers.google-merchant.product-url-template and .currency
 * (see GoogleMerchantProperties) work as store-wide defaults for the common
 * case, but a per-item value here always wins — required for stores whose
 * URL structure isn't a simple template (different paths per category,
 * custom slugs) or that sell in more than one currency. See
 * GoogleMerchantItemMapper.resolveLink/resolveCurrency for the fallback order.
 */
public record CatalogItem(
        String productId,
        String title,
        String description,
        BigDecimal price,
        String category,
        String imageUrl,
        Map<String, Object> attributes,
        Instant updatedAt,
        String gtin,
        String brand,
        /** "new" | "used" | "refurbished" */
        String condition,
        /** "in_stock" | "out_of_stock" | "preorder" */
        String availability,
        BigDecimal shippingWeight,
        /** Landing-page URL for this specific item. Null falls back to the store-wide product-url-template. */
        String productUrl,
        /** ISO 4217 code, e.g. "USD". Null falls back to the store-wide default currency. */
        String currency
) {
    public CatalogItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("CatalogItem.productId is required");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Convenience constructor for item-based providers/tests that never touch any feed-only field. */
    public CatalogItem(String productId, String title, String description, BigDecimal price, String category,
                        String imageUrl, Map<String, Object> attributes, Instant updatedAt) {
        this(productId, title, description, price, category, imageUrl, attributes, updatedAt, null, null, null, null, null, null, null);
    }

    /** Convenience constructor preserving the shape from before productUrl/currency existed — both default to null, i.e. fall back to store-level GoogleMerchantProperties config. */
    public CatalogItem(String productId, String title, String description, BigDecimal price, String category,
                        String imageUrl, Map<String, Object> attributes, Instant updatedAt,
                        String gtin, String brand, String condition, String availability, BigDecimal shippingWeight) {
        this(productId, title, description, price, category, imageUrl, attributes, updatedAt, gtin, brand, condition, availability, shippingWeight, null, null);
    }
}
