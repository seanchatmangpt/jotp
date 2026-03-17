package io.github.seanchatmangpt.jotp.cluster;

import java.util.Optional;
import java.util.Set;

/**
 * Leader election via bully algorithm or distributed consensus.
 *
 * <p>Joe Armstrong: "In distributed systems, the leader is chosen by the processes themselves via
 * consensus. If the leader fails, a new one is elected without manual intervention."
 *
 * <p>This interface implements the bully algorithm:
 *
 * <ol>
 *   <li>Each node publishes its ID (typically a timestamp + hostname hash)
 *   <li>If a node detects its own ID is highest, it becomes leader
 *   <li>If the current leader is unavailable, a new election is triggered
 *   <li>Elections use quorum: majority of cluster must agree
 * </ol>
 *
 * <p><strong>Partition Safety:</strong>
 *
 * <p>In a network partition, the minority partition has no leader. This prevents split-brain
 * scenarios where two parts of the cluster make conflicting decisions.
 *
 * <pre>{@code
 * Cluster: [A, B, C]
 * Leader: A (holds lease with TTL)
 *
 * Partition: [A] vs [B, C]
 * - B and C: Can elect new leader? Yes (2/3 >= quorum)
 * - A: Can renew lease? No (1/3 < quorum) → steps down
 * }</pre>
 *
 * <p><strong>Graceful Handoff:</strong>
 *
 * <p>When a leader voluntarily steps down (shutdown, migration):
 *
 * <ol>
 *   <li>Current leader releases its lease (DELETE in Redis, UPDATE in PostgreSQL)
 *   <li>Remaining nodes immediately start new election
 *   <li>First to acquire lock becomes new leader
 * </ol>
 */
public interface LeaderElection extends AutoCloseable {

  /**
   * Run a leader election among the given candidates.
   *
   * <p>Blocks until election completes (or timeout). Uses bully algorithm: the node with the
   * highest ID becomes leader.
   *
   * @param nodeName this node's name
   * @param candidates all candidate nodes (including this one)
   * @param electionTimeoutMs max time to wait for election (e.g., 5000ms)
   * @return new leader name, or empty if election timeout/failed
   */
  Optional<String> electLeader(String nodeName, Set<String> candidates, long electionTimeoutMs);

  /**
   * Attempt to acquire leadership lease.
   *
   * <p>Blocks until lease acquired or timeout. Lease is automatically renewed until released or
   * timeout.
   *
   * @param nodeName this node's name
   * @param leaseDurationMs how long to hold the lease (e.g., 10000ms)
   * @param acquireTimeoutMs max time to wait for lease (e.g., 5000ms)
   * @return true if lease acquired, false on timeout
   */
  boolean acquireLeaderLease(String nodeName, long leaseDurationMs, long acquireTimeoutMs);

  /**
   * Release current leadership lease.
   *
   * <p>If this node is not the current leader, this is a no-op.
   *
   * @param nodeName leader node name
   */
  void releaseLeaderLease(String nodeName);

  /**
   * Renew the current leadership lease (extend TTL).
   *
   * <p>Called automatically by lease manager. Failure to renew means this node is partitioned or
   * crashed, so it should step down.
   *
   * @param nodeName leader node name
   * @param newDurationMs new lease duration
   * @return true if lease renewed, false if not leader
   */
  boolean renewLeaderLease(String nodeName, long newDurationMs);

  /**
   * Check if this node currently holds the leader lease.
   *
   * @param nodeName node to check
   * @return true if lease held by this node
   */
  boolean isLeader(String nodeName);

  /**
   * Get the current leader (may be on minority partition).
   *
   * <p>Always returns the node with the current lease, even if this node is partitioned from it.
   *
   * @return node holding the leader lease, or empty if no active lease
   */
  Optional<String> getCurrentLeader();

  /**
   * Check if an election is in progress.
   *
   * @return true if nodes are competing for leadership
   */
  boolean isElectionInProgress();

  @Override
  void close();
}
