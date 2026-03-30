package flashcache.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

public class ConsistentHashRing {

    private static final Logger log = LoggerFactory.getLogger(ConsistentHashRing.class);

    private final int virtualNodesPerNode;
    private final ConcurrentSkipListMap<Integer, String> ring = new ConcurrentSkipListMap<>();
    private final Set<String> nodes = Collections.synchronizedSet(new HashSet<>());

    public ConsistentHashRing(int virtualNodesPerNode) {
        this.virtualNodesPerNode = virtualNodesPerNode;
    }

    public void addNode(String nodeId) {
        for (int i = 0; i < virtualNodesPerNode; i++) {
            int hash = hash(nodeId + "#" + i);
            ring.put(hash, nodeId);
        }
        nodes.add(nodeId);
        log.debug("addNode nodeId={} vnodes={}", nodeId, virtualNodesPerNode);
    }

    public void removeNode(String nodeId) {
        if (!nodes.contains(nodeId)) {
            return;
        }
        for (int i = 0; i < virtualNodesPerNode; i++) {
            int hash = hash(nodeId + "#" + i);
            ring.remove(hash);
        }
        nodes.remove(nodeId);
        log.debug("removeNode nodeId={}", nodeId);
    }

    public String getNode(String key) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("Hash ring is empty — no nodes available");
        }
        int hash = hash(key);
        Map.Entry<Integer, String> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            // wrap around
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    public Set<String> getNodes() {
        return Collections.unmodifiableSet(new HashSet<>(nodes));
    }

    public int nodeCount() {
        return nodes.size();
    }

    /**
     * Returns an iterable over all ring entries in ascending key order, starting
     * just after the hash of the given key (wrapping around). Used by ShardRouter
     * to enumerate replica nodes clockwise.
     */
    Iterable<Map.Entry<Integer, String>> ringEntries() {
        return ring.entrySet();
    }

    // --- internal ---

    private static int hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 4).getInt();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
