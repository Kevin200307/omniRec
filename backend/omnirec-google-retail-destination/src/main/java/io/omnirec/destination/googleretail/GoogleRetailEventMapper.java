// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.googleretail;

import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductDetail;
import com.google.cloud.retail.v2.PurchaseTransaction;
import com.google.cloud.retail.v2.UserEvent;
import com.google.cloud.retail.v2.UserInfo;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Timestamp;
import io.omnirec.commerce.model.EventData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;

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

    private static final Map<EventName, String> EVENT_TYPES = Map.of(
            StandardEventNames.HOME_PAGE_VIEWED, "home-page-view",
            StandardEventNames.CATEGORY_VIEWED, "category-page-view",
            StandardEventNames.PRODUCT_LIST_VIEWED, "category-page-view",
            StandardEventNames.PRODUCT_VIEWED, "detail-page-view",
            StandardEventNames.SEARCH_PERFORMED, "search",
            StandardEventNames.CART_VIEWED, "shopping-cart-page-view",
            StandardEventNames.PRODUCT_ADDED_TO_CART, "add-to-cart",
            StandardEventNames.PURCHASE_COMPLETED, "purchase-complete"
    );

    /** True when Retail has a genuine counterpart for this event type. */
    public boolean supports(EventName eventType) {
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
        EventData d = event.data();
        EventData.OrderData order = d.order();
        return switch (type) {
            case "category-page-view" -> category(d) != null;
            case "search" -> notBlank(d.search().query());
            case "detail-page-view", "add-to-cart" -> notBlank(d.product().id());
            case "purchase-complete" -> order.items() != null && !order.items().isEmpty()
                    && order.total() != null && notBlank(order.currency());
            default -> true;
        };
    }

    /** The catalog events this destination maps; everything else is skipped. */
    public static java.util.Set<String> handledEvents() {
        return EVENT_TYPES.keySet().stream().map(EventName::wireName).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String retailEventType(EventName eventType) {
        return EVENT_TYPES.get(eventType);
    }

    public UserEvent toUserEvent(CommerceEvent event) {
        if (!supports(event)) {
            throw new IllegalArgumentException(
                    "No valid Google Retail event for " + event.eventType().wireName());
        }
        String retailEventType = EVENT_TYPES.get(event.eventType());
        EventData d = event.data();

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
                    builder.addProductDetails(productDetail(d.product().id(), null));
            case "add-to-cart" -> {
                Integer quantity = d.product().quantity();
                builder.addProductDetails(productDetail(d.product().id(), quantity == null ? 1 : quantity));
            }
            case "search" -> {
                builder.setSearchQuery(d.search().query());
                addProductIds(builder, d.list().productIds());
            }
            case "category-page-view" -> {
                builder.addPageCategories(category(d));
                addProductIds(builder, d.list().productIds());
            }
            case "purchase-complete" -> {
                EventData.OrderData order = d.order();
                for (CommerceItem item : order.items()) {
                    builder.addProductDetails(productDetail(item.productId(), item.quantity()));
                }
                PurchaseTransaction.Builder transaction = PurchaseTransaction.newBuilder()
                        .setRevenue(order.total().floatValue())
                        .setCurrencyCode(order.currency());
                if (order.id() != null) transaction.setId(order.id());
                builder.setPurchaseTransaction(transaction.build());
            }
            default -> {
                // home-page-view and shopping-cart-page-view need nothing more.
            }
        }

        EventData.RecommendationData recommendation = d.recommendation();
        if (recommendation.id() != null && PROVIDER_NAME.equals(recommendation.provider())) {
            builder.setAttributionToken(recommendation.id());
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

    private static String category(EventData d) {
        if (notBlank(d.category().name())) return d.category().name();
        return notBlank(d.category().id()) ? d.category().id() : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String limit(String value) {
        return value.length() <= MAX_ID_LENGTH ? value : value.substring(0, MAX_ID_LENGTH);
    }
}
