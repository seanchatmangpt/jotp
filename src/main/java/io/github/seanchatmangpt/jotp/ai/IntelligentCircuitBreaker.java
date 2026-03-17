package io.github.seanchatmangpt.jotp.ai;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Intelligent Circuit Breaker that learns failure patterns and predicts recovery.
 *
 * <p>This circuit breaker extends the standard circuit breaker pattern with machine learning
 * capabilities. It:
 *
 * <ul>
 *   <li>Classifies failures as transient (temporary, retry-friendly) or permanent (broken,
 *       fail-fast)
 *   <li>Tracks failure duration, recovery time, and retry success rates
 *   <li>Predicts whether a retry will succeed based on historical patterns
 *   <li>Uses exponential backoff with adaptive parameters learned from behavior
 *   <li>Prevents cascading failures through intelligent circuit decisions
 * </ul>
 *
 * <p><strong>States:</strong>
 *
 * <ul>
 *   <li><strong>CLOSED:</strong> Normal operation — all requests pass through
 *   <li><strong>OPEN:</strong> Failure threshold exceeded — requests fail immediately
 *   <li><strong>HALF_OPEN:</strong> Testing recovery — probe with limited retry budget
 * </ul>
 *
 * <p><strong>Failure Classification:</strong>
 *
 * <ul>
 *   <li><strong>Transient:</strong> Temporary unavailability (timeouts, 503, connection reset).
 *       Success rate > 60% on retry.
 *   <li><strong>Permanent:</strong> Broken dependency (404, 400, authentication failed). Success
 *       rate < 10% on retry.
 * </ul>
 *
 * <p><strong>Backoff Strategy:</strong>
 *
 * <p>Uses exponential backoff with jitter and adaptive parameters:
 *
 * <pre>
 * delay = min_delay * (2 ^ attempt) + random_jitter
 * min_delay and max_delay adapt based on observed recovery times
 * </pre>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * IntelligentCircuitBreaker<String, String> breaker =
 *     IntelligentCircuitBreaker.create(
 *         "payment-api",
 *         5,                           // max failures before open
 *         Duration.ofSeconds(60),      // failure window
 *         Duration.ofSeconds(30),      // half-open timeout
 *         Duration.ofMillis(100),      // initial backoff
 *         Duration.ofSeconds(30)       // max backoff
 *     );
 *
 * var result = breaker.execute("user-123", request -> apiCall(request));
 * switch (result) {
 *     case IntelligentCircuitBreaker.Result.Success<String, Exception>(var value) ->
 *         System.out.println("Success: " + value);
 *     case IntelligentCircuitBreaker.Result.Failure<String, Exception>(var error) ->
 *         handleFailure(error);
 *     case IntelligentCircuitBreaker.Result.CircuitOpen<String, Exception>() ->
 *         System.out.println("Circuit open, will retry smartly");
 * }
 * }</pre>
 *
 * @param <R> request type
 * @param <V> success value type
 * @param <E> exception type
 */
public final class IntelligentCircuitBreaker<R, V, E extends Exception> {

  /**
   * Sealed result type with exhaustive pattern matching.
   *
   * @param <V> success value type
   * @param <E> exception type
   */
  public sealed interface Result<V, E extends Exception>
      permits Result.Success, Result.Failure, Result.CircuitOpen {

    /** Request succeeded with a value. */
    record Success<V, E extends Exception>(V value) implements Result<V, E> {}

    /** Request failed with an exception. */
    record Failure<V, E extends Exception>(E error, FailureClassification classification)
        implements Result<V, E> {}

    /** Circuit is open. Request not attempted. */
    record CircuitOpen<V, E extends Exception>(int remainingWaitTimeMs)
        implements Result<V, E> {}

    default boolean isSuccess() {
      return this instanceof Success<V, E>;
    }

    default boolean isFailure() {
      return this instanceof Failure<V, E>;
    }

    default boolean isCircuitOpen() {
      return this instanceof CircuitOpen<V, E>;
    }
  }

  /**
   * Circuit breaker state machine.
   *
   * <p>CLOSED → normal operation OPEN → fail-fast, wait for recovery HALF_OPEN → probe for
   * recovery
   */
  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  /** Classification of failure type. */
  public enum FailureClassification {
    TRANSIENT,  // Retry likely to succeed
    PERMANENT,  // Retry unlikely to succeed
    UNKNOWN     // Not enough data yet
  }

  /** Statistics for a specific failure type. */
  private static class FailureStats {
    long totalFailures = 0;
    long successfulRetries = 0;
    long failedRetries = 0;
    long totalRecoveryTimeMs = 0;
    long failureStartTimeMs = 0;

    double getRetrySuccessRate() {
      long totalRetries = successfulRetries + failedRetries;
      return totalRetries > 0 ? (double) successfulRetries / totalRetries : 0.5;
    }

    long getAverageRecoveryTimeMs() {
      long recoveredCount = successfulRetries + (totalFailures - successfulRetries - failedRetries);
      return recoveredCount > 0 ? totalRecoveryTimeMs / recoveredCount : 0;
    }

    FailureClassification classify() {
      if (totalFailures < 3) {
        return FailureClassification.UNKNOWN;
      }
      double successRate = getRetrySuccessRate();
      if (successRate > 0.6) {
        return FailureClassification.TRANSIENT;
      } else if (successRate < 0.1) {
        return FailureClassification.PERMANENT;
      }
      return FailureClassification.UNKNOWN;
    }
  }

  private final String name;
  private final int maxFailures;
  private final Duration failureWindow;
  private final Duration halfOpenTimeout;
  private final Duration minBackoff;
  private final Duration maxBackoff;

  private volatile State state = State.CLOSED;
  private volatile Instant openedTime = null;
  private volatile Instant lastFailureTime = null;
  private volatile int consecutiveFailures = 0;
  private volatile int retryAttempt = 0;

  // Failure tracking
  private final LinkedList<Instant> failureTimes = new LinkedList<>();
  private final ConcurrentHashMap<Class<?>, FailureStats> failureStats =
      new ConcurrentHashMap<>();

  // Adaptive backoff parameters
  private volatile long adaptiveMinBackoffMs;
  private volatile long adaptiveMaxBackoffMs;
  private final Random random = new Random();

  private IntelligentCircuitBreaker(
      String name,
      int maxFailures,
      Duration failureWindow,
      Duration halfOpenTimeout,
      Duration minBackoff,
      Duration maxBackoff) {
    this.name = name;
    this.maxFailures = maxFailures;
    this.failureWindow = failureWindow;
    this.halfOpenTimeout = halfOpenTimeout;
    this.minBackoff = minBackoff;
    this.maxBackoff = maxBackoff;
    this.adaptiveMinBackoffMs = minBackoff.toMillis();
    this.adaptiveMaxBackoffMs = maxBackoff.toMillis();
  }

  /**
   * Create an intelligent circuit breaker.
   *
   * @param name circuit breaker identifier
   * @param maxFailures threshold for opening
   * @param failureWindow time window for counting failures
   * @param halfOpenTimeout wait time before attempting recovery
   * @param minBackoff initial backoff duration
   * @param maxBackoff maximum backoff duration
   * @return new IntelligentCircuitBreaker instance
   */
  public static <R, V, E extends Exception>
      IntelligentCircuitBreaker<R, V, E> create(
          String name,
          int maxFailures,
          Duration failureWindow,
          Duration halfOpenTimeout,
          Duration minBackoff,
          Duration maxBackoff) {
    if (maxFailures <= 0) throw new IllegalArgumentException("maxFailures must be > 0");
    Objects.requireNonNull(failureWindow, "failureWindow cannot be null");
    Objects.requireNonNull(halfOpenTimeout, "halfOpenTimeout cannot be null");
    Objects.requireNonNull(minBackoff, "minBackoff cannot be null");
    Objects.requireNonNull(maxBackoff, "maxBackoff cannot be null");

    return new IntelligentCircuitBreaker<>(
        name, maxFailures, failureWindow, halfOpenTimeout, minBackoff, maxBackoff);
  }

  /**
   * Execute a request with intelligent retry and failure learning.
   *
   * <p>The handler is invoked if the circuit is CLOSED or HALF_OPEN. If OPEN, returns
   * CircuitOpen immediately. On success, the circuit remains/becomes CLOSED. On failure, the type
   * is classified and statistics are updated.
   *
   * @param request the input request
   * @param handler function to execute
   * @return Success, Failure (with classification), or CircuitOpen
   */
  @SuppressWarnings("unchecked")
  public Result<V, E> execute(R request, Function<R, V> handler) {
    synchronized (this) {
      State currentState = state;

      // State machine: transition and check if execution is allowed
      switch (currentState) {
        case OPEN -> {
          if (openedTime != null
              && Instant.now().isAfter(openedTime.plus(halfOpenTimeout))) {
            state = State.HALF_OPEN;
            retryAttempt = 0;
          } else {
            long remainingMs =
                halfOpenTimeout.toMillis()
                    - Duration.between(openedTime, Instant.now()).toMillis();
            return new Result.CircuitOpen<>(Math.max(0, (int) remainingMs));
          }
        }
        case HALF_OPEN -> {
          // Allow limited retries in half-open state
          if (retryAttempt >= 3) {
            // Reopen if too many retry attempts failed
            state = State.OPEN;
            openedTime = Instant.now();
            long remainingMs = halfOpenTimeout.toMillis();
            return new Result.CircuitOpen<>(Math.max(0, (int) remainingMs));
          }
        }
        case CLOSED -> {
          // Prune old failures
          Instant cutoff = Instant.now().minus(failureWindow);
          failureTimes.removeIf(t -> t.isBefore(cutoff));
        }
      }
    }

    // Execute handler
    try {
      V value = handler.apply(request);
      handleSuccess();
      return new Result.Success<>(value);
    } catch (Exception e) {
      return handleFailure((E) e);
    }
  }

  /**
   * Calculate backoff delay with exponential increase and jitter.
   *
   * <p>Uses the formula: delay = min_delay * (2 ^ attempt) + random_jitter, capped at max_delay
   *
   * @return backoff duration
   */
  public Duration calculateBackoff() {
    long baseDelay = Math.min(
        adaptiveMinBackoffMs * (1L << Math.min(retryAttempt, 10)),
        adaptiveMaxBackoffMs);
    long jitter = random.nextLong(Math.max(1, adaptiveMinBackoffMs));
    long delay = Math.min(baseDelay + jitter, adaptiveMaxBackoffMs);
    return Duration.ofMillis(Math.max(adaptiveMinBackoffMs, delay));
  }

  /**
   * Check if a retry should be attempted based on failure classification.
   *
   * @param exception the thrown exception
   * @return true if retry is predicted to likely succeed
   */
  public boolean shouldRetry(E exception) {
    FailureStats stats = failureStats.computeIfAbsent(
        exception.getClass(), k -> new FailureStats());
    FailureClassification classification = stats.classify();

    return switch (classification) {
      case TRANSIENT -> true;
      case PERMANENT -> false;
      case UNKNOWN -> stats.totalFailures < 5; // Explore initially
    };
  }

  /**
   * Predict whether the service will be recovered soon.
   *
   * @return estimated recovery time, or empty if no pattern detected
   */
  public Optional<Duration> predictRecoveryTime() {
    FailureStats stats =
        failureStats.values().stream()
            .filter(s -> s.totalFailures > 0)
            .findFirst()
            .orElse(null);

    if (stats == null || stats.getAverageRecoveryTimeMs() == 0) {
      return Optional.empty();
    }

    long avgRecoveryMs = stats.getAverageRecoveryTimeMs();
    return Optional.of(Duration.ofMillis(Math.min(avgRecoveryMs, adaptiveMaxBackoffMs)));
  }

  /** Classify exception type as transient or permanent. */
  private FailureClassification classifyException(E exception) {
    String exMessage = exception.getMessage() != null ? exception.getMessage() : "";
    String className = exception.getClass().getSimpleName();

    // Heuristic classification based on exception type
    if (className.contains("Timeout")
        || className.contains("SocketTimeout")
        || exMessage.contains("temporarily unavailable")
        || exMessage.contains("UNAVAILABLE")) {
      return FailureClassification.TRANSIENT;
    }

    if (className.contains("ConnectionRefused")
        || className.contains("SocketException")
        || exMessage.contains("reset by peer")
        || exMessage.contains("Connection refused")) {
      return FailureClassification.TRANSIENT;
    }

    if (className.contains("BadRequest")
        || className.contains("NotFound")
        || className.contains("Unauthorized")
        || className.contains("Forbidden")
        || exMessage.contains("404")
        || exMessage.contains("400")
        || exMessage.contains("401")) {
      return FailureClassification.PERMANENT;
    }

    // Unknown: check learned patterns
    FailureStats stats =
        failureStats.computeIfAbsent(exception.getClass(), k -> new FailureStats());
    return stats.classify();
  }

  /** Handle successful execution: reset failure counters and learn recovery. */
  private void handleSuccess() {
    synchronized (this) {
      if (lastFailureTime != null) {
        // Learn recovery time
        long recoveryMs = Duration.between(lastFailureTime, Instant.now()).toMillis();
        failureStats.values().forEach(stats -> stats.totalRecoveryTimeMs += recoveryMs);
      }

      consecutiveFailures = 0;
      retryAttempt = 0;
      failureTimes.clear();
      lastFailureTime = null;

      if (state == State.HALF_OPEN) {
        state = State.CLOSED;
        adaptiveMinBackoffMs = minBackoff.toMillis();
      }
    }
  }

  /** Handle failed execution: classify failure and decide circuit state. */
  @SuppressWarnings("unchecked")
  private Result<V, E> handleFailure(E exception) {
    FailureClassification classification = classifyException(exception);

    synchronized (this) {
      lastFailureTime = Instant.now();
      failureTimes.add(lastFailureTime);
      consecutiveFailures++;
      retryAttempt++;

      // Update statistics
      FailureStats stats =
          failureStats.computeIfAbsent(exception.getClass(), k -> new FailureStats());
      stats.totalFailures++;

      // Prune old failures
      Instant cutoff = lastFailureTime.minus(failureWindow);
      failureTimes.removeIf(t -> t.isBefore(cutoff));

      // Decide state transition based on failure type
      int recentFailures = failureTimes.size();

      if (classification == FailureClassification.PERMANENT && state == State.CLOSED) {
        // Fail fast on permanent failures
        if (recentFailures >= 2) {
          state = State.OPEN;
          openedTime = lastFailureTime;
        }
      } else if (classification == FailureClassification.TRANSIENT && state == State.CLOSED) {
        // Transient: only open after many failures
        if (recentFailures >= maxFailures) {
          state = State.OPEN;
          openedTime = lastFailureTime;
          adaptiveMinBackoffMs = Math.min(adaptiveMaxBackoffMs, adaptiveMinBackoffMs * 2);
        }
      } else if (state == State.HALF_OPEN) {
        // In half-open, failure reopens immediately
        state = State.OPEN;
        openedTime = lastFailureTime;
      }
    }

    return new Result.Failure<>(exception, classification);
  }

  /**
   * Record a successful retry to learn from experience.
   *
   * @param exception the originally thrown exception
   */
  public void recordRetrySuccess(E exception) {
    failureStats
        .computeIfAbsent(exception.getClass(), k -> new FailureStats())
        .successfulRetries++;
  }

  /**
   * Record a failed retry to update statistics.
   *
   * @param exception the originally thrown exception
   */
  public void recordRetryFailure(E exception) {
    failureStats
        .computeIfAbsent(exception.getClass(), k -> new FailureStats())
        .failedRetries++;
  }

  /** Get the current circuit state. */
  public State getState() {
    return state;
  }

  /** Get the number of consecutive failures. */
  public int getConsecutiveFailures() {
    return consecutiveFailures;
  }

  /** Get the current retry attempt number. */
  public int getRetryAttempt() {
    return retryAttempt;
  }

  /** Get the number of recent failures within the window. */
  public int getRecentFailureCount() {
    synchronized (this) {
      Instant cutoff = Instant.now().minus(failureWindow);
      failureTimes.removeIf(t -> t.isBefore(cutoff));
      return failureTimes.size();
    }
  }

  /** Get detailed statistics for a specific exception class. */
  public Optional<String> getFailureStats(Class<?> exceptionClass) {
    FailureStats stats = failureStats.get(exceptionClass);
    if (stats == null) {
      return Optional.empty();
    }

    return Optional.of(
        String.format(
            "Failures: %d, Successful Retries: %d, Failed Retries: %d, "
                + "Success Rate: %.1f%%, Avg Recovery: %dms, Classification: %s",
            stats.totalFailures,
            stats.successfulRetries,
            stats.failedRetries,
            stats.getRetrySuccessRate() * 100,
            stats.getAverageRecoveryTimeMs(),
            stats.classify()));
  }

  /** Get summary statistics for all exceptions. */
  public String getDetailedStats() {
    return failureStats.entrySet().stream()
        .map(
            e ->
                String.format(
                    "%s: %s",
                    e.getKey().getSimpleName(),
                    getFailureStats(e.getKey()).orElse("N/A")))
        .collect(Collectors.joining("\n"));
  }

  /** Reset the circuit to CLOSED state and clear all statistics. */
  public void reset() {
    synchronized (this) {
      state = State.CLOSED;
      failureTimes.clear();
      lastFailureTime = null;
      openedTime = null;
      consecutiveFailures = 0;
      retryAttempt = 0;
      failureStats.clear();
      adaptiveMinBackoffMs = minBackoff.toMillis();
      adaptiveMaxBackoffMs = maxBackoff.toMillis();
    }
  }

  /** Forcibly open the circuit (for manual override or testing). */
  public void open() {
    synchronized (this) {
      state = State.OPEN;
      openedTime = Instant.now();
    }
  }

  @Override
  public String toString() {
    return String.format(
        "[IntelligentCircuitBreaker: %s, state=%s, failures=%d, "
            + "consecutive=%d, retries=%d, minBackoff=%dms, maxBackoff=%dms]",
        name,
        state,
        getRecentFailureCount(),
        consecutiveFailures,
        retryAttempt,
        adaptiveMinBackoffMs,
        adaptiveMaxBackoffMs);
  }
}
