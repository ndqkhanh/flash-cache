package flashcache.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

public class ShardRouter {

    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

    public enum RouteType { LOCAL, FORWARD, REDIRECT }

    public record RouteDecision(RouteType type, String targetNodeId) {}

    private final ConsistentHashRing ring;
    private final String localNodeId;

    public ShardRouter(ConsistentHashRing ring, String localNodeId) {
        this.ring = ring;
        this.localNodeId = localNodeId;
    }

    public boolean isLocal(String key) {
        return localNodeId.equals(ring.getNode(key));
    }

    public String getOwner(String key) {
        return ring.getNode(key);
    }

    public List<String> getReplicaNodes(String key, int replicaCount) {
        // Walk the ring clockwise from the primary, collecting distinct physical nodes
        Set<String> nodes = ring.getNodes();
        int needed = Math.min(replicaCount, nodes.size());

        // We need internal ring access — use reflection-free approach via ring API
        // Primary first, then walk the ring map clockwise
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String primary = ring.getNode(key);
        result.add(primary);

        if (needed <= 1) {
            return new ArrayList<>(result);
        }

        // Access the ring's internal sorted map via package-private helper
        // Since ring is in the same package, we rely on the package-level visibility
        for (Map.Entry<Integer, String> entry : ring.ringEntries()) {
            if (result.size() >= needed) break;
            result.add(entry.getValue());
        }

        // If we still need more and wrapped around
        if (result.size() < needed) {
            result.addAll(nodes);
        }

        return new ArrayList<>(result).subList(0, needed);
    }

    public RouteDecision routeCommand(String key, String command) {
        String owner = getOwner(key);
        if (localNodeId.equals(owner)) {
            log.debug("routeCommand key={} -> LOCAL", key);
            return new RouteDecision(RouteType.LOCAL, localNodeId);
        }
        log.debug("routeCommand key={} -> FORWARD to {}", key, owner);
        return new RouteDecision(RouteType.FORWARD, owner);
    }
}
