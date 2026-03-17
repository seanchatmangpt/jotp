package io.github.seanchatmangpt.jotp.distributed;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks heartbeat history and detects node failures using monotonic time.
 */
public final class NodeFailureDetector {

  private final int failureThreshold;
  private final ConcurrentHashMap<NodeId, HeartbeatEntry> heartbeats = new ConcurrentHashMap<>();
  private volatile Consumer<HealthChange> healthChangeCallback = change -> {};

  private static final class HeartbeatEntry {
    int consecutiveFailures = 0;
    boolean isHealthy = true;

    synchronized void recordSuccess() {
      this.consecutiveFailures = 0;
      this.isHealthy = true;
    }

    synchronized void recordFailure() {
      this.consecutiveFailures++;
    }

    synchronized boolean markUnhealthyIfThresholdReached(int threshold) {
      if (consecutiveFailures >= threshold && isHealthy) {
        isHealthy = false;
        return true;
      }
      return false;
    }

    synchronized boolean isHealthy() {
      return isHealthy;
    }
  }

  public NodeFailureDetector(int failureThreshold) {
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be >= 1");
    }
    this.failureThreshold = failureThreshold;
  }

  public static NodeFailureDetector defaults() {
    return new NodeFailureDetector(3);
  }

  public void onHealthChange(Consumer<HealthChange> callback) {
    this.healthChangeCallback = callback;
  }

  public void recordHeartbeat(NodeId nodeId, boolean healthy) {
    HeartbeatEntry entry = heartbeats.computeIfAbsent(nodeId, _ -> new HeartbeatEntry());
    if (healthy) {
      entry.recordSuccess();
    } else {
      entry.recordFailure();
      if (entry.markUnhealthyIfThresholdReached(failureThreshold)) {
        healthChangeCallback.accept(HealthChange.down(nodeId));
      }
    }
  }

  public Set<NodeId> getHealthyNodes() {
    return Collections.unmodifiableSet(
        heartbeats.entrySet().stream()
            .filter(e -> e.getValue().isHealthy())
            .map(e -> e.getKey())
            .collect(HashSet::new, Set::add, Set::addAll));
  }

  public Set<NodeId> getUnhealthyNodes() {
    return Collections.unmodifiableSet(
        heartbeats.entrySet().stream()
            .filter(e -> !e.getValue().isHealthy())
            .map(e -> e.getKey())
            .collect(HashSet::new, Set::add, Set::addAll));
  }

  public boolean isHealthy(NodeId nodeId) {
    HeartbeatEntry entry = heartbeats.get(nodeId);
    return entry != null && entry.isHealthy();
  }

  public void reset() {
    heartbeats.clear();
  }

  public void resetNode(NodeId nodeId) {
    heartbeats.remove(nodeId);
  }

  public sealed interface HealthChange permits HealthChange.Up, HealthChange.Down {
    static HealthChange up(NodeId nodeId) {
      return new Up(nodeId);
    }

    static HealthChange down(NodeId nodeId) {
      return new Down(nodeId);
    }

    record Up(NodeId nodeId) implements HealthChange {}
    record Down(NodeId nodeId) implements HealthChange {}
  }
}
