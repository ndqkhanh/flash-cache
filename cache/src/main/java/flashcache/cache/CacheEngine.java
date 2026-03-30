package flashcache.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CacheEngine {

    private static final Logger log = LoggerFactory.getLogger(CacheEngine.class);

    private record Entry(String value, long expiryMs) {
        boolean isExpired() {
            return expiryMs > 0 && System.currentTimeMillis() > expiryMs;
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public void set(String key, String value) {
        store.put(key, new Entry(value, 0));
        log.debug("set key={}", key);
    }

    public void set(String key, String value, long ttlMs) {
        long expiryMs = System.currentTimeMillis() + ttlMs;
        store.put(key, new Entry(value, expiryMs));
        log.debug("set key={} ttlMs={}", key, ttlMs);
    }

    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            store.remove(key);
            log.debug("get key={} expired", key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public boolean del(String key) {
        boolean removed = store.remove(key) != null;
        log.debug("del key={} removed={}", key, removed);
        return removed;
    }

    public boolean exists(String key) {
        Entry entry = store.get(key);
        if (entry == null) return false;
        if (entry.isExpired()) {
            store.remove(key);
            return false;
        }
        return true;
    }

    public Set<String> keys() {
        return store.entrySet().stream()
                .filter(e -> !e.getValue().isExpired())
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public int size() {
        return (int) store.entrySet().stream()
                .filter(e -> !e.getValue().isExpired())
                .count();
    }

    public void flush() {
        store.clear();
        log.debug("flush");
    }

    /** Called by TTLScheduler to remove an expired key. */
    public void expireKey(String key) {
        store.remove(key);
        log.debug("expireKey key={}", key);
    }
}
