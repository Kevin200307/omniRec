package io.omnirec.catalog.providers.googlemerchant;

import io.omnirec.catalog.CatalogItem;
import io.omnirec.googlemerchant.GoogleMerchantProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared CatalogItem → Google Merchant field mapping and validation, used
 * by both GoogleMerchantCatalogProvider (push mode) and
 * GoogleMerchantFeedFileProvider (scheduled-fetch mode) — the two modes
 * format the result differently (a Content API Product resource vs. a TSV
 * row), but they must reject the exact same items for the exact same
 * reasons, so that logic lives here once, not duplicated per mode.
 *
 * Every rejection here happens before any network/file call — a missing
 * GTIN is caught in this method, not discovered from a Content API error
 * response after the fact.
 */
public class GoogleMerchantItemMapper {

    private static final Set<String> VALID_AVAILABILITY = Set.of("in_stock", "out_of_stock", "preorder");

    public MappingOutcome map(CatalogItem item, GoogleMerchantProperties properties) {
        if (item.title() == null || item.title().isBlank()) {
            return new MappingOutcome.Rejected(item.productId(), "missing title");
        }
        if (item.price() == null) {
            return new MappingOutcome.Rejected(item.productId(), "missing price");
        }
        if (item.availability() == null || !VALID_AVAILABILITY.contains(item.availability())) {
            return new MappingOutcome.Rejected(item.productId(),
                    "availability must be one of " + VALID_AVAILABILITY + ", was: " + item.availability());
        }
        if (item.gtin() == null || item.gtin().isBlank()) {
            return new MappingOutcome.Rejected(item.productId(), "missing GTIN");
        }
        String link = resolveLink(item, properties);
        if (link == null) {
            return new MappingOutcome.Rejected(item.productId(),
                    "no product URL available — set CatalogItem.productUrl() for this item, or configure omnirec.providers.google-merchant.product-url-template as a store-wide default");
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("id", item.productId());
        attributes.put("title", item.title());
        if (item.description() != null) {
            attributes.put("description", item.description());
        }
        attributes.put("link", link);
        if (item.imageUrl() != null) {
            attributes.put("image_link", item.imageUrl());
        }
        attributes.put("availability", item.availability());
        attributes.put("price", item.price() + " " + resolveCurrency(item, properties));
        attributes.put("gtin", item.gtin());
        if (item.brand() != null) {
            attributes.put("brand", item.brand());
        }
        if (item.condition() != null) {
            attributes.put("condition", item.condition());
        }
        if (item.shippingWeight() != null) {
            attributes.put("shipping_weight", item.shippingWeight() + " kg");
        }
        attributes.put("content_language", properties.getContentLanguage());
        attributes.put("target_country", properties.getTargetCountry());
        attributes.put("channel", properties.getChannel());

        return new MappingOutcome.Mapped(attributes);
    }

    /** Per-item CatalogItem.productUrl() wins when present; otherwise falls back to the store-wide template. Returns null only when neither is available — the caller turns that into a rejection. */
    private String resolveLink(CatalogItem item, GoogleMerchantProperties properties) {
        if (item.productUrl() != null && !item.productUrl().isBlank()) {
            return item.productUrl();
        }
        if (properties.getProductUrlTemplate() != null && !properties.getProductUrlTemplate().isBlank()) {
            return properties.getProductUrlTemplate().replace("{productId}", item.productId());
        }
        return null;
    }

    /** Per-item CatalogItem.currency() wins when present; otherwise falls back to the store-wide default (GoogleMerchantProperties.currency always has a value, so this never returns null — a missing currency, unlike a missing URL, always has a safe default). */
    private String resolveCurrency(CatalogItem item, GoogleMerchantProperties properties) {
        if (item.currency() != null && !item.currency().isBlank()) {
            return item.currency();
        }
        return properties.getCurrency();
    }

    /**
     * Content API's REST resource id for a product is a composite string —
     * "channel:contentLanguage:targetCountry:offerId" — not the raw offerId.
     * Needed only by push mode's delete call (products().delete() takes
     * this composite); insert doesn't need it, Google computes it server-side.
     */
    public static String toRestProductId(String offerId, GoogleMerchantProperties properties) {
        return properties.getChannel() + ":" + properties.getContentLanguage() + ":" + properties.getTargetCountry() + ":" + offerId;
    }
}
