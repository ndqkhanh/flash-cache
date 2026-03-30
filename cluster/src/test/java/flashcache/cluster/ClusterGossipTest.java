package flashcache.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterGossipTest {

    private ClusterGossip gossip;
    private FakeTransport transport;

    static class FakeTransport implements ClusterGossip.GossipTransport {
        private final ConcurrentHashMap<String, Boolean> results = new ConcurrentHashMap<>();

        void setReachable(String host, int port, boolean reachable) {
            results.put(host + ":" + port, reachable);
        }

        @Override
        public boolean ping(String host, int port) {
            return results.getOrDefault(host + ":" + port, true);
        }
    }

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        gossip = new ClusterGossip("local-node", transport);
    }

    @Test
    void addPeer_registersNodeAsAlive() {
        gossip.addPeer("node1", "host1", 7000);
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.ALIVE);
    }

    @Test
    void probeNode_reachablePeer_returnsTrue() {
        gossip.addPeer("node1", "host1", 7000);
        transport.setReachable("host1", 7000, true);
        assertThat(gossip.probeNode("node1")).isTrue();
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.ALIVE);
    }

    @Test
    void probeNode_unreachablePeer_returnsFalseAndMarksSuspect() {
        gossip.addPeer("node1", "host1", 7000);
        transport.setReachable("host1", 7000, false);
        assertThat(gossip.probeNode("node1")).isFalse();
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.SUSPECT);
    }

    @Test
    void expireSuspects_transitionsSuspectToDeadAfterTimeout() {
        gossip.addPeer("node1", "host1", 7000);
        gossip.suspectNode("node1");

        Instant suspectedAt = gossip.getMember("node1").lastUpdated();
        Instant later = suspectedAt.plus(Duration.ofSeconds(10));

        gossip.expireSuspects(later, Duration.ofSeconds(5));
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.DEAD);
    }

    @Test
    void expireSuspects_doesNotExpireBeforeTimeout() {
        gossip.addPeer("node1", "host1", 7000);
        gossip.suspectNode("node1");

        Instant suspectedAt = gossip.getMember("node1").lastUpdated();
        Instant tooSoon = suspectedAt.plus(Duration.ofSeconds(1));

        gossip.expireSuspects(tooSoon, Duration.ofSeconds(5));
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.SUSPECT);
    }

    @Test
    void mergeState_higherIncarnationWins() {
        gossip.addPeer("node1", "host1", 7000);
        // local has incarnation 0
        ClusterGossip.MemberInfo remote = new ClusterGossip.MemberInfo(
                "node1", "host1", 7000, ClusterGossip.NodeState.SUSPECT, 5L, Instant.now()
        );
        gossip.mergeState("node1", remote);
        assertThat(gossip.getMember("node1").incarnation()).isEqualTo(5L);
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.SUSPECT);
    }

    @Test
    void mergeState_sameIncarnation_moreSevereStateWins() {
        gossip.addPeer("node1", "host1", 7000);
        // existing is ALIVE inc=3, incoming is SUSPECT inc=3 → SUSPECT should win
        ClusterGossip.MemberInfo existing = new ClusterGossip.MemberInfo(
                "node1", "host1", 7000, ClusterGossip.NodeState.ALIVE, 3L, Instant.now()
        );
        gossip.mergeState("node1", existing);

        ClusterGossip.MemberInfo incoming = new ClusterGossip.MemberInfo(
                "node1", "host1", 7000, ClusterGossip.NodeState.SUSPECT, 3L, Instant.now()
        );
        gossip.mergeState("node1", incoming);
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.SUSPECT);
    }

    @Test
    void mergeState_lowerIncarnation_doesNotOverwrite() {
        gossip.addPeer("node1", "host1", 7000);
        ClusterGossip.MemberInfo high = new ClusterGossip.MemberInfo(
                "node1", "host1", 7000, ClusterGossip.NodeState.ALIVE, 10L, Instant.now()
        );
        gossip.mergeState("node1", high);

        ClusterGossip.MemberInfo low = new ClusterGossip.MemberInfo(
                "node1", "host1", 7000, ClusterGossip.NodeState.DEAD, 2L, Instant.now()
        );
        gossip.mergeState("node1", low);
        assertThat(gossip.getMember("node1").incarnation()).isEqualTo(10L);
        assertThat(gossip.getMember("node1").state()).isEqualTo(ClusterGossip.NodeState.ALIVE);
    }

    @Test
    void selfRefutation_bumpsIncarnationWhenSeenAsSuspect() {
        // local node registers itself via addPeer
        gossip.addPeer("local-node", "localhost", 7000);

        ClusterGossip.MemberInfo suspectSelf = new ClusterGossip.MemberInfo(
                "local-node", "localhost", 7000, ClusterGossip.NodeState.SUSPECT, 1L, Instant.now()
        );
        gossip.mergeState("local-node", suspectSelf);

        // Should bump incarnation and stay ALIVE
        assertThat(gossip.getMember("local-node").state()).isEqualTo(ClusterGossip.NodeState.ALIVE);
        assertThat(gossip.getMember("local-node").incarnation()).isGreaterThan(1L);
    }

    @Test
    void getAliveNodes_excludesSuspectAndDead() {
        gossip.addPeer("node1", "host1", 7000);
        gossip.addPeer("node2", "host2", 7001);
        gossip.addPeer("node3", "host3", 7002);

        gossip.suspectNode("node2");
        transport.setReachable("host3", 7002, false);
        gossip.probeNode("node3"); // marks node3 as SUSPECT too
        gossip.expireSuspects(Instant.now().plus(Duration.ofMinutes(1)), Duration.ofSeconds(1));

        assertThat(gossip.getAliveNodes()).containsExactly("node1");
    }

    @Test
    void gossipRound_probesRandomAlivePeer() {
        gossip.addPeer("node1", "host1", 7000);
        gossip.addPeer("node2", "host2", 7001);
        transport.setReachable("host1", 7000, true);
        transport.setReachable("host2", 7001, true);

        // Should not throw, and all peers should remain alive
        gossip.gossipRound();
        assertThat(gossip.getAliveNodes()).contains("node1", "node2");
    }

    @Test
    void memberCount_tracksAllKnownMembers() {
        assertThat(gossip.memberCount()).isEqualTo(0);
        gossip.addPeer("node1", "host1", 7000);
        gossip.addPeer("node2", "host2", 7001);
        assertThat(gossip.memberCount()).isEqualTo(2);
    }
}
