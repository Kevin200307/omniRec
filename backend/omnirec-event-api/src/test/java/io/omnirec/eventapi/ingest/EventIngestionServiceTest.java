// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.ingest;

import io.omnirec.commerce.dedup.DeduplicationStore;
import io.omnirec.commerce.dedup.InMemoryDeduplicationStore;
import io.omnirec.commerce.identity.IdentityResolver;
import io.omnirec.commerce.identity.InMemoryIdentityLinkStore;
import io.omnirec.commerce.metrics.EventMetrics;
import io.omnirec.commerce.model.CommerceData;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.CommerceItem;
import io.omnirec.commerce.model.EventContext;
import io.omnirec.commerce.model.EventIdentity;
import io.omnirec.commerce.model.EventType;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.eventapi.dto.EventDto;
import io.omnirec.eventapi.dto.IngestResponse;
import io.omnirec.eventapi.normalize.EventNormalizer;
import io.omnirec.eventapi.queue.EventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventIngestionServiceTest {

    private static final String TENANT = "demo-store";
    private static final EventNormalizer.RequestMetadata REQUEST =
            new EventNormalizer.RequestMetadata("203.0.113.9", "GB", "Mozilla/5.0 (iPhone)", Instant.parse("2026-01-01T12:00:00Z"));

    /** Records what reached the queue so assertions can look at the finished event. */
    private static final class RecordingPublisher implements EventPublisher {
        final List<CommerceEvent> published = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void publish(CommerceEvent event) {
            if (failWith != null) throw failWith;
            published.add(event);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private RecordingPublisher publisher;
    private InMemoryIdentityLinkStore linkStore;
    private DeduplicationStore deduplicationStore;
    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        linkStore = new InMemoryIdentityLinkStore();
        deduplicationStore = new InMemoryDeduplicationStore();
        service = new EventIngestionService(
                new EventNormalizer(false),
                new EventValidator(),
                deduplicationStore,
                new IdentityResolver(linkStore),
                publisher,
                EventMetrics.noop(),
                Duration.ofHours(24),
                JSON);
    }

    private EventDto dto(String eventId, EventType type, CommerceData commerce, EventIdentity identity) {
        return new EventDto(eventId, type, "1.0", Instant.parse("2026-01-01T11:59:00Z"),
                identity, EventContext.empty(), commerce, Map.of());
    }

    private EventDto productViewed(String eventId) {
        return dto(eventId, EventType.PRODUCT_VIEWED,
                CommerceData.builder().productId("p1").build(),
                EventIdentity.anonymous("anon_A", "session_1"));
    }

    private IngestResponse ingest(EventDto... events) {
        return service.ingest(List.of(events), TENANT, REQUEST);
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        void acceptsAndQueuesAValidEvent() {
            IngestResponse response = ingest(productViewed("evt_1"));

            assertEquals(1, response.accepted());
            assertEquals(0, response.rejected());
            assertEquals(1, publisher.published.size());
        }

        @Test
        void stampsTheTenantFromTheApiKeyNotTheBody() {
            ingest(productViewed("evt_1"));

            assertEquals(TENANT, publisher.published.get(0).tenantId());
        }

        @Test
        void stampsTheServerReceiveTime() {
            ingest(productViewed("evt_1"));

            assertEquals(REQUEST.receivedAt(), publisher.published.get(0).receivedAt());
        }

        @Test
        void derivesDeviceAndCountryFromTheRequest() {
            ingest(productViewed("evt_1"));

            EventContext context = publisher.published.get(0).context();
            assertEquals(io.omnirec.commerce.model.DeviceType.MOBILE, context.device());
            assertEquals("GB", context.country());
        }

        @Test
        void dropsTheIpUnlessRetentionIsExplicitlyEnabled() {
            ingest(productViewed("evt_1"));

            assertNull(publisher.published.get(0).context().ip(),
                    "retaining an IP should be an explicit, deliberate choice");
        }

        /** A6: query strings carry reset tokens and emails; they must not reach a provider. */
        @Test
        void scrubsSecretsFromTheUrlEvenWhenTheClientDidNot() {
            EventContext raw = new EventContext(
                    "https://shop.example/reset?token=abc&email=a%40b.com&utm_source=mail#access_token=x",
                    "/reset", "https://user:pw@mail.example/inbox?sid=123",
                    null, null, null, null, null, null, null, null, null);
            ingest(new EventDto("evt_url", EventType.PAGE_VIEWED, "1.0", Instant.parse("2026-01-01T11:59:00Z"),
                    EventIdentity.anonymous("anon_A", "s1"), raw, CommerceData.empty(), Map.of()));

            EventContext stored = publisher.published.get(0).context();
            assertEquals("https://shop.example/reset?utm_source=mail", stored.url());
            assertEquals("https://mail.example/inbox", stored.referrer());
        }

        @Test
        void queuesEveryEventOfABatch() {
            IngestResponse response = ingest(
                    productViewed("evt_1"), productViewed("evt_2"), productViewed("evt_3"));

            assertEquals(3, response.accepted());
            assertEquals(3, publisher.published.size());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsAnInvalidEventWithoutQueueingIt() {
            IngestResponse response = ingest(dto("evt_1", EventType.PRODUCT_VIEWED,
                    CommerceData.empty(), EventIdentity.anonymous("anon_A", "session_1")));

            assertEquals(0, response.accepted());
            assertEquals(1, response.rejected());
            assertTrue(publisher.published.isEmpty());
        }

        @Test
        void namesTheOffendingFieldInTheResponse() {
            IngestResponse response = ingest(dto("evt_1", EventType.PRODUCT_VIEWED,
                    CommerceData.empty(), EventIdentity.anonymous("anon_A", "session_1")));

            assertTrue(response.errors().get(0).reason().contains("commerce.productId"));
        }

        /**
         * One bad event in a batch must not discard the good ones — otherwise
         * the SDK retries the whole batch forever and eventually loses all of it.
         */
        @Test
        void oneInvalidEventDoesNotSinkTheRestOfTheBatch() {
            IngestResponse response = ingest(
                    productViewed("evt_1"),
                    dto("evt_bad", EventType.PRODUCT_VIEWED, CommerceData.empty(),
                            EventIdentity.anonymous("anon_A", "session_1")),
                    productViewed("evt_3"));

            assertEquals(2, response.accepted());
            assertEquals(1, response.rejected());
            assertEquals(2, publisher.published.size());
        }

        @Test
        void refusesAnEventCarryingSensitivePaymentData() {
            EventDto unsafe = new EventDto("evt_1", EventType.PAYMENT_INFORMATION_ADDED, "1.0",
                    Instant.parse("2026-01-01T11:59:00Z"),
                    EventIdentity.anonymous("anon_A", "session_1"),
                    EventContext.empty(),
                    CommerceData.builder().cartId("cart_1").build(),
                    Map.of("cardNumber", "4111111111111111"));

            IngestResponse response = ingest(unsafe);

            assertEquals(1, response.rejected());
            assertTrue(publisher.published.isEmpty(), "a PAN must never reach the queue");
        }

        @Test
        void rejectsAPurchaseMissingItsRequiredFields() {
            EventDto incomplete = dto("evt_1", EventType.PURCHASE_COMPLETED,
                    CommerceData.builder().orderId("order_1").build(),
                    EventIdentity.authenticated("anon_A", "customer_123", "session_1"));

            assertEquals(1, ingest(incomplete).rejected());
        }
    }

    @Nested
    @DisplayName("deduplication")
    class Deduplication {

        @Test
        void queuesTheSameEventIdOnlyOnce() {
            ingest(productViewed("evt_123"));
            IngestResponse second = ingest(productViewed("evt_123"));

            assertEquals(1, publisher.published.size());
            assertEquals(1, second.duplicates());
            assertEquals(0, second.accepted());
        }

        @Test
        void deduplicatesWithinASingleBatchToo() {
            IngestResponse response = ingest(productViewed("evt_123"), productViewed("evt_123"));

            assertEquals(1, response.accepted());
            assertEquals(1, response.duplicates());
            assertEquals(1, publisher.published.size());
        }

        @Test
        void neverForwardsADuplicatePurchase() {
            EventDto purchase = dto("evt_purchase", EventType.PURCHASE_COMPLETED,
                    CommerceData.builder()
                            .orderId("order_1")
                            .items(List.of(CommerceItem.of("p1", 1, new BigDecimal("10.00"), "USD")))
                            .total(new BigDecimal("10.00"))
                            .currency("USD")
                            .build(),
                    EventIdentity.authenticated("anon_A", "customer_123", "session_1"));

            for (int i = 0; i < 5; i++) {
                ingest(purchase);
            }

            assertEquals(1, publisher.published.size(),
                    "a duplicated purchase would teach a recommender the order happened five times");
        }

        @Test
        void differentEventIdsAreNotConfused() {
            ingest(productViewed("evt_1"), productViewed("evt_2"));

            assertEquals(2, publisher.published.size());
        }

        @Test
        void tenantsDoNotShareADeduplicationNamespace() {
            service.ingest(List.of(productViewed("evt_1")), "tenant-a", REQUEST);
            service.ingest(List.of(productViewed("evt_1")), "tenant-b", REQUEST);

            assertEquals(2, publisher.published.size(),
                    "two tenants coincidentally generating the same eventId must both be accepted");
        }

        /**
         * A validation failure must not burn the deduplication key: a client
         * that sends a broken event, fixes it, and resends with the same id
         * should get through.
         */
        @Test
        void aRejectedEventDoesNotConsumeItsDeduplicationKey() {
            ingest(dto("evt_1", EventType.PRODUCT_VIEWED, CommerceData.empty(),
                    EventIdentity.anonymous("anon_A", "session_1")));

            IngestResponse corrected = ingest(productViewed("evt_1"));

            assertEquals(1, corrected.accepted());
            assertEquals(1, publisher.published.size());
        }
    }

    @Nested
    @DisplayName("identity resolution")
    class Identity {

        @Test
        void anIdentifyEventLinksButIsNotForwarded() {
            EventDto identify = dto("evt_identify", EventType.IDENTIFY, CommerceData.empty(),
                    EventIdentity.authenticated("anon_A", "customer_123", "session_1"));

            IngestResponse response = ingest(identify);

            assertEquals(1, response.accepted(), "the caller should see success");
            assertTrue(publisher.published.isEmpty(), "but a control event carries no behavioural signal");
            assertEquals(java.util.Optional.of("customer_123"), linkStore.resolveUserId(TENANT, "anon_A"));
        }

        @Test
        void aLaterAnonymousEventInheritsTheLinkedUserId() {
            ingest(dto("evt_identify", EventType.IDENTIFY, CommerceData.empty(),
                    EventIdentity.authenticated("anon_A", "customer_123", "session_1")));

            ingest(productViewed("evt_after"));

            assertEquals("customer_123", publisher.published.get(0).identity().userId());
        }

        @Test
        void resolutionHappensBeforeQueueingSoConsumersNeedNoLookup() {
            ingest(dto("evt_identify", EventType.IDENTIFY, CommerceData.empty(),
                    EventIdentity.authenticated("anon_A", "customer_123", "session_1")));
            ingest(productViewed("evt_after"));

            CommerceEvent queued = publisher.published.get(0);
            assertEquals("customer_123", queued.identity().userId());
            assertEquals("anon_A", queued.identity().anonymousId(),
                    "the anonymousId survives — it is how the device stays recognisable");
        }
    }

    @Nested
    @DisplayName("broker failure")
    class BrokerFailure {

        @Test
        void surfacesAPublishFailureSoTheClientRetries() {
            publisher.failWith = new IllegalStateException("broker unreachable");

            assertThrows(EventIngestionService.EventPublishException.class,
                    () -> ingest(productViewed("evt_1")));
        }

        /**
         * The dedup key is claimed before publishing. If publishing fails and
         * the key stayed claimed, the client's retry would be silently dropped
         * as a duplicate of an event that never actually reached the queue.
         */
        @Test
        void releasesTheDeduplicationKeySoARetryCanSucceed() {
            publisher.failWith = new IllegalStateException("broker unreachable");
            assertThrows(EventIngestionService.EventPublishException.class,
                    () -> ingest(productViewed("evt_1")));

            publisher.failWith = null;
            IngestResponse retry = ingest(productViewed("evt_1"));

            assertEquals(1, retry.accepted());
            assertEquals(1, publisher.published.size());
        }
    }

    @Nested
    @DisplayName("the crash window (lease, then complete)")
    class CrashWindow {

        /**
         * The old protocol claimed the key as "done" for 24h before publishing.
         * A crash in between left every retry dropped as a duplicate: the event
         * was silently lost. Now a crash leaves only a short lease.
         */
        @Test
        void anEventWhoseIngestionCrashedMidFlightIsRetriedNotLost() {
            String key = DeduplicationStore.key("ingest", TENANT, "evt_crash");
            // Simulate a request that took the lease and then died.
            assertEquals(DeduplicationStore.ClaimResult.CLAIMED, deduplicationStore.claim(key, Duration.ofSeconds(30)));

            IngestResponse whileLeased = ingest(productViewed("evt_crash"));

            assertEquals(1, whileLeased.retryLater(), "not a duplicate: nothing was ever queued");
            assertEquals(0, whileLeased.duplicates());
            assertTrue(whileLeased.needsRetry());
            assertTrue(publisher.published.isEmpty());
        }

        @Test
        void theRetryGoesThroughOnceTheCrashedLeaseExpires() {
            MutableClock clock = new MutableClock();
            deduplicationStore = new InMemoryDeduplicationStore(clock, 10_000);
            service = serviceWith(deduplicationStore);
            deduplicationStore.claim(DeduplicationStore.key("ingest", TENANT, "evt_crash"), Duration.ofSeconds(30));

            clock.advance(Duration.ofSeconds(31));
            IngestResponse retry = ingest(productViewed("evt_crash"));

            assertEquals(1, retry.accepted());
            assertEquals(1, publisher.published.size());
        }

        @Test
        void aSuccessfullyQueuedEventIsRememberedForTheWholeWindow() {
            MutableClock clock = new MutableClock();
            deduplicationStore = new InMemoryDeduplicationStore(clock, 10_000);
            service = serviceWith(deduplicationStore);
            ingest(productViewed("evt_1"));

            clock.advance(Duration.ofHours(23));

            assertEquals(1, ingest(productViewed("evt_1")).duplicates());
        }
    }

    @Nested
    @DisplayName("per-event binding — one bad event never sinks a batch")
    class PerEventBinding {

        private List<JsonNode> json(String... events) throws Exception {
            return JSON.readValue("[" + String.join(",", events) + "]",
                    JSON.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
        }

        private String event(String id, String type, String extra) {
            return "{\"eventId\":\"" + id + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":\"1.0\","
                    + "\"timestamp\":\"2026-01-01T11:59:00Z\","
                    + "\"identity\":{\"anonymousId\":\"anon_A\",\"sessionId\":\"s1\"},"
                    + "\"commerce\":{\"productId\":\"p1\"" + extra + "}}";
        }

        @Test
        void anUnknownEventTypeFromANewerSdkIsRejectedAloneWhileTheRestIsAccepted() throws Exception {
            IngestResponse response = service.ingestJson(json(
                    event("good", "product_viewed", ""),
                    event("future", "product_teleported", "")), TENANT, REQUEST);

            assertEquals(1, response.accepted());
            assertEquals(1, response.rejected());
            assertEquals("future", response.errors().get(0).eventId());
            assertTrue(response.errors().get(0).reason().contains("eventType"));
        }

        @Test
        void aWrongTypedFieldIsRejectedWithoutEchoingTheValue() throws Exception {
            IngestResponse response = service.ingestJson(json(
                    event("bad", "product_viewed", ",\"quantity\":\"4111111111111111\"")), TENANT, REQUEST);

            assertEquals(1, response.rejected());
            String reason = response.errors().get(0).reason();
            assertTrue(reason.contains("commerce.quantity"), reason);
            assertFalse(reason.contains("4111"), "a rejected value must never be echoed back");
        }

        @Test
        void aMalformedTimestampIsRejectedPerEvent() throws Exception {
            String badTimestamp = event("t", "product_viewed", "").replace("2026-01-01T11:59:00Z", "last tuesday");

            IngestResponse response = service.ingestJson(json(badTimestamp, event("ok", "product_viewed", "")), TENANT, REQUEST);

            assertEquals(1, response.accepted());
            assertTrue(response.errors().get(0).reason().contains("timestamp"));
        }
    }

    private EventIngestionService serviceWith(DeduplicationStore store) {
        return new EventIngestionService(new EventNormalizer(false), new EventValidator(), store,
                new IdentityResolver(linkStore), publisher, EventMetrics.noop(), Duration.ofHours(24), JSON);
    }

    private static final class MutableClock extends java.time.Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }
}
