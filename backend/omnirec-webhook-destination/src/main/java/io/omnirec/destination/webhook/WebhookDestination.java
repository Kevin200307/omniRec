// SPDX-License-Identifier: Apache-2.0
package io.omnirec.destination.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.commerce.destination.DestinationException;
import io.omnirec.commerce.destination.EventDestination;
import io.omnirec.commerce.model.CommerceEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POSTs events to one configured URL.
 *
 * <p>The body is {@code {"events":[...]}} with the canonical v2 events. Each
 * request carries
 * <ul>
 *   <li>{@code X-Omnirec-Signature: t=<unix seconds>,v1=<hex>}, an HMAC-SHA256
 *       of {@code <t>.<body>} with the endpoint secret (the scheme Stripe uses,
 *       so existing verification code applies). Reject old timestamps to stop
 *       replays;</li>
 *   <li>{@code X-Omnirec-Delivery}, a unique id per attempt.</li>
 * </ul>
 *
 * <p>A 2xx is success. 408, 429 and 5xx, and network failures, are retried
 * through the destination's retry tiers and dead-lettered when they run out.
 * Any other 4xx means the receiver refuses this payload and always will: it is
 * dead-lettered at once. Receivers should deduplicate by {@code eventId}:
 * delivery is at least once.
 */
public class WebhookDestination implements EventDestination {

    public static final String SIGNATURE_HEADER = "X-Omnirec-Signature";
    public static final String DELIVERY_HEADER = "X-Omnirec-Delivery";

    private final String name;
    private final WebhookDestinationProperties.Endpoint endpoint;
    private final URI uri;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WebhookDestination(String name, WebhookDestinationProperties.Endpoint endpoint, HttpClient http,
                              ObjectMapper mapper, Clock clock) {
        this.name = name;
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
        String where = "omnirec.destinations.webhook.endpoints." + name;
        if (endpoint.getUrl() == null || endpoint.getUrl().isBlank()) throw new IllegalStateException(where + ".url is required");
        if (endpoint.getSecret() == null || endpoint.getSecret().isBlank()) {
            throw new IllegalStateException(where + ".secret is required: receivers must be able to verify requests");
        }
        if (endpoint.getEvents().isEmpty()) {
            throw new IllegalStateException(where + ".events is empty; list event names, or \"*\" for all");
        }
        this.uri = URI.create(endpoint.getUrl());
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !local) {
            throw new IllegalStateException(where + ".url must use https: events contain customer data");
        }
    }

    @Override
    public String id() {
        return "webhook-" + name;
    }

    @Override
    public boolean supports(CommerceEvent event) {
        if (!EventDestination.super.supports(event)) return false;
        if (!endpoint.getTenants().isEmpty() && !endpoint.getTenants().contains(event.tenantId())) return false;
        String eventName = event.eventType().wireName();
        for (String pattern : endpoint.getEvents()) {
            if (pattern.equals("*") || pattern.equals(eventName)) return true;
            if (pattern.endsWith("*") && eventName.startsWith(pattern.substring(0, pattern.length() - 1))) return true;
        }
        return false;
    }

    @Override
    public boolean acceptsUnplanned() {
        return endpoint.isIncludeUnplanned();
    }

    @Override
    public void send(CommerceEvent event) {
        sendBatch(List.of(event));
    }

    @Override
    public void sendBatch(List<CommerceEvent> events) {
        if (events.isEmpty()) return;
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("events", events));
        } catch (JsonProcessingException e) {
            throw new DestinationException(id(), "could not serialise events", e, false);
        }
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(endpoint.getTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "omnirec-webhook/1")
                .header(SIGNATURE_HEADER, signature(endpoint.getSecret(), timestamp, body))
                .header(DELIVERY_HEADER, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        endpoint.getHeaders().forEach(request::header);

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DestinationException(id(), "receiver unreachable: " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DestinationException(id(), "interrupted", e, true);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        String detail = "receiver answered " + status;
        if (status == 408 || status == 429 || status >= 500) throw new DestinationException(id(), detail, null, true);
        throw DestinationException.permanent(id(), detail);
    }

    /** {@code t=<timestamp>,v1=<hex HMAC-SHA256 of "<timestamp>.<body>">}. */
    public static String signature(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
