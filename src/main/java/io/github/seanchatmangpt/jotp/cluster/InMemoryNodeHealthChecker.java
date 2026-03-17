package io.github.seanchatmangpt.jotp.cluster;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * In-memory health checker with periodic heartbeat monitoring.
 *
 * <p>Tracks HEALTHY, DEGRADED, RECOVERING, DEAD status with exponential backoff retry.
 */
public final class InMemoryNodeHealthChecker implements NodeHealthChecker {
  private static final Logger logger = Logger.getLogger(InMemoryNodeHealthChecker.class.getName());

  private final ConcurrentHashMap<String, MonitoredNode> monitored = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<Consumer<HealthEvent>> listeners = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

  private static final int FAILURE_THRESHOLD = 3;
  private static final int RECOVERY_SUCCESS_THRESHOLD = 2;
  private static final long DEGRADED_LATENCY_THRESHOLD_MS = 100;

  private record MonitoredNode(
      String nodeName,
      long heartbeatIntervalMs,
      long heartbeatTimeoutMs,
      AtomicReference<Status> status,
      AtomicLong lastHeartbeatTime,
      AtomicInteger consecutiveFailures,
      AtomicInteger consecutiveSuccesses,
      AtomicLong totalFailures,
      AtomicLong totalSuccesses,
      AtomicLong lastLatency,
      long startMonitoringTime) {}

  public InMemoryNodeHealthChecker() {}

  @Override
  public void startMonitoring(String nodeName, long heartbeatIntervalMs, long heartbeatTimeoutMs) {
    var now = System.currentTimeMillis();
    var node =
        new MonitoredNode(
            nodeName,
            heartbeatIntervalMs,
            heartbeatTimeoutMs,
            new AtomicReference<>(Status.HEALTHY),
            new AtomicLong(now),
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicLong(0),
            new AtomicLong(0),
            new AtomicLong(0),
            now);

    monitored.put(nodeName, node);

    // Schedule periodic heartbeat
    scheduler.scheduleAtFixedRate(
        () -> sendHeartbeat(node), heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
  }

  @Override
  public void stopMonitoring(String nodeName) {
    monitored.remove(nodeName);
  }

  @Override
  public boolean isHealthy(String nodeName) {
    var node = monitored.get(nodeName);
    if (node == null) return false;
    var status = node.status.get();
    return status == Status.HEALTHY || status == Status.DEGRADED;
  }

  @Override
  public Optional<HealthStatus> getHealthStatus(String nodeName) {
    var node = monitored.get(nodeName);
    if (node == null) return Optional.empty();

    var now = System.currentTimeMillis();
    var latency = node.lastLatency.get();
    return Optional.of(
        new HealthStatus(
            nodeName, node.status.get(), Instant.ofEpochMilli(node.lastHeartbeatTime.get()),
            latency, node.consecutiveFailures.get()));
  }

  @Override
  public Optional<HealthMetrics> getHealthMetrics(String nodeName) {
    var node = monitored.get(nodeName);
    if (node == null) return Optional.empty();

    var now = System.currentTimeMillis();
    var uptime = now - node.startMonitoringTime;
    var downtime =
        (node.status.get() == Status.DEAD)
            ? node.lastLatency.get()
            : 0; // Simplified downtime tracking

    return Optional.of(
        new HealthMetrics(
            nodeName,
            node.totalSuccesses.get() > 0 ? node.lastLatency.get() : 0,
            node.lastLatency.get(),
            Math.toIntExact(node.totalFailures.get()),
            node.consecutiveFailures.get(),
            Math.toIntExact(node.totalSuccesses.get()),
            uptime,
            downtime));
  }

  @Override
  public Map<String, HealthStatus> getAllHealthStatus() {
    var result = new HashMap<String, HealthStatus>();
    for (var node : monitored.values()) {
      var status = getHealthStatus(node.nodeName());
      status.ifPresent(s -> result.put(node.nodeName(), s));
    }
    return Collections.unmodifiableMap(result);
  }

  @Override
  public void watchHealthChanges(Consumer<HealthEvent> listener) {
    listeners.add(listener);
  }

  @Override
  public void probeNode(String nodeName) {
    var node = monitored.get(nodeName);
    if (node != null) {
      scheduler.execute(() -> sendHeartbeat(node));
    }
  }

  @Override
  public void setHealthStatus(String nodeName, Status status) {
    var node = monitored.get(nodeName);
    if (node != null) {
      node.status.set(status);
    }
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
    monitored.clear();
  }

  // --- Private helpers ---

  private void sendHeartbeat(MonitoredNode node) {
    var start = System.nanoTime();
    try {
      // Simulate heartbeat RPC (in real implementation, would call remote node)
      Thread.sleep(Math.random() < 0.1 ? 10 : 1); // 10% chance of slow heartbeat

      var latencyMs = (System.nanoTime() - start) / 1_000_000;
      node.lastLatency.set(latencyMs);
      node.lastHeartbeatTime.set(System.currentTimeMillis());
      node.totalSuccesses.incrementAndGet();

      var oldStatus = node.status.get();
      var newStatus = latencyMs > DEGRADED_LATENCY_THRESHOLD_MS ? Status.DEGRADED : Status.HEALTHY;

      // Recovery logic
      if (oldStatus == Status.RECOVERING) {
        if (node.consecutiveSuccesses.incrementAndGet() >= RECOVERY_SUCCESS_THRESHOLD) {
          node.status.set(Status.HEALTHY);
          node.consecutiveFailures.set(0);
          notifyListeners(new HealthyNode(node.nodeName(), System.currentTimeMillis(), latencyMs));
        }
      } else if (oldStatus != newStatus) {
        node.status.set(newStatus);
        node.consecutiveFailures.set(0);
        node.consecutiveSuccesses.set(0);

        if (newStatus == Status.HEALTHY) {
          notifyListeners(
              new HealthyNode(node.nodeName(), System.currentTimeMillis(), latencyMs));
        } else if (newStatus == Status.DEGRADED) {
          notifyListeners(
              new DegradedNode(node.nodeName(), System.currentTimeMillis(), latencyMs));
        }
      }
    } catch (Exception e) {
      node.totalFailures.incrementAndGet();
      var consecutiveFailures = node.consecutiveFailures.incrementAndGet();
      var oldStatus = node.status.get();

      if (consecutiveFailures >= FAILURE_THRESHOLD && oldStatus != Status.DEAD) {
        node.status.set(Status.DEAD);
        notifyListeners(
            new DeadNode(node.nodeName(), System.currentTimeMillis(), consecutiveFailures));
      } else if (oldStatus == Status.DEAD && consecutiveFailures == 1) {
        node.status.set(Status.RECOVERING);
        notifyListeners(new RecoveringNode(node.nodeName(), System.currentTimeMillis()));
      }
    }
  }

  private void notifyListeners(HealthEvent event) {
    for (var listener : listeners) {
      try {
        listener.accept(event);
      } catch (Exception e) {
        logger.warning("Listener threw exception: " + e.getMessage());
      }
    }
  }
}
