// SPDX-License-Identifier: Apache-2.0
package io.omnirec.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.core.provider.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Generic CacheProvider on top of Redis — recently-viewed is the first
 * consumer (via pushCapped) but this isn't recently-viewed-specific, so a
 * future feature can reuse it without a new interface.
 */
public class RedisCacheProvider implements CacheProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;

    public RedisCacheProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String id() {
        return "redis";
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, MAPPER.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Redis set failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public Optional<Object> get(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            return raw == null ? Optional.empty() : Optional.of(MAPPER.readValue(raw, Object.class));
        } catch (Exception e) {
            log.warn("Redis get failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void pushCapped(String key, Object value, int maxSize, Duration ttl) {
        try {
            String json = MAPPER.writeValueAsString(value);
            // Move-to-front: drop any existing occurrence first so a re-viewed product jumps back to the top instead of appearing twice.
            redisTemplate.opsForList().remove(key, 0, json);
            redisTemplate.opsForList().leftPush(key, json);
            redisTemplate.opsForList().trim(key, 0, maxSize - 1);
            redisTemplate.expire(key, ttl);
        } catch (Exception e) {
            log.warn("Redis pushCapped failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public List<Object> getList(String key) {
        try {
            List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
            if (raw == null) return List.of();
            return raw.stream().map(s -> {
                try {
                    return MAPPER.readValue(s, Object.class);
                } catch (Exception e) {
                    return s;
                }
            }).toList();
        } catch (Exception e) {
            log.warn("Redis getList failed for key {}: {}", key, e.getMessage());
            return List.of();
        }
    }
}
