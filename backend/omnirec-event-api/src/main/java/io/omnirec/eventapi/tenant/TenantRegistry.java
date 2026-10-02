// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.tenant;

import java.util.Collection;
import java.util.Optional;

/**
 * Where tenants and their keys come from. Implement it to keep tenants in your
 * own system; register the implementation as a bean and the collector uses it.
 *
 * Publishable keys may only write events. Secret keys may also read and delete
 * a tenant's history. A key resolves to at most one tenant.
 */
public interface TenantRegistry {

    /** The tenant with this id, enabled or not. */
    Optional<Tenant> find(String tenantId);

    /** The enabled tenant a publishable key writes to. */
    Optional<String> tenantForPublishableKey(String key);

    /** The enabled tenant a secret key belongs to. */
    Optional<String> tenantForSecretKey(String key);

    /** Every known tenant. */
    Collection<Tenant> tenants();

    /** Whether any enabled tenant has a publishable key; decides {@code auth-mode: auto}. */
    boolean hasAnyPublishableKey();
}
