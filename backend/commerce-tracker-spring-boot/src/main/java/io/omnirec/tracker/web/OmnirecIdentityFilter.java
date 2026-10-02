// SPDX-License-Identifier: Apache-2.0
package io.omnirec.tracker.web;

import io.omnirec.tracker.ServerEventEmitter.ServerIdentity;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Reads the visitor's {@code omnirec_anonymous_id} and {@code omnirec_session_id}
 * cookies, set by the browser SDK on the same site, into
 * {@link OmnirecRequestIdentity} for the duration of the request.
 *
 * Values that do not look like ids the SDK writes are ignored, so a tampered
 * cookie cannot inject arbitrary strings into events.
 */
public class OmnirecIdentityFilter implements Filter {

    public static final String ANONYMOUS_COOKIE = "omnirec_anonymous_id";
    public static final String SESSION_COOKIE = "omnirec_session_id";

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_:.\\-]{1,128}$");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)) {
            chain.doFilter(request, response);
            return;
        }
        String anonymousId = cookie(http, ANONYMOUS_COOKIE);
        String sessionId = cookie(http, SESSION_COOKIE);
        if (anonymousId == null && sessionId == null) {
            chain.doFilter(request, response);
            return;
        }
        OmnirecRequestIdentity.set(new ServerIdentity(anonymousId, null, sessionId));
        try {
            chain.doFilter(request, response);
        } finally {
            OmnirecRequestIdentity.clear();
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value != null && ID.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }
}
