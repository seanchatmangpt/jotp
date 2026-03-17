package io.github.seanchatmangpt.jotp.cluster;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * In-memory cluster manager — single JVM only (for testing and single-node deployments).
 *
 * <p>Uses a scheduled executor for heartbeat timeout detection. Suitable for development and
 * testing. For production distributed clusters, use RedisClusterManager or PostgresClusterManager.
 */
public final class InMemoryClusterManager implements ClusterManager {
  private static final Logger logger = Logger.getLogger(InMemoryClusterManager.class.getName());

  private final Map<String, NodeRegistry> nodes = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<Consumer<NodeEvent>> listeners = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final AtomicReference<Optional<String>> leader = new AtomicReference<>(Optional.empty());
  private final long heartbeatTimeoutMs;

  private record NodeRegistry(
      String nodeName,
      int port,
      Map<String, String> metadata,
      long registeredAt,
      AtomicReference<Long> lastHeartbeat) {}

  public InMemoryClusterManager(long heartbeatTimeoutMs) {
    this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    // Start heartbeat timeout detector
    scheduler.scheduleAtFixedRate(this::detectTimeouts, 100, 100, TimeUnit.MILLISECONDS);
  }

  @Override
  public void registerNode(String nodeName, int port, Map<String, String> metadata) {
    var now = System.currentTimeMillis();
    var registry =
        new NodeRegistry(
            nodeName, port, new HashMap<>(metadata), now, new AtomicReference<>(now));
    nodes.put(nodeName, registry);

    // Notify listeners
    notifyListeners(
        new NodeUp(nodeName, now, Collections.unmodifiableMap(metadata)));

    // Check if this is the first node - it becomes leader
    if (nodes.size() == 1) {
      leader.set(Optional.of(nodeName));
      notifyListeners(new LeaderChanged(nodeName, now, Optional.of(nodeName)));
    }
  }

  @Override
  public void deregisterNode(String nodeName) {
    var registry = nodes.remove(nodeName);
    if (registry != null) {
      var now = System.currentTimeMillis();
      notifyListeners(new NodeDown(nodeName, now));

      // If this was the leader, trigger election
      if (leader.get().filter(l -> l.equals(nodeName)).isPresent()) {
        leader.set(Optional.empty());
        notifyListeners(new LeaderChanged(nodeName, now, Optional.empty()));
        electNewLeader(now);
      }
    }
  }

  @Override
  public Set<String> getAliveNodes() {
    var now = System.currentTimeMillis();
    return nodes.values().stream()
        .filter(reg -> (now - reg.lastHeartbeat.get()) < heartbeatTimeoutMs)
        .map(reg -> reg.nodeName)
        .collect(java.util.stream.Collectors.toSet());
  }

  @Override
  public Set<String> getNodesByMetadata(String metadataKey, String value) {
    return nodes.values().stream()
        .filter(
            reg ->
                value.equals(reg.metadata.get(metadataKey))
                    && getAliveNodes().contains(reg.nodeName))
        .map(reg -> reg.nodeName)
        .collect(java.util.stream.Collectors.toSet());
  }

  @Override
  public Optional<String> getLeader() {
    return leader.get();
  }

  @Override
  public boolean isNodeAlive(String nodeName) {
    var registry = nodes.get(nodeName);
    if (registry == null) return false;
    var now = System.currentTimeMillis();
    return (now - registry.lastHeartbeat.get()) < heartbeatTimeoutMs;
  }

  @Override
  public Map<String, String> getNodeMetadata(String nodeName) {
    var registry = nodes.get(nodeName);
    return registry != null
        ? Collections.unmodifiableMap(registry.metadata)
        : Collections.emptyMap();
  }

  @Override
  public void watchNodeChanges(Consumer<NodeEvent> listener) {
    listeners.add(listener);
  }

  @Override
  public boolean isPartitioned() {
    var alive = getAliveNodes().size();
    var quorum = (nodes.size() / 2) + 1;
    return alive < quorum;
  }

  @Override
  public int getClusterSize() {
    return nodes.size();
  }

  @Override
  public int getAliveCount() {
    return getAliveNodes().size();
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warning("Scheduler did not terminate within timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    nodes.clear();
  }

  // --- Private helpers ---

  private void detectTimeouts() {
    var now = System.currentTimeMillis();
    var timedOut = new HashSet<String>();

    for (var reg : nodes.values()) {
      var lastHb = reg.lastHeartbeat.get();
      if ((now - lastHb) >= heartbeatTimeoutMs && isNodeAlive(reg.nodeName)) {
        timedOut.add(reg.nodeName);
      }
    }

    for (var nodeName : timedOut) {
      notifyListeners(new NodeDown(nodeName, now));

      // If leader is down, trigger election
      if (leader.get().filter(l -> l.equals(nodeName)).isPresent()) {
        leader.set(Optional.empty());
        notifyListeners(new LeaderChanged(nodeName, now, Optional.empty()));
        electNewLeader(now);
      }
    }
  }

  private void electNewLeader(long now) {
    var candidates = getAliveNodes();
    if (candidates.isEmpty()) {
      return;
    }
    // Simple election: highest node name becomes leader
    var newLeader = candidates.stream().max(String::compareTo).orElse(null);
    if (newLeader != null) {
      leader.set(Optional.of(newLeader));
      notifyListeners(new LeaderChanged(newLeader, now, Optional.of(newLeader)));
    }
  }

  private void notifyListeners(NodeEvent event) {
    for (var listener : listeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        logger.warning("Listener threw exception: " + e.getMessage());
      }
    }
  }
}
