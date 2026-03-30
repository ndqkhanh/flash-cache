package flashcache.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClusterGossip {

    private static final Logger log = LoggerFactory.getLogger(ClusterGossip.class);

    public enum NodeState {
        ALIVE, SUSPECT, DEAD
    }

    public record MemberInfo(
            String nodeId,
            String host,
            int port,
            NodeState state,
            long incarnation,
            Instant lastUpdated
    ) {
        MemberInfo withState(NodeState newState, Instant now) {
            return new MemberInfo(nodeId, host, port, newState, incarnation, now);
        }

        MemberInfo withIncarnation(long newIncarnation, NodeState newState, Instant now) {
            return new MemberInfo(nodeId, host, port, newState, newIncarnation, now);
        }
    }

    public interface GossipTransport {
        boolean ping(String host, int port);
    }

    private final String localNodeId;
    private final GossipTransport transport;
    private final ConcurrentHashMap<String, MemberInfo> members = new ConcurrentHashMap<>();

    public ClusterGossip(String localNodeId, GossipTransport transport) {
        this.localNodeId = localNodeId;
        this.transport = transport;
    }

    public void addPeer(String nodeId, String host, int port) {
        MemberInfo info = new MemberInfo(nodeId, host, port, NodeState.ALIVE, 0L, Instant.now());
        members.put(nodeId, info);
        log.debug("addPeer nodeId={} host={} port={}", nodeId, host, port);
    }

    public boolean probeNode(String nodeId) {
        MemberInfo info = members.get(nodeId);
        if (info == null) return false;
        boolean reachable = transport.ping(info.host(), info.port());
        if (!reachable) {
            suspectNode(nodeId);
        }
        log.debug("probeNode nodeId={} reachable={}", nodeId, reachable);
        return reachable;
    }

    public void suspectNode(String nodeId) {
        members.computeIfPresent(nodeId, (id, info) -> {
            if (info.state() == NodeState.ALIVE) {
                log.debug("suspectNode nodeId={}", nodeId);
                return info.withState(NodeState.SUSPECT, Instant.now());
            }
            return info;
        });
    }

    public void expireSuspects(Instant now, Duration timeout) {
        members.replaceAll((id, info) -> {
            if (info.state() == NodeState.SUSPECT) {
                Duration elapsed = Duration.between(info.lastUpdated(), now);
                if (elapsed.compareTo(timeout) >= 0) {
                    log.debug("expireSuspect nodeId={}", id);
                    return info.withState(NodeState.DEAD, now);
                }
            }
            return info;
        });
    }

    public void mergeState(String nodeId, MemberInfo remote) {
        members.merge(nodeId, remote, (local, incoming) -> {
            // Self-refutation: if we see ourselves as SUSPECT, bump incarnation
            if (nodeId.equals(localNodeId) && incoming.state() == NodeState.SUSPECT) {
                long newIncarnation = Math.max(local.incarnation(), incoming.incarnation()) + 1;
                log.debug("self-refutation bumping incarnation to {}", newIncarnation);
                return local.withIncarnation(newIncarnation, NodeState.ALIVE, Instant.now());
            }
            // Higher incarnation wins
            if (incoming.incarnation() > local.incarnation()) {
                return incoming;
            }
            // Same incarnation: more severe state wins (DEAD > SUSPECT > ALIVE)
            if (incoming.incarnation() == local.incarnation()) {
                if (stateSeverity(incoming.state()) > stateSeverity(local.state())) {
                    return incoming;
                }
            }
            return local;
        });
    }

    public Set<String> getAliveNodes() {
        return members.values().stream()
                .filter(m -> m.state() == NodeState.ALIVE)
                .map(MemberInfo::nodeId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public int memberCount() {
        return members.size();
    }

    /** Perform one gossip round: exchange state with a random alive peer. */
    public void gossipRound() {
        List<MemberInfo> aliveList = new ArrayList<>(
                members.values().stream()
                        .filter(m -> m.state() == NodeState.ALIVE && !m.nodeId().equals(localNodeId))
                        .toList()
        );
        if (aliveList.isEmpty()) return;
        Collections.shuffle(aliveList);
        MemberInfo peer = aliveList.get(0);
        log.debug("gossipRound with peer={}", peer.nodeId());
        // In real impl we'd send our full member table; here we probe as a proxy
        probeNode(peer.nodeId());
    }

    public MemberInfo getMember(String nodeId) {
        return members.get(nodeId);
    }

    // --- internal ---

    private static int stateSeverity(NodeState state) {
        return switch (state) {
            case ALIVE -> 0;
            case SUSPECT -> 1;
            case DEAD -> 2;
        };
    }
}
