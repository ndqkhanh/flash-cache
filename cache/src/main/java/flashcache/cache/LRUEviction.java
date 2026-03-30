package flashcache.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public class LRUEviction {

    private static final Logger log = LoggerFactory.getLogger(LRUEviction.class);

    private static class Node {
        String key;
        Node prev, next;

        Node(String key) {
            this.key = key;
        }
    }

    private final int maxSize;
    private final HashMap<String, Node> map = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    // Sentinel head (most recent) and tail (least recent)
    private final Node head = new Node(null);
    private final Node tail = new Node(null);

    public LRUEviction(int maxSize) {
        this.maxSize = maxSize;
        head.next = tail;
        tail.prev = head;
    }

    /** Add a new key. Does not auto-evict — caller decides when to call evict(). */
    public void add(String key) {
        lock.lock();
        try {
            if (map.containsKey(key)) {
                moveToHead(map.get(key));
                return;
            }
            Node node = new Node(key);
            map.put(key, node);
            insertAtHead(node);
            log.debug("add key={}", key);
        } finally {
            lock.unlock();
        }
    }

    /** Mark key as recently accessed (move to head). */
    public void access(String key) {
        lock.lock();
        try {
            Node node = map.get(key);
            if (node != null) {
                moveToHead(node);
                log.debug("access key={}", key);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Remove and return the least recently used key. */
    public Optional<String> evict() {
        lock.lock();
        try {
            if (tail.prev == head) {
                return Optional.empty();
            }
            Node lru = tail.prev;
            removeNode(lru);
            map.remove(lru.key);
            log.debug("evict key={}", lru.key);
            return Optional.of(lru.key);
        } finally {
            lock.unlock();
        }
    }

    /** Remove a specific key. */
    public boolean remove(String key) {
        lock.lock();
        try {
            Node node = map.remove(key);
            if (node == null) return false;
            removeNode(node);
            log.debug("remove key={}", key);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    public int maxSize() {
        return maxSize;
    }

    public boolean isFull() {
        lock.lock();
        try {
            return map.size() >= maxSize;
        } finally {
            lock.unlock();
        }
    }

    // --- internal helpers (caller must hold lock) ---

    private void insertAtHead(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        insertAtHead(node);
    }
}
