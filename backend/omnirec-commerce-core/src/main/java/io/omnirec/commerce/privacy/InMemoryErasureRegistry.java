// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.privacy;

import io.omnirec.commerce.model.EventIdentity;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tombstones held in memory: lost on restart and not shared. A base for
 * persistent registries, which load their fingerprints into it, and enough for
 * tests.
 */
public class InMemoryErasureRegistry implements ErasureRegistry {

    private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isErased(String tenantId, EventIdentity identity) {
        if (identity == null || fingerprints.isEmpty()) return false;
        return matches(tenantId, identity.userId()) || matches(tenantId, identity.anonymousId());
    }

    @Override
    public void record(String tenantId, String userId, Collection<String> anonymousIds) {
        if (userId != null && !userId.isBlank()) fingerprints.add(ErasureRegistry.fingerprint(tenantId, userId));
        for (String anonymousId : anonymousIds) {
            if (anonymousId != null && !anonymousId.isBlank()) {
                fingerprints.add(ErasureRegistry.fingerprint(tenantId, anonymousId));
            }
        }
    }

    /** Adds fingerprints computed elsewhere, for example loaded from a database. */
    protected void addFingerprints(Collection<String> loaded) {
        fingerprints.addAll(loaded);
    }

    public int size() {
        return fingerprints.size();
    }

    private boolean matches(String tenantId, String id) {
        return id != null && !id.isBlank() && fingerprints.contains(ErasureRegistry.fingerprint(tenantId, id));
    }
}
