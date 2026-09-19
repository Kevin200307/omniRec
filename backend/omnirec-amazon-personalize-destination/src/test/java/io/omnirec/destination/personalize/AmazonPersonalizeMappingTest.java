package io.omnirec.destination.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.personalizeevents.PersonalizeEventsClient;
import software.amazon.awssdk.services.personalizeevents.model.Event;
import software.amazon.awssdk.services.personalizeevents.model.PutEventsRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AmazonPersonalizeMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private PersonalizeEventsClient client;

    private AmazonPersonalizeEventMapper mapper;
    private AmazonPersonalizeProperties properties;

    @BeforeEach
    void setUp() {
        mapper = new AmazonPersonalizeEventMapper(JSON);
        properties = new AmazonPersonalizeProperties();
        properties.setTrackingId("tracker-123");
    }

    private AmazonPersonalizeDestination destination() {
        return new AmazonPersonalizeDestination(client, properties, mapper);
    }

    private CommerceEvent.Builder base(EventType type) {
        return CommerceEvent.builder()
                .eventId("evt_1")
                .eventType(type)
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .tenantId("demo-store")
                .identity(EventIdentity.anonymous("anon_A", "session_1"))
                .context(EventContext.empty())
                .commerce(CommerceData.empty())
                .properties(Map.of());
    }

    private PutEventsRequest capture(CommerceEvent event) {
        destination().send(event);
        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client).putEvents(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("core field mapping")
    class CoreMapping {

        @Test
        void mapsTheCanonicalEventOntoPersonalizeFields() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p123").price(new BigDecimal("1500.00")).build())
                    .build();

            PutEventsRequest request = capture(event);
            Event mapped = request.eventList().get(0);

            assertEquals("tracker-123", request.trackingId());
            assertEquals("session_1", request.sessionId());
            assertEquals("evt_1", mapped.eventId());
            assertEquals("product_viewed", mapped.eventType());
            assertEquals("p123", mapped.itemId());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), mapped.sentAt());
        }

        @Test
        void usesPriceAsTheEventValueSoExpensiveInteractionsWeighMore() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").price(new BigDecimal("1500.00")).build())
                    .build();

            assertEquals(1500.0f, capture(event).eventList().get(0).eventValue(), 0.001);
        }

        /**
         * Personalize rejects properties keys its interactions schema doesn't
         * define, so nothing is sent unless the operator allow-lists it.
         */
        @Test
        void sendsNoPropertiesByDefault() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").categoryId("laptops").build())
                    .properties(Map.of("theme", "dark"))
                    .build();

            assertNull(capture(event).eventList().get(0).properties());
        }

        @Test
        void sendsOnlyAllowListedKeysAsAStringMap() throws Exception {
            mapper = new AmazonPersonalizeEventMapper(JSON, Set.of("categoryId", "theme"));
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").categoryId("laptops").currency("USD").build())
                    .properties(Map.of("theme", "dark", "secretSauce", "x"))
                    .build();

            Map<?, ?> properties = JSON.readValue(capture(event).eventList().get(0).properties(), Map.class);

            assertEquals(Map.of("categoryId", "laptops", "theme", "dark"), properties);
        }

        /**
         * recommendationId inside properties is a reserved keyword and makes
         * PutEvents fail — this used to break every recommendation event.
         */
        @Test
        void neverPutsRecommendationIdInProperties() throws Exception {
            mapper = new AmazonPersonalizeEventMapper(JSON, Set.of("categoryId"));
            CommerceEvent event = base(EventType.RECOMMENDATION_CLICKED)
                    .commerce(CommerceData.builder().productId("p1").recommendationId("rec_1").categoryId("c").build())
                    .build();

            String properties = capture(event).eventList().get(0).properties();

            assertFalse(properties.toLowerCase().contains("recommendationid"));
        }

        @Test
        void refusesToStartWithAReservedKeyAllowListed() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AmazonPersonalizeEventMapper(JSON, Set.of("recommendationId")));
            assertThrows(IllegalArgumentException.class,
                    () -> new AmazonPersonalizeEventMapper(JSON, Set.of("USERID")));
        }

        @Test
        void dropsPropertiesThatWouldExceedTheApiLimitRatherThanFailTheCall() {
            mapper = new AmazonPersonalizeEventMapper(JSON, Set.of("note"));
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .properties(Map.of("note", "x".repeat(2000)))
                    .build();

            Event mapped = capture(event).eventList().get(0);

            assertNull(mapped.properties());
            assertEquals("p1", mapped.itemId(), "the interaction itself is still delivered");
        }
    }

    @Nested
    @DisplayName("recommendation attribution")
    class Attribution {

        @Test
        void setsPersonalizesOwnRecommendationIdFieldForAListItServed() {
            CommerceEvent event = base(EventType.RECOMMENDATION_CLICKED)
                    .commerce(CommerceData.builder().productId("p1")
                            .recommendationId("rid-123").recommendationProvider("amazon-personalize").build())
                    .build();

            assertEquals("rid-123", capture(event).eventList().get(0).recommendationId());
        }

        @Test
        void doesNotForwardAnotherEnginesRecommendationId() {
            CommerceEvent event = base(EventType.RECOMMENDATION_CLICKED)
                    .commerce(CommerceData.builder().productId("p1")
                            .recommendationId("google-token").recommendationProvider("google-retail").build())
                    .build();

            assertNull(capture(event).eventList().get(0).recommendationId());
        }

        @Test
        void doesNotForwardARecommendationIdLongerThanTheApiAllows() {
            CommerceEvent event = base(EventType.RECOMMENDATION_CLICKED)
                    .commerce(CommerceData.builder().productId("p1")
                            .recommendationId("r".repeat(41)).recommendationProvider("amazon-personalize").build())
                    .build();

            assertNull(capture(event).eventList().get(0).recommendationId());
        }

        @Test
        void capsImpressionsAtTheApis25Items() {
            List<String> shown = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "p" + i).toList();
            CommerceEvent event = base(EventType.RECOMMENDATION_IMPRESSION)
                    .commerce(CommerceData.builder().recommendationId("rec_1").productIds(shown).build())
                    .build();

            assertEquals(25, capture(event).eventList().get(0).impression().size());
        }
    }

    @Nested
    @DisplayName("anonymous vs authenticated identity")
    class Identity {

        /**
         * The single most important assertion in this class. Putting the
         * anonymousId into Personalize's userId would create a throwaway user
         * per browser that never reconciles with the real customer.
         */
        @Test
        void anAnonymousVisitorSendsNoUserIdAtAll() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .identity(EventIdentity.anonymous("anon_A", "session_1"))
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();

            PutEventsRequest request = capture(event);

            assertNull(request.userId(), "an anonymous visitor must not be given a Personalize userId");
            assertEquals("session_1", request.sessionId(),
                    "Personalize stitches anonymous sessions to a user via sessionId");
        }

        @Test
        void anAuthenticatedVisitorSendsTheRealUserId() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();

            assertEquals("customer_123", capture(event).userId());
        }

        @Test
        void theSessionIsSentForBothSoLoginStitchesTheSessionToTheUser() {
            CommerceEvent anonymous = base(EventType.PRODUCT_VIEWED)
                    .identity(EventIdentity.anonymous("anon_A", "session_1"))
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();

            assertEquals("session_1", capture(anonymous).sessionId());
        }

        @Test
        void fallsBackToTheAnonymousIdWhenNoSessionIsPresent() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .identity(new EventIdentity("anon_A", null, null))
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();

            assertEquals("anon_A", capture(event).sessionId());
        }
    }

    @Nested
    @DisplayName("purchases and impressions")
    class PurchasesAndImpressions {

        @Test
        void splitsAMultiLineOrderIntoOneInteractionPerItem() {
            CommerceEvent event = base(EventType.PURCHASE_COMPLETED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder()
                            .orderId("order_1")
                            .items(List.of(
                                    CommerceItem.of("p1", 1, new BigDecimal("10.00"), "USD"),
                                    CommerceItem.of("p2", 2, new BigDecimal("20.00"), "USD")))
                            .total(new BigDecimal("50.00"))
                            .currency("USD")
                            .build())
                    .build();

            List<Event> events = capture(event).eventList();

            assertEquals(2, events.size(), "Personalize carries one itemId per event");
            assertEquals("p1", events.get(0).itemId());
            assertEquals("p2", events.get(1).itemId());
        }

        @Test
        void givesEachSplitLineItsOwnEventIdSoPersonalizeKeepsThemAll() {
            CommerceEvent event = base(EventType.PURCHASE_COMPLETED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder()
                            .orderId("order_1")
                            .items(List.of(
                                    CommerceItem.of("p1", 1, new BigDecimal("10.00"), "USD"),
                                    CommerceItem.of("p2", 1, new BigDecimal("20.00"), "USD")))
                            .build())
                    .build();

            List<Event> events = capture(event).eventList();

            assertNotEquals(events.get(0).eventId(), events.get(1).eventId(),
                    "Personalize deduplicates on eventId — identical ids would collapse the order");
        }

        @Test
        void weightsEachLineByQuantityTimesPrice() {
            CommerceEvent event = base(EventType.PURCHASE_COMPLETED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder()
                            .orderId("order_1")
                            .items(List.of(
                                    CommerceItem.of("p1", 3, new BigDecimal("10.00"), "USD"),
                                    CommerceItem.of("p2", 1, new BigDecimal("20.00"), "USD")))
                            .build())
                    .build();

            assertEquals(30.0f, capture(event).eventList().get(0).eventValue(), 0.001);
        }

        @Test
        void sendsShownProductsAsAnImpressionSoNonClicksAreLearnable() {
            CommerceEvent event = base(EventType.RECOMMENDATION_IMPRESSION)
                    .commerce(CommerceData.builder()
                            .recommendationId("rec_1")
                            .productIds(List.of("p1", "p2", "p3"))
                            .build())
                    .build();

            assertEquals(List.of("p1", "p2", "p3"), capture(event).eventList().get(0).impression());
        }

        @Test
        void doesNotAttachImpressionsToOrdinaryEvents() {
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").productIds(List.of("p1", "p2")).build())
                    .build();

            assertTrue(capture(event).eventList().get(0).impression().isEmpty());
        }
    }

    @Nested
    @DisplayName("filtering and failures")
    class FilteringAndFailures {

        @Test
        void skipsEventTypesWithNoMeaningInAnInteractionsDataset() {
            for (EventType type : List.of(EventType.SESSION_STARTED, EventType.SESSION_ENDED,
                    EventType.USER_LOGGED_OUT, EventType.IDENTIFY)) {
                assertFalse(destination().supports(base(type).build()), type + " should be filtered out");
            }
        }

        @Test
        void acceptsTheEventTypesThatDoCarrySignal() {
            for (EventType type : List.of(EventType.PRODUCT_VIEWED, EventType.PRODUCT_ADDED_TO_CART,
                    EventType.PURCHASE_COMPLETED, EventType.SEARCH_PERFORMED)) {
                assertTrue(destination().supports(base(type).build()), type + " should be delivered");
            }
        }

        @Test
        void aMissingTrackingIdIsAPermanentFailureNotARetryLoop() {
            properties.setTrackingId(null);
            CommerceEvent event = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .build();

            DestinationException thrown = assertThrows(DestinationException.class, () -> destination().send(event));

            assertFalse(thrown.isRetryable(), "retrying a configuration error forever helps nobody");
        }

        @Test
        void skipsADwellTimeUpdateSoAViewIsCountedOnce() {
            CommerceEvent dwellUpdate = base(EventType.PRODUCT_VIEWED)
                    .commerce(CommerceData.builder().productId("p1").build())
                    .properties(Map.of(CommerceEvent.PROPERTY_DWELL_TIME_MS, 4200,
                            CommerceEvent.PROPERTY_VIEW_EVENT_ID, "evt_view"))
                    .build();

            assertFalse(destination().supports(dwellUpdate));
        }

        /**
         * PutEvents accepts at most 10 events per call. A 23-line order is 23
         * interactions, which used to go out as one rejected call.
         */
        @Test
        void sendsALargeOrderInChunksOfAtMostTen() {
            List<CommerceItem> lines = java.util.stream.IntStream.range(0, 23)
                    .mapToObj(i -> CommerceItem.of("p" + i, 1, new BigDecimal("1.00"), "USD"))
                    .toList();
            CommerceEvent order = base(EventType.PURCHASE_COMPLETED)
                    .identity(EventIdentity.authenticated("anon_A", "customer_123", "session_1"))
                    .commerce(CommerceData.builder().orderId("o1").items(lines)
                            .total(new BigDecimal("23.00")).currency("USD").build())
                    .build();

            destination().send(order);

            ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
            verify(client, org.mockito.Mockito.times(3)).putEvents(captor.capture());
            List<Integer> sizes = captor.getAllValues().stream().map(r -> r.eventList().size()).toList();
            assertEquals(List.of(10, 10, 3), sizes);
            assertTrue(captor.getAllValues().stream().allMatch(r -> "customer_123".equals(r.userId())));
        }

        @Test
        void reportsItsIdForLogsAndMetrics() {
            assertEquals("amazon-personalize", destination().id());
        }
    }
}
