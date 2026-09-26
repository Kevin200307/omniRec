// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Who an event belongs to.
 *
 * {@code anonymousId} is always present — it is the device/browser identity and
 * it never changes, not even at login or logout. {@code userId} is present only
 * once the merchant has identified the visitor.
 *
 * Crucially, these are never merged in place. An event captured anonymously
 * keeps its null {@code userId} forever; the association lives separately as an
 * {@link io.omnirec.commerce.identity.IdentityLink}, which lets us attribute
 * history to a user without rewriting it. See docs/identity.md.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventIdentity(String anonymousId, String userId, String sessionId) {

    public static EventIdentity anonymous(String anonymousId, String sessionId) {
        return new EventIdentity(anonymousId, null, sessionId);
    }

    public static EventIdentity authenticated(String anonymousId, String userId, String sessionId) {
        return new EventIdentity(anonymousId, userId, sessionId);
    }

    @JsonIgnore
    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }

    /**
     * Attaches a userId discovered through an identity link. Used only when the
     * event itself carried none — an event that already names a user is
     * authoritative and must not be overwritten by an older link.
     */
    public EventIdentity resolvedTo(String resolvedUserId) {
        if (isAuthenticated() || resolvedUserId == null) return this;
        return new EventIdentity(anonymousId, resolvedUserId, sessionId);
    }
}
