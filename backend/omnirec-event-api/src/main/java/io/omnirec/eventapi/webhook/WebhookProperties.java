// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound webhooks, served at {@code POST /v1/webhooks/{source}}. See
 * docs/webhooks.md.
 *
 * <pre>
 * omnirec:
 *   webhooks:
 *     stripe:
 *       enabled: true
 *       secret: ${STRIPE_WEBHOOK_SECRET}
 *     json:
 *       shipping:
 *         secret: ${SHIPPING_WEBHOOK_SECRET}
 *         events:
 *           - when: shipment.delivered
 *             event: shipment_delivered
 *             user-id: customer.id
 *             data:
 *               shipment: { id: shipment_id }
 *               order: { id: order_ref }
 * </pre>
 *
 * Each source has one secret for the default tenant and optionally one per
 * tenant ({@code tenant-secrets}); a call picks its tenant with
 * {@code ?tenant=<id>}. A tenant without a secret cannot receive webhooks.
 */
@ConfigurationProperties(prefix = "omnirec.webhooks")
public class WebhookProperties {

    /** Stripe disputes and refunds. */
    private final Stripe stripe = new Stripe();

    /** Generic JSON sources, keyed by the {@code {source}} path segment. */
    private Map<String, JsonSource> json = new LinkedHashMap<>();

    public Stripe getStripe() { return stripe; }
    public Map<String, JsonSource> getJson() { return json; }
    public void setJson(Map<String, JsonSource> json) { this.json = json; }

    public static class Stripe {
        private boolean enabled = false;
        /** The endpoint's signing secret ({@code whsec_...}) for the default tenant. */
        private String secret;
        /** Signing secret per tenant id, one Stripe endpoint per store. */
        private Map<String, String> tenantSecrets = new LinkedHashMap<>();
        /** Largest accepted age of a signature, against replays. Stripe's libraries use five minutes. */
        private Duration tolerance = Duration.ofMinutes(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public Map<String, String> getTenantSecrets() { return tenantSecrets; }
        public void setTenantSecrets(Map<String, String> tenantSecrets) { this.tenantSecrets = tenantSecrets; }
        public Duration getTolerance() { return tolerance; }
        public void setTolerance(Duration tolerance) { this.tolerance = tolerance; }
    }

    /** A sender that posts JSON and signs the body with HMAC-SHA256. */
    public static class JsonSource {
        private String secret;
        private Map<String, String> tenantSecrets = new LinkedHashMap<>();
        /** Header carrying the signature. */
        private String signatureHeader = "X-Signature";
        /** Text before the signature, removed when present, for example {@code sha256=}. */
        private String signaturePrefix = "sha256=";
        /** {@code hex} or {@code base64} (Shopify, for example, sends base64). */
        private SignatureEncoding signatureEncoding = SignatureEncoding.HEX;
        /** Path to an array of events in the body. Unset: the body is one event, or an array of them. */
        private String eventsPath;
        /** Path to the sender's event id, used for deduplication. Unset or missing: a hash of the event. */
        private String idPath = "id";
        /** Path to the sender's event type, matched against each mapping's {@code when}. */
        private String typePath = "type";
        /** Path to when it happened: ISO-8601 text, or epoch seconds or milliseconds. Unset: now. */
        private String timestampPath;
        /** Event mappings. Every mapping whose {@code when} matches produces one event. */
        private List<EventMapping> events = new ArrayList<>();

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public Map<String, String> getTenantSecrets() { return tenantSecrets; }
        public void setTenantSecrets(Map<String, String> tenantSecrets) { this.tenantSecrets = tenantSecrets; }
        public String getSignatureHeader() { return signatureHeader; }
        public void setSignatureHeader(String signatureHeader) { this.signatureHeader = signatureHeader; }
        public String getSignaturePrefix() { return signaturePrefix; }
        public void setSignaturePrefix(String signaturePrefix) { this.signaturePrefix = signaturePrefix; }
        public SignatureEncoding getSignatureEncoding() { return signatureEncoding; }
        public void setSignatureEncoding(SignatureEncoding signatureEncoding) { this.signatureEncoding = signatureEncoding; }
        public String getEventsPath() { return eventsPath; }
        public void setEventsPath(String eventsPath) { this.eventsPath = eventsPath; }
        public String getIdPath() { return idPath; }
        public void setIdPath(String idPath) { this.idPath = idPath; }
        public String getTypePath() { return typePath; }
        public void setTypePath(String typePath) { this.typePath = typePath; }
        public String getTimestampPath() { return timestampPath; }
        public void setTimestampPath(String timestampPath) { this.timestampPath = timestampPath; }
        public List<EventMapping> getEvents() { return events; }
        public void setEvents(List<EventMapping> events) { this.events = events; }
    }

    public enum SignatureEncoding { HEX, BASE64 }

    /**
     * One sender event type to one omniRec event.
     *
     * {@code data} and {@code properties} mirror the shape of the omniRec
     * event. Each leaf is a path into the sender's event ({@code order.ref},
     * {@code lines[0].sku}); a leaf starting with {@code =} is a constant
     * ({@code =USD}); an object with an {@code each} key maps an array, with
     * paths relative to each element:
     *
     * <pre>
     * data:
     *   order:
     *     id: order_ref
     *     currency: =USD
     *     items: { each: lines, productId: sku, quantity: qty }
     * </pre>
     */
    public static class EventMapping {
        /** Sender event type this applies to. Unset: every event. */
        private String when;
        /** The omniRec event name. */
        private String event;
        /** Path to the store's user id, when the sender knows it. */
        private String userId;
        /** Path to the sender's customer id, which becomes the anonymous id {@code <source>_<value>}. */
        private String subject;
        private Map<String, Object> data = new LinkedHashMap<>();
        private Map<String, Object> properties = new LinkedHashMap<>();

        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }
        public String getEvent() { return event; }
        public void setEvent(String event) { this.event = event; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }
}
