// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.privacy;

import io.omnirec.commerce.model.EventIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/**
 * Tombstones for erased customers. Once a customer is deleted, events that
 * still arrive for their user id or for any of their devices' anonymous ids
 * (a tab left open, a queue draining, a retry) are dropped instead of
 * recreating the history that was just deleted.
 *
 * Only a fingerprint of each id is kept, never the id itself: SHA-256 of the
 * tenant and the id. That is enough to recognise a returning id and says
 * nothing about whom it was.
 */
public interface ErasureRegistry {

    /** Whether the event's user id or anonymous id belongs to an erased customer. */
    boolean isErased(String tenantId, EventIdentity identity);

    /** Records tombstones for a user id and the anonymous ids of their devices. */
    void record(String tenantId, String userId, Collection<String> anonymousIds);

    /** No erasures, ever: for deployments without deletion. */
    static ErasureRegistry none() {
        return new ErasureRegistry() {
            @Override public boolean isErased(String tenantId, EventIdentity identity) { return false; }
            @Override public void record(String tenantId, String userId, Collection<String> anonymousIds) {
                throw new UnsupportedOperationException("customer erasure needs omnirec-event-storage");
            }
        };
    }

    /** {@code sha256(tenantId + NUL + id)} as hex. Ids of different tenants never collide. */
    static String fingerprint(String tenantId, String id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((tenantId == null ? "" : tenantId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
