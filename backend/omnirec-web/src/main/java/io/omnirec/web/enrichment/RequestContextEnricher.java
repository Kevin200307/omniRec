// SPDX-License-Identifier: Apache-2.0
package io.omnirec.web.enrichment;

import io.omnirec.core.model.EventContext;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;

/**
 * Derives contextual signals from the request itself rather than trusting
 * the client payload — a spoofed "geo" or "deviceType" field in the request
 * body is simply ignored. Country resolution here reads the header most
 * CDNs (CloudFront, Cloudflare) already set at the edge; swap in a MaxMind
 * GeoIP2 lookup keyed off resolveIp() if you're not behind one of those.
 */
public class RequestContextEnricher {

    public EventContext enrich(HttpServletRequest request, String season) {
        String ip = resolveIp(request);
        String deviceType = resolveDeviceType(request.getHeader("User-Agent"));
        String country = firstNonNull(
                request.getHeader("CloudFront-Viewer-Country"),
                request.getHeader("CF-IPCountry")
        );
        return new EventContext(ip, deviceType, country, null, Instant.now(), season);
    }

    private String resolveIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveDeviceType(String userAgent) {
        if (userAgent == null) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("tablet") || ua.contains("ipad")) return "tablet";
        if (ua.contains("mobi") || ua.contains("android") || ua.contains("iphone")) return "mobile";
        return "desktop";
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
