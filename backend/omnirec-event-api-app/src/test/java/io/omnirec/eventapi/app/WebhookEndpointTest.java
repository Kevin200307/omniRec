// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.app;

import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.model.EventSource;
import io.omnirec.eventapi.webhook.StripeWebhookAdapter;
import io.omnirec.eventapi.webhook.WebhookSignatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/webhooks/{source}} through the whole HTTP pipeline, with the
 * generic JSON source configured the way an operator would, in properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "omnirec.processing.queue-enabled=false",
        "omnirec.events.tenants.demo-store.api-key=pk_test_demo_store",
        "omnirec.events.default-tenant-id=demo-store",
        "omnirec.events.max-payload-bytes=4096",
        "omnirec.destinations.amazon-personalize.enabled=false",
        "omnirec.destinations.google-retail.enabled=false",
        "omnirec.webhooks.stripe.enabled=true",
        "omnirec.webhooks.stripe.secret=whsec_demo",
        "omnirec.webhooks.stripe.tenant-secrets.store-b=whsec_store_b",
        "omnirec.webhooks.json.shipping.secret=ship_secret",
        "omnirec.webhooks.json.shipping.events[0].when=shipment.delivered",
        "omnirec.webhooks.json.shipping.events[0].event=shipment_delivered",
        "omnirec.webhooks.json.shipping.events[0].user-id=customer.ref",
        "omnirec.webhooks.json.shipping.events[0].data.shipment.id=shipment.id",
        "omnirec.webhooks.json.shipping.events[0].data.shipment.carrier=shipment.carrier",
        "omnirec.webhooks.json.shipping.events[0].data.shipment.orderId=order_ref",
        "omnirec.webhooks.json.shipping.events[1].when=shipment.lost",
        "omnirec.webhooks.json.shipping.events[1].event=delivery_failed",
        "omnirec.webhooks.json.shipping.events[1].data.shipment.carrier=shipment.carrier"
})
class WebhookEndpointTest {

    static class RecordingDestination implements EventDestination {
        final List<CommerceEvent> received = new CopyOnWriteArrayList<>();

        @Override public String id() { return "recording-webhooks"; }

        @Override public void send(CommerceEvent event) { received.add(event); }
    }

    @TestConfiguration
    static class TestDestinations {
        @Bean
        RecordingDestination recordingWebhookDestination() {
            return new RecordingDestination();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RecordingDestination destination;

    @BeforeEach
    void clear() {
        destination.received.clear();
    }

    private static String refund(String eventId) {
        return """
                {"id":"%s","object":"event","type":"charge.refunded","created":%d,
                 "data":{"object":{"id":"ch_1","object":"charge","amount_refunded":1250,"currency":"usd",
                 "customer":"cus_1","metadata":{"order_id":"ord_9","user_id":"user_9"},"refunded":false}}}
                """.formatted(eventId, Instant.now().getEpochSecond());
    }

    private ResultActions stripe(String query, String secret, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mockMvc.perform(post("/v1/webhooks/stripe" + query)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeWebhookAdapter.signatureHeader(secret, Instant.now().getEpochSecond(), bytes))
                .content(bytes));
    }

    @Test
    void aSignedStripeRefundReachesDestinationsAsAWebhookEvent() throws Exception {
        stripe("", "whsec_demo", refund("evt_ok_1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        assertEquals(1, destination.received.size());
        CommerceEvent event = destination.received.get(0);
        assertEquals("refund_issued", event.eventType().wireName());
        assertEquals(EventSource.WEBHOOK, event.source());
        assertEquals("demo-store", event.tenantId());
        assertEquals("ord_9", event.data().order().id());
        assertEquals("user_9", event.identity().userId());
        assertEquals(0, new BigDecimal("12.50").compareTo(
                new BigDecimal(String.valueOf(((java.util.Map<?, ?>) event.data().asMap().get("payment")).get("amount")))));
    }

    @Test
    void aRetriedWebhookIsADuplicate() throws Exception {
        stripe("", "whsec_demo", refund("evt_retry")).andExpect(status().isAccepted());
        stripe("", "whsec_demo", refund("evt_retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));
        assertEquals(1, destination.received.size());
    }

    @Test
    void badSignaturesAreRefusedWithoutAnApiKeyBeingInvolved() throws Exception {
        stripe("", "whsec_wrong", refund("evt_bad")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(refund("evt_none")))
                .andExpect(status().isUnauthorized());
        // A valid API key is not a webhook credential.
        mockMvc.perform(post("/v1/webhooks/stripe").header("X-Omnirec-Key", "pk_test_demo_store")
                        .contentType(MediaType.APPLICATION_JSON).content(refund("evt_key")))
                .andExpect(status().isUnauthorized());
        assertTrue(destination.received.isEmpty());
    }

    @Test
    void theTenantComesFromTheQueryAndNeedsItsOwnSecret() throws Exception {
        stripe("?tenant=store-b", "whsec_demo", refund("evt_t1")).andExpect(status().isUnauthorized());
        stripe("?tenant=store-c", "whsec_demo", refund("evt_t2")).andExpect(status().isUnauthorized());
        stripe("?tenant=store-b", "whsec_store_b", refund("evt_t3")).andExpect(status().isAccepted());

        assertEquals(1, destination.received.size());
        assertEquals("store-b", destination.received.get(0).tenantId());
    }

    @Test
    void unknownSourcesAre404AndOversizedBodiesAre413() throws Exception {
        mockMvc.perform(post("/v1/webhooks/shopify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        String huge = "{\"pad\":\"" + "x".repeat(5000) + "\"}";
        stripe("", "whsec_demo", huge).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void unmappedStripeTypesAreAcknowledged() throws Exception {
        String body = """
                {"id":"evt_cust","type":"customer.created","created":1,"data":{"object":{"id":"cus_1"}}}""";
        stripe("", "whsec_demo", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0));
        assertTrue(destination.received.isEmpty());
    }

    @Test
    void aGenericJsonSourceConfiguredInPropertiesWorksEndToEnd() throws Exception {
        String body = """
                {"id":"ship_1","type":"shipment.delivered","order_ref":"ord_5",
                 "customer":{"ref":"user_5"},"shipment":{"id":"sh_5","carrier":"UPS"}}""";
        mockMvc.perform(post("/v1/webhooks/shipping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "sha256=" + WebhookSignatures.hmacSha256Hex("ship_secret",
                                body.getBytes(StandardCharsets.UTF_8)))
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1));

        CommerceEvent event = destination.received.get(0);
        assertEquals("delivery_completed", event.eventType().wireName(), "the alias is resolved to the canonical name");
        java.util.Map<?, ?> shipment = (java.util.Map<?, ?>) event.data().asMap().get("shipment");
        assertEquals("sh_5", shipment.get("id"));
        assertEquals("UPS", shipment.get("carrier"));
        assertEquals("ord_5", shipment.get("orderId"));
        assertEquals("user_5", event.identity().userId());
    }

    @Test
    void anInvalidMappedEventIsReportedButStill202SoTheSenderStopsRetrying() throws Exception {
        // delivery_failed requires shipment.id, which this mapping never sets.
        String body = """
                {"id":"ship_2","type":"shipment.lost","shipment":{"carrier":"UPS"}}""";
        mockMvc.perform(post("/v1/webhooks/shipping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", WebhookSignatures.hmacSha256Hex("ship_secret",
                                body.getBytes(StandardCharsets.UTF_8)))
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].reason").value(org.hamcrest.Matchers.containsString("shipment.id")));
        assertTrue(destination.received.isEmpty());
    }
}
