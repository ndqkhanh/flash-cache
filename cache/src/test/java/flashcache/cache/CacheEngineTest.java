package flashcache.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEngineTest {

    private CacheEngine cache;

    @BeforeEach
    void setUp() {
        cache = new CacheEngine();
    }

    @Test
    void setAndGet_roundTrip() {
        cache.set("k1", "v1");
        assertThat(cache.get("k1")).isEqualTo(Optional.of("v1"));
    }

    @Test
    void get_nonexistentKey_returnsEmpty() {
        assertThat(cache.get("missing")).isEmpty();
    }

    @Test
    void del_removesEntry() {
        cache.set("k1", "v1");
        assertThat(cache.del("k1")).isTrue();
        assertThat(cache.get("k1")).isEmpty();
    }

    @Test
    void del_nonexistentKey_returnsFalse() {
        assertThat(cache.del("ghost")).isFalse();
    }

    @Test
    void exists_returnsTrueForPresentKey() {
        cache.set("k1", "v1");
        assertThat(cache.exists("k1")).isTrue();
    }

    @Test
    void exists_returnsFalseForMissingKey() {
        assertThat(cache.exists("missing")).isFalse();
    }

    @Test
    void setWithTtl_getBeforeExpiry_returnsValue() throws InterruptedException {
        cache.set("k1", "v1", 500);
        Thread.sleep(100);
        assertThat(cache.get("k1")).isEqualTo(Optional.of("v1"));
    }

    @Test
    void setWithTtl_getAfterExpiry_returnsEmpty() throws InterruptedException {
        cache.set("k1", "v1", 50);
        Thread.sleep(100);
        assertThat(cache.get("k1")).isEmpty();
    }

    @Test
    void overwrite_existingKey_updatesValue() {
        cache.set("k1", "v1");
        cache.set("k1", "v2");
        assertThat(cache.get("k1")).isEqualTo(Optional.of("v2"));
    }

    @Test
    void overwrite_resetsTtl() throws InterruptedException {
        cache.set("k1", "v1", 80);
        Thread.sleep(50);
        // reset with new TTL
        cache.set("k1", "v2", 500);
        Thread.sleep(60);
        // old TTL would have expired but new one hasn't
        assertThat(cache.get("k1")).isEqualTo(Optional.of("v2"));
    }

    @Test
    void flush_clearsAllEntries() {
        cache.set("a", "1");
        cache.set("b", "2");
        cache.flush();
        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.get("a")).isEmpty();
    }

    @Test
    void size_returnsCorrectCount() {
        assertThat(cache.size()).isEqualTo(0);
        cache.set("a", "1");
        cache.set("b", "2");
        assertThat(cache.size()).isEqualTo(2);
        cache.del("a");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void keys_returnsAllNonExpiredKeys() throws InterruptedException {
        cache.set("a", "1");
        cache.set("b", "2", 50);
        cache.set("c", "3");
        Thread.sleep(100);
        assertThat(cache.keys()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void concurrentSetGet_isThreadSafe() throws InterruptedException {
        int threads = 20;
        int ops = 100;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            exec.submit(() -> {
                for (int i = 0; i < ops; i++) {
                    String key = "key-" + tid + "-" + i;
                    cache.set(key, "val-" + i);
                    cache.get(key);
                }
                latch.countDown();
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();
        assertThat(cache.size()).isEqualTo(threads * ops);
    }

    @Test
    void expiredKey_removedFromExists() throws InterruptedException {
        cache.set("k1", "v1", 50);
        Thread.sleep(100);
        assertThat(cache.exists("k1")).isFalse();
    }

    @Test
    void expiredKey_notCountedInSize() throws InterruptedException {
        cache.set("k1", "v1", 50);
        cache.set("k2", "v2");
        Thread.sleep(100);
        assertThat(cache.size()).isEqualTo(1);
    }
}
