// SPDX-License-Identifier: Apache-2.0
package io.omnirec.commerce.identity;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for anonymous -> user associations.
 *
 * Implementations must be idempotent: the SDK sends an {@code identify} event
 * on login, and RabbitMQ can redeliver it, so linking the same pair twice has
 * to be harmless.
 *
 * Ships with an in-memory implementation for tests and single-node development,
 * and a Redis-backed one for deployment. Deliberately an interface so a merchant
 * who wants links in their own database can supply one — this is the only piece
 * of the pipeline that holds identity data long-term.
 */
public interface IdentityLinkStore {

    /** Records the association. Calling it repeatedly with the same pair must be a no-op. */
    void link(IdentityLink link);

    /**
     * The user an anonymous visitor belongs to, if any. When an anonymousId has
     * somehow been linked to more than one user — shared device, or a genuine
     * account switch — the most recent link wins, because that reflects who is
     * actually using the device now.
     */
    Optional<String> resolveUserId(String tenantId, String anonymousId);

    /** Every anonymous id known for a user; how history across devices is gathered. */
    List<String> anonymousIdsFor(String tenantId, String userId);
}
