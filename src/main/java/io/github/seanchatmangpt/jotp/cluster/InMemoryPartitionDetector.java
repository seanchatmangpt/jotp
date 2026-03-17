package io.github.seanchatmangpt.jotp.cluster;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * In-memory partition detector — tracks reachable/unreachable nodes.
 *
 * <p>Integrates with NodeHealthChecker to determine reachability.
 */
public final class InMemoryPartitionDetector implements PartitionDetector {
  private static final Logger logger = Logger.getLogger(InMemoryPartitionDetector.class.getName());

  private final int clusterSize;
  private final NodeHealthChecker healthChecker;
  private final CopyOnWriteArrayList<Consumer<PartitionEvent>> listeners =
      new CopyOnWriteArrayList<>();
  private final AtomicBoolean currentlyPartitioned = new AtomicBoolean(false);
  private final AtomicReference<Optional<PartitionEvent>> lastEvent =
      new AtomicReference<>(Optional.empty());

  public InMemoryPartitionDetector(int clusterSize, NodeHealthChecker healthChecker) {
    this.clusterSize = clusterSize;
    this.healthChecker = healthChecker;

    // Watch health changes to detect partition transitions
    healthChecker.watchHealthChanges(this::handleHealthEvent);
  }

  @Override
  public boolean isMinorityPartition() {
    var quorum = getQuorumSize();
    var aliveCount = healthChecker.getAllHealthStatus().size();
    return aliveCount < quorum;
  }

  @Override
  public Set<String> getReachableNodes() {
    return healthChecker.getAllHealthStatus().entrySet().stream()
        .filter(e -> !e.getValue().failureCount() > 0)
        .map(java.util.Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toSet());
  }

  @Override
  public Set<String> getUnreachableNodes() {
    return healthChecker.getAllHealthStatus().entrySet().stream()
        .filter(e -> e.getValue().failureCount() > 0)
        .map(java.util.Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toSet());
  }

  @Override
  public boolean isReachable(String nodeName) {
    var status = healthChecker.getHealthStatus(nodeName);
    return status.isPresent() && status.get().failureCount() == 0;
  }

  @Override
  public int getQuorumSize() {
    return (clusterSize / 2) + 1;
  }

  @Override
  public boolean hasQuorum() {
    var healthy = healthChecker.getAllHealthStatus();
    return healthy.size() >= getQuorumSize();
  }

  @Override
  public void watchPartitionChanges(Consumer<PartitionEvent> listener) {
    listeners.add(listener);
  }

  @Override
  public Optional<PartitionEvent> getLastPartitionEvent() {
    return lastEvent.get();
  }

  @Override
  public void close() {
    listeners.clear();
  }

  // --- Private helpers ---

  private void handleHealthEvent(NodeHealthChecker.HealthEvent event) {
    var reachable = getReachableNodes();
    var quorum = getQuorumSize();
    var aliveCount = reachable.size();
    var wasPartitioned = currentlyPartitioned.get();
    var isPartitioned = aliveCount < quorum;

    if (wasPartitioned && !isPartitioned) {
      // Recovered from partition
      var partEvent =
          new PartitionRecovered(System.currentTimeMillis(), aliveCount, quorum, reachable);
      lastEvent.set(Optional.of(partEvent));
      currentlyPartitioned.set(false);
      notifyListeners(partEvent);
    } else if (!wasPartitioned && isPartitioned) {
      // Entered partition
      var partEvent =
          new PartitionLost(System.currentTimeMillis(), aliveCount, quorum, reachable);
      lastEvent.set(Optional.of(partEvent));
      currentlyPartitioned.set(true);
      notifyListeners(partEvent);
    }

    // Also track individual node unreachability
    if (event instanceof NodeHealthChecker.DeadNode dead) {
      var partEvent =
          new NodeUnreachable(System.currentTimeMillis(), dead.nodeName(), aliveCount, quorum);
      lastEvent.set(Optional.of(partEvent));
      notifyListeners(partEvent);
    }
  }

  private void notifyListeners(PartitionEvent event) {
    for (var listener : listeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        logger.warning("Listener threw exception: " + e.getMessage());
      }
    }
  }
}
