// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.jdbc;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns the configured connection string into what the JDBC driver needs.
 *
 * Accepts either form, because hosted PostgreSQL providers hand out the libpq
 * form and nobody should have to rewrite it by hand:
 * <pre>
 *   jdbc:postgresql://localhost:5432/omnirec                         (as-is)
 *   postgresql://user:secret@ep-x.eu-central-1.aws.neon.tech/omnirec?sslmode=require
 *   postgres://user:secret@db.example.com:5432/omnirec
 * </pre>
 * The driver does not accept {@code user:password@} in the URL, so credentials
 * embedded in a libpq URL are lifted out. Explicitly configured
 * {@code username}/{@code password} win over embedded ones.
 *
 * Nothing about Neon is special-cased: it is PostgreSQL, reached with a URL.
 */
public final class PostgresConnectionUrl {

    /** @param jdbcUrl never contains credentials, so it is safe to log the host part of it */
    public record Resolved(String jdbcUrl, String username, String password, String host) {
        @Override
        public String toString() {
            // Never print the password, even by accident in a debugger or log line.
            return "Resolved[host=" + host + ", username=" + username + "]";
        }
    }

    private PostgresConnectionUrl() {
    }

    public static Resolved resolve(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "omnirec.storage.postgres.url is required when omnirec.storage.enabled=true");
        }
        String trimmed = url.trim();

        if (trimmed.startsWith("jdbc:postgresql:")) {
            return new Resolved(trimmed, blankToNull(username), blankToNull(password), hostOf(trimmed.substring(5)));
        }
        if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
            return fromLibpqUrl(trimmed, username, password);
        }
        // Deliberately does not echo the value: it may contain a password.
        throw new IllegalStateException("omnirec.storage.postgres.url must start with jdbc:postgresql://, "
                + "postgresql:// or postgres://");
    }

    private static Resolved fromLibpqUrl(String url, String username, String password) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("omnirec.storage.postgres.url is not a valid URL");
        }
        if (uri.getHost() == null) {
            throw new IllegalStateException("omnirec.storage.postgres.url has no host");
        }

        String embeddedUser = null;
        String embeddedPassword = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            embeddedUser = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            embeddedPassword = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            jdbc.append('?').append(uri.getRawQuery());
        }

        return new Resolved(jdbc.toString(),
                firstNonBlank(username, embeddedUser),
                firstNonBlank(password, embeddedPassword),
                uri.getHost());
    }

    private static String hostOf(String urlWithoutJdbcPrefix) {
        try {
            String host = new URI(urlWithoutJdbcPrefix).getHost();
            return host == null ? "unknown" : host;
        } catch (URISyntaxException e) {
            return "unknown";
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : blankToNull(fallback);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
