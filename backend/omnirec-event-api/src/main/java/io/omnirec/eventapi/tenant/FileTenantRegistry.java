// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.tenant;

import io.omnirec.eventapi.config.EventApiProperties;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tenants from {@code omnirec.events.tenants.*} in application configuration.
 * The default for self-hosted installs.
 *
 * The default tenant always exists: if it is not configured, it is synthesised
 * with the default validation mode and default plans, so open mode works with
 * no tenant configuration at all.
 */
public class FileTenantRegistry implements TenantRegistry {

    private final Map<String, Tenant> tenants;
    private final Map<String, String> tenantByPublishableKey;
    private final Map<String, String> tenantBySecretKey;

    public FileTenantRegistry(EventApiProperties properties) {
        Map<String, Tenant> all = new LinkedHashMap<>();
        Map<String, String> publishable = new LinkedHashMap<>();
        Map<String, String> secret = new LinkedHashMap<>();

        properties.getTenants().forEach((id, config) -> {
            all.put(id, new Tenant(id, config.isEnabled(), config.getAllowedOrigins(),
                    config.getValidationMode() != null ? config.getValidationMode() : properties.getDefaultValidationMode(),
                    config.getPlanPaths()));
            if (!config.isEnabled()) return;
            if (!isBlank(config.getApiKey())) claim(publishable, config.getApiKey(), id, "api-key");
            if (!isBlank(config.getSecretKey())) claim(secret, config.getSecretKey(), id, "secret-key");
        });
        for (String key : secret.keySet()) {
            if (publishable.containsKey(key)) {
                throw new IllegalStateException("omnirec.events.tenants." + secret.get(key)
                        + ".secret-key equals a publishable api-key. Publishable keys are public; "
                        + "a key that reads history must be a separate secret.");
            }
        }
        all.computeIfAbsent(properties.getDefaultTenantId(), id -> new Tenant(id, true, java.util.List.of(),
                properties.getDefaultValidationMode(), properties.getDefaultPlanPaths()));

        this.tenants = Map.copyOf(all);
        this.tenantByPublishableKey = Map.copyOf(publishable);
        this.tenantBySecretKey = Map.copyOf(secret);
    }

    private static void claim(Map<String, String> keys, String key, String tenantId, String property) {
        String existing = keys.putIfAbsent(key, tenantId);
        if (existing != null) {
            throw new IllegalStateException("omnirec.events.tenants." + tenantId + "." + property
                    + " is also configured for tenant '" + existing + "'; every key must be distinct");
        }
    }

    @Override
    public Optional<Tenant> find(String tenantId) {
        return Optional.ofNullable(tenantId).map(tenants::get);
    }

    @Override
    public Optional<String> tenantForPublishableKey(String key) {
        return lookup(tenantByPublishableKey, key);
    }

    @Override
    public Optional<String> tenantForSecretKey(String key) {
        return lookup(tenantBySecretKey, key);
    }

    /**
     * Compared in constant time against every key, so response timing reveals
     * nothing about how close a guess was.
     */
    private static Optional<String> lookup(Map<String, String> keys, String presented) {
        if (isBlank(presented)) return Optional.empty();
        String match = null;
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (KeyHash.constantTimeEquals(entry.getKey(), presented)) match = entry.getValue();
        }
        return Optional.ofNullable(match);
    }

    @Override
    public Collection<Tenant> tenants() {
        return tenants.values();
    }

    @Override
    public boolean hasAnyPublishableKey() {
        return !tenantByPublishableKey.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
