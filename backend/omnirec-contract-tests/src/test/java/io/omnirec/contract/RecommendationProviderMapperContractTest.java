// SPDX-License-Identifier: Apache-2.0
package io.omnirec.contract;

import com.google.cloud.retail.v2.UserEvent;
import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.core.model.EventType;
import io.omnirec.googlerecai.GoogleRecAiProperties;
import io.omnirec.googlerecai.GoogleRecommendationsAiProvider;
import io.omnirec.personalize.AmazonPersonalizeProvider;
import io.omnirec.personalize.PersonalizeProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.personalizeevents.model.Event;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The <em>legacy</em> serving-side mappers, reached only through the legacy
 * ingestion endpoint (off by default; see docs/AUDIT.md, S1).
 *
 * This test used to assert {@code visitorId == userId} as correct — the audit
 * found it was encoding the bug, not guarding against it. It now holds the
 * legacy mappers to the same identity rules as the new adapters, so anyone who
 * re-enables legacy ingestion doesn't get the old bugs back.
 */
class RecommendationProviderMapperContractTest {

    private final CanonicalEvent fixture = CanonicalEventFixture.productClicked();

    private CanonicalEvent viewed(String userId) {
        return new CanonicalEvent("evt-fixture-2", "tenant-1", userId, "anon-99", "session-7",
                EventType.PRODUCT_VIEWED, fixture.category(), fixture.payload(), fixture.context());
    }

    @Test
    void personalizeMapperPreservesIdentityFields() throws Exception {
        AmazonPersonalizeProvider provider = new AmazonPersonalizeProvider(null, null, new PersonalizeProperties());

        Event mapped = provider.toPersonalizeEvent(fixture);

        assertEquals(fixture.eventId(), mapped.eventId(), "eventId must round-trip unchanged");
        assertEquals(fixture.eventType().name(), mapped.eventType(), "eventType must round-trip unchanged");
        assertEquals("sku-123", mapped.itemId(), "payload.productId must map to itemId");
        assertNotNull(mapped.sentAt(), "sentAt must never be null — Personalize rejects events without it");
    }

    @Test
    void googleVisitorIdIsTheAnonymousVisitorEvenWhenLoggedIn() {
        GoogleRecommendationsAiProvider provider = new GoogleRecommendationsAiProvider(null, null, new GoogleRecAiProperties());

        UserEvent mapped = provider.toUserEvent(viewed("user-42"));

        assertEquals("anon-99", mapped.getVisitorId(), "visitorId must be the anonymous visitor");
        assertEquals("user-42", mapped.getUserInfo().getUserId(), "the user rides alongside in userInfo");
        assertEquals("detail-page-view", mapped.getEventType());
        assertEquals("sku-123", mapped.getProductDetails(0).getProduct().getId());
    }

    @Test
    void googleDropsTypesWithNoValidRetailCounterpartInsteadOfCoercingThem() {
        GoogleRecommendationsAiProvider provider = new GoogleRecommendationsAiProvider(null, null, new GoogleRecAiProperties());

        CanonicalEvent removal = new CanonicalEvent("evt-3", "tenant-1", null, "anon-99", "session-7",
                EventType.CART_REMOVE, fixture.category(), fixture.payload(), fixture.context());

        assertThrows(IllegalArgumentException.class, () -> provider.toUserEvent(removal),
                "a removal used to be sent as add-to-cart — the opposite of what happened");
    }
}
