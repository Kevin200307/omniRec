package io.omnirec.redis.state;

import io.omnirec.commerce.dedup.DeduplicationStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Shared {@link DeduplicationStore} for deployments with more than one instance.
 *
 * The in-memory store is atomic only within one JVM, so two nodes each keep
 * their own map and the same event can pass both — a duplicate purchase
 * reaching a provider. This one makes the lease global.
 *
 * <pre>
 *   claim     SET key "pending" NX PX lease   — atomic: sets only if absent
 *   complete  SET key "done" PX ttl           — overwrites the lease
 *   release   Lua: DEL key only if "pending"  — never un-completes a fact
 * </pre>
 *
 * A get-then-set anywhere in here would reintroduce the race this class exists
 * to prevent, which is why the conditional delete is a script.
 */
public class RedisDeduplicationStore implements DeduplicationStore {

    static final String PENDING = "pending";
    static final String DONE = "done";

    private static final DefaultRedisScript<Long> RELEASE_IF_PENDING = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDeduplicationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ClaimResult claim(String key, Duration lease) {
        Duration effectiveLease = positiveOr(lease, Duration.ofMinutes(5));

        // Two attempts: the key can expire between a failed SET NX and the GET
        // that follows it, and in that case the event is simply claimable now.
        for (int attempt = 0; attempt < 2; attempt++) {
            Boolean claimed = redis.opsForValue().setIfAbsent(key, PENDING, effectiveLease);
            if (Boolean.TRUE.equals(claimed)) return ClaimResult.CLAIMED;
            if (claimed == null) {
                // The command didn't complete (connection trouble). Don't proceed
                // on an unconfirmed claim: report "try later" rather than risk a
                // duplicate purchase.
                return ClaimResult.IN_PROGRESS;
            }
            String state = redis.opsForValue().get(key);
            if (DONE.equals(state)) return ClaimResult.ALREADY_COMPLETED;
            if (PENDING.equals(state)) return ClaimResult.IN_PROGRESS;
        }
        return ClaimResult.IN_PROGRESS;
    }

    @Override
    public void complete(String key, Duration ttl) {
        redis.opsForValue().set(key, DONE, positiveOr(ttl, Duration.ofHours(24)));
    }

    @Override
    public void release(String key) {
        redis.execute(RELEASE_IF_PENDING, List.of(key), PENDING);
    }

    @Override
    public boolean isCompleted(String key) {
        return DONE.equals(redis.opsForValue().get(key));
    }

    /** Redis rejects a non-positive expiry, and "remember forever" is never what a caller means. */
    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
