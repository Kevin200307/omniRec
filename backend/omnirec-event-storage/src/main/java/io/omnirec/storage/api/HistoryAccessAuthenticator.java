// SPDX-License-Identifier: Apache-2.0
package io.omnirec.storage.api;

import io.omnirec.eventapi.config.EventApiProperties;
import io.omnirec.storage.config.EventStorageProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a secret key to the tenants it may read.
 *
 * Reading history is a different privilege from writing events, so it needs a
 * different credential. The publishable key is in every storefront's
 * JavaScript; if it could read, anyone could download any customer's journey.
 * So the publishable key is refused here outright, and a secret key is
 * refused at startup if it equals any publishable key.
 *
 * <ul>
 *   <li>{@code omnirec.events.tenants.<id>.secret-key} reads that one tenant — a single store.</li>
 *   <li>{@code omnirec.storage.history-api.platform-keys.<name>} reads a named set of
 *       tenants — a platform running many stores.</li>
 * </ul>
 * Both come from server configuration; nothing in a request can widen a grant.
 */
public class HistoryAccessAuthenticator {

    /** What a key may read. {@code principal} names the key for logs, never the key itself. */
    public record Grant(String principal, Set<String> tenants) {
        public Grant {
            tenants = Set.copyOf(tenants);
        }
    }

    private final Map<String, Grant> grantsByKey;
    /** Secret keys of tenants kept outside configuration (tenant-source: jdbc). May be null. */
    private final io.omnirec.eventapi.tenant.TenantRegistry tenantRegistry;

    public HistoryAccessAuthenticator(EventApiProperties events, EventStorageProperties.HistoryApi historyApi) {
        this(events, historyApi, null);
    }

    public HistoryAccessAuthenticator(EventApiProperties events, EventStorageProperties.HistoryApi historyApi,
                                      io.omnirec.eventapi.tenant.TenantRegistry tenantRegistry) {
        this.tenantRegistry = tenantRegistry;
        Set<String> publishableKeys = new HashSet<>();
        Set<String> enabledTenants = new LinkedHashSet<>();
        events.getTenants().forEach((tenantId, tenant) -> {
            if (!isBlank(tenant.getApiKey())) publishableKeys.add(tenant.getApiKey());
            if (tenant.isEnabled()) enabledTenants.add(tenantId);
        });

        Map<String, Grant> grants = new LinkedHashMap<>();

        events.getTenants().forEach((tenantId, tenant) -> {
            if (!tenant.isEnabled() || isBlank(tenant.getSecretKey())) return;
            register(grants, publishableKeys, tenant.getSecretKey(),
                    new Grant("tenant:" + tenantId, Set.of(tenantId)),
                    "omnirec.events.tenants." + tenantId + ".secret-key");
        });

        historyApi.getPlatformKeys().forEach((name, platformKey) -> {
            String property = "omnirec.storage.history-api.platform-keys." + name;
            if (isBlank(platformKey.getKey())) {
                throw new IllegalStateException(property + ".key is empty");
            }
            if (platformKey.getTenants() == null || platformKey.getTenants().isEmpty()) {
                throw new IllegalStateException(property + ".tenants is empty; list the tenants it may read");
            }
            Set<String> readable = new LinkedHashSet<>();
            for (String tenantId : platformKey.getTenants()) {
                if (!events.getTenants().containsKey(tenantId)) {
                    // A typo here would otherwise be a key that silently reads nothing — or, worse,
                    // starts reading a tenant that is later created under that name.
                    throw new IllegalStateException(property + ".tenants names unknown tenant '" + tenantId + "'");
                }
                if (enabledTenants.contains(tenantId)) readable.add(tenantId);
            }
            register(grants, publishableKeys, platformKey.getKey(), new Grant("platform:" + name, readable), property);
        });

        this.grantsByKey = Map.copyOf(grants);
    }

    private static void register(Map<String, Grant> grants, Set<String> publishableKeys, String key, Grant grant,
                                 String property) {
        if (publishableKeys.contains(key)) {
            throw new IllegalStateException(property + " equals a publishable api-key. Publishable keys are "
                    + "public; a key that reads history must be a separate secret.");
        }
        if (grants.containsKey(key)) {
            throw new IllegalStateException(property + " reuses a key already configured for "
                    + grants.get(key).principal() + "; every read key must be distinct");
        }
        grants.put(key, grant);
    }

    /**
     * The grant for a presented key, or empty. Compared in constant time
     * against every configured key, so response timing reveals nothing about
     * how close a guess was.
     */
    public Optional<Grant> authenticate(String presentedKey) {
        if (isBlank(presentedKey)) return Optional.empty();
        byte[] presented = presentedKey.getBytes(StandardCharsets.UTF_8);
        Grant match = null;
        for (Map.Entry<String, Grant> entry : grantsByKey.entrySet()) {
            if (MessageDigest.isEqual(entry.getKey().getBytes(StandardCharsets.UTF_8), presented)) {
                match = entry.getValue();
            }
        }
        if (match == null && tenantRegistry != null) {
            return tenantRegistry.tenantForSecretKey(presentedKey)
                    .map(tenantId -> new Grant("tenant:" + tenantId, Set.of(tenantId)));
        }
        return Optional.ofNullable(match);
    }

    public boolean hasAnyKeyConfigured() {
        return !grantsByKey.isEmpty() || tenantRegistry != null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
