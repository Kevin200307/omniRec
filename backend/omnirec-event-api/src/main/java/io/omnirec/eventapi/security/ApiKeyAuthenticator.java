package io.omnirec.eventapi.security;

import io.omnirec.eventapi.config.EventApiProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a publishable API key to the tenant it belongs to.
 *
 * The key is a <em>publishable</em> credential: it ships in browser JavaScript
 * and is visible to anyone who views source. That is by design, and it is why it
 * can do exactly one thing — write events for its own tenant. It can't read
 * anything, and it never unlocks provider credentials, which live server-side
 * and are never sent anywhere near the browser.
 *
 * Because it is public, this check is about <em>routing and isolation</em>
 * (which tenant do these events belong to, and can this key write to it), not
 * about secrecy. Abuse is handled by rate limiting and payload caps, not by
 * hiding the key.
 */
public class ApiKeyAuthenticator {

    private final Map<String, String> tenantByKey;

    public ApiKeyAuthenticator(EventApiProperties properties) {
        Map<String, String> index = new LinkedHashMap<>();
        properties.getTenants().forEach((tenantId, tenant) -> {
            // A disabled tenant is simply absent from the index, so its key is
            // rejected like any unknown key. (Previously the flag was ignored.)
            if (tenant.isEnabled() && tenant.getApiKey() != null && !tenant.getApiKey().isBlank()) {
                index.put(tenant.getApiKey(), tenantId);
            }
        });
        this.tenantByKey = Map.copyOf(index);
    }

    /**
     * Returns the tenant this key writes to, or empty if the key is unknown.
     *
     * Comparison is constant-time across the whole key set. A publishable key
     * isn't really a secret, so this is belt-and-braces — but the same class
     * shape gets reused for privileged keys, and a timing oracle that only
     * appears once someone adds a secret key is a nasty way to find out.
     */
    public Optional<String> resolveTenant(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String match = null;
        for (Map.Entry<String, String> entry : tenantByKey.entrySet()) {
            if (constantTimeEquals(entry.getKey(), apiKey)) {
                match = entry.getValue();
            }
        }
        return Optional.ofNullable(match);
    }

    public boolean hasAnyKeyConfigured() {
        return !tenantByKey.isEmpty();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
