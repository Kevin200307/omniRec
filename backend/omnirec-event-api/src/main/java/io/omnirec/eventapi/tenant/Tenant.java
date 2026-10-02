// SPDX-License-Identifier: Apache-2.0
package io.omnirec.eventapi.tenant;

import io.omnirec.commerce.validation.ValidationMode;

import java.util.List;

/**
 * One store sending events. A single-store install has exactly one, usually the
 * default tenant; a multi-store install has one per store. Keys are not part of
 * this record: the registry resolves keys to tenants without exposing them.
 *
 * @param id             tenant id, stamped on every event
 * @param enabled        a disabled tenant's keys are rejected
 * @param allowedOrigins browser origins allowed to send events for this tenant
 * @param validationMode how unknown event names are treated
 * @param planPaths      tracking-plan locations (Spring resource syntax)
 */
public record Tenant(String id, boolean enabled, List<String> allowedOrigins, ValidationMode validationMode,
                     List<String> planPaths) {

    public Tenant {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        planPaths = planPaths == null ? List.of() : List.copyOf(planPaths);
        validationMode = validationMode == null ? ValidationMode.PERMISSIVE : validationMode;
    }
}
