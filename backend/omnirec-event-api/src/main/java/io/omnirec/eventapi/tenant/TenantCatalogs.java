// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.tenant;

import io.omnirec.commerce.catalog.EventRegistry;
import io.omnirec.commerce.validation.EventValidator;
import io.omnirec.commerce.validation.ValidationMode;
import io.omnirec.eventapi.plan.PlanLoader;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each tenant's view of the event catalog: the standard catalog plus that
 * tenant's tracking plans, and a validator in that tenant's mode.
 *
 * Built lazily and cached per (plans, mode), so tenants sharing a plan share a
 * registry, and a JDBC tenant whose plan paths change gets a new one on its
 * next request. Plans are read from disk once per distinct set of paths.
 */
public class TenantCatalogs {

    /** What ingestion needs for one tenant. */
    public record TenantCatalog(EventRegistry registry, EventValidator validator) {
    }

    private record Key(List<String> planPaths, ValidationMode mode) {
    }

    private final TenantRegistry tenants;
    private final PlanLoader plans;
    private final EventRegistry standard;
    private final ValidationMode defaultMode;
    private final List<String> defaultPlanPaths;
    private final Map<Key, TenantCatalog> cache = new ConcurrentHashMap<>();

    public TenantCatalogs(TenantRegistry tenants, PlanLoader plans, EventRegistry standard,
                          ValidationMode defaultMode, List<String> defaultPlanPaths) {
        this.tenants = tenants;
        this.plans = plans;
        this.standard = standard;
        this.defaultMode = defaultMode;
        this.defaultPlanPaths = List.copyOf(defaultPlanPaths);
    }

    public TenantCatalog forTenant(String tenantId) {
        Key key = tenants.find(tenantId)
                .map(t -> new Key(t.planPaths(), t.validationMode()))
                .orElse(new Key(defaultPlanPaths, defaultMode));
        return cache.computeIfAbsent(key, k -> {
            EventRegistry registry = k.planPaths().isEmpty() ? standard : plans.extend(standard, k.planPaths());
            return new TenantCatalog(registry, new EventValidator(registry, k.mode()));
        });
    }

    /**
     * Loads every configured tenant's plans now, so a broken plan fails startup
     * instead of the first request for that tenant.
     *
     * @throws io.omnirec.eventapi.plan.PlanException on the first invalid plan
     */
    public void validateAll() {
        for (Tenant tenant : tenants.tenants()) {
            forTenant(tenant.id());
        }
    }
}
