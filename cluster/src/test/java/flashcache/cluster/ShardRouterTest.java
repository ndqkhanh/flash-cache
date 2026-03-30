package flashcache.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShardRouterTest {

    private ConsistentHashRing ring;
    private ShardRouter router;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(150);
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");
        router = new ShardRouter(ring, "node1");
    }

    @Test
    void getOwner_returnsConsistentNodeForSameKey() {
        String owner1 = router.getOwner("stable-key");
        String owner2 = router.getOwner("stable-key");
        assertThat(owner1).isEqualTo(owner2);
    }

    @Test
    void isLocal_returnsTrueForLocallyOwnedKey() {
        // Find a key that node1 owns
        String localKey = findKeyOwnedBy("node1");
        router = new ShardRouter(ring, "node1");
        assertThat(router.isLocal(localKey)).isTrue();
    }

    @Test
    void isLocal_returnsFalseForRemoteKey() {
        // Find a key that node2 owns
        String remoteKey = findKeyOwnedBy("node2");
        router = new ShardRouter(ring, "node1");
        assertThat(router.isLocal(remoteKey)).isFalse();
    }

    @Test
    void routeCommand_localKey_returnsLocalDecision() {
        String localKey = findKeyOwnedBy("node1");
        router = new ShardRouter(ring, "node1");
        ShardRouter.RouteDecision decision = router.routeCommand(localKey, "GET");
        assertThat(decision.type()).isEqualTo(ShardRouter.RouteType.LOCAL);
        assertThat(decision.targetNodeId()).isEqualTo("node1");
    }

    @Test
    void routeCommand_remoteKey_returnsForwardWithCorrectTarget() {
        String remoteKey = findKeyOwnedBy("node2");
        router = new ShardRouter(ring, "node1");
        ShardRouter.RouteDecision decision = router.routeCommand(remoteKey, "GET");
        assertThat(decision.type()).isEqualTo(ShardRouter.RouteType.FORWARD);
        assertThat(decision.targetNodeId()).isEqualTo("node2");
    }

    @Test
    void getReplicaNodes_returnsCorrectCountOfDistinctNodes() {
        List<String> replicas = router.getReplicaNodes("some-key", 2);
        assertThat(replicas).hasSize(2);
        assertThat(replicas).doesNotHaveDuplicates();
    }

    @Test
    void getReplicaNodes_replicaCountGreaterThanNodeCount_returnsAllNodes() {
        List<String> replicas = router.getReplicaNodes("some-key", 10);
        // Only 3 nodes in ring
        assertThat(replicas).hasSize(3);
        assertThat(replicas).doesNotHaveDuplicates();
    }

    @Test
    void singleNodeCluster_allKeysAreLocal() {
        ConsistentHashRing singleRing = new ConsistentHashRing(150);
        singleRing.addNode("only-node");
        ShardRouter singleRouter = new ShardRouter(singleRing, "only-node");

        for (int i = 0; i < 50; i++) {
            assertThat(singleRouter.isLocal("key-" + i)).isTrue();
            ShardRouter.RouteDecision decision = singleRouter.routeCommand("key-" + i, "GET");
            assertThat(decision.type()).isEqualTo(ShardRouter.RouteType.LOCAL);
        }
    }

    @Test
    void afterNodeAdded_someKeysReroute() {
        ConsistentHashRing twoNodeRing = new ConsistentHashRing(150);
        twoNodeRing.addNode("node1");
        twoNodeRing.addNode("node2");
        ShardRouter r = new ShardRouter(twoNodeRing, "node1");

        int total = 1000;
        int localBefore = 0;
        for (int i = 0; i < total; i++) {
            if (r.isLocal("key-" + i)) localBefore++;
        }

        twoNodeRing.addNode("node3");

        int localAfter = 0;
        for (int i = 0; i < total; i++) {
            if (r.isLocal("key-" + i)) localAfter++;
        }

        // Adding node3 should reduce keys owned by node1
        assertThat(localAfter).isLessThan(localBefore);
    }

    // --- helper ---

    private String findKeyOwnedBy(String targetNode) {
        for (int i = 0; i < 10_000; i++) {
            String key = "search-key-" + i;
            if (ring.getNode(key).equals(targetNode)) {
                return key;
            }
        }
        throw new IllegalStateException("Could not find key owned by " + targetNode);
    }
}
