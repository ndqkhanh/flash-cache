package flashcache.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataTypesTest {

    // -------------------------------------------------------------------------
    // CacheString
    // -------------------------------------------------------------------------
    @Test
    void cacheString_holdsValue() {
        DataTypes.CacheString s = new DataTypes.CacheString("hello");
        assertThat(s.value()).isEqualTo("hello");
    }

    // -------------------------------------------------------------------------
    // CacheList
    // -------------------------------------------------------------------------
    @Test
    void cacheList_lpush_addsToFront() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        list.lpush("a");
        list.lpush("b");
        assertThat(list.toList()).containsExactly("b", "a");
    }

    @Test
    void cacheList_rpush_addsToBack() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        list.rpush("a");
        list.rpush("b");
        assertThat(list.toList()).containsExactly("a", "b");
    }

    @Test
    void cacheList_lpop_removesFromFront() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        list.rpush("a");
        list.rpush("b");
        assertThat(list.lpop()).isEqualTo(Optional.of("a"));
        assertThat(list.llen()).isEqualTo(1);
    }

    @Test
    void cacheList_rpop_removesFromBack() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        list.rpush("a");
        list.rpush("b");
        assertThat(list.rpop()).isEqualTo(Optional.of("b"));
    }

    @Test
    void cacheList_popOnEmpty_returnsEmpty() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        assertThat(list.lpop()).isEmpty();
        assertThat(list.rpop()).isEmpty();
    }

    @Test
    void cacheList_lrange_returnsSublist() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        list.rpush("a");
        list.rpush("b");
        list.rpush("c");
        list.rpush("d");
        assertThat(list.lrange(1, 2)).containsExactly("b", "c");
    }

    @Test
    void cacheList_lrange_fullRange() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        list.rpush("a");
        list.rpush("b");
        list.rpush("c");
        assertThat(list.lrange(0, -1)).containsExactly("a", "b", "c");
    }

    @Test
    void cacheList_llen_returnsSize() {
        DataTypes.CacheList list = new DataTypes.CacheList();
        assertThat(list.llen()).isEqualTo(0);
        list.rpush("x");
        list.rpush("y");
        assertThat(list.llen()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // CacheHash
    // -------------------------------------------------------------------------
    @Test
    void cacheHash_hset_hget() {
        DataTypes.CacheHash hash = new DataTypes.CacheHash();
        hash.hset("name", "Alice");
        assertThat(hash.hget("name")).isEqualTo(Optional.of("Alice"));
    }

    @Test
    void cacheHash_hget_missingField_returnsEmpty() {
        DataTypes.CacheHash hash = new DataTypes.CacheHash();
        assertThat(hash.hget("missing")).isEmpty();
    }

    @Test
    void cacheHash_hdel_removesField() {
        DataTypes.CacheHash hash = new DataTypes.CacheHash();
        hash.hset("f1", "v1");
        assertThat(hash.hdel("f1")).isTrue();
        assertThat(hash.hget("f1")).isEmpty();
        assertThat(hash.hdel("f1")).isFalse();
    }

    @Test
    void cacheHash_hgetall_returnsAllFields() {
        DataTypes.CacheHash hash = new DataTypes.CacheHash();
        hash.hset("a", "1");
        hash.hset("b", "2");
        Map<String, String> all = hash.hgetall();
        assertThat(all).containsEntry("a", "1").containsEntry("b", "2");
    }

    @Test
    void cacheHash_hlen() {
        DataTypes.CacheHash hash = new DataTypes.CacheHash();
        assertThat(hash.hlen()).isEqualTo(0);
        hash.hset("x", "1");
        hash.hset("y", "2");
        assertThat(hash.hlen()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // CacheSet
    // -------------------------------------------------------------------------
    @Test
    void cacheSet_sadd_sismember() {
        DataTypes.CacheSet set = new DataTypes.CacheSet();
        assertThat(set.sadd("a")).isTrue();
        assertThat(set.sadd("a")).isFalse(); // duplicate
        assertThat(set.sismember("a")).isTrue();
        assertThat(set.sismember("b")).isFalse();
    }

    @Test
    void cacheSet_srem_removeMember() {
        DataTypes.CacheSet set = new DataTypes.CacheSet();
        set.sadd("a");
        assertThat(set.srem("a")).isTrue();
        assertThat(set.sismember("a")).isFalse();
        assertThat(set.srem("a")).isFalse();
    }

    @Test
    void cacheSet_smembers_returnsAll() {
        DataTypes.CacheSet set = new DataTypes.CacheSet();
        set.sadd("x");
        set.sadd("y");
        set.sadd("z");
        assertThat(set.smembers()).containsExactlyInAnyOrder("x", "y", "z");
    }

    @Test
    void cacheSet_scard() {
        DataTypes.CacheSet set = new DataTypes.CacheSet();
        assertThat(set.scard()).isEqualTo(0);
        set.sadd("a");
        set.sadd("b");
        assertThat(set.scard()).isEqualTo(2);
        set.srem("a");
        assertThat(set.scard()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // CacheSortedSet
    // -------------------------------------------------------------------------
    @Test
    void cacheSortedSet_zadd_zcard() {
        DataTypes.CacheSortedSet zset = new DataTypes.CacheSortedSet();
        assertThat(zset.zadd("a", 1.0)).isTrue();
        assertThat(zset.zadd("b", 2.0)).isTrue();
        assertThat(zset.zadd("a", 1.5)).isFalse(); // update score
        assertThat(zset.zcard()).isEqualTo(2);
    }

    @Test
    void cacheSortedSet_zrange_returnsSortedByScore() {
        DataTypes.CacheSortedSet zset = new DataTypes.CacheSortedSet();
        zset.zadd("c", 3.0);
        zset.zadd("a", 1.0);
        zset.zadd("b", 2.0);
        assertThat(zset.zrange(0, -1)).containsExactly("a", "b", "c");
    }

    @Test
    void cacheSortedSet_zscore() {
        DataTypes.CacheSortedSet zset = new DataTypes.CacheSortedSet();
        zset.zadd("m1", 4.5);
        assertThat(zset.zscore("m1")).isEqualTo(Optional.of(4.5));
        assertThat(zset.zscore("missing")).isEmpty();
    }

    @Test
    void cacheSortedSet_zrem_removesMember() {
        DataTypes.CacheSortedSet zset = new DataTypes.CacheSortedSet();
        zset.zadd("a", 1.0);
        zset.zadd("b", 2.0);
        assertThat(zset.zrem("a")).isTrue();
        assertThat(zset.zrem("a")).isFalse();
        assertThat(zset.zcard()).isEqualTo(1);
        assertThat(zset.zrange(0, -1)).containsExactly("b");
    }

    @Test
    void cacheSortedSet_zrangeSubset() {
        DataTypes.CacheSortedSet zset = new DataTypes.CacheSortedSet();
        zset.zadd("a", 1.0);
        zset.zadd("b", 2.0);
        zset.zadd("c", 3.0);
        zset.zadd("d", 4.0);
        assertThat(zset.zrange(1, 2)).containsExactly("b", "c");
    }

    @Test
    void cacheSortedSet_zadd_updateScore_changesOrder() {
        DataTypes.CacheSortedSet zset = new DataTypes.CacheSortedSet();
        zset.zadd("a", 1.0);
        zset.zadd("b", 2.0);
        zset.zadd("a", 3.0); // a now has higher score than b
        assertThat(zset.zrange(0, -1)).containsExactly("b", "a");
    }

    @Test
    void sealedInterface_instanceOf() {
        DataTypes s = new DataTypes.CacheString("x");
        assertThat(s).isInstanceOf(DataTypes.CacheString.class);

        DataTypes l = new DataTypes.CacheList();
        assertThat(l).isInstanceOf(DataTypes.CacheList.class);
    }
}
