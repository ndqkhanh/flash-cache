package flashcache.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationTest {

    private Replication.FakeReplicationTransport transport;
    private Replication replication;

    @BeforeEach
    void setUp() {
        transport = new Replication.FakeReplicationTransport();
        replication = new Replication(2, List.of("replica1", "replica2"), transport);
    }

    @Test
    void replicate_enqueuesEntryToAllReplicas() {
        replication.replicate("key1", "SET key1 value1");
        // 2 replicas, each gets 1 send → 2 sends total
        assertThat(transport.getSent()).hasSize(2);
    }

    @Test
    void ack_reducesPendingCount() {
        transport.setReplicaResult("replica1", false); // don't auto-ack
        transport.setReplicaResult("replica2", false);

        // Use a transport that does NOT auto-ack
        Replication.FakeReplicationTransport manual = new Replication.FakeReplicationTransport() {
            @Override
            public CompletableFuture<Boolean> sendToReplica(String replicaId, Replication.ReplicationEntry entry) {
                getSent(); // just track
                return CompletableFuture.completedFuture(false); // don't ack
            }
        };
        Replication r = new Replication(2, List.of("r1", "r2"), manual);
        Replication.ReplicationEntry entry = r.replicate("key1", "SET");

        // Both replicas are pending
        int pendingAfterReplicate = r.getPendingCount();
        r.ack("r1", entry.sequenceNum());
        assertThat(r.getPendingCount()).isLessThan(pendingAfterReplicate);
    }

    @Test
    void allReplicasAck_entryFullyReplicated() {
        // Default transport succeeds immediately → both replicas auto-ack
        replication.replicate("key1", "SET key1 val");
        // After successful sends both auto-ack
        assertThat(replication.getPendingCount()).isEqualTo(0);
    }

    @Test
    void getPendingCount_tracksUnackedEntries() {
        Replication.FakeReplicationTransport noAck = new Replication.FakeReplicationTransport() {
            @Override
            public CompletableFuture<Boolean> sendToReplica(String replicaId, Replication.ReplicationEntry entry) {
                return CompletableFuture.completedFuture(false);
            }
        };
        Replication r = new Replication(2, List.of("r1", "r2"), noAck);
        r.replicate("k1", "SET");
        r.replicate("k2", "SET");
        // 2 entries × 2 replicas = 4 pending acks
        assertThat(r.getPendingCount()).isEqualTo(4);
    }

    @Test
    void getReplicaLag_showsSequenceDifference() {
        Replication.FakeReplicationTransport noAck = new Replication.FakeReplicationTransport() {
            @Override
            public CompletableFuture<Boolean> sendToReplica(String replicaId, Replication.ReplicationEntry entry) {
                return CompletableFuture.completedFuture(false);
            }
        };
        Replication r = new Replication(2, List.of("r1", "r2"), noAck);
        r.replicate("k1", "SET");
        r.replicate("k2", "SET");
        r.replicate("k3", "SET");
        // sequence is at 3, replica has acked 0
        assertThat(r.getReplicaLag("r1")).isEqualTo(3L);

        r.ack("r1", 2L);
        assertThat(r.getReplicaLag("r1")).isEqualTo(1L);
    }

    @Test
    void failedReplication_retries() {
        AtomicInteger attempts = new AtomicInteger(0);
        Replication.FakeReplicationTransport retryTransport = new Replication.FakeReplicationTransport() {
            @Override
            public CompletableFuture<Boolean> sendToReplica(String replicaId, Replication.ReplicationEntry entry) {
                int attempt = attempts.incrementAndGet();
                // fail first attempt, succeed second
                return CompletableFuture.completedFuture(attempt > 1);
            }
        };
        Replication r = new Replication(1, List.of("r1"), retryTransport);
        r.replicate("key1", "SET");
        // At least 2 attempts were made due to retry
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        assertThat(r.getPendingCount()).isEqualTo(0);
    }

    @Test
    void replicaCount_controlsFanOut() {
        Replication.FakeReplicationTransport t = new Replication.FakeReplicationTransport();
        // replicaCount=1 but 3 replicas available → only 1 gets the entry
        Replication r = new Replication(1, List.of("r1", "r2", "r3"), t);
        r.replicate("key1", "SET");
        assertThat(t.getSent()).hasSize(1);
    }

    @Test
    void multipleKeys_replicatedIndependently() {
        Replication.FakeReplicationTransport t = new Replication.FakeReplicationTransport();
        Replication r = new Replication(1, List.of("r1"), t);
        r.replicate("key1", "SET key1 v1");
        r.replicate("key2", "SET key2 v2");
        r.replicate("key3", "SET key3 v3");
        assertThat(t.getSent()).hasSize(3);
    }

    @Test
    void replicationLog_maintainsOrder() {
        Replication.FakeReplicationTransport t = new Replication.FakeReplicationTransport();
        Replication r = new Replication(1, List.of("r1"), t);
        r.replicate("k1", "CMD1");
        r.replicate("k2", "CMD2");
        r.replicate("k3", "CMD3");

        List<Replication.ReplicationEntry> log = r.getReplicationLog();
        assertThat(log).hasSize(3);
        assertThat(log.get(0).sequenceNum()).isLessThan(log.get(1).sequenceNum());
        assertThat(log.get(1).sequenceNum()).isLessThan(log.get(2).sequenceNum());
        assertThat(log.get(0).command()).isEqualTo("CMD1");
        assertThat(log.get(2).command()).isEqualTo("CMD3");
    }

    @Test
    void outOfOrderAck_handledCorrectly() {
        Replication.FakeReplicationTransport noAck = new Replication.FakeReplicationTransport() {
            @Override
            public CompletableFuture<Boolean> sendToReplica(String replicaId, Replication.ReplicationEntry entry) {
                return CompletableFuture.completedFuture(false);
            }
        };
        Replication r = new Replication(1, List.of("r1"), noAck);
        r.replicate("k1", "CMD1");
        r.replicate("k2", "CMD2");
        r.replicate("k3", "CMD3");

        // Ack out of order: 3, 1, 2
        r.ack("r1", 3L);
        r.ack("r1", 1L);
        r.ack("r1", 2L);

        assertThat(r.getPendingCount()).isEqualTo(0);
        assertThat(r.getReplicaLag("r1")).isEqualTo(0L);
    }
}
