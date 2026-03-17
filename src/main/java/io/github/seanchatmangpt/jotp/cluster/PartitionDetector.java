package io.github.seanchatmangpt.jotp.cluster;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Network partition detection and split-brain prevention.
 *
 * <p>Joe Armstrong: "In a distributed system, when nodes can't communicate, you have a
 * partition. The minority partition must not continue operating — only the majority partition
 * can safely make decisions."
 *
 * <p>This interface detects:
 *
 * <ul>
 *   <li><strong>Split-Brain:</strong> Cluster split into isolated components
 *   <li><strong>Minority Partition:</strong> This node is in a minority partition (< N/2 nodes)
 *   <li><strong>Quorum Loss:</strong> Cannot reach quorum (replication factor N, need N/2+1)
 * </ul>
 *
 * <p><strong>Partition Healing:</strong>
 *
 * <pre>{@code
 * Before partition:  [A, B, C, D, E] (leader: A)
 * Partition occurs:  [A, B] vs [C, D, E]
 *
 * [A, B]: Size 2/5, minority → no leader, reads/writes blocked
 * [C, D, E]: Size 3/5, majority → elect new leader, reads/writes allowed
 *
 * Partition heals:   [A, B, C, D, E]
 * Healing listener triggered, cluster resumes with new leader
 * }</pre>
 *
 * <p><strong>Decision Rules:</strong>
 *
 * <ul>
 *   <li>Quorum = ceiling(N/2) + 1 nodes must be alive
 *   <li>Minority partition = alive nodes < quorum
 *   <li>Only majority partition can elect leader and serve requests
 * </ul>
 */
public interface PartitionDetector extends AutoCloseable {

  /**
   * Detect if this node is in a minority partition.
   *
   * <p>A node is in a minority partition if:
   *
   * <ul>
   *   <li>Total alive nodes < ceiling(clusterSize / 2) + 1
   *   <li>Cannot reach majority of original cluster members
   * </ul>
   *
   * @return true if in minority partition
   */
  boolean isMinorityPartition();

  /**
   * Get all reachable nodes from this node (excluding this node itself).
   *
   * @return set of reachable node names
   */
  Set<String> getReachableNodes();

  /**
   * Get all unreachable nodes (heartbeat timeout).
   *
   * @return set of unreachable node names
   */
  Set<String> getUnreachableNodes();

  /**
   * Check if a specific node is reachable.
   *
   * @param nodeName node to check
   * @return true if heartbeat received recently
   */
  boolean isReachable(String nodeName);

  /**
   * Get required quorum size (majority needed for safe decisions).
   *
   * <p>Formula: floor(clusterSize / 2) + 1
   *
   * <p>Example: 5-node cluster needs 3 nodes; 4-node cluster needs 3 nodes.
   *
   * @return quorum size
   */
  int getQuorumSize();

  /**
   * Check if this node can safely make decisions (has quorum).
   *
   * @return true if alive nodes >= quorum size
   */
  boolean hasQuorum();

  /**
   * Watch for partition events (partition detected, healed, etc).
   *
   * <p>Listener is called asynchronously when:
   *
   * <ul>
   *   <li>This node enters a minority partition (hasQuorum() becomes false)
   *   <li>This node recovers from minority partition (hasQuorum() becomes true)
   *   <li>A node becomes unreachable
   *   <li>A previously unreachable node recovers
   * </ul>
   *
   * @param listener receives {@link PartitionEvent}
   */
  void watchPartitionChanges(Consumer<PartitionEvent> listener);

  /**
   * Get the last partition event (for diagnostics).
   *
   * @return most recent partition change, or empty if no events
   */
  java.util.Optional<PartitionEvent> getLastPartitionEvent();

  /** Partition detection event. */
  sealed interface PartitionEvent permits PartitionLost, PartitionRecovered, NodeUnreachable {
    long timestamp();
  }

  /** This node lost quorum and entered minority partition. */
  record PartitionLost(long timestamp, int aliveNodes, int quorumSize, Set<String> reachableNodes)
      implements PartitionEvent {}

  /** This node recovered quorum after being in minority partition. */
  record PartitionRecovered(
      long timestamp, int aliveNodes, int quorumSize, Set<String> reachableNodes)
      implements PartitionEvent {}

  /** A node became unreachable (heartbeat timeout). */
  record NodeUnreachable(long timestamp, String nodeName, int aliveNodes, int quorumSize)
      implements PartitionEvent {}

  @Override
  void close();
}
