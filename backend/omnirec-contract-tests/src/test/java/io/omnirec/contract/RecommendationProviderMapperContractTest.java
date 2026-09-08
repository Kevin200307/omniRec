package io.omnirec.contract;

import io.omnirec.core.model.CanonicalEvent;
import io.omnirec.googlerecai.GoogleRecAiProperties;
import io.omnirec.googlerecai.GoogleRecommendationsAiProvider;
import io.omnirec.personalize.AmazonPersonalizeProvider;
import io.omnirec.personalize.PersonalizeProperties;
import software.amazon.awssdk.services.personalizeevents.model.Event;
import com.google.cloud.retail.v2.UserEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enforces the implementation plan's Phase 2 exit criteria in code: every
 * RecommendationProvider's mapper must preserve the identity fields
 * (eventId/eventType/productId/session) from the canonical event. If a
 * future change to @omnirec/core's OutgoingEvent shape or to a mapper drops
 * one of these, this test fails before it ships — not after a provider
 * silently stops receiving a signal in production.
 */
class RecommendationProviderMapperContractTest {

    private final CanonicalEvent fixture = CanonicalEventFixture.productClicked();

    @Test
    void personalizeMapperPreservesIdentityFields() throws Exception {
        AmazonPersonalizeProvider provider = new AmazonPersonalizeProvider(null, null, new PersonalizeProperties());

        Event mapped = provider.toPersonalizeEvent(fixture);

        assertEquals(fixture.eventId(), mapped.eventId(), "eventId must round-trip unchanged");
        assertEquals(fixture.eventType().name(), mapped.eventType(), "eventType must round-trip unchanged");
        assertEquals("sku-123", mapped.itemId(), "payload.productId must map to itemId");
        assertNotNull(mapped.sentAt(), "sentAt must never be null — Personalize rejects events without it");
        assertNotNull(mapped.properties(), "payload must be preserved as the properties JSON blob");
    }

    @Test
    void googleRecAiMapperPreservesIdentityFields() {
        GoogleRecommendationsAiProvider provider = new GoogleRecommendationsAiProvider(null, null, new GoogleRecAiProperties());

        UserEvent mapped = provider.toUserEvent(fixture);

        assertEquals(fixture.userId(), mapped.getVisitorId(), "userId must map to visitorId");
        assertEquals(fixture.sessionId(), mapped.getSessionId(), "sessionId must round-trip unchanged");
        assertEquals(1, mapped.getProductDetailsCount(), "payload.productId must produce exactly one ProductDetail");
        assertEquals("sku-123", mapped.getProductDetails(0).getProduct().getId(), "payload.productId must map to Product.id");
        assertFalse(mapped.getEventType().isBlank(), "eventType vocabulary mapping must never produce a blank string");
    }

    @Test
    void anonymousEventsFallBackToAnonymousIdWhenUserIdIsAbsent() throws Exception {
        CanonicalEvent anonymous = new CanonicalEvent(
                "evt-fixture-2", "tenant-1", null, "anon-99", "session-7",
                fixture.eventType(), fixture.category(), fixture.payload(), fixture.context()
        );

        GoogleRecommendationsAiProvider googleProvider = new GoogleRecommendationsAiProvider(null, null, new GoogleRecAiProperties());
        assertEquals("anon-99", googleProvider.toUserEvent(anonymous).getVisitorId(),
                "an unauthenticated visitor must still be identifiable to the provider via anonymousId");
    }
}
