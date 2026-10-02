// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage;

import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.DeviceType;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.catalog.generated.StandardEventNames;
import io.omnirec.commerce.model.EventName;
import io.omnirec.commerce.model.Platform;
import io.omnirec.commerce.storage.CustomerEventPage;
import io.omnirec.commerce.storage.EventQuery;
import io.omnirec.commerce.storage.EventStore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event builders shared by the storage tests. */
public final class StorageFixtures {

    public static final Instant T0 = Instant.parse("2026-09-28T08:00:00Z");

    private StorageFixtures() {
    }

    /** A unique tenant per test, so tests sharing one database can't see each other's rows. */
    public static String tenant() {
        return "tenant_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String eventId() {
        return "evt_" + UUID.randomUUID();
    }

    public static CommerceEvent anonymousView(String tenant, String anonymousId, String productId, Instant at) {
        return event(tenant, StandardEventNames.PRODUCT_VIEWED, EventIdentity.anonymous(anonymousId, "session_1"), productId, at);
    }

    public static CommerceEvent userView(String tenant, String anonymousId, String userId, String productId, Instant at) {
        return event(tenant, StandardEventNames.PRODUCT_VIEWED,
                EventIdentity.authenticated(anonymousId, userId, "session_2"), productId, at);
    }

    public static CommerceEvent identify(String tenant, String anonymousId, String userId, Instant at) {
        return CommerceEvent.builder()
                .eventId(eventId())
                .eventType(StandardEventNames.IDENTIFY)
                .timestamp(at)
                .tenantId(tenant)
                .identity(EventIdentity.authenticated(anonymousId, userId, "session_login"))
                .context(EventContext.server())
                .receivedAt(at.plusMillis(5))
                .build();
    }

    public static CommerceEvent event(String tenant, EventName type, EventIdentity identity, String productId, Instant at) {
        return CommerceEvent.builder()
                .eventId(eventId())
                .eventType(type)
                .timestamp(at)
                .tenantId(tenant)
                .identity(identity)
                .context(EventContext.empty())
                .commerce(CommerceData.builder().productId(productId).build())
                .receivedAt(at.plusMillis(5))
                .build();
    }

    /** Every part of the canonical model populated, to prove nothing is lost on the way through the database. */
    public static CommerceEvent fullyPopulatedPurchase(String tenant) {
        return CommerceEvent.builder()
                .eventId(eventId())
                .eventType(StandardEventNames.PURCHASE_COMPLETED)
                .timestamp(Instant.parse("2026-09-28T08:15:20.123456Z"))
                .tenantId(tenant)
                .identity(EventIdentity.authenticated("anon_full", "user_full", "session_full"))
                .context(new EventContext("https://shop.example/checkout/done", "/checkout/done",
                        "https://shop.example/cart", Platform.WEB, DeviceType.MOBILE, "Mozilla/5.0",
                        "en-GB", "Europe/London", 390, 844, null, "GB"))
                .commerce(CommerceData.builder()
                        .orderId("order_1")
                        .currency("GBP")
                        .total(new BigDecimal("1234.50"))
                        .items(List.of(
                                CommerceItem.of("p1", 2, new BigDecimal("0.10"), "GBP"),
                                new CommerceItem("p2", 1, new BigDecimal("1234.30"), "GBP", "shoes")))
                        .build())
                .properties(Map.of("coupon", "SUMMER", "nested", Map.of("gift", true, "note", "é ✓")))
                .receivedAt(Instant.parse("2026-09-28T08:15:21.5Z"))
                .build();
    }

    /** Walks every page and returns all events, newest first. */
    public static List<CommerceEvent> allPages(EventStore store, String tenant, String customer, EventQuery query) {
        List<CommerceEvent> all = new ArrayList<>();
        CustomerEventPage page = store.findCustomerEvents(tenant, customer, query);
        all.addAll(page.events());
        int guard = 0;
        while (page.hasMore()) {
            page = store.findCustomerEvents(tenant, customer, query.after(page.nextCursor()));
            all.addAll(page.events());
            if (++guard > 1000) throw new AssertionError("pagination does not terminate");
        }
        return all;
    }
}
