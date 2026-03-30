package flashcache.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

public class TTLScheduler {

    private static final Logger log = LoggerFactory.getLogger(TTLScheduler.class);

    public interface ExpiryCallback {
        void onExpired(String key);
    }

    private record ScheduledEntry(String key, long expiryTimeMs) implements Comparable<ScheduledEntry> {
        @Override
        public int compareTo(ScheduledEntry other) {
            return Long.compare(this.expiryTimeMs, other.expiryTimeMs);
        }
    }

    private final ExpiryCallback callback;
    private final long scanIntervalMs;
    private final ReentrantLock lock = new ReentrantLock();

    // key -> canonical expiry time (for cancel/reschedule logic)
    private final Map<String, Long> scheduled = new HashMap<>();
    // min-heap ordered by expiry time
    private final PriorityQueue<ScheduledEntry> queue = new PriorityQueue<>();

    private volatile boolean running = false;
    private Thread scannerThread;

    public TTLScheduler(ExpiryCallback callback, long scanIntervalMs) {
        this.callback = callback;
        this.scanIntervalMs = scanIntervalMs;
    }

    public void start() {
        running = true;
        scannerThread = Thread.ofVirtual().name("ttl-scanner").start(this::scanLoop);
        log.debug("TTLScheduler started, scanInterval={}ms", scanIntervalMs);
    }

    public void stop() {
        running = false;
        if (scannerThread != null) {
            scannerThread.interrupt();
        }
        log.debug("TTLScheduler stopped");
    }

    /** Schedule a key to expire at expiryTimeMs (absolute epoch ms). */
    public void schedule(String key, long expiryTimeMs) {
        lock.lock();
        try {
            scheduled.put(key, expiryTimeMs);
            queue.offer(new ScheduledEntry(key, expiryTimeMs));
            log.debug("schedule key={} expiryTimeMs={}", key, expiryTimeMs);
        } finally {
            lock.unlock();
        }
    }

    /** Cancel a pending expiry. */
    public void cancel(String key) {
        lock.lock();
        try {
            scheduled.remove(key);
            log.debug("cancel key={}", key);
            // stale entries in queue are filtered during scan
        } finally {
            lock.unlock();
        }
    }

    /** Number of keys currently pending expiration. */
    public int activeCount() {
        lock.lock();
        try {
            return scheduled.size();
        } finally {
            lock.unlock();
        }
    }

    private void scanLoop() {
        while (running) {
            try {
                Thread.sleep(scanIntervalMs);
                processExpired();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    void processExpired() {
        long now = System.currentTimeMillis();
        while (true) {
            String key;
            lock.lock();
            try {
                ScheduledEntry top = queue.peek();
                if (top == null || top.expiryTimeMs() > now) {
                    break;
                }
                queue.poll();
                Long canonical = scheduled.get(top.key());
                // skip stale heap entries (cancelled or rescheduled)
                if (canonical == null || !canonical.equals(top.expiryTimeMs())) {
                    continue;
                }
                // skip if rescheduled to a future time
                if (canonical > now) {
                    continue;
                }
                scheduled.remove(top.key());
                key = top.key();
            } finally {
                lock.unlock();
            }
            log.debug("expiring key={}", key);
            callback.onExpired(key);
        }
    }
}
