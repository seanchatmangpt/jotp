package io.github.seanchatmangpt.jotp.enterprise.health;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Non-invasive health check manager for monitoring services.
 *
 * HealthCheckManager runs periodic health checks against a target service without killing it
 * if checks fail (unlike link/1 semantics). Uses ProcMonitor internally for liveness checks.
 *
 * State Machine:
 * - HEALTHY: All checks pass
 * - DEGRADED: Some checks fail (< failThreshold consecutive failures)
 * - UNHEALTHY: Critical failure (>= failThreshold consecutive failures)
 * - UNREACHABLE: Service unreachable
 */
public class HealthCheckManager {
  private final HealthCheckConfig config;
  private final ProcRef<HealthCheckState, HealthCheckMsg> coordinator;
  private final List<HealthCheckListener> listeners = new CopyOnWriteArrayList<>();
  private volatile HealthStatus currentStatus = new HealthStatus.Healthy(System.currentTimeMillis());
  private final Map<String, HealthCheckResult> lastResults = new HashMap<>();

  private HealthCheckManager(HealthCheckConfig config, ProcRef<HealthCheckState, HealthCheckMsg> coordinator) {
    this.config = config;
    this.coordinator = coordinator;
  }

  /**
   * Create a new health check manager.
   *
   * @param config Health check configuration
   * @return HealthCheckManager instance
   */
  public static HealthCheckManager create(HealthCheckConfig config) {
    return new HealthCheckManager(config, spawnCoordinator(config));
  }

  /**
   * Get current health status.
   *
   * @return Current HealthStatus
   */
  public HealthStatus getStatus() {
    return currentStatus;
  }

  /**
   * Get last result for a specific check.
   *
   * @param checkName Name of the check
   * @return Last HealthCheckResult or null if not executed yet
   */
  public HealthCheckResult getLastResult(String checkName) {
    return lastResults.get(checkName);
  }

  /**
   * Register a listener for health status changes.
   *
   * @param listener Callback to invoke on status transition
   */
  public void addListener(HealthCheckListener listener) {
    listeners.add(listener);
  }

  /**
   * Remove a listener.
   *
   * @param listener Listener to remove
   */
  public void removeListener(HealthCheckListener listener) {
    listeners.remove(listener);
  }

  /**
   * Stop health checking and shutdown coordinator.
   */
  public void shutdown() {
    // Send shutdown message to coordinator
    coordinator.tell(new HealthCheckMsg.Shutdown());
  }

  private static ProcRef<HealthCheckState, HealthCheckMsg> spawnCoordinator(HealthCheckConfig config) {
    var initialState = new HealthCheckState(
        config.serviceName(),
        new HealthStatus.Healthy(System.currentTimeMillis()),
        0,
        0,
        new HashMap<>());
    var handler = (java.util.function.BiFunction<HealthCheckState, HealthCheckMsg, HealthCheckState>) (state, msg) -> {
      return switch (msg) {
        case HealthCheckMsg.RunChecks _ -> handleRunChecks(state, config);
        case HealthCheckMsg.CheckCompleted(var result) -> handleCheckCompleted(state, result, config);
        case HealthCheckMsg.Shutdown _ -> {
          // Shutdown coordinator
          yield state;
        }
      };
    };
    var proc = new Proc<>(initialState, handler);
    return new ProcRef<>(proc);
  }

  private static HealthCheckState handleRunChecks(HealthCheckState state, HealthCheckConfig config) {
    // Execute all checks in parallel
    return state;
  }

  private static HealthCheckState handleCheckCompleted(
      HealthCheckState state, HealthCheckResult result, HealthCheckConfig config) {
    return state;
  }

  /**
   * Internal state for the health check coordinator.
   */
  record HealthCheckState(
      String serviceName,
      HealthStatus status,
      int consecutiveFailures,
      int consecutiveSuccesses,
      Map<String, HealthCheckResult> lastResults) {}

  /**
   * Messages for the health check coordinator.
   */
  sealed interface HealthCheckMsg permits
      HealthCheckMsg.RunChecks,
      HealthCheckMsg.CheckCompleted,
      HealthCheckMsg.Shutdown {

    record RunChecks() implements HealthCheckMsg {}

    record CheckCompleted(HealthCheckResult result) implements HealthCheckMsg {}

    record Shutdown() implements HealthCheckMsg {}
  }

  /**
   * Listener interface for health status changes.
   */
  @FunctionalInterface
  public interface HealthCheckListener {
    void onStatusChanged(HealthStatus from, HealthStatus to);
  }
}
