// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnirec.eventapi.webhook.WebhookProperties.EventMapping;
import io.omnirec.eventapi.webhook.WebhookProperties.JsonSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Connects most tools without writing Java: a configured mapping from the
 * sender's JSON to omniRec events, with HMAC-SHA256 verification of the raw
 * body. See {@link WebhookProperties.EventMapping} for the mapping syntax.
 */
public class GenericJsonWebhookAdapter implements WebhookAdapter {

    private static final Pattern EVENT_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final String EACH = "each";

    private final String source;
    private final JsonSource config;
    private final String defaultTenantId;
    private final ObjectMapper mapper;

    public GenericJsonWebhookAdapter(String source, JsonSource config, String defaultTenantId, ObjectMapper mapper) {
        this.source = source;
        this.config = config;
        this.defaultTenantId = defaultTenantId;
        this.mapper = mapper;
        checkConfig();
    }

    /** Fails startup on a mapping that could never produce a valid event. */
    private void checkConfig() {
        String where = "omnirec.webhooks.json." + source;
        if (config.getEvents().isEmpty()) {
            throw new IllegalStateException(where + ".events is empty: the source would accept calls and record nothing");
        }
        if ((config.getSecret() == null || config.getSecret().isBlank()) && config.getTenantSecrets().isEmpty()) {
            throw new IllegalStateException(where + " has no secret: unsigned webhooks are never accepted");
        }
        for (int i = 0; i < config.getEvents().size(); i++) {
            String name = config.getEvents().get(i).getEvent();
            if (name == null || !EVENT_NAME.matcher(name).matches()) {
                throw new IllegalStateException(where + ".events[" + i + "].event must be an event name, got " + name);
            }
        }
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public boolean verify(WebhookRequest request) {
        String secret = WebhookSignatures.secretFor(request.tenantId(), defaultTenantId, config.getSecret(),
                config.getTenantSecrets());
        String sent = request.header(config.getSignatureHeader());
        if (secret == null || sent == null) return false;
        sent = sent.trim();
        String prefix = config.getSignaturePrefix();
        if (prefix != null && !prefix.isEmpty() && sent.startsWith(prefix)) sent = sent.substring(prefix.length());

        String expectedHex = WebhookSignatures.hmacSha256Hex(secret, request.body());
        String expected = config.getSignatureEncoding() == WebhookProperties.SignatureEncoding.BASE64
                ? Base64.getEncoder().encodeToString(HexFormat.of().parseHex(expectedHex))
                : expectedHex;
        if (config.getSignatureEncoding() == WebhookProperties.SignatureEncoding.HEX) sent = sent.toLowerCase(java.util.Locale.ROOT);
        return WebhookSignatures.constantTimeEquals(expected, sent);
    }

    @Override
    public List<ObjectNode> translate(WebhookRequest request) {
        JsonNode body;
        try {
            body = mapper.readTree(request.body());
        } catch (IOException e) {
            throw new WebhookPayloadException("body is not valid JSON");
        }
        if (body == null) throw new WebhookPayloadException("body is empty");
        JsonNode items = config.getEventsPath() == null ? body : JsonPaths.read(body, config.getEventsPath());
        if (items == null) throw new WebhookPayloadException("no events at " + config.getEventsPath());

        List<ObjectNode> events = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(item -> translateOne(item, events));
        } else {
            translateOne(items, events);
        }
        return events;
    }

    private void translateOne(JsonNode item, List<ObjectNode> out) {
        if (!item.isObject()) throw new WebhookPayloadException("each event must be a JSON object");
        String type = JsonPaths.text(item, config.getTypePath());
        String senderId = JsonPaths.text(item, config.getIdPath());
        if (senderId == null) senderId = hash(item);

        for (EventMapping mapping : config.getEvents()) {
            if (mapping.getWhen() != null && !mapping.getWhen().equals(type)) continue;
            WebhookEnvelope envelope = WebhookEnvelope.create(mapper, source,
                            source + ":" + senderId + ":" + mapping.getEvent(), mapping.getEvent(),
                            config.getTimestampPath() == null ? null : JsonPaths.instant(item, config.getTimestampPath()))
                    .userId(mapping.getUserId() == null ? null : JsonPaths.text(item, mapping.getUserId()))
                    .subject(mapping.getSubject() == null ? null : JsonPaths.text(item, mapping.getSubject()));
            fill(envelope.data(), mapping.getData(), item);
            fill(envelope.properties(), mapping.getProperties(), item);
            out.add(envelope.build());
        }
    }

    /** Writes each mapped leaf that resolves; a missing source value leaves the field out. */
    private void fill(ObjectNode target, Map<String, Object> spec, JsonNode item) {
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            JsonNode value = resolve(entry.getValue(), item);
            if (value != null) target.set(entry.getKey(), value);
        }
    }

    @SuppressWarnings("unchecked")
    private JsonNode resolve(Object spec, JsonNode item) {
        if (spec instanceof String text) {
            if (text.startsWith("=")) return mapper.getNodeFactory().textNode(text.substring(1));
            JsonNode value = JsonPaths.read(item, text);
            return value == null ? null : value.deepCopy();
        }
        if (spec instanceof Map<?, ?> nested) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>((Map<String, Object>) nested);
            Object each = fields.remove(EACH);
            if (each instanceof String arrayPath) {
                JsonNode array = JsonPaths.read(item, arrayPath);
                if (array == null || !array.isArray()) return null;
                ArrayNode result = mapper.createArrayNode();
                for (JsonNode element : array) {
                    ObjectNode mapped = mapper.createObjectNode();
                    fill(mapped, fields, element);
                    result.add(mapped);
                }
                return result;
            }
            ObjectNode object = mapper.createObjectNode();
            fill(object, fields, item);
            return object.isEmpty() ? null : object;
        }
        // Spring binds a YAML list under a map as a map keyed "0", "1", ...; anything else is a constant.
        return spec == null ? null : mapper.valueToTree(spec);
    }

    private static String hash(JsonNode item) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(item.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
