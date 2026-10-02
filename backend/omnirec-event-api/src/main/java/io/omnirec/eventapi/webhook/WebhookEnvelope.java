// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Builds a v2 event envelope for a webhook adapter.
 *
 * Webhook events describe a customer but come from no browser session, so the
 * identity is synthesised: the anonymous id is {@code <source>_<subject>} (for
 * example a Stripe customer id) and the session id {@code webhook_<eventId>}.
 * Set {@link #userId} when the sender knows the store's own user id, so the
 * event joins that customer's history.
 */
public final class WebhookEnvelope {

    private final ObjectNode root;
    private final ObjectNode identity;
    private final ObjectNode data;
    private final ObjectNode properties;
    private final String source;

    private WebhookEnvelope(ObjectMapper mapper, String source, String eventId, String event, Instant timestamp) {
        this.source = source;
        root = mapper.createObjectNode();
        root.put("eventId", eventId);
        root.put("event", event);
        root.put("schemaVersion", "2.0");
        root.put("source", "webhook");
        root.put("timestamp", (timestamp == null ? Instant.now() : timestamp).toString());
        identity = root.putObject("identity");
        identity.put("sessionId", "webhook_" + eventId);
        root.putObject("context").put("platform", "server");
        data = root.putObject("data");
        properties = root.putObject("properties");
    }

    /**
     * @param source    the adapter's source, used to namespace synthesised ids
     * @param eventId   a stable id derived from the sender's id
     * @param event     catalog or plan event name
     * @param timestamp when it happened according to the sender; now when null
     */
    public static WebhookEnvelope create(ObjectMapper mapper, String source, String eventId, String event,
                                         Instant timestamp) {
        return new WebhookEnvelope(mapper, source, eventId, event, timestamp);
    }

    public WebhookEnvelope userId(String userId) {
        if (userId != null && !userId.isBlank()) identity.put("userId", userId);
        return this;
    }

    /** The sender's id for the customer or object this is about; becomes {@code <source>_<subject>}. */
    public WebhookEnvelope subject(String subject) {
        if (subject != null && !subject.isBlank()) identity.put("anonymousId", source + "_" + subject);
        return this;
    }

    public WebhookEnvelope anonymousId(String anonymousId) {
        if (anonymousId != null && !anonymousId.isBlank()) identity.put("anonymousId", anonymousId);
        return this;
    }

    /** The {@code data} object, for adding blocks. */
    public ObjectNode data() {
        return data;
    }

    /** A block inside {@code data}, created on first use. */
    public ObjectNode block(String name) {
        JsonNode existing = data.get(name);
        return existing instanceof ObjectNode node ? node : data.putObject(name);
    }

    public ObjectNode properties() {
        return properties;
    }

    /** The finished envelope. An anonymous id is always present: the subject, the user id, or the event id. */
    public ObjectNode build() {
        if (!identity.hasNonNull("anonymousId")) {
            String fallback = identity.hasNonNull("userId") ? identity.get("userId").asText() : root.get("eventId").asText();
            identity.put("anonymousId", source + "_" + fallback);
        }
        return root;
    }
}
