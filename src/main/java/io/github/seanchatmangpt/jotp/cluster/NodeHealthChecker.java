package io.github.seanchatmangpt.jotp.cluster;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Health monitoring for cluster nodes via periodic heartbeats.
 *
 * <p>Joe Armstrong: "The supervisor must know when a supervised process dies. Erlang supervisors
 * use monitors and links for this. In a distributed cluster, we use heartbeats."
 *
 * <p>This interface provides:
 *
 * <ul>
 *   <li><strong>Periodic Heartbeats:</strong> Send heartbeat every heartbeatInterval to each node
 *   <li><strong>Health Status:</strong> Track HEALTHY, DEGRADED, DEAD, RECOVERING
 *   <li><strong>Recovery:</strong> Retry logic with exponential backoff
 *   <li><strong>Metrics:</strong> Heartbeat latency, failure rate, recovery time
 * </ul>
 *
 * <p><strong>Heartbeat Protocol:</strong>
 *
 * <pre>{@code
 * Every heartbeatInterval (e.g., 1 second):
 *   1. Send heartbeat to node
 *   2. Await response or timeout (e.g., 500ms)
 *   3. Update health status
 *   4. If healthy: notify cluster of UP event
 *   5. If dead (consecutive failures): notify cluster of DOWN event
 * }</pre>
 *
 * <p><strong>Health States:</strong>
 *
 * <pre>{@code
 * HEALTHY: Heartbeat succeeded, latency < 100ms
 * DEGRADED: Heartbeat succeeded, latency >= 100ms (slow network)
 * DEAD: Last N heartbeats failed (N = failureThreshold, e.g., 3)
 * RECOVERING: Dead node heartbeat succeeded, but not yet considered healthy
 * }</pre>
 *
 * <p><strong>Recovery Backoff:</strong>
 *
 * <pre>{@code
 * Attempt 1: heartbeat timeout (e.g., 500ms)
 * Attempt 2: 1s later
 * Attempt 3: 2s later
 * Attempt 4: 4s later (max backoff = heartbeatInterval)
 *
 * After consecutiveSuccesses >= recoverySuccessThreshold:
 *   Status changes from RECOVERING to HEALTHY
 * }</pre>
 */
public interface NodeHealthChecker extends AutoCloseable {

  /**
   * Start health checking for a node.
   *
   * <p>Heartbeats are sent automatically every heartbeatInterval. Node is initially considered
   * HEALTHY if the first heartbeat succeeds.
   *
   * @param nodeName node to monitor
   * @param heartbeatIntervalMs frequency of heartbeats (e.g., 1000)
   * @param heartbeatTimeoutMs max time to wait for response (e.g., 500)
   */
  void startMonitoring(String nodeName, long heartbeatIntervalMs, long heartbeatTimeoutMs);

  /**
   * Stop health checking for a node.
   *
   * @param nodeName node to stop monitoring
   */
  void stopMonitoring(String nodeName);

  /**
   * Check if a node is currently healthy.
   *
   * @param nodeName node to check
   * @return true if status is HEALTHY or DEGRADED
   */
  boolean isHealthy(String nodeName);

  /**
   * Get detailed health status for a node.
   *
   * @param nodeName node to query
   * @return current health status snapshot, or empty if not monitored
   */
  Optional<HealthStatus> getHealthStatus(String nodeName);

  /**
   * Get health metrics for a node.
   *
   * @param nodeName node to query
   * @return metrics including latency, failure count, uptime, or empty if not monitored
   */
  Optional<HealthMetrics> getHealthMetrics(String nodeName);

  /**
   * Get health status for all monitored nodes.
   *
   * @return map of node name to health status
   */
  Map<String, HealthStatus> getAllHealthStatus();

  /**
   * Watch health status changes (node HEALTHY → DEGRADED, DEAD, RECOVERING).
   *
   * <p>Listener is called asynchronously when a node's health status changes. Exceptions in
   * listener are caught and logged.
   *
   * @param listener receives {@link HealthEvent}
   */
  void watchHealthChanges(Consumer<HealthEvent> listener);

  /**
   * Trigger an immediate heartbeat to a node (for testing or diagnostics).
   *
   * <p>Does not wait for response; updates health status asynchronously.
   *
   * @param nodeName node to probe
   */
  void probeNode(String nodeName);

  /**
   * Manually set a node's health status.
   *
   * <p>Used when external health checks provide status (e.g., Kubernetes liveness probe). Does
   * not affect automatic heartbeat checking.
   *
   * @param nodeName node to update
   * @param status new health status
   */
  void setHealthStatus(String nodeName, Status status);

  /** Health status of a node at a point in time. */
  record HealthStatus(
      String nodeName, Status status, Instant lastHeartbeat, long latencyMs, int failureCount) {}

  /** Health metrics for a node. */
  record HealthMetrics(
      String nodeName,
      double avgLatencyMs,
      double maxLatencyMs,
      int totalFailures,
      int consecutiveFailures,
      int totalSuccesses,
      long uptime,
      long downtime) {}

  /** Health event fired when node status changes. */
  sealed interface HealthEvent permits HealthyNode, DegradedNode, DeadNode, RecoveringNode {
    String nodeName();

    long timestamp();
  }

  record HealthyNode(String nodeName, long timestamp, long latencyMs) implements HealthEvent {}

  record DegradedNode(String nodeName, long timestamp, long latencyMs) implements HealthEvent {}

  record DeadNode(String nodeName, long timestamp, int consecutiveFailures) implements HealthEvent {}

  record RecoveringNode(String nodeName, long timestamp) implements HealthEvent {}

  /** Health status of a node. */
  enum Status {
    HEALTHY,
    DEGRADED,
    RECOVERING,
    DEAD
  }

  @Override
  void close();
}
