// SPDX-License-Identifier: Apache-2.0
package io.omnirec.redis.state;

import io.omnirec.commerce.identity.IdentityLink;
import io.omnirec.commerce.identity.IdentityLinkStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared {@link IdentityLinkStore} for deployments with more than one instance.
 *
 * Two structures, because the two questions have opposite shapes:
 *
 * <pre>
 *   identity:anon:{tenant}:{anonymousId}  -> STRING  the current userId
 *   identity:user:{tenant}:{userId}       -> SET     every anonymousId for that user
 * </pre>
 *
 * The forward lookup is a plain string, overwritten on each link, which gives
 * "most recent link wins" for free — the right answer for a shared device or a
 * genuine account switch. The reverse lookup is a set, because a customer
 * legitimately accumulates one entry per device they use.
 *
 * Both writes are idempotent: {@code SET} and {@code SADD} are no-ops when the
 * value is already there, which matters because RabbitMQ can redeliver an
 * {@code identify} event.
 */
public class RedisIdentityLinkStore implements IdentityLinkStore {

    private static final String ANON_PREFIX = "identity:anon:";
    private static final String USER_PREFIX = "identity:user:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /**
     * @param ttl how long a link survives without being refreshed. Links are
     *            long-lived by nature — a returning customer months later should
     *            still be recognised — so this defaults to a year rather than
     *            hours. It exists at all so an abandoned device identity
     *            eventually ages out rather than accumulating forever.
     */
    public RedisIdentityLinkStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(365) : ttl;
    }

    @Override
    public void link(IdentityLink link) {
        String anonKey = anonKey(link.tenantId(), link.anonymousId());
        String userKey = userKey(link.tenantId(), link.userId());

        redis.opsForValue().set(anonKey, link.userId(), ttl);
        redis.opsForSet().add(userKey, link.anonymousId());
        // A set has no per-member expiry, so the TTL is refreshed on the key as
        // a whole every time the user is seen. An active customer's link set
        // therefore never expires; a dormant one eventually does.
        redis.expire(userKey, ttl);
    }

    @Override
    public Optional<String> resolveUserId(String tenantId, String anonymousId) {
        if (anonymousId == null) return Optional.empty();
        return Optional.ofNullable(redis.opsForValue().get(anonKey(tenantId, anonymousId)));
    }

    @Override
    public List<String> anonymousIdsFor(String tenantId, String userId) {
        if (userId == null) return List.of();
        Set<String> members = redis.opsForSet().members(userKey(tenantId, userId));
        return members == null ? List.of() : List.copyOf(members);
    }

    private static String anonKey(String tenantId, String anonymousId) {
        return ANON_PREFIX + tenant(tenantId) + ":" + anonymousId;
    }

    private static String userKey(String tenantId, String userId) {
        return USER_PREFIX + tenant(tenantId) + ":" + userId;
    }

    /** Namespaced per tenant so one tenant's identity graph can never resolve in another's. */
    private static String tenant(String tenantId) {
        return tenantId == null ? "_" : tenantId;
    }
}
