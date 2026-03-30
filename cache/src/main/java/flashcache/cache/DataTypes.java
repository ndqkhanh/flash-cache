package flashcache.cache;

import java.util.*;

public sealed interface DataTypes permits
        DataTypes.CacheString,
        DataTypes.CacheList,
        DataTypes.CacheHash,
        DataTypes.CacheSet,
        DataTypes.CacheSortedSet {

    // -------------------------------------------------------------------------
    // CacheString
    // -------------------------------------------------------------------------
    record CacheString(String value) implements DataTypes {}

    // -------------------------------------------------------------------------
    // CacheList  (LPUSH / RPUSH / LPOP / RPOP / LRANGE / LLEN)
    // -------------------------------------------------------------------------
    final class CacheList implements DataTypes {
        private final LinkedList<String> list = new LinkedList<>();

        public void lpush(String value) { list.addFirst(value); }
        public void rpush(String value) { list.addLast(value); }

        public Optional<String> lpop() {
            return list.isEmpty() ? Optional.empty() : Optional.of(list.removeFirst());
        }

        public Optional<String> rpop() {
            return list.isEmpty() ? Optional.empty() : Optional.of(list.removeLast());
        }

        /** Returns elements from index start to stop (inclusive), Redis semantics. */
        public List<String> lrange(int start, int stop) {
            int size = list.size();
            if (size == 0) return Collections.emptyList();
            int from = Math.max(0, start);
            int to = stop < 0 ? size + stop : Math.min(stop, size - 1);
            if (from > to) return Collections.emptyList();
            return new ArrayList<>(list.subList(from, to + 1));
        }

        public int llen() { return list.size(); }

        public List<String> toList() { return Collections.unmodifiableList(list); }
    }

    // -------------------------------------------------------------------------
    // CacheHash  (HSET / HGET / HDEL / HGETALL / HLEN)
    // -------------------------------------------------------------------------
    final class CacheHash implements DataTypes {
        private final HashMap<String, String> map = new HashMap<>();

        public void hset(String field, String value) { map.put(field, value); }

        public Optional<String> hget(String field) {
            return Optional.ofNullable(map.get(field));
        }

        public boolean hdel(String field) { return map.remove(field) != null; }

        public Map<String, String> hgetall() { return Collections.unmodifiableMap(map); }

        public int hlen() { return map.size(); }
    }

    // -------------------------------------------------------------------------
    // CacheSet  (SADD / SREM / SISMEMBER / SMEMBERS / SCARD)
    // -------------------------------------------------------------------------
    final class CacheSet implements DataTypes {
        private final HashSet<String> set = new HashSet<>();

        public boolean sadd(String member) { return set.add(member); }
        public boolean srem(String member) { return set.remove(member); }
        public boolean sismember(String member) { return set.contains(member); }
        public Set<String> smembers() { return Collections.unmodifiableSet(set); }
        public int scard() { return set.size(); }
    }

    // -------------------------------------------------------------------------
    // CacheSortedSet  (ZADD / ZREM / ZRANGE / ZSCORE / ZCARD)
    // -------------------------------------------------------------------------
    final class CacheSortedSet implements DataTypes {
        // member -> score
        private final HashMap<String, Double> scores = new HashMap<>();
        // score -> member set (TreeMap keeps scores sorted)
        private final TreeMap<Double, LinkedHashSet<String>> byScore = new TreeMap<>();

        public boolean zadd(String member, double score) {
            boolean isNew = !scores.containsKey(member);
            if (!isNew) {
                // remove from old score bucket
                double old = scores.get(member);
                LinkedHashSet<String> bucket = byScore.get(old);
                if (bucket != null) {
                    bucket.remove(member);
                    if (bucket.isEmpty()) byScore.remove(old);
                }
            }
            scores.put(member, score);
            byScore.computeIfAbsent(score, k -> new LinkedHashSet<>()).add(member);
            return isNew;
        }

        public boolean zrem(String member) {
            Double score = scores.remove(member);
            if (score == null) return false;
            LinkedHashSet<String> bucket = byScore.get(score);
            if (bucket != null) {
                bucket.remove(member);
                if (bucket.isEmpty()) byScore.remove(score);
            }
            return true;
        }

        /** Returns members sorted by score ascending, index range [start, stop] inclusive. */
        public List<String> zrange(int start, int stop) {
            List<String> all = new ArrayList<>();
            for (LinkedHashSet<String> bucket : byScore.values()) {
                all.addAll(bucket);
            }
            int size = all.size();
            if (size == 0) return Collections.emptyList();
            int from = Math.max(0, start);
            int to = stop < 0 ? size + stop : Math.min(stop, size - 1);
            if (from > to) return Collections.emptyList();
            return all.subList(from, to + 1);
        }

        public Optional<Double> zscore(String member) {
            return Optional.ofNullable(scores.get(member));
        }

        public int zcard() { return scores.size(); }
    }
}
