// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.googleretail;

import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductDetail;
import com.google.cloud.retail.v2.PurchaseTransaction;
import com.google.cloud.retail.v2.UserEvent;
import com.google.cloud.retail.v2.UserInfo;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Timestamp;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventType;

import java.util.List;
import java.util.Map;

/**
 * Maps the canonical {@link CommerceEvent} onto Google Cloud Retail's
 * {@code UserEvent}. Pure translation, no network calls.
 *
 * Checked against the current documentation:
 * https://docs.cloud.google.com/retail/docs/user-events and
 * https://docs.cloud.google.com/retail/docs/reference/rest/v2/projects.locations.catalogs.userEvents
 *
 * <h2>The closed vocabulary</h2>
 * Retail accepts exactly seven event types. Anything else is <b>dropped, not
 * coerced</b> — including {@code product_removed_from_cart} and
 * {@code page_viewed}, which have no counterpart ({@code remove-from-cart} and
 * {@code page-visit} are <em>not</em> valid types and are rejected).
 *
 * <h2>No double counting</h2>
 * Clicks ({@code product_clicked}, {@code search_result_clicked},
 * {@code recommendation_clicked}) are not mapped to {@code detail-page-view}:
 * the detail page the click leads to sends its own {@code product_viewed}, and
 * mapping both would count every visit twice. The same applies to
 * {@code recommendation_added_to_cart} (the add itself is sent) and
 * {@code recommendation_purchased} (the purchase itself is sent). Dwell-time
 * engagement updates are skipped for the same reason.
 *
 * <h2>Identity</h2>
 * {@code visitorId} is always the anonymous visitor, with
 * {@code userInfo.userId} alongside it. Putting the userId in visitorId would
 * make one person two visitors either side of login.
 *
 * <h2>Attribution</h2>
 * {@code attributionToken} must be a token Google itself returned, so it is
 * set only when {@code commerce.recommendationProvider} is google-retail.
 */
public class GoogleRetailEventMapper {

    public static final String PROVIDER_NAME = "google-retail";
    private static final int MAX_ID_LENGTH = 128;

    private static final Map<EventType, String> EVENT_TYPES = Map.of(
            EventType.HOME_PAGE_VIEWED, "home-page-view",
            EventType.CATEGORY_VIEWED, "category-page-view",
            EventType.PRODUCT_LIST_VIEWED, "category-page-view",
            EventType.PRODUCT_VIEWED, "detail-page-view",
            EventType.SEARCH_PERFORMED, "search",
            EventType.CART_VIEWED, "shopping-cart-page-view",
            EventType.PRODUCT_ADDED_TO_CART, "add-to-cart",
            EventType.PURCHASE_COMPLETED, "purchase-complete"
    );

    /** True when Retail has a genuine counterpart for this event type. */
    public boolean supports(EventType eventType) {
        return EVENT_TYPES.containsKey(eventType);
    }

    /**
     * True when this specific event can be delivered: a supported type, not an
     * engagement update, and carrying the fields Retail requires for that type.
     * An event failing this would be rejected by the API, so it is filtered
     * here instead of burning retries on its way to the dead-letter queue.
     */
    public boolean supports(CommerceEvent event) {
        String type = EVENT_TYPES.get(event.eventType());
        if (type == null || event.isEngagementUpdate()) return false;
        CommerceData c = event.commerce();
        return switch (type) {
            case "category-page-view" -> category(c) != null;
            case "search" -> notBlank(c.searchQuery());
            case "detail-page-view", "add-to-cart" -> notBlank(c.productId());
            case "purchase-complete" -> c.items() != null && !c.items().isEmpty()
                    && c.total() != null && notBlank(c.currency());
            default -> true;
        };
    }

    public String retailEventType(EventType eventType) {
        return EVENT_TYPES.get(eventType);
    }

    public UserEvent toUserEvent(CommerceEvent event) {
        if (!supports(event)) {
            throw new IllegalArgumentException(
                    "No valid Google Retail event for " + event.eventType().wireName());
        }
        String retailEventType = EVENT_TYPES.get(event.eventType());
        CommerceData c = event.commerce();

        UserEvent.Builder builder = UserEvent.newBuilder()
                .setEventType(retailEventType)
                .setVisitorId(limit(event.identity().anonymousId()))
                .setEventTime(Timestamp.newBuilder()
                        .setSeconds(event.timestamp().getEpochSecond())
                        .setNanos(event.timestamp().getNano())
                        .build());

        if (event.identity().sessionId() != null) {
            builder.setSessionId(limit(event.identity().sessionId()));
        }
        if (event.identity().isAuthenticated()) {
            builder.setUserInfo(UserInfo.newBuilder().setUserId(limit(event.identity().userId())).build());
        }

        switch (retailEventType) {
            case "detail-page-view" ->
                    // Exactly one product per detail-page-view.
                    builder.addProductDetails(productDetail(c.productId(), null));
            case "add-to-cart" ->
                    builder.addProductDetails(productDetail(c.productId(), c.quantity() == null ? 1 : c.quantity()));
            case "search" -> {
                builder.setSearchQuery(c.searchQuery());
                addProductIds(builder, c.productIds());
            }
            case "category-page-view" -> {
                builder.addPageCategories(category(c));
                addProductIds(builder, c.productIds());
            }
            case "purchase-complete" -> {
                for (CommerceItem item : c.items()) {
                    builder.addProductDetails(productDetail(item.productId(), item.quantity()));
                }
                PurchaseTransaction.Builder transaction = PurchaseTransaction.newBuilder()
                        .setRevenue(c.total().floatValue())
                        .setCurrencyCode(c.currency());
                if (c.orderId() != null) transaction.setId(c.orderId());
                builder.setPurchaseTransaction(transaction.build());
            }
            default -> {
                // home-page-view and shopping-cart-page-view need nothing more.
            }
        }

        if (c.recommendationId() != null && PROVIDER_NAME.equals(c.recommendationProvider())) {
            builder.setAttributionToken(c.recommendationId());
        }
        if (event.context().url() != null) builder.setUri(event.context().url());
        if (event.context().referrer() != null) builder.setReferrerUri(event.context().referrer());

        return builder.build();
    }

    private void addProductIds(UserEvent.Builder builder, List<String> productIds) {
        if (productIds == null) return;
        for (String productId : productIds) {
            builder.addProductDetails(productDetail(productId, null));
        }
    }

    private ProductDetail productDetail(String productId, Integer quantity) {
        ProductDetail.Builder detail = ProductDetail.newBuilder()
                .setProduct(Product.newBuilder().setId(productId).build());
        if (quantity != null) detail.setQuantity(Int32Value.of(quantity));
        return detail.build();
    }

    private static String category(CommerceData c) {
        if (notBlank(c.category())) return c.category();
        return notBlank(c.categoryId()) ? c.categoryId() : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String limit(String value) {
        return value.length() <= MAX_ID_LENGTH ? value : value.substring(0, MAX_ID_LENGTH);
    }
}
