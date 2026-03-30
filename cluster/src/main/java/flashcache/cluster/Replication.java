package flashcache.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class Replication {

    private static final Logger log = LoggerFactory.getLogger(Replication.class);

    public record ReplicationEntry(long sequenceNum, String key, String command) {}

    public interface ReplicationTransport {
        CompletableFuture<Boolean> sendToReplica(String replicaId, ReplicationEntry entry);
    }

    /** Fake transport for testing — immediately succeeds or fails per configuration. */
    public static class FakeReplicationTransport implements ReplicationTransport {
        private final Map<String, Boolean> replicaResults = new ConcurrentHashMap<>();
        private final List<ReplicationEntry> sent = new CopyOnWriteArrayList<>();

        public void setReplicaResult(String replicaId, boolean success) {
            replicaResults.put(replicaId, success);
        }

        public List<ReplicationEntry> getSent() {
            return Collections.unmodifiableList(sent);
        }

        @Override
        public CompletableFuture<Boolean> sendToReplica(String replicaId, ReplicationEntry entry) {
            sent.add(entry);
            boolean result = replicaResults.getOrDefault(replicaId, true);
            return CompletableFuture.completedFuture(result);
        }
    }

    // --- ReplicationManager ---

    private final int replicaCount;
    private final ReplicationTransport transport;
    private final List<String> replicaIds;

    private final AtomicLong sequenceCounter = new AtomicLong(0);
    // sequenceNum -> set of replicaIds that have NOT yet acked
    private final ConcurrentHashMap<Long, Set<String>> pendingAcks = new ConcurrentHashMap<>();
    // replicaId -> last acked sequence
    private final ConcurrentHashMap<String, Long> replicaAcked = new ConcurrentHashMap<>();
    // replication log in insertion order
    private final CopyOnWriteArrayList<ReplicationEntry> replicationLog = new CopyOnWriteArrayList<>();

    public Replication(int replicaCount, List<String> replicaIds, ReplicationTransport transport) {
        this.replicaCount = replicaCount;
        this.replicaIds = new ArrayList<>(replicaIds);
        this.transport = transport;
        for (String id : replicaIds) {
            replicaAcked.put(id, 0L);
        }
    }

    public ReplicationEntry replicate(String key, String command) {
        long seq = sequenceCounter.incrementAndGet();
        ReplicationEntry entry = new ReplicationEntry(seq, key, command);
        replicationLog.add(entry);

        // Fan out to configured replica count (up to available replicas)
        int fanout = Math.min(replicaCount, replicaIds.size());
        Set<String> waiting = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < fanout; i++) {
            String replicaId = replicaIds.get(i);
            waiting.add(replicaId);
        }
        pendingAcks.put(seq, waiting);

        for (String replicaId : new ArrayList<>(waiting)) {
            sendWithRetry(replicaId, entry, 3);
        }

        log.debug("replicate seq={} key={} fanout={}", seq, key, fanout);
        return entry;
    }

    public void ack(String replicaId, long sequenceNum) {
        replicaAcked.merge(replicaId, sequenceNum, Math::max);
        Set<String> waiting = pendingAcks.get(sequenceNum);
        if (waiting != null) {
            waiting.remove(replicaId);
            if (waiting.isEmpty()) {
                pendingAcks.remove(sequenceNum);
                log.debug("ack fully replicated seq={}", sequenceNum);
            }
        }
        log.debug("ack replicaId={} seq={}", replicaId, sequenceNum);
    }

    public int getPendingCount() {
        return pendingAcks.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    public long getReplicaLag(String replicaId) {
        long current = sequenceCounter.get();
        long acked = replicaAcked.getOrDefault(replicaId, 0L);
        return current - acked;
    }

    public List<ReplicationEntry> getReplicationLog() {
        return Collections.unmodifiableList(replicationLog);
    }

    // --- internal ---

    private void sendWithRetry(String replicaId, ReplicationEntry entry, int maxRetries) {
        doSend(replicaId, entry, maxRetries, 0);
    }

    private void doSend(String replicaId, ReplicationEntry entry, int maxRetries, int attempt) {
        transport.sendToReplica(replicaId, entry).thenAccept(success -> {
            if (success) {
                ack(replicaId, entry.sequenceNum());
            } else if (attempt < maxRetries - 1) {
                log.debug("retry replicaId={} seq={} attempt={}", replicaId, entry.sequenceNum(), attempt + 1);
                doSend(replicaId, entry, maxRetries, attempt + 1);
            } else {
                log.warn("replication failed after {} attempts replicaId={} seq={}", maxRetries, replicaId, entry.sequenceNum());
            }
        });
    }
}
