package io.omnirec.commerce.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * A recorded association between an anonymous visitor and an authenticated user.
 *
 * This is deliberately a separate record rather than an update to past events.
 * Rewriting history would mean re-reading and re-writing every event a visitor
 * ever produced the moment they log in — unbounded work triggered by a login,
 * and destructive: you could no longer tell what was genuinely known at capture
 * time. Keeping the link separate makes attribution a join instead.
 *
 * The relationship is many-to-one: one user accumulates a link per device or
 * browser they use. {@code anon_A -> customer_123} and
 * {@code anon_B -> customer_123} coexist, and both resolve to the same user.
 */
public record IdentityLink(String tenantId, String anonymousId, String userId, Instant linkedAt) {

    public IdentityLink {
        Objects.requireNonNull(anonymousId, "anonymousId");
        Objects.requireNonNull(userId, "userId");
        linkedAt = linkedAt == null ? Instant.now() : linkedAt;
    }

    public static IdentityLink of(String tenantId, String anonymousId, String userId) {
        return new IdentityLink(tenantId, anonymousId, userId, Instant.now());
    }
}
