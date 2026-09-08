package io.omnirec.core.provider;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Implemented by RedisCacheProvider and InMemoryCacheProvider. Deliberately
 * generic (not "RecentlyViewedProvider") so future uses beyond
 * recently-viewed reuse it without a new interface.
 */
public interface CacheProvider {

    String id();

    void set(String key, Object value, Duration ttl);

    Optional<Object> get(String key);

    /** Push onto a capped list — the mechanism recently-viewed is built on. */
    void pushCapped(String key, Object value, int maxSize, Duration ttl);

    List<Object> getList(String key);
}
