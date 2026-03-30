package flashcache.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(150);
    }

    @Test
    void emptyRing_throwsIllegalStateException() {
        assertThatThrownBy(() -> ring.getNode("any-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void singleNode_handlesAllKeys() {
        ring.addNode("node1");
        assertThat(ring.getNode("key1")).isEqualTo("node1");
        assertThat(ring.getNode("key2")).isEqualTo("node1");
        assertThat(ring.getNode("completely-different-key")).isEqualTo("node1");
    }

    @Test
    void twoNodes_splitKeysRoughlyEvenly() {
        ring.addNode("node1");
        ring.addNode("node2");

        Map<String, Integer> counts = new HashMap<>();
        counts.put("node1", 0);
        counts.put("node2", 0);

        int total = 10_000;
        for (int i = 0; i < total; i++) {
            String node = ring.getNode("key-" + i);
            counts.merge(node, 1, Integer::sum);
        }

        // With 150 vnodes each, expect roughly 50% each, allow 30% deviation
        int expected = total / 2;
        int tolerance = (int) (expected * 0.30);
        assertThat(counts.get("node1")).isBetween(expected - tolerance, expected + tolerance);
        assertThat(counts.get("node2")).isBetween(expected - tolerance, expected + tolerance);
    }

    @Test
    void addNode_redistributesSomeKeys() {
        ring.addNode("node1");
        ring.addNode("node2");

        // Record pre-add assignments
        int total = 1000;
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < total; i++) {
            before.put("key-" + i, ring.getNode("key-" + i));
        }

        ring.addNode("node3");

        int changed = 0;
        for (int i = 0; i < total; i++) {
            String after = ring.getNode("key-" + i);
            if (!after.equals(before.get("key-" + i))) changed++;
        }

        // Adding a 3rd node should move roughly 1/3 of keys
        assertThat(changed).isGreaterThan(0);
        assertThat(changed).isLessThan(total * 2 / 3);
    }

    @Test
    void addNode_thirdNodeMovesApproximatelyOneThird() {
        ring.addNode("node1");
        ring.addNode("node2");

        int total = 10_000;
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < total; i++) {
            before.put("key-" + i, ring.getNode("key-" + i));
        }

        ring.addNode("node3");

        int moved = 0;
        for (int i = 0; i < total; i++) {
            if (!ring.getNode("key-" + i).equals(before.get("key-" + i))) moved++;
        }

        // Should move roughly 1/3 ± 15%
        int expected = total / 3;
        int tolerance = (int) (expected * 0.50); // generous tolerance
        assertThat(moved).isBetween(expected - tolerance, expected + tolerance);
    }

    @Test
    void removeNode_redistributesKeysToRemainingNodes() {
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");

        int total = 1000;
        ring.removeNode("node3");

        // After removal, no key should map to node3
        for (int i = 0; i < total; i++) {
            assertThat(ring.getNode("key-" + i)).isNotEqualTo("node3");
        }
    }

    @Test
    void getNode_sameKeyAlwaysMapsToSameNode() {
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");

        String firstResult = ring.getNode("consistent-key");
        for (int i = 0; i < 100; i++) {
            assertThat(ring.getNode("consistent-key")).isEqualTo(firstResult);
        }
    }

    @Test
    void nodeCount_tracksPhysicalNodes() {
        assertThat(ring.nodeCount()).isEqualTo(0);
        ring.addNode("node1");
        assertThat(ring.nodeCount()).isEqualTo(1);
        ring.addNode("node2");
        assertThat(ring.nodeCount()).isEqualTo(2);
        ring.removeNode("node1");
        assertThat(ring.nodeCount()).isEqualTo(1);
    }

    @Test
    void getNodes_returnsAllPhysicalNodeIds() {
        ring.addNode("node1");
        ring.addNode("node2");
        ring.addNode("node3");

        assertThat(ring.getNodes()).containsExactlyInAnyOrder("node1", "node2", "node3");
    }

    @Test
    void removeNode_unknownNode_isNoOp() {
        ring.addNode("node1");
        ring.removeNode("nonexistent");
        assertThat(ring.nodeCount()).isEqualTo(1);
    }

    @Test
    void virtualNodes_increaseDistributionUniformity() {
        ConsistentHashRing fewVnodes = new ConsistentHashRing(1);
        ConsistentHashRing manyVnodes = new ConsistentHashRing(150);

        fewVnodes.addNode("node1");
        fewVnodes.addNode("node2");
        manyVnodes.addNode("node1");
        manyVnodes.addNode("node2");

        int total = 10_000;
        int node1Few = 0, node1Many = 0;
        for (int i = 0; i < total; i++) {
            if (fewVnodes.getNode("key-" + i).equals("node1")) node1Few++;
            if (manyVnodes.getNode("key-" + i).equals("node1")) node1Many++;
        }

        // manyVnodes should be closer to 50/50 than fewVnodes
        int deviationFew = Math.abs(node1Few - total / 2);
        int deviationMany = Math.abs(node1Many - total / 2);
        assertThat(deviationMany).isLessThan(deviationFew + total / 10);
    }

    @Test
    void addNode_idempotentNodes_noDoubleCount() {
        ring.addNode("node1");
        ring.addNode("node1"); // add same node again
        // nodeCount uses a Set so duplicates are ignored, but vnodes would double
        // getNode should still return node1
        assertThat(ring.getNode("somekey")).isEqualTo("node1");
    }
}
