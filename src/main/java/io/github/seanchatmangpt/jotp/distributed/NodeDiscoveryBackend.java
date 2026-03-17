package io.github.seanchatmangpt.jotp.distributed;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Interface for pluggable storage backends for node discovery.
 *
 * <p>Abstracts the storage mechanism for cluster membership information. Different backends can
 * provide varying levels of persistence, consistency, and scalability:
 *
 * <ul>
 *   <li>{@link InMemoryNodeDiscoveryBackend} - Single-node testing (default)
 *   <li>{@link RocksDBNodeDiscoveryBackend} - Persistent local storage
 *   <li>Consul, Etcd, Redis - Distributed coordination (future)
 * </ul>
 *
 * <p><strong>Idempotence Requirements:</strong> Backends must support atomic writes for
 * consistency. The combination of node info and heartbeat ACK must be written atomically to detect
 * partial writes after JVM crashes.
 *
 * <p><strong>Thread Safety:</strong> All implementations must be thread-safe. Methods may be called
 * concurrently from health check threads and node registration.
 *
 * @see NodeInfo
 * @see StaticNodeDiscovery
 */
public interface NodeDiscoveryBackend {

    /**
     * Store node information in the backend.
     *
     * <p>Creates or updates the node registration. If the node already exists, its information is
     * overwritten with the new data. Implementations should atomically write both the node info and
     * the heartbeat ACK to ensure consistency after crashes.
     *
     * @param nodeInfo the node information to store (must not be null)
     * @throws NullPointerException if nodeInfo is null
     */
    void storeNode(NodeInfo nodeInfo);

    /**
     * Get node information by name.
     *
     * <p>Returns the most recent node registration for the given name. If the node is not found,
     * returns {@link Optional#empty()}.
     *
     * @param nodeName the name of the node to retrieve (must not be null)
     * @return the node information, or empty if not found
     * @throws NullPointerException if nodeName is null
     */
    Optional<NodeInfo> getNode(String nodeName);

    /**
     * List all registered nodes.
     *
     * <p>Returns a snapshot of all nodes currently stored in the backend. The list includes nodes
     * in all states (HEALTHY, DEGRADED, DOWN). The returned list is not live — changes after this
     * call will not be reflected.
     *
     * @return list of all registered nodes (empty list if none)
     */
    List<NodeInfo> listNodes();

    /**
     * Update node heartbeat timestamp.
     *
     * <p>Updates the {@link NodeInfo#lastHeartbeat()} field to the current time. This is called
     * periodically by health check mechanisms to indicate the node is still alive. Implementations
     * should persist this update atomically with the node info to support crash recovery.
     *
     * @param nodeName the name of the node to update (must not be null)
     * @param timestamp the new heartbeat timestamp (must not be null)
     * @throws NullPointerException if nodeName or timestamp is null
     * @throws IllegalArgumentException if the node does not exist
     */
    void updateHeartbeat(String nodeName, Instant timestamp);

    /**
     * Remove a node from the registry.
     *
     * <p>Permanently removes the node registration from the backend. This is typically called when
     * a node is intentionally decommissioned rather than crashing. After removal, {@link
     * #getNode(String)} will return empty for this node.
     *
     * <p>Safe to call even if the node does not exist — implementations should ignore missing
     * nodes.
     *
     * @param nodeName the name of the node to remove (must not be null)
     * @throws NullPointerException if nodeName is null
     */
    void removeNode(String nodeName);

    /**
     * Find nodes with expired heartbeats (potential failures).
     *
     * <p>Returns all nodes whose {@link NodeInfo#lastHeartbeat()} is older than the given
     * threshold. These nodes are candidates for being marked as DOWN. The health check system uses
     * this to trigger failover.
     *
     * <p>Nodes with {@link NodeInfo.NodeStatus#DOWN} status are typically excluded even if their
     * heartbeats are stale, since they're already marked as failed.
     *
     * @param threshold the cutoff time — nodes with heartbeats older than this are returned (must
     *     not be null)
     * @return list of nodes with stale heartbeats (empty list if none)
     * @throws NullPointerException if threshold is null
     */
    List<NodeInfo> findStaleNodes(Instant threshold);
}
