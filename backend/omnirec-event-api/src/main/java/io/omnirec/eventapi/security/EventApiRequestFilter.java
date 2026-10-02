// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.security;

import io.omnirec.eventapi.tenant.Tenant;
import io.omnirec.eventapi.tenant.TenantRegistry;
import java.net.URI;
import java.util.List;
import io.omnirec.eventapi.config.EventApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The cheap checks, run <em>before</em> the body is read:
 *
 * <ol>
 *   <li><b>Payload size.</b> A declared Content-Length over the limit is refused
 *       with 413 immediately; a chunked body is counted as it streams and cut
 *       off at the limit.</li>
 *   <li><b>API key → tenant.</b> An unknown key gets 401 before a single byte
 *       of JSON is parsed. In open mode a missing key means the default
 *       tenant; a wrong key is still a 401, never silently rerouted.</li>
 *   <li><b>Origin.</b> In open mode a browser request must come from an allowed
 *       origin or from the collector's own origin, otherwise 403. In keys mode
 *       the same applies to tenants that list allowed origins.</li>
 *   <li><b>Rate limit,</b> per tenant per client address.</li>
 * </ol>
 *
 * The resolved tenant is handed to the controller as a request attribute.
 * Preflight OPTIONS requests pass straight through, so CORS keeps working.
 */
public class EventApiRequestFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = "omnirec.tenantId";
    static final String API_KEY_HEADER = "X-Omnirec-Key";
    static final String API_KEY_QUERY_PARAM = "api_key";

    private final TenantRegistry tenants;
    private final RateLimiter rateLimiter;
    private final EventApiProperties properties;

    public EventApiRequestFilter(TenantRegistry tenants, RateLimiter rateLimiter, EventApiProperties properties) {
        this.tenants = tenants;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** The mode in force right now; {@code auto} depends on whether any tenant has a key. */
    @SuppressWarnings("deprecation")
    public static EventApiProperties.AuthMode effectiveMode(EventApiProperties properties, TenantRegistry tenants) {
        if (properties.isAllowAnonymousIngestion()) return EventApiProperties.AuthMode.OPEN;
        return switch (properties.getAuthMode()) {
            case OPEN -> EventApiProperties.AuthMode.OPEN;
            case KEYS -> EventApiProperties.AuthMode.KEYS;
            case AUTO -> tenants.hasAnyPublishableKey() ? EventApiProperties.AuthMode.KEYS : EventApiProperties.AuthMode.OPEN;
        };
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean eventEndpoint = path.equals("/v1/events") || path.equals("/v1/events/batch")
                || path.equals("/v1/identify") || path.equals("/v1/catalog");
        return !eventEndpoint || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        int maxBytes = properties.getMaxPayloadBytes();
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            reject(request, response, 413, "payload exceeds " + maxBytes + " bytes");
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            // sendBeacon can't set headers, so the key may arrive as a query
            // parameter. Acceptable only because the key is publishable.
            apiKey = request.getParameter(API_KEY_QUERY_PARAM);
        }
        EventApiProperties.AuthMode mode = effectiveMode(properties, tenants);
        String tenantId;
        if (apiKey != null && !apiKey.isBlank()) {
            tenantId = tenants.tenantForPublishableKey(apiKey).orElse(null);
            if (tenantId == null) {
                reject(request, response, 401, "invalid or missing API key");
                return;
            }
        } else if (mode == EventApiProperties.AuthMode.OPEN) {
            tenantId = properties.getDefaultTenantId();
        } else {
            reject(request, response, 401, "invalid or missing API key");
            return;
        }

        if (!originAllowed(request, tenantId, mode)) {
            reject(request, response, 403, "origin not allowed");
            return;
        }

        if (!rateLimiter.tryAcquire(tenantId, request.getRemoteAddr())) {
            response.setHeader("Retry-After", String.valueOf(properties.getRateLimit().getWindow().toSeconds()));
            reject(request, response, 429, "rate limit exceeded");
            return;
        }

        request.setAttribute(TENANT_ATTRIBUTE, tenantId);
        // A chunked body has no Content-Length to check up front, so it is
        // counted as it is read instead.
        chain.doFilter(declared < 0 ? new SizeLimitedRequest(request, maxBytes) : request, response);
    }

    /**
     * Requests without an Origin header (servers, curl) are not browser
     * cross-site requests and pass. Browser requests pass when the origin is
     * listed globally or for the tenant, or is the collector's own origin.
     * Open mode always checks; keys mode checks only tenants that list origins,
     * so existing keyed deployments keep working unchanged.
     */
    private boolean originAllowed(HttpServletRequest request, String tenantId, EventApiProperties.AuthMode mode) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return true;
        List<String> tenantOrigins = tenants.find(tenantId).map(Tenant::allowedOrigins).orElse(List.of());
        if (mode == EventApiProperties.AuthMode.KEYS && tenantOrigins.isEmpty()) return true;
        if (tenantOrigins.contains(origin) || properties.getCors().getAllowedOrigins().contains(origin)) return true;
        return isSameOrigin(request, origin);
    }

    static boolean isSameOrigin(HttpServletRequest request, String origin) {
        String host = request.getHeader("Host");
        if (host == null) return false;
        try {
            URI uri = URI.create(origin);
            String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host.equalsIgnoreCase(authority);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Rejections carry CORS headers for allowed storefront origins. Spring MVC
     * adds CORS headers in the dispatcher, after this filter, so without this
     * a 401 or 413 would reach the browser as an opaque network error — which
     * the SDK has to treat as retryable, and it would retry a bad key forever.
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && corsOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
    }

    private java.util.Set<String> corsOrigins() {
        java.util.Set<String> all = new java.util.HashSet<>(properties.getCors().getAllowedOrigins());
        tenants.tenants().forEach(t -> all.addAll(t.allowedOrigins()));
        return all;
    }

    /** Fails the read once more than {@code limit} bytes have been consumed. */
    static final class SizeLimitedRequest extends HttpServletRequestWrapper {
        private final int limit;

        SizeLimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long consumed;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) count(1);
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = delegate.read(buffer, offset, length);
                    if (n > 0) count(n);
                    return n;
                }

                private void count(int n) throws IOException {
                    consumed += n;
                    if (consumed > limit) {
                        throw new PayloadTooLargeException(limit);
                    }
                }

                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
            };
        }
    }

    /** Raised mid-read for an oversized chunked body; mapped to 413. */
    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(int limit) {
            super("payload exceeds " + limit + " bytes");
        }
    }
}
