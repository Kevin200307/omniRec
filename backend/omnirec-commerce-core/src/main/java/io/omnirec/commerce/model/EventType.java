// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The commerce event taxonomy. Mirrors {@code packages/commerce-web/src/events/types.ts}
 * and {@code schema/commerce-event.schema.json} — the contract test in
 * omnirec-contract-tests fails if the three drift apart.
 *
 * The wire format is the lowercase name ("product_viewed"), not the enum
 * constant, so a consumer in any language sees the same string.
 */
public enum EventType {

    // --- SESSION ---
    SESSION_STARTED("session_started", EventCategory.SESSION),
    SESSION_ENDED("session_ended", EventCategory.SESSION),
    PAGE_VIEWED("page_viewed", EventCategory.SESSION),
    HOME_PAGE_VIEWED("home_page_viewed", EventCategory.SESSION),

    // --- DISCOVERY ---
    SEARCH_PERFORMED("search_performed", EventCategory.DISCOVERY),
    SEARCH_RESULT_CLICKED("search_result_clicked", EventCategory.DISCOVERY),
    PRODUCT_LIST_VIEWED("product_list_viewed", EventCategory.DISCOVERY),
    CATEGORY_VIEWED("category_viewed", EventCategory.DISCOVERY),
    PRODUCT_VIEWED("product_viewed", EventCategory.DISCOVERY),
    PRODUCT_CLICKED("product_clicked", EventCategory.DISCOVERY),

    // --- PRODUCT INTERACTION ---
    PRODUCT_WISHLISTED("product_wishlisted", EventCategory.PRODUCT_INTERACTION),
    PRODUCT_SHARED("product_shared", EventCategory.PRODUCT_INTERACTION),
    PRODUCT_COMPARED("product_compared", EventCategory.PRODUCT_INTERACTION),
    PRODUCT_REVIEW_VIEWED("product_review_viewed", EventCategory.PRODUCT_INTERACTION),
    PRODUCT_REVIEW_SUBMITTED("product_review_submitted", EventCategory.PRODUCT_INTERACTION),

    // --- CART ---
    CART_VIEWED("cart_viewed", EventCategory.CART),
    PRODUCT_ADDED_TO_CART("product_added_to_cart", EventCategory.CART),
    PRODUCT_REMOVED_FROM_CART("product_removed_from_cart", EventCategory.CART),
    CART_QUANTITY_UPDATED("cart_quantity_updated", EventCategory.CART),
    CART_ABANDONED("cart_abandoned", EventCategory.CART),

    // --- CHECKOUT ---
    CHECKOUT_STARTED("checkout_started", EventCategory.CHECKOUT),
    SHIPPING_INFORMATION_ADDED("shipping_information_added", EventCategory.CHECKOUT),
    PAYMENT_INFORMATION_ADDED("payment_information_added", EventCategory.CHECKOUT),
    CHECKOUT_COMPLETED("checkout_completed", EventCategory.CHECKOUT),
    CHECKOUT_FAILED("checkout_failed", EventCategory.CHECKOUT),

    // --- PURCHASE ---
    PURCHASE_COMPLETED("purchase_completed", EventCategory.PURCHASE),
    PURCHASE_FAILED("purchase_failed", EventCategory.PURCHASE),
    ORDER_CANCELLED("order_cancelled", EventCategory.PURCHASE),
    ORDER_REFUNDED("order_refunded", EventCategory.PURCHASE),

    // --- RECOMMENDATION ---
    RECOMMENDATION_IMPRESSION("recommendation_impression", EventCategory.RECOMMENDATION),
    RECOMMENDATION_CLICKED("recommendation_clicked", EventCategory.RECOMMENDATION),
    RECOMMENDATION_ADDED_TO_CART("recommendation_added_to_cart", EventCategory.RECOMMENDATION),
    RECOMMENDATION_PURCHASED("recommendation_purchased", EventCategory.RECOMMENDATION),

    // --- USER ---
    USER_REGISTERED("user_registered", EventCategory.USER),
    USER_LOGGED_IN("user_logged_in", EventCategory.USER),
    USER_LOGGED_OUT("user_logged_out", EventCategory.USER),
    USER_PROFILE_UPDATED("user_profile_updated", EventCategory.USER),

    /** Internal control event: establishes an anonymousId -> userId link. Not delivered to providers. */
    IDENTIFY("identify", EventCategory.IDENTITY);

    private static final Map<String, EventType> BY_WIRE_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(EventType::wireName, Function.identity()));

    private final String wireName;
    private final EventCategory category;

    EventType(String wireName, EventCategory category) {
        this.wireName = wireName;
        this.category = category;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public EventCategory category() {
        return category;
    }

    /**
     * Control events steer the pipeline itself rather than describing shopper
     * behaviour, so they are never forwarded to a provider.
     */
    public boolean isControlEvent() {
        return category == EventCategory.IDENTITY;
    }

    /**
     * Jackson rejects an unknown event type outright rather than mapping it to a
     * null or a catch-all: an event type we don't recognise is a client/server
     * version mismatch, and silently accepting it would deliver a shape no
     * adapter knows how to map.
     */
    @JsonCreator
    public static EventType fromWireName(String value) {
        EventType type = value == null ? null : BY_WIRE_NAME.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown eventType: " + value);
        }
        return type;
    }

    public static Optional<EventType> find(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_NAME::get);
    }
}
