// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decides, before the controller runs, which single tenant a history request
 * reads.
 *
 * <ol>
 *   <li>The secret key must arrive as {@code Authorization: Bearer <key>}.
 *       Never a query parameter: URLs end up in access logs and proxies.</li>
 *   <li>The key resolves to a {@link HistoryAccessAuthenticator.Grant} — the
 *       tenants it may read. Unknown key: 401.</li>
 *   <li>{@code X-Omnirec-Tenant}, if sent, picks one of those tenants. Naming
 *       any other tenant is 403, whatever the key. A key for exactly one tenant
 *       may omit the header.</li>
 * </ol>
 *
 * The tenant is handed to the controller as a request attribute. Nothing in
 * the path, query, or body is ever used as the tenant.
 */
public class CustomerHistoryAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = "omnirec.history.tenantId";
    public static final String PRINCIPAL_ATTRIBUTE = "omnirec.history.principal";
    static final String TENANT_HEADER = "X-Omnirec-Tenant";
    private static final String BEARER = "Bearer ";

    private final HistoryAccessAuthenticator authenticator;

    public CustomerHistoryAuthFilter(HistoryAccessAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /**
     * Matched on the decoded path — the one Spring MVC routes on — so an
     * encoded spelling such as {@code /v1/%63ustomers/...} cannot reach the
     * controller without passing through here. (The controller also fails
     * closed if it ever does.)
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.getPathWithinApplication(request).startsWith("/v1/customers/");
    }

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<HistoryAccessAuthenticator.Grant> grant = bearerToken(request).flatMap(authenticator::authenticate);
        if (grant.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(response, 401, "invalid or missing secret key");
            return;
        }

        String requested = request.getHeader(TENANT_HEADER);
        String tenantId;
        if (requested != null && !requested.isBlank()) {
            if (!grant.get().tenants().contains(requested.trim())) {
                reject(response, 403, "this key may not read that tenant");
                return;
            }
            tenantId = requested.trim();
        } else if (grant.get().tenants().size() == 1) {
            tenantId = grant.get().tenants().iterator().next();
        } else if (grant.get().tenants().isEmpty()) {
            reject(response, 403, "this key may not read any enabled tenant");
            return;
        } else {
            reject(response, 400, TENANT_HEADER + " is required for a key that can read several tenants");
            return;
        }

        request.setAttribute(TENANT_ATTRIBUTE, tenantId);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, grant.get().principal());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /** Same error shape as the rest of the API: {"status": 401, "error": "..."}. */
    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
    }
}
