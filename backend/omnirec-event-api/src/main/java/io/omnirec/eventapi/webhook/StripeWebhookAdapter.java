// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stripe disputes and refunds, at {@code POST /v1/webhooks/stripe}.
 *
 * <ul>
 *   <li>{@code charge.dispute.created} becomes {@code chargeback_opened}</li>
 *   <li>{@code charge.dispute.closed} becomes {@code chargeback_resolved}</li>
 *   <li>{@code charge.refunded} becomes {@code refund_issued}</li>
 * </ul>
 * Other event types are acknowledged and ignored.
 *
 * The order id comes from {@code metadata.order_id} (or {@code orderId}) on the
 * Stripe object, falling back to the payment intent and then the charge id;
 * set the metadata when creating the payment so the event joins the order.
 * {@code metadata.user_id} likewise becomes the event's user id.
 *
 * Signatures follow Stripe's documented scheme: the {@code Stripe-Signature}
 * header holds {@code t=<unix time>} and one or more {@code v1=<hex>}, each an
 * HMAC-SHA256 of {@code <t>.<raw body>} with the endpoint secret; a signature
 * older than the tolerance is refused.
 */
public class StripeWebhookAdapter implements WebhookAdapter {

    public static final String SOURCE = "stripe";

    /** Currencies Stripe counts in whole units (no minor unit). */
    private static final Set<String> ZERO_DECIMAL = Set.of("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");
    /** Currencies Stripe counts in thousandths. */
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

    private final WebhookProperties.Stripe config;
    private final String defaultTenantId;
    private final ObjectMapper mapper;
    private final Clock clock;

    public StripeWebhookAdapter(WebhookProperties.Stripe config, String defaultTenantId, ObjectMapper mapper,
                                Clock clock) {
        this.config = config;
        this.defaultTenantId = defaultTenantId;
        this.mapper = mapper;
        this.clock = clock;
        if ((config.getSecret() == null || config.getSecret().isBlank()) && config.getTenantSecrets().isEmpty()) {
            throw new IllegalStateException("omnirec.webhooks.stripe.enabled=true needs a secret (whsec_...)");
        }
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean verify(WebhookRequest request) {
        String secret = WebhookSignatures.secretFor(request.tenantId(), defaultTenantId, config.getSecret(),
                config.getTenantSecrets());
        String header = request.header("Stripe-Signature");
        if (secret == null || header == null) return false;

        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) timestamp = value;
            else if (key.equals("v1")) signatures.add(value);
        }
        if (timestamp == null || signatures.isEmpty()) return false;

        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        Duration age = Duration.between(Instant.ofEpochSecond(signedAt), clock.instant()).abs();
        if (age.compareTo(config.getTolerance()) > 0) return false;

        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + request.body().length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(request.body(), 0, signed, prefix.length, request.body().length);
        String expected = WebhookSignatures.hmacSha256Hex(secret, signed);

        boolean match = false;
        for (String signature : signatures) {
            // No early exit: every candidate is compared, in constant time.
            match |= WebhookSignatures.constantTimeEquals(expected, signature);
        }
        return match;
    }

    @Override
    public List<ObjectNode> translate(WebhookRequest request) {
        JsonNode event;
        try {
            event = mapper.readTree(request.body());
        } catch (IOException e) {
            throw new WebhookPayloadException("body is not valid JSON");
        }
        String id = JsonPaths.text(event, "id");
        String type = JsonPaths.text(event, "type");
        JsonNode object = JsonPaths.read(event, "data.object");
        if (id == null || type == null || object == null) {
            throw new WebhookPayloadException("not a Stripe event: id, type and data.object are required");
        }
        Instant created = JsonPaths.instant(event, "created");

        return switch (type) {
            case "charge.dispute.created" -> List.of(dispute(id, created, object, "chargeback_opened").build());
            case "charge.dispute.closed" -> disputeClosed(id, created, object);
            case "charge.refunded" -> List.of(refund(id, created, object));
            default -> List.of();
        };
    }

    private WebhookEnvelope dispute(String eventId, Instant created, JsonNode dispute, String name) {
        WebhookEnvelope envelope = envelope(eventId, name, created, dispute);
        envelope.block("order").put("id", orderId(dispute, JsonPaths.text(dispute, "charge")));
        payment(envelope, dispute, "amount", JsonPaths.text(dispute, "charge"));
        String reason = JsonPaths.text(dispute, "reason");
        if (reason != null) envelope.properties().put("reason", reason);
        envelope.properties().put("stripeDisputeId", JsonPaths.text(dispute, "id"));
        return envelope;
    }

    private List<ObjectNode> disputeClosed(String eventId, Instant created, JsonNode dispute) {
        String outcome = switch (String.valueOf(JsonPaths.text(dispute, "status"))) {
            case "won", "warning_closed" -> "won";
            case "lost" -> "lost";
            default -> null;
        };
        if (outcome == null) return List.of();
        WebhookEnvelope envelope = dispute(eventId, created, dispute, "chargeback_resolved");
        envelope.data().put("outcome", outcome);
        return List.of(envelope.build());
    }

    private ObjectNode refund(String eventId, Instant created, JsonNode charge) {
        WebhookEnvelope envelope = envelope(eventId, "refund_issued", created, charge);
        String chargeId = JsonPaths.text(charge, "id");
        envelope.block("order").put("id", orderId(charge, chargeId));
        // Older API versions list the refunds, newest first: report that one.
        // Otherwise only the running total is known.
        JsonNode latest = JsonPaths.read(charge, "refunds.data[0]");
        if (latest != null && JsonPaths.read(latest, "amount") != null) {
            payment(envelope, latest, "amount", JsonPaths.text(latest, "id"));
            envelope.block("payment").put("currency", currency(charge));
        } else {
            payment(envelope, charge, "amount_refunded", chargeId);
            envelope.properties().put("cumulative", true);
        }
        envelope.properties().put("fullyRefunded", charge.path("refunded").asBoolean(false));
        return envelope.build();
    }

    private WebhookEnvelope envelope(String eventId, String name, Instant created, JsonNode object) {
        String customer = JsonPaths.text(object, "customer");
        return WebhookEnvelope.create(mapper, SOURCE, "stripe:" + eventId, name, created)
                .userId(metadata(object, "user_id", "userId"))
                .subject(customer != null ? customer : JsonPaths.text(object, "id"));
    }

    private void payment(WebhookEnvelope envelope, JsonNode object, String amountField, String paymentId) {
        ObjectNode payment = envelope.block("payment");
        payment.put("provider", SOURCE);
        if (paymentId != null) payment.put("id", paymentId);
        String currency = currency(object);
        JsonNode minor = object.get(amountField);
        if (currency != null) payment.put("currency", currency);
        if (minor != null && minor.canConvertToLong() && currency != null) {
            payment.put("amount", toMajorUnits(minor.asLong(), currency));
        }
    }

    private static String currency(JsonNode object) {
        String currency = JsonPaths.text(object, "currency");
        return currency == null ? null : currency.toUpperCase(Locale.ROOT);
    }

    /** Stripe amounts are integers in the currency's smallest unit: 2400 USD cents is 24.00. */
    static BigDecimal toMajorUnits(long minor, String currency) {
        int scale = ZERO_DECIMAL.contains(currency) ? 0 : THREE_DECIMAL.contains(currency) ? 3 : 2;
        return BigDecimal.valueOf(minor, scale);
    }

    private static String orderId(JsonNode object, String fallback) {
        String fromMetadata = metadata(object, "order_id", "orderId");
        if (fromMetadata != null) return fromMetadata;
        String intent = JsonPaths.text(object, "payment_intent");
        return intent != null ? intent : fallback;
    }

    private static String metadata(JsonNode object, String... keys) {
        JsonNode metadata = object.get("metadata");
        if (metadata == null || !metadata.isObject()) return null;
        for (String key : keys) {
            String value = JsonPaths.text(metadata, key);
            if (value != null) return value;
        }
        return null;
    }

    /** For tests and tools: the header Stripe would send for {@code body} at {@code timestamp}. */
    public static String signatureHeader(String secret, long timestamp, byte[] body) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return "t=" + timestamp + ",v1=" + WebhookSignatures.hmacSha256Hex(secret, signed);
    }
}
