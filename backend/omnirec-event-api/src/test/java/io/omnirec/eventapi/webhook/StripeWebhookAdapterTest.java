// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.commerce.validation.ValidationResult;
import io.omnirec.eventapi.config.EventApiAutoConfiguration;
import io.omnirec.eventapi.dto.EventDto;
import io.omnirec.eventapi.normalize.EventNormalizer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Recorded Stripe payloads, signed the way Stripe documents. */
class StripeWebhookAdapterTest {

    private static final String SECRET = "whsec_test_secret";
    private static final long NOW = 1_790_000_060L;

    private final ObjectMapper mapper = new EventApiAutoConfiguration().omnirecObjectMapper();
    private final StripeWebhookAdapter adapter = adapter(Map.of());

    private StripeWebhookAdapter adapter(Map<String, String> tenantSecrets) {
        WebhookProperties.Stripe config = new WebhookProperties.Stripe();
        config.setEnabled(true);
        config.setSecret(SECRET);
        config.setTenantSecrets(tenantSecrets);
        return new StripeWebhookAdapter(config, "default", mapper,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    /** Stripe's scheme, written out independently of the adapter: HMAC-SHA256 of "t.body", hex. */
    private static String stripeSignature(String secret, long t, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = StripeWebhookAdapterTest.class.getResourceAsStream("/stripe/" + name + ".json")) {
            return in.readAllBytes();
        }
    }

    private static WebhookRequest request(String tenant, String header, byte[] body) {
        return new WebhookRequest("stripe", tenant, header == null ? Map.of() : Map.of("stripe-signature", header),
                body, Instant.ofEpochSecond(NOW));
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        byte[] body = fixture("charge.refunded");
        String header = "t=" + NOW + ",v1=" + stripeSignature(SECRET, NOW, body);
        assertTrue(adapter.verify(request("default", header, body)));
        assertEquals(header, StripeWebhookAdapter.signatureHeader(SECRET, NOW, body));
    }

    @Test
    void acceptsWhenAnyOfSeveralSignaturesMatches() throws Exception {
        // Stripe sends one v1 per active secret while a secret is being rolled.
        byte[] body = fixture("charge.refunded");
        String header = "t=" + NOW + ",v1=" + stripeSignature("whsec_old", NOW, body)
                + ",v1=" + stripeSignature(SECRET, NOW, body) + ",v0=ignored";
        assertTrue(adapter.verify(request("default", header, body)));
    }

    @Test
    void rejectsTamperedBodiesWrongSecretsMissingHeadersAndReplays() throws Exception {
        byte[] body = fixture("charge.refunded");
        String valid = "t=" + NOW + ",v1=" + stripeSignature(SECRET, NOW, body);

        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("2400", "9900").getBytes(StandardCharsets.UTF_8);
        assertFalse(adapter.verify(request("default", valid, tampered)));
        assertFalse(adapter.verify(request("default", "t=" + NOW + ",v1=" + stripeSignature("whsec_other", NOW, body), body)));
        assertFalse(adapter.verify(request("default", null, body)));
        assertFalse(adapter.verify(request("default", "v1=" + stripeSignature(SECRET, NOW, body), body)));

        long old = NOW - 301;
        assertFalse(adapter.verify(request("default", "t=" + old + ",v1=" + stripeSignature(SECRET, old, body), body)),
                "a signature older than the tolerance is a replay");
    }

    @Test
    void aTenantNeedsItsOwnSecret() throws Exception {
        byte[] body = fixture("charge.refunded");
        StripeWebhookAdapter multi = adapter(Map.of("store-b", "whsec_store_b"));

        assertFalse(multi.verify(request("store-a", "t=" + NOW + ",v1=" + stripeSignature(SECRET, NOW, body), body)),
                "the default secret must not open other tenants");
        assertFalse(multi.verify(request("store-b", "t=" + NOW + ",v1=" + stripeSignature(SECRET, NOW, body), body)));
        assertTrue(multi.verify(request("store-b", "t=" + NOW + ",v1=" + stripeSignature("whsec_store_b", NOW, body), body)));
    }

    @Test
    void chargeRefundedBecomesRefundIssued() throws Exception {
        ObjectNode event = only(adapter.translate(request("default", null, fixture("charge.refunded"))));

        assertEquals("refund_issued", event.path("event").asText());
        assertEquals("stripe:evt_3PxRefund0001", event.path("eventId").asText());
        assertEquals("webhook", event.path("source").asText());
        assertEquals(Instant.ofEpochSecond(1_790_000_000L).toString(), event.path("timestamp").asText());
        assertEquals("ord_1001", event.at("/data/order/id").asText());
        assertEquals(new BigDecimal("24.00"), event.at("/data/payment/amount").decimalValue());
        assertEquals("USD", event.at("/data/payment/currency").asText());
        assertEquals("re_3PxRefund0001", event.at("/data/payment/id").asText());
        assertEquals("user_42", event.at("/identity/userId").asText());
        assertEquals("stripe_cus_Q1Customer", event.at("/identity/anonymousId").asText());
        assertFalse(event.at("/properties/fullyRefunded").asBoolean());
        assertValid(event);
    }

    @Test
    void withoutARefundListTheRunningTotalIsReportedAsCumulative() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(fixture("charge.refunded"));
        ((ObjectNode) body.at("/data/object")).remove("refunds");
        ObjectNode event = only(adapter.translate(request("default", null, mapper.writeValueAsBytes(body))));

        assertEquals(new BigDecimal("24.00"), event.at("/data/payment/amount").decimalValue());
        assertTrue(event.at("/properties/cumulative").asBoolean());
        assertValid(event);
    }

    @Test
    void disputeCreatedBecomesChargebackOpened() throws Exception {
        ObjectNode event = only(adapter.translate(request("default", null, fixture("charge.dispute.created"))));

        assertEquals("chargeback_opened", event.path("event").asText());
        // No order metadata on the dispute: the payment intent stands in for the order.
        assertEquals("pi_3PxIntent0002", event.at("/data/order/id").asText());
        assertEquals(new BigDecimal("19.99"), event.at("/data/payment/amount").decimalValue());
        assertEquals("EUR", event.at("/data/payment/currency").asText());
        assertEquals("fraudulent", event.at("/properties/reason").asText());
        assertEquals("stripe_dp_1PxDispute0001", event.at("/identity/anonymousId").asText());
        assertValid(event);
    }

    @Test
    void disputeClosedBecomesChargebackResolvedWithTheOutcome() throws Exception {
        ObjectNode event = only(adapter.translate(request("default", null, fixture("charge.dispute.closed"))));

        assertEquals("chargeback_resolved", event.path("event").asText());
        assertEquals("ord_2002", event.at("/data/order/id").asText());
        assertEquals("lost", event.at("/data/outcome").asText());
        assertValid(event);
    }

    @Test
    void otherEventTypesAreAcknowledgedAndIgnored() throws Exception {
        assertEquals(List.of(), adapter.translate(request("default", null, fixture("customer.created"))));
    }

    @Test
    void nonStripeBodiesAreRejected() {
        assertThrows(WebhookPayloadException.class,
                () -> adapter.translate(request("default", null, "{\"hello\":1}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(WebhookPayloadException.class,
                () -> adapter.translate(request("default", null, "not json".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void minorUnitsFollowTheCurrency() {
        assertEquals(new BigDecimal("24.00"), StripeWebhookAdapter.toMajorUnits(2400, "USD"));
        assertEquals(new BigDecimal("2400"), StripeWebhookAdapter.toMajorUnits(2400, "JPY"));
        assertEquals(new BigDecimal("2.400"), StripeWebhookAdapter.toMajorUnits(2400, "KWD"));
    }

    private static ObjectNode only(List<ObjectNode> events) {
        assertEquals(1, events.size());
        return events.get(0);
    }

    /** The produced envelope binds, normalizes and passes the strict catalog validator. */
    private void assertValid(JsonNode envelope) throws Exception {
        EventDto dto = mapper.treeToValue(envelope, EventDto.class);
        CommerceEvent event = new EventNormalizer(false).normalize(dto, "default",
                new EventNormalizer.RequestMetadata(null, null, null, Instant.ofEpochSecond(NOW)));
        ValidationResult result = new EventValidator(io.omnirec.commerce.catalog.EventRegistry.standard(), ValidationMode.STRICT).validate(event);
        assertTrue(result.valid(), result.describe());
    }
}
