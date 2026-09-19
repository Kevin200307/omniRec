package io.omnirec.eventapi.security;

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
 *       of JSON is parsed. Doing this in the controller, as before, meant any
 *       anonymous caller could make the server parse a full-size body first.</li>
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

    private final ApiKeyAuthenticator authenticator;
    private final RateLimiter rateLimiter;
    private final EventApiProperties properties;

    public EventApiRequestFilter(ApiKeyAuthenticator authenticator, RateLimiter rateLimiter, EventApiProperties properties) {
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean eventEndpoint = path.equals("/v1/events") || path.equals("/v1/events/batch") || path.equals("/v1/identify");
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
        String tenantId = authenticator.resolveTenant(apiKey).orElse(null);
        if (tenantId == null) {
            if (!properties.isAllowAnonymousIngestion()) {
                reject(request, response, 401, "invalid or missing API key");
                return;
            }
            tenantId = properties.getDefaultTenantId();
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
     * Rejections carry CORS headers for allowed storefront origins. Spring MVC
     * adds CORS headers in the dispatcher, after this filter, so without this
     * a 401 or 413 would reach the browser as an opaque network error — which
     * the SDK has to treat as retryable, and it would retry a bad key forever.
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && properties.getCors().getAllowedOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
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
