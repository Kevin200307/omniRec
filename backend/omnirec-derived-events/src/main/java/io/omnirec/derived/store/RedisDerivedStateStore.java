// SPDX-License-Identifier: Apache-2.0
package io.omnirec.derived.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.derived.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared store in Redis. Timers are a sorted set scored by due time plus a
 * hash holding each timer's data; every change to the pair is one Lua script,
 * so a claim and a reschedule of the same timer cannot interleave.
 *
 * <pre>
 *   schedule  ZADD timers dueAt id + HSET timer-data id json
 *   cancel    ZREM + HDEL
 *   claimDue  ZRANGEBYSCORE -inf now, then ZREM + HGET + HDEL each, in one script
 * </pre>
 *
 * Because the claim removes the timer inside the script, two instances polling
 * at the same moment never both receive it: no double fire. Timers survive
 * restarts with Redis itself.
 */
public class RedisDerivedStateStore implements DerivedStateStore {

    private static final DefaultRedisScript<Long> SCHEDULE = new DefaultRedisScript<>(
            "redis.call('zadd', KEYS[1], ARGV[2], ARGV[1]); redis.call('hset', KEYS[2], ARGV[1], ARGV[3]); return 1",
            Long.class);

    private static final DefaultRedisScript<Long> CANCEL = new DefaultRedisScript<>(
            "redis.call('zrem', KEYS[1], ARGV[1]); return redis.call('hdel', KEYS[2], ARGV[1])",
            Long.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM = new DefaultRedisScript<>("""
            local ids = redis.call('zrangebyscore', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            local claimed = {}
            for _, id in ipairs(ids) do
              if redis.call('zrem', KEYS[1], id) == 1 then
                local data = redis.call('hget', KEYS[2], id)
                redis.call('hdel', KEYS[2], id)
                if data then table.insert(claimed, data) end
              end
            end
            return claimed
            """, List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String prefix;
    private final String timersKey;
    private final String dataKey;

    public RedisDerivedStateStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix.endsWith(":") ? prefix : prefix + ":";
        this.timersKey = this.prefix + "timers";
        this.dataKey = this.prefix + "timer-data";
    }

    @Override
    public void schedule(Timer timer) {
        redis.execute(SCHEDULE, List.of(timersKey, dataKey), timer.id(),
                String.valueOf(timer.dueAt().toEpochMilli()), toJson(timer));
    }

    @Override
    public void cancel(String timerId) {
        redis.execute(CANCEL, List.of(timersKey, dataKey), timerId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Timer> claimDue(Instant now, int limit) {
        List<Object> raw = redis.execute(CLAIM, List.of(timersKey, dataKey),
                String.valueOf(now.toEpochMilli()), String.valueOf(limit));
        List<Timer> timers = new ArrayList<>();
        if (raw == null) return timers;
        for (Object json : raw) timers.add(fromJson(String.valueOf(json)));
        return timers;
    }

    @Override
    public boolean setIfAbsent(String key, Duration ttl) {
        Boolean set = redis.opsForValue().setIfAbsent(prefix + key, "1", ttl);
        if (set == null) throw new IllegalStateException("Redis did not answer SET NX for " + key);
        return set;
    }

    @Override
    public long increment(String key) {
        Long value = redis.opsForValue().increment(prefix + key);
        if (value == null) throw new IllegalStateException("Redis did not answer INCR for " + key);
        return value;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(prefix + key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(prefix + key, value, ttl);
    }

    private String toJson(Timer timer) {
        try {
            return mapper.writeValueAsString(Map.of("rule", timer.rule(), "key", timer.key(),
                    "dueAt", timer.dueAt().toEpochMilli(), "payload", timer.payload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Timer fromJson(String json) {
        try {
            Map<String, Object> map = mapper.readValue(json, Map.class);
            return new Timer((String) map.get("rule"), (String) map.get("key"),
                    Instant.ofEpochMilli(((Number) map.get("dueAt")).longValue()),
                    (Map<String, String>) map.get("payload"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable timer in Redis: " + json, e);
        }
    }
}
