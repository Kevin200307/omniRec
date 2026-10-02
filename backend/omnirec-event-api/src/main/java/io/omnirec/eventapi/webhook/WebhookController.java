// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnirec.eventapi.dto.IngestResponse;
import io.omnirec.eventapi.ingest.EventIngestionService;
import io.omnirec.eventapi.normalize.EventNormalizer;
import io.omnirec.eventapi.tenant.Tenant;
import io.omnirec.eventapi.tenant.TenantRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POST /v1/webhooks/{source}}: events from other systems.
 *
 * <ol>
 *   <li>Unknown source: 404.</li>
 *   <li>The tenant is {@code ?tenant=<id>}, or the default tenant.</li>
 *   <li>The body is read raw, capped at {@code omnirec.events.max-payload-bytes} (413).</li>
 *   <li>The adapter checks the signature against that tenant's secret: 401 on failure.
 *       Webhooks need no API key; the signature is the credential.</li>
 *   <li>The adapter's events enter the normal pipeline as {@code source: webhook}.</li>
 * </ol>
 *
 * The answer is 202 even when some events failed validation: the sender would
 * only resend the same body, so the rejection is logged for the operator
 * instead. A broker outage is a 503, which senders retry.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final Map<String, WebhookAdapter> adapters;
    private final EventIngestionService ingestion;
    private final TenantRegistry tenants;
    private final String defaultTenantId;
    private final int maxPayloadBytes;
    private final Clock clock;

    public WebhookController(List<WebhookAdapter> adapters, EventIngestionService ingestion, TenantRegistry tenants,
                             String defaultTenantId, int maxPayloadBytes, Clock clock) {
        Map<String, WebhookAdapter> bySource = new LinkedHashMap<>();
        for (WebhookAdapter adapter : adapters) {
            WebhookAdapter clash = bySource.put(adapter.source(), adapter);
            if (clash != null) {
                throw new IllegalStateException("Two webhook adapters serve source '" + adapter.source() + "': "
                        + clash.getClass().getName() + " and " + adapter.getClass().getName());
            }
        }
        this.adapters = Collections.unmodifiableMap(bySource);
        this.ingestion = ingestion;
        this.tenants = tenants;
        this.defaultTenantId = defaultTenantId;
        this.maxPayloadBytes = maxPayloadBytes;
        this.clock = clock;
    }

    /** The sources served, for logs and tests. */
    public java.util.Set<String> sources() {
        return adapters.keySet();
    }

    @PostMapping(value = "/v1/webhooks/{source}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> receive(@PathVariable String source, HttpServletRequest request) throws IOException {
        WebhookAdapter adapter = adapters.get(source);
        if (adapter == null) return error(HttpStatus.NOT_FOUND, "unknown webhook source");

        // Read from the query string, never request.getParameter: for a form
        // body that would consume the bytes the signature is computed over.
        String tenantId = queryParameter(request.getQueryString(), "tenant");
        if (tenantId == null || tenantId.isBlank()) tenantId = defaultTenantId;

        if (request.getContentLengthLong() > maxPayloadBytes) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload exceeds " + maxPayloadBytes + " bytes");
        }
        byte[] body = readAtMost(request.getInputStream(), maxPayloadBytes);
        if (body == null) return error(HttpStatus.PAYLOAD_TOO_LARGE, "payload exceeds " + maxPayloadBytes + " bytes");

        WebhookRequest webhook = new WebhookRequest(source, tenantId, headers(request), body, clock.instant());
        if (!adapter.verify(webhook)) {
            log.warn("Rejected {} webhook for tenant {}: signature missing or invalid", source, tenantId);
            return error(HttpStatus.UNAUTHORIZED, "invalid webhook signature");
        }
        if (!tenants.find(tenantId).map(Tenant::enabled).orElse(true)) {
            return error(HttpStatus.FORBIDDEN, "tenant disabled");
        }

        List<ObjectNode> events;
        try {
            events = adapter.translate(webhook);
        } catch (WebhookPayloadException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (events.isEmpty()) return ResponseEntity.accepted().body(IngestResponse.of(0, 0, 0, List.of()));

        List<JsonNode> batch = new ArrayList<>(events.size());
        for (ObjectNode event : events) {
            event.put("source", "webhook");
            batch.add(event);
        }
        // No client address or country: the caller is the sender's server, not the customer.
        IngestResponse response = ingestion.ingestJson(batch, tenantId,
                new EventNormalizer.RequestMetadata(null, null, request.getHeader("User-Agent"), webhook.receivedAt()));

        if (!response.errors().isEmpty()) {
            log.warn("{} webhook for tenant {} produced {} invalid event(s); fix the mapping: {}",
                    source, tenantId, response.errors().size(), response.errors());
        }
        if (response.needsRetry()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                    .body(response);
        }
        return ResponseEntity.accepted().body(response);
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }

    /** The body, or null when it is longer than {@code limit}. */
    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > limit) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    static String queryParameter(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status.value(), "error", message));
    }
}
