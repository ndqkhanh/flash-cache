package flashcache.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LRUEvictionTest {

    @Test
    void add_andEvict_returnsLRU() {
        LRUEviction lru = new LRUEviction(3);
        lru.add("a");
        lru.add("b");
        lru.add("c");
        // a is LRU (oldest)
        assertThat(lru.evict()).isEqualTo(Optional.of("a"));
    }

    @Test
    void access_updatesRecencyOrder() {
        LRUEviction lru = new LRUEviction(3);
        lru.add("a");
        lru.add("b");
        lru.add("c");
        lru.access("a"); // a is now MRU
        // b is now LRU
        assertThat(lru.evict()).isEqualTo(Optional.of("b"));
    }

    @Test
    void evict_onEmpty_returnsEmpty() {
        LRUEviction lru = new LRUEviction(5);
        assertThat(lru.evict()).isEmpty();
    }

    @Test
    void evict_multipleTimesReturnsCorrectOrder() {
        LRUEviction lru = new LRUEviction(4);
        lru.add("a");
        lru.add("b");
        lru.add("c");
        lru.add("d");
        // LRU order: a, b, c, d  (a is least recent)
        assertThat(lru.evict()).isEqualTo(Optional.of("a"));
        assertThat(lru.evict()).isEqualTo(Optional.of("b"));
        assertThat(lru.evict()).isEqualTo(Optional.of("c"));
        assertThat(lru.evict()).isEqualTo(Optional.of("d"));
        assertThat(lru.evict()).isEmpty();
    }

    @Test
    void add_movesMRUToHead_accessUpdatesOrder() {
        LRUEviction lru = new LRUEviction(3);
        lru.add("x");
        lru.add("y");
        lru.add("z");
        lru.access("x"); // x becomes MRU
        lru.access("y"); // y becomes MRU
        // LRU should be z
        assertThat(lru.evict()).isEqualTo(Optional.of("z"));
    }

    @Test
    void capacityTracking() {
        LRUEviction lru = new LRUEviction(3);
        assertThat(lru.size()).isEqualTo(0);
        lru.add("a");
        assertThat(lru.size()).isEqualTo(1);
        lru.add("b");
        lru.add("c");
        assertThat(lru.size()).isEqualTo(3);
        assertThat(lru.isFull()).isTrue();
    }

    @Test
    void addBeyondMax_doesNotAutoEvict_callerDecides() {
        LRUEviction lru = new LRUEviction(2);
        lru.add("a");
        lru.add("b");
        assertThat(lru.isFull()).isTrue();
        // caller decides to evict before adding
        lru.evict();
        lru.add("c");
        assertThat(lru.size()).isEqualTo(2);
    }

    @Test
    void remove_specificKey() {
        LRUEviction lru = new LRUEviction(3);
        lru.add("a");
        lru.add("b");
        lru.add("c");
        assertThat(lru.remove("b")).isTrue();
        assertThat(lru.size()).isEqualTo(2);
        // eviction order should skip b
        assertThat(lru.evict()).isEqualTo(Optional.of("a"));
        assertThat(lru.evict()).isEqualTo(Optional.of("c"));
    }

    @Test
    void remove_nonexistentKey_returnsFalse() {
        LRUEviction lru = new LRUEviction(3);
        assertThat(lru.remove("ghost")).isFalse();
    }

    @Test
    void size_tracksCorrectlyAfterAddRemove() {
        LRUEviction lru = new LRUEviction(5);
        lru.add("a");
        lru.add("b");
        lru.add("c");
        assertThat(lru.size()).isEqualTo(3);
        lru.remove("b");
        assertThat(lru.size()).isEqualTo(2);
        lru.evict();
        assertThat(lru.size()).isEqualTo(1);
    }

    @Test
    void add_duplicateKey_doesNotIncreaseSize() {
        LRUEviction lru = new LRUEviction(5);
        lru.add("a");
        lru.add("a");
        assertThat(lru.size()).isEqualTo(1);
    }

    @Test
    void concurrentAccess_isThreadSafe() throws InterruptedException {
        LRUEviction lru = new LRUEviction(1000);
        int threads = 10;
        int ops = 50;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            exec.submit(() -> {
                for (int i = 0; i < ops; i++) {
                    lru.add("t" + tid + "-k" + i);
                }
                latch.countDown();
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();
        assertThat(lru.size()).isEqualTo(threads * ops);
    }
}
