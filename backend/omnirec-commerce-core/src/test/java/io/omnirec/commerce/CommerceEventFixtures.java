package io.omnirec.commerce;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.model.Platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared fixtures. Extend these rather than forking them, so every test moves together with the schema. */
public final class CommerceEventFixtures {

    public static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    public static final String ANON = "anon_A";
    public static final String USER = "customer_123";
    public static final String SESSION = "session_1";
    public static final String TENANT = "demo-store";

    private CommerceEventFixtures() {
    }

    public static CommerceEvent.Builder base(EventType type) {
        return CommerceEvent.builder()
                .eventId("evt_" + type.wireName())
                .eventType(type)
                .schemaVersion(CommerceEvent.CURRENT_SCHEMA_VERSION)
                .timestamp(AT)
                .tenantId(TENANT)
                .identity(EventIdentity.anonymous(ANON, SESSION))
                .context(new EventContext("https://shop.example/p/1", "/p/1", null, Platform.WEB,
                        null, "Mozilla/5.0", "en-US", "UTC", 1920, 1080, null, null))
                .commerce(CommerceData.empty())
                .properties(Map.of());
    }

    public static CommerceEvent productViewed() {
        return base(EventType.PRODUCT_VIEWED)
                .commerce(CommerceData.builder()
                        .productId("p123")
                        .categoryId("laptops")
                        .price(new BigDecimal("1500.00"))
                        .currency("USD")
                        .build())
                .build();
    }

    public static CommerceEvent productViewedByUser() {
        return productViewed().withIdentity(EventIdentity.authenticated(ANON, USER, SESSION));
    }

    public static CommerceEvent addedToCart() {
        return base(EventType.PRODUCT_ADDED_TO_CART)
                .commerce(CommerceData.builder()
                        .productId("p123")
                        .cartId("cart_1")
                        .quantity(2)
                        .price(new BigDecimal("1200.00"))
                        .currency("USD")
                        .build())
                .build();
    }

    public static CommerceEvent purchaseCompleted() {
        return base(EventType.PURCHASE_COMPLETED)
                .identity(EventIdentity.authenticated(ANON, USER, SESSION))
                .commerce(CommerceData.builder()
                        .orderId("order_1")
                        .items(List.of(CommerceItem.of("p123", 2, new BigDecimal("1200.00"), "USD")))
                        .total(new BigDecimal("2400.00"))
                        .currency("USD")
                        .build())
                .build();
    }

    public static CommerceEvent searchPerformed() {
        return base(EventType.SEARCH_PERFORMED)
                .commerce(CommerceData.builder().searchQuery("gaming laptop").build())
                .properties(Map.of("resultCount", 24))
                .build();
    }

    public static CommerceEvent identify() {
        return base(EventType.IDENTIFY)
                .identity(EventIdentity.authenticated(ANON, USER, SESSION))
                .build();
    }

    public static CommerceEvent recommendationImpression() {
        return base(EventType.RECOMMENDATION_IMPRESSION)
                .commerce(CommerceData.builder()
                        .recommendationId("rec_123")
                        .productIds(List.of("p1", "p2", "p3"))
                        .build())
                .properties(Map.of("source", "homepage"))
                .build();
    }
}
