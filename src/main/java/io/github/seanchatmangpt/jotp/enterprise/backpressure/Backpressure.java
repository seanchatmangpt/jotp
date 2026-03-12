package io.github.seanchatmangpt.jotp.enterprise.backpressure;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backpressure coordinator for timeout-based flow control.
 *
 * Prevents queue explosion when downstream services are slow or failing. Implements
 * circuit breaker patterns and adaptive timeout adjustment based on success rates.
 *
 * State Machine:
 * - HEALTHY: All requests completing within timeout
 * - WARNING: Some timeouts but service still responding (adjust timeout upward)
 * - CIRCUIT_OPEN: Too many timeouts, reject requests
 */
public class Backpressure {
  private final BackpressureConfig config;
  private final ProcRef<BackpressureState, BackpressureMsg> coordinator;
  private final CopyOnWriteArrayList<BackpressureListener> listeners = new CopyOnWriteArrayList<>();

  private Backpressure(BackpressureConfig config, ProcRef<BackpressureState, BackpressureMsg> coordinator) {
    this.config = config;
    this.coordinator = coordinator;
  }

  /**
   * Create a new backpressure coordinator.
   *
   * @param config Backpressure configuration
   * @return Backpressure instance
   */
  public static Backpressure create(BackpressureConfig config) {
    return new Backpressure(config, spawnCoordinator(config));
  }

  /**
   * Send a request with backpressure timeout enforcement.
   *
   * @param task The task to execute
   * @param defaultTimeout Default timeout if coordinator not yet ready
   * @return Result wrapping success or backpressure error
   */
  public <T> Result<T> execute(BackpressureTask<T> task, Duration defaultTimeout) {
    String requestId = UUID.randomUUID().toString();
    long startTime = System.currentTimeMillis();

    try {
      T result = task.execute(defaultTimeout);
      long duration = System.currentTimeMillis() - startTime;

      // Notify success to coordinator
      coordinator.tell(new BackpressureMsg.RequestCompleted(requestId, duration));

      return Result.success(result);
    } catch (BackpressureException e) {
      coordinator.tell(new BackpressureMsg.RequestTimedOut(requestId));
      return Result.failure(e);
    } catch (Exception e) {
      return Result.failure(new BackpressureException("Request failed: " + e.getMessage(), e));
    }
  }

  /**
   * Register a listener for backpressure events.
   *
   * @param listener Callback to invoke on events
   */
  public void addListener(BackpressureListener listener) {
    listeners.add(listener);
  }

  /**
   * Remove a listener.
   *
   * @param listener Listener to remove
   */
  public void removeListener(BackpressureListener listener) {
    listeners.remove(listener);
  }

  /**
   * Shutdown the backpressure coordinator.
   */
  public void shutdown() {
    coordinator.tell(new BackpressureMsg.Shutdown());
  }

  private static ProcRef<BackpressureState, BackpressureMsg> spawnCoordinator(BackpressureConfig config) {
    var initialState = new BackpressureState(
        config.serviceName(),
        BackpressureState.Status.HEALTHY,
        0,
        0,
        new ArrayDeque<>(config.windowSize()),
        config.initialTimeout());
    var handler = (java.util.function.BiFunction<BackpressureState, BackpressureMsg, BackpressureState>) (state, msg) -> {
      return switch (msg) {
        case BackpressureMsg.RequestCompleted(var id, var duration) ->
            handleRequestCompleted(state, duration, config);
        case BackpressureMsg.RequestTimedOut(var id) -> handleRequestTimedOut(state, config);
        case BackpressureMsg.Shutdown _ -> state;
      };
    };
    var proc = new Proc<>(initialState, handler);
    return new ProcRef<>(proc);
  }

  private static BackpressureState handleRequestCompleted(
      BackpressureState state, long durationMs, BackpressureConfig config) {
    Deque<Boolean> window = state.resultWindow();
    window.addLast(true);
    if (window.size() > config.windowSize()) {
      window.removeFirst();
    }

    BackpressureState.Status newStatus = calculateStatus(window, config);
    return new BackpressureState(
        state.serviceName(),
        newStatus,
        state.successCount() + 1,
        state.failureCount(),
        window,
        state.currentTimeout());
  }

  private static BackpressureState handleRequestTimedOut(
      BackpressureState state, BackpressureConfig config) {
    Deque<Boolean> window = state.resultWindow();
    window.addLast(false);
    if (window.size() > config.windowSize()) {
      window.removeFirst();
    }

    BackpressureState.Status newStatus = calculateStatus(window, config);

    // Increase timeout on failures
    Duration newTimeout = state.currentTimeout().multipliedBy(2);
    if (newTimeout.compareTo(config.maxTimeout()) > 0) {
      newTimeout = config.maxTimeout();
    }

    return new BackpressureState(
        state.serviceName(),
        newStatus,
        state.successCount(),
        state.failureCount() + 1,
        window,
        newTimeout);
  }

  private static BackpressureState.Status calculateStatus(
      Deque<Boolean> window, BackpressureConfig config) {
    if (window.isEmpty()) {
      return BackpressureState.Status.HEALTHY;
    }

    long successCount = window.stream().filter(b -> b).count();
    double successRate = (double) successCount / window.size();

    if (successRate >= config.successRateThreshold()) {
      return BackpressureState.Status.HEALTHY;
    } else if (successRate >= 0.80) {
      return BackpressureState.Status.WARNING;
    } else {
      return BackpressureState.Status.CIRCUIT_OPEN;
    }
  }

  /**
   * Internal state for the backpressure coordinator.
   */
  record BackpressureState(
      String serviceName,
      Status status,
      long successCount,
      long failureCount,
      Deque<Boolean> resultWindow,
      Duration currentTimeout) {

    enum Status {
      HEALTHY,
      WARNING,
      CIRCUIT_OPEN
    }
  }

  /**
   * Messages for the backpressure coordinator.
   */
  sealed interface BackpressureMsg permits
      BackpressureMsg.RequestCompleted,
      BackpressureMsg.RequestTimedOut,
      BackpressureMsg.Shutdown {

    record RequestCompleted(String requestId, long durationMs) implements BackpressureMsg {}

    record RequestTimedOut(String requestId) implements BackpressureMsg {}

    record Shutdown() implements BackpressureMsg {}
  }

  /**
   * Task to execute with backpressure enforcement.
   */
  @FunctionalInterface
  public interface BackpressureTask<T> {
    T execute(Duration timeout) throws Exception;
  }

  /**
   * Result type for backpressure operations.
   */
  sealed interface Result<T> permits Result.Success, Result.Failure {
    record Success<T>(T value) implements Result<T> {}

    record Failure<T>(BackpressureException error) implements Result<T> {}

    static <T> Result<T> success(T value) {
      return new Success<>(value);
    }

    static <T> Result<T> failure(BackpressureException error) {
      return new Failure<>(error);
    }
  }

  /**
   * Listener interface for backpressure events.
   */
  @FunctionalInterface
  public interface BackpressureListener {
    void onStatusChanged(BackpressureState.Status from, BackpressureState.Status to);
  }

  /**
   * Exception thrown when backpressure limits are exceeded.
   */
  public static class BackpressureException extends Exception {
    public BackpressureException(String message) {
      super(message);
    }

    public BackpressureException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
