// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * One inbound webhook call, as received. The body is kept as raw bytes because
 * signatures are computed over the exact bytes sent; parsing and re-serialising
 * first would break every scheme.
 *
 * @param source     the {@code {source}} path segment
 * @param tenantId   the tenant the call writes to
 * @param headers    request headers, names lowercased
 * @param body       the raw body
 * @param receivedAt when the collector received the call
 */
public record WebhookRequest(String source, String tenantId, Map<String, String> headers, byte[] body,
                             Instant receivedAt) {

    public WebhookRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /** A header value, matched case-insensitively, or null. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
