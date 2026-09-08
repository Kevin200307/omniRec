package io.omnirec.core.fake;

import io.omnirec.core.provider.CacheProvider;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local cache — no TTL enforcement (fine for local dev; a real deployment should use omnirec-redis-starter). */
public class InMemoryCacheProvider implements CacheProvider {

    private final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedList<Object>> lists = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "in-memory";
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        values.put(key, value);
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public synchronized void pushCapped(String key, Object value, int maxSize, Duration ttl) {
        LinkedList<Object> list = lists.computeIfAbsent(key, k -> new LinkedList<>());
        list.remove(value); // move-to-front semantics: a re-viewed product jumps back up
        list.addFirst(value);
        while (list.size() > maxSize) list.removeLast();
    }

    @Override
    public List<Object> getList(String key) {
        return List.copyOf(lists.getOrDefault(key, new LinkedList<>()));
    }
}
