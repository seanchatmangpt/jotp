package io.github.seanchatmangpt.jotp.distributed;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link NodeDiscoveryBackend} for single-node testing.
 *
 * <p>Stores node information in a {@link ConcurrentHashMap}. This backend provides no persistence —
 * all data is lost on JVM shutdown. It is intended for:
 *
 * <ul>
 *   <li>Unit testing of node discovery logic
 *   <li>Single-node development setups
 *   <li>Demonstrations and examples
 * </ul>
 *
 * <p><strong>Not for production use:</strong> This backend provides no durability or cross-node
 * coordination. For production deployments, use {@link RocksDBNodeDiscoveryBackend} or a
 * distributed backend (Consul, Etcd, etc.).
 *
 * <p><strong>Thread Safety:</strong> This implementation is thread-safe via {@link
 * ConcurrentHashMap}. All operations are atomic and non-blocking.
 *
 * @see NodeDiscoveryBackend
 * @see RocksDBNodeDiscoveryBackend
 */
public final class InMemoryNodeDiscoveryBackend implements NodeDiscoveryBackend {

    private final ConcurrentHashMap<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    /** Creates a new in-memory backend with an empty node registry. */
    public InMemoryNodeDiscoveryBackend() {}

    @Override
    public void storeNode(NodeInfo nodeInfo) {
        if (nodeInfo == null) {
            throw new NullPointerException("nodeInfo must not be null");
        }
        nodes.put(nodeInfo.nodeName(), nodeInfo);
    }

    @Override
    public Optional<NodeInfo> getNode(String nodeName) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }
        return Optional.ofNullable(nodes.get(nodeName));
    }

    @Override
    public List<NodeInfo> listNodes() {
        return List.copyOf(nodes.values());
    }

    @Override
    public void updateHeartbeat(String nodeName, Instant timestamp) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }
        if (timestamp == null) {
            throw new NullPointerException("timestamp must not be null");
        }

        NodeInfo current = nodes.get(nodeName);
        if (current == null) {
            throw new IllegalArgumentException("Node not found: " + nodeName);
        }

        // Create updated node info with new heartbeat
        NodeInfo updated = current.withHeartbeat(timestamp);
        nodes.put(nodeName, updated);
    }

    @Override
    public void removeNode(String nodeName) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }
        nodes.remove(nodeName);
    }

    @Override
    public List<NodeInfo> findStaleNodes(Instant threshold) {
        if (threshold == null) {
            throw new NullPointerException("threshold must not be null");
        }

        return nodes.values().stream()
                .filter(node -> node.lastHeartbeat().isBefore(threshold))
                .filter(node -> node.status() != NodeInfo.NodeStatus.DOWN)
                .toList();
    }

    /**
     * Clear all nodes from the registry.
     *
     * <p>Primarily for testing. Resets the backend to its initial empty state.
     */
    public void clear() {
        nodes.clear();
    }

    /**
     * Get the number of nodes currently stored.
     *
     * <p>Useful for testing and monitoring.
     *
     * @return the node count
     */
    public int size() {
        return nodes.size();
    }
}
