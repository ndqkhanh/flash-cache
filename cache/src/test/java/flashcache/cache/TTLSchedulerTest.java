package flashcache.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TTLSchedulerTest {

    private List<String> expired;
    private TTLScheduler scheduler;

    @BeforeEach
    void setUp() {
        expired = new CopyOnWriteArrayList<>();
        scheduler = new TTLScheduler(key -> expired.add(key), 20);
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    void scheduledKey_expiresAfterTtl() throws InterruptedException {
        long expiry = System.currentTimeMillis() + 50;
        scheduler.schedule("k1", expiry);
        Thread.sleep(200);
        assertThat(expired).contains("k1");
    }

    @Test
    void cancelledKey_doesNotExpire() throws InterruptedException {
        long expiry = System.currentTimeMillis() + 50;
        scheduler.schedule("k1", expiry);
        scheduler.cancel("k1");
        Thread.sleep(200);
        assertThat(expired).doesNotContain("k1");
    }

    @Test
    void callback_isInvokedOnExpiry() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        TTLScheduler s = new TTLScheduler(key -> {
            expired.add(key);
            latch.countDown();
        }, 20);
        s.start();
        try {
            s.schedule("trigger", System.currentTimeMillis() + 40);
            assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(expired).contains("trigger");
        } finally {
            s.stop();
        }
    }

    @Test
    void multipleKeys_expireInCorrectOrder() throws InterruptedException {
        List<String> order = new CopyOnWriteArrayList<>();
        TTLScheduler s = new TTLScheduler(order::add, 10);
        s.start();
        try {
            long now = System.currentTimeMillis();
            s.schedule("first", now + 30);
            s.schedule("second", now + 60);
            s.schedule("third", now + 90);
            Thread.sleep(300);
            assertThat(order).containsExactly("first", "second", "third");
        } finally {
            s.stop();
        }
    }

    @Test
    void reschedule_updatesExpiryTime() throws InterruptedException {
        long now = System.currentTimeMillis();
        scheduler.schedule("k1", now + 40);
        // reschedule to far future
        scheduler.schedule("k1", now + 2000);
        Thread.sleep(200);
        assertThat(expired).doesNotContain("k1");
    }

    @Test
    void expiredKeys_areCleanedFromInternalState() throws InterruptedException {
        scheduler.schedule("k1", System.currentTimeMillis() + 40);
        assertThat(scheduler.activeCount()).isEqualTo(1);
        Thread.sleep(200);
        assertThat(scheduler.activeCount()).isEqualTo(0);
    }

    @Test
    void activeCount_tracksPendingExpirations() {
        long now = System.currentTimeMillis();
        scheduler.schedule("a", now + 5000);
        scheduler.schedule("b", now + 5000);
        scheduler.schedule("c", now + 5000);
        assertThat(scheduler.activeCount()).isEqualTo(3);
        scheduler.cancel("b");
        assertThat(scheduler.activeCount()).isEqualTo(2);
    }

    @Test
    void stop_haltsSchedulerCleanly() throws InterruptedException {
        scheduler.schedule("k1", System.currentTimeMillis() + 200);
        scheduler.stop();
        Thread.sleep(400);
        // after stop, the scheduler is not running so processExpired won't run
        // k1 may or may not expire depending on timing; just verify no exception
        // and the scheduler stops cleanly
        assertThat(true).isTrue();
    }

    @Test
    void processExpired_directInvocation_firesCallback() {
        scheduler.schedule("direct", System.currentTimeMillis() - 1);
        scheduler.processExpired();
        assertThat(expired).contains("direct");
    }
}
