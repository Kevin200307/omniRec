package io.omnirec.eventapi.normalize;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips secrets and personal data from {@code context.url} and
 * {@code context.referrer} before an event enters the pipeline.
 *
 * The browser SDK does the same ({@code sanitizeUrl} in @omnirec/commerce-web),
 * but a client-side scrub is a courtesy: anyone can POST to this API directly.
 * Query strings routinely carry password-reset tokens, OAuth codes, emails and
 * session ids; none of that may reach a provider.
 *
 * Parameters are dropped by name (a denylist over names is predictable), plus
 * any parameter whose value looks like an email address, URL credentials
 * ({@code user:pass@}), and the fragment.
 */
public final class UrlSanitizer {

    /** Kept identical to SENSITIVE_PARAMS in packages/commerce-web/src/context/sanitizeUrl.ts. */
    public static final Set<String> SENSITIVE_PARAMS = Set.of(
            "token", "access_token", "id_token", "refresh_token", "auth", "authorization", "code", "state",
            "password", "pass", "pwd", "secret", "key", "api_key", "apikey", "signature", "sig",
            "session", "sessionid", "sid", "jwt", "otp", "email", "e-mail", "mail", "phone",
            "reset", "reset_token");

    private static final Pattern EMAIL = Pattern.compile("[^\\s@/?#&=]+@[^\\s@/?#&=]+\\.[^\\s@/?#&=]+");

    private UrlSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            // Unparseable: keep only what precedes the query, where secrets live.
            return raw.split("[?#]", 2)[0];
        }
        if (uri.getScheme() == null || uri.getRawAuthority() == null) {
            return raw.split("[?#]", 2)[0];
        }

        String query = filterQuery(uri.getRawQuery());
        // Rebuilt from parts: drops user-info (credentials) and the fragment.
        StringBuilder out = new StringBuilder()
                .append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) out.append(':').append(uri.getPort());
        out.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (query != null && !query.isEmpty()) out.append('?').append(query);
        return out.toString();
    }

    private static String filterQuery(String rawQuery) {
        if (rawQuery == null) return null;
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (SENSITIVE_PARAMS.contains(name) || name.endsWith("token") || EMAIL.matcher(value).find()) {
                continue;
            }
            kept.add(pair);
        }
        return String.join("&", kept);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
