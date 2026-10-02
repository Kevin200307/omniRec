// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.model.CommerceEvent;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.commerce.validation.ValidationResult;
import io.omnirec.eventapi.config.EventApiAutoConfiguration;
import io.omnirec.eventapi.dto.EventDto;
import io.omnirec.eventapi.normalize.EventNormalizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenericJsonWebhookAdapterTest {

    private final ObjectMapper mapper = new EventApiAutoConfiguration().omnirecObjectMapper();

    private static WebhookProperties.EventMapping mapping(String when, String event, Map<String, Object> data) {
        WebhookProperties.EventMapping mapping = new WebhookProperties.EventMapping();
        mapping.setWhen(when);
        mapping.setEvent(event);
        mapping.setData(new LinkedHashMap<>(data));
        return mapping;
    }

    private static WebhookProperties.JsonSource shipping() {
        WebhookProperties.JsonSource config = new WebhookProperties.JsonSource();
        config.setSecret("shh");
        config.setTimestampPath("occurred_at");
        WebhookProperties.EventMapping delivered = mapping("shipment.delivered", "shipment_delivered",
                Map.of("shipment", Map.of("id", "shipment.id", "carrier", "shipment.carrier", "method", "=express")));
        delivered.setUserId("customer.ref");
        delivered.setSubject("customer.id");
        delivered.setProperties(new LinkedHashMap<>(Map.of("attempts", "shipment.attempts")));
        config.setEvents(List.of(
                delivered,
                mapping("order.paid", "purchase_completed", Map.of("order", Map.of(
                        "id", "order.ref",
                        "total", "order.total",
                        "currency", "=USD",
                        "items", Map.of("each", "order.lines", "productId", "sku", "quantity", "qty", "price", "unit_price"))))));
        return config;
    }

    private GenericJsonWebhookAdapter adapter(WebhookProperties.JsonSource config) {
        return new GenericJsonWebhookAdapter("shipping", config, "default", mapper);
    }

    private static WebhookRequest request(String tenant, Map<String, String> headers, String body) {
        return new WebhookRequest("shipping", tenant, headers, body.getBytes(StandardCharsets.UTF_8), Instant.now());
    }

    private static final String DELIVERED = """
            {"id":"wh_1","type":"shipment.delivered","occurred_at":"2026-09-30T10:15:00Z",
             "customer":{"id":"c_9","ref":"user_42"},
             "shipment":{"id":"sh_1","carrier":"DHL","attempts":2}}""";

    @Test
    void mapsFieldsConstantsIdentityAndTimestamp() throws Exception {
        ObjectNode event = only(adapter(shipping()).translate(request("default", Map.of(), DELIVERED)));

        assertEquals("shipment_delivered", event.path("event").asText());
        assertEquals("shipping:wh_1:shipment_delivered", event.path("eventId").asText());
        assertEquals("2026-09-30T10:15:00Z", event.path("timestamp").asText());
        assertEquals("sh_1", event.at("/data/shipment/id").asText());
        assertEquals("DHL", event.at("/data/shipment/carrier").asText());
        assertEquals("express", event.at("/data/shipment/method").asText());
        assertEquals(2, event.at("/properties/attempts").asInt());
        assertEquals("user_42", event.at("/identity/userId").asText());
        assertEquals("shipping_c_9", event.at("/identity/anonymousId").asText());
        assertValid(event, "delivery_completed");
    }

    @Test
    void mapsArraysElementByElementAndKeepsMoneyExact() throws Exception {
        String body = """
                {"id":"wh_2","type":"order.paid","order":{"ref":"o_77","total":179.90,
                 "lines":[{"sku":"p1","qty":1,"unit_price":89.95},{"sku":"p2","qty":1,"unit_price":89.95,"ignored":true}]}}""";
        ObjectNode event = only(adapter(shipping()).translate(request("default", Map.of(), body)));

        assertEquals(2, event.at("/data/order/items").size());
        assertEquals("p2", event.at("/data/order/items/1/productId").asText());
        assertFalse(event.at("/data/order/items/1").has("ignored"));
        assertEquals(new BigDecimal("179.90"), event.at("/data/order/total").decimalValue());
        assertEquals(new BigDecimal("89.95"), event.at("/data/order/items/0/price").decimalValue());
        assertValid(event, "purchase_completed");
    }

    @Test
    void missingFieldsAreLeftOutSoTheValidatorNamesThem() {
        ObjectNode event = only(adapter(shipping()).translate(request("default", Map.of(),
                "{\"id\":\"wh_3\",\"type\":\"shipment.delivered\",\"shipment\":{}}")));
        assertFalse(event.at("/data/shipment").has("id"));
        assertFalse(event.at("/identity").has("userId"));
        assertEquals("shipping_shipping:wh_3:shipment_delivered", event.at("/identity/anonymousId").asText());
    }

    @Test
    void unmatchedTypesProduceNothingAndBatchesAreSplit() {
        WebhookProperties.JsonSource config = shipping();
        config.setEventsPath("events");
        String body = "{\"events\":[" + DELIVERED + ",{\"id\":\"wh_9\",\"type\":\"shipment.created\"}]}";
        List<ObjectNode> events = adapter(config).translate(request("default", Map.of(), body));
        assertEquals(1, events.size());

        assertEquals(List.of(), adapter(shipping()).translate(request("default", Map.of(),
                "{\"id\":\"x\",\"type\":\"something.else\"}")));
    }

    @Test
    void withoutASenderIdTheEventIdIsAStableHash() {
        WebhookProperties.JsonSource config = shipping();
        config.setIdPath("missing");
        String id1 = only(adapter(config).translate(request("default", Map.of(), DELIVERED))).path("eventId").asText();
        String id2 = only(adapter(config).translate(request("default", Map.of(), DELIVERED))).path("eventId").asText();
        assertEquals(id1, id2, "a retried webhook must deduplicate");
        assertTrue(id1.startsWith("shipping:"));
    }

    @Test
    void rejectsBodiesThatAreNotJsonObjects() {
        assertThrows(WebhookPayloadException.class,
                () -> adapter(shipping()).translate(request("default", Map.of(), "nope")));
        assertThrows(WebhookPayloadException.class,
                () -> adapter(shipping()).translate(request("default", Map.of(), "[1,2]")));
    }

    @Test
    void verifiesHexAndBase64SignaturesWithOrWithoutPrefix() {
        byte[] body = DELIVERED.getBytes(StandardCharsets.UTF_8);
        String hex = WebhookSignatures.hmacSha256Hex("shh", body);
        GenericJsonWebhookAdapter adapter = adapter(shipping());

        assertTrue(adapter.verify(request("default", Map.of("x-signature", "sha256=" + hex), DELIVERED)));
        assertTrue(adapter.verify(request("default", Map.of("x-signature", hex.toUpperCase()), DELIVERED)));
        assertFalse(adapter.verify(request("default", Map.of("x-signature", "sha256=" + hex), DELIVERED + " ")));
        assertFalse(adapter.verify(request("default", Map.of(), DELIVERED)));
        assertFalse(adapter.verify(request("store-b", Map.of("x-signature", hex), DELIVERED)),
                "no secret for store-b");

        WebhookProperties.JsonSource shopifyStyle = shipping();
        shopifyStyle.setSignatureHeader("X-Shopify-Hmac-Sha256");
        shopifyStyle.setSignaturePrefix("");
        shopifyStyle.setSignatureEncoding(WebhookProperties.SignatureEncoding.BASE64);
        String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
        assertTrue(adapter(shopifyStyle).verify(request("default", Map.of("x-shopify-hmac-sha256", base64), DELIVERED)));
    }

    @Test
    void refusesConfigurationThatCouldNeverWork() {
        WebhookProperties.JsonSource noSecret = shipping();
        noSecret.setSecret(null);
        assertThrows(IllegalStateException.class, () -> adapter(noSecret));

        WebhookProperties.JsonSource noEvents = shipping();
        noEvents.setEvents(List.of());
        assertThrows(IllegalStateException.class, () -> adapter(noEvents));

        WebhookProperties.JsonSource badName = shipping();
        badName.setEvents(List.of(mapping(null, "Shipment Delivered", Map.of())));
        assertThrows(IllegalStateException.class, () -> adapter(badName));
    }

    @Test
    void pathsReadNestedFieldsAndIndexes() throws Exception {
        var node = mapper.readTree("{\"a\":{\"b\":[{\"c\":1},{\"c\":[5,6]}]},\"n\":null}");
        assertEquals(1, JsonPaths.read(node, "a.b[0].c").asInt());
        assertEquals(6, JsonPaths.read(node, "a.b[1].c[1]").asInt());
        assertNull(JsonPaths.read(node, "a.b[2].c"));
        assertNull(JsonPaths.read(node, "n"));
        assertSame(node, JsonPaths.read(node, "$"));
        assertEquals(Instant.ofEpochSecond(1_790_000_000L), JsonPaths.instant(mapper.readTree("{\"t\":1790000000}"), "t"));
        assertEquals(Instant.ofEpochMilli(1_790_000_000_123L), JsonPaths.instant(mapper.readTree("{\"t\":1790000000123}"), "t"));
        assertEquals(Instant.parse("2026-09-30T08:15:00Z"),
                JsonPaths.instant(mapper.readTree("{\"t\":\"2026-09-30T10:15:00+02:00\"}"), "t"));
    }

    private static ObjectNode only(List<ObjectNode> events) {
        assertEquals(1, events.size());
        return events.get(0);
    }

    private void assertValid(ObjectNode envelope, String canonicalName) throws Exception {
        EventDto dto = mapper.treeToValue(envelope, EventDto.class);
        CommerceEvent event = new EventNormalizer(false).normalize(dto, "default",
                new EventNormalizer.RequestMetadata(null, null, null, Instant.now()));
        assertEquals(canonicalName, event.eventType().wireName());
        ValidationResult result = new EventValidator(EventRegistry.standard(), ValidationMode.STRICT).validate(event);
        assertTrue(result.valid(), result.describe());
    }
}
