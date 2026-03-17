package io.github.seanchatmangpt.jotp.cluster;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Cluster membership and node monitoring — Erlang's distributed clustering for JOTP.
 *
 * <p>Joe Armstrong: "Let It Crash. Everything is distributed. This implies that supervisors are
 * distributed, and we need to know about other nodes in the cluster to supervise them."
 *
 * <p>This interface provides:
 *
 * <ul>
 *   <li><strong>Node Registration:</strong> Register/deregister nodes with metadata (capacity,
 *       region, tags)
 *   <li><strong>Health Monitoring:</strong> Track node liveness via heartbeats; automatic timeout
 *   <li><strong>Leader Election:</strong> Single leader across cluster using quorum
 *   <li><strong>Partition Detection:</strong> Split-brain prevention via minority partition
 *       detection
 *   <li><strong>Shutdown Coordination:</strong> Graceful handoff and cleanup
 * </ul>
 *
 * <p><strong>Mapping to Erlang/OTP:</strong>
 *
 * <pre>{@code
 * Erlang                              JOTP ClusterManager
 * ─────────────────────────────────   ─────────────────────────────
 * erlang:nodes()                   → getAliveNodes()
 * erlang:node()                    → current node name (application config)
 * net_kernel:connect_node/1        → registerNode() + heartbeat
 * erlang:monitor_node/2            → watchNodeChanges()
 * pg:get_members(Group)            → getAliveNodes() (filtered by metadata)
 * rpc:call(Node, Module, Func, Args) → send message via Proc + await response
 * }</pre>
 *
 * <p><strong>Node Lifecycle:</strong>
 *
 * <pre>{@code
 * 1. Boot: registerNode(name, port, metadata)
 * 2. Monitor: watchNodeChanges() to track UP/DOWN events
 * 3. Heartbeat: automatic heartbeat every heartbeatInterval
 * 4. Timeout: node removed if heartbeat not received within heartbeatTimeout
 * 5. Shutdown: deregisterNode() triggers graceful shutdown handlers
 * }</pre>
 */
public interface ClusterManager extends AutoCloseable {

  /**
   * Register this node in the cluster.
   *
   * @param nodeName unique name (e.g., "node1@192.168.1.1")
   * @param port RPC port for inter-node communication
   * @param metadata custom attributes (e.g., capacity, region, tags, role)
   */
  void registerNode(String nodeName, int port, Map<String, String> metadata);

  /**
   * Deregister this node from the cluster.
   *
   * <p>Triggers graceful shutdown: waiting processes receive shutdown signal, remaining messages
   * processed before exit.
   *
   * @param nodeName node to deregister
   */
  void deregisterNode(String nodeName);

  /**
   * Get all alive nodes in the cluster.
   *
   * @return immutable set of node names
   */
  Set<String> getAliveNodes();

  /**
   * Get nodes matching a filter criteria.
   *
   * @param metadataKey metadata key to filter on
   * @param value expected metadata value
   * @return immutable set of matching node names
   */
  Set<String> getNodesByMetadata(String metadataKey, String value);

  /**
   * Get the current cluster leader.
   *
   * <p>Leader election is via bully algorithm or Redis/PostgreSQL atomic primitives. In a
   * partition, the minority partition has no leader. Returns empty if this node is partitioned.
   *
   * @return leader node name, or empty if no leader elected
   */
  Optional<String> getLeader();

  /**
   * Check if a node is alive (heartbeat received within timeout).
   *
   * @param nodeName node to check
   * @return true if heartbeat received within heartbeatTimeout
   */
  boolean isNodeAlive(String nodeName);

  /**
   * Get metadata for a node.
   *
   * @param nodeName node to query
   * @return immutable map of metadata key-values
   */
  Map<String, String> getNodeMetadata(String nodeName);

  /**
   * Watch cluster membership changes (node UP/DOWN events).
   *
   * <p>Listener is called asynchronously when:
   *
   * <ul>
   *   <li>A node comes online (heartbeat starts)
   *   <li>A node goes offline (heartbeat timeout)
   *   <li>Leader changes
   * </ul>
   *
   * <p>Listener exceptions are caught and logged; they do not stop the cluster manager.
   *
   * @param listener receives {@link NodeEvent} when cluster changes
   */
  void watchNodeChanges(Consumer<NodeEvent> listener);

  /**
   * Check if this node is partitioned from the majority.
   *
   * <p>Returns true if:
   *
   * <ul>
   *   <li>Heartbeat timeout exceeded with majority of nodes
   *   <li>Cannot reach more than N/2 other nodes
   * </ul>
   *
   * @return true if in a minority partition
   */
  boolean isPartitioned();

  /**
   * Get current cluster size (including this node).
   *
   * @return number of registered nodes (alive or dead)
   */
  int getClusterSize();

  /**
   * Get number of alive nodes.
   *
   * @return count of nodes with recent heartbeat
   */
  int getAliveCount();

  /**
   * Cluster node event — UP, DOWN, or LEADER_CHANGED.
   *
   * <p>Immutable snapshot at the moment the event occurred.
   */
  sealed interface NodeEvent permits NodeUp, NodeDown, LeaderChanged {
    String nodeName();

    long timestamp();
  }

  /** Node came online. */
  record NodeUp(String nodeName, long timestamp, Map<String, String> metadata)
      implements NodeEvent {}

  /** Node went offline (heartbeat timeout). */
  record NodeDown(String nodeName, long timestamp) implements NodeEvent {}

  /** Leader election result changed. */
  record LeaderChanged(String nodeName, long timestamp, Optional<String> leader)
      implements NodeEvent {}
}
