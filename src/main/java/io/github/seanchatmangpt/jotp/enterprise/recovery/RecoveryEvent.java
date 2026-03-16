package io.github.seanchatmangpt.jotp.enterprise.recovery;

import java.time.Duration;

/**
 * Sealed interface for recovery and retry lifecycle events.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track retry attempts,
 * successes, failures, circuit breaker trips, and completion. These events provide comprehensive
 * observability into retry behavior for monitoring, alerting, and failure analysis.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>AttemptStarted</b>: New retry attempt initiated. Tracks retry rate
 *   <li><b>AttemptSucceeded</b>: Attempt succeeded. Tracks success latency and final attempt number
 *   <li><b>AttemptFailed</b>: Attempt failed. Tracks failure reasons and retry progression
 *   <li><b>MaxAttemptsExceeded</b>: All retries exhausted without success. Final failure
 *   <li><b>CircuitBreakerTripped</b>: Too many consecutive failures, circuit opened. Critical
 *   <li><b>RecoveryCompleted</b>: Retry cycle completed (success or failure). Summary stats
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to recovery events
 * EventManager<RecoveryEvent> events = EventManager.create();
 * events.subscribe(RecoveryEvent.class, event -> {
 *     switch (event) {
 *         case RecoveryEvent.AttemptFailed(var task, var attempt, var err, var dur, var ts) ->
 *             log.warn("Task {} attempt {} failed in {}ms: {}", task, attempt, dur, err);
 *
 *         case RecoveryEvent.MaxAttemptsExceeded(var task, var attempts, var lastErr, var ts) ->
 *             alerts.fire("Task {} failed after {} attempts: {}", task, attempts, lastErr);
 *
 *         case RecoveryEvent.CircuitBreakerTripped(var task, var reason, var ts) ->
 *             log.error("Circuit breaker tripped for {}: {}", task, reason);
 *             // Trigger manual intervention or service restart
 *
 *         case RecoveryEvent.RecoveryCompleted(var task, var attempts, var totalDur, var ts) ->
 *             metrics.record("recovery.duration", totalDur, "task", task);
 *             metrics.record("recovery.attempts", attempts, "task", task);
 *
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Monitoring Metrics:</h2>
 *
 * <pre>
 * AttemptStarted rate         → Retry volume (attempts/sec)
 * AttemptSucceeded rate       → Recovery success rate (successes/sec)
 * AttemptFailed rate          → Retry failure rate (failures/sec)
 * MaxAttemptsExceeded rate    → Recovery failures (failures/min)
 * CircuitBreakerTripped rate  → Circuit breaker trips (trips/hour)
 * RecoveryCompleted duration  → Total recovery time (p50, p95, p99)
 * RecoveryCompleted attempts  → Average attempts per recovery
 * </pre>
 *
 * <h2>Alerting Rules:</h2>
 *
 * <pre>
 * MaxAttemptsExceeded         → WARNING (task failing consistently)
 * CircuitBreakerTripped       → CRITICAL (service down, retrying is pointless)
 * 5+ AttemptFailed in 1min    → WARNING (potential degradation)
 * RecoveryCompleted attempts>5 → INFO (high retry count, investigate)
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link EnterpriseRecovery} coordinator on all retry attempts
 *   <li>Consumed by monitoring systems (Prometheus, Grafana, Datadog)
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Non-blocking emission (doesn't slow down retry execution)
 * </ul>
 *
 * @see EnterpriseRecovery
 * @see RecoveryConfig
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface RecoveryEvent
        permits RecoveryEvent.AttemptStarted,
                RecoveryEvent.AttemptSucceeded,
                RecoveryEvent.AttemptFailed,
                RecoveryEvent.MaxAttemptsExceeded,
                RecoveryEvent.CircuitBreakerTripped,
                RecoveryEvent.RecoveryCompleted {

    record AttemptStarted(String taskName, int attemptNumber, Duration delay, long timestamp)
            implements RecoveryEvent {}

    record AttemptSucceeded(String taskName, int attemptNumber, Duration duration, long timestamp)
            implements RecoveryEvent {}

    record AttemptFailed(
            String taskName, int attemptNumber, String error, Duration duration, long timestamp)
            implements RecoveryEvent {}

    record MaxAttemptsExceeded(String taskName, int totalAttempts, String lastError, long timestamp)
            implements RecoveryEvent {}

    record CircuitBreakerTripped(String taskName, String reason, long timestamp)
            implements RecoveryEvent {}

    record RecoveryCompleted(String taskName, int attempts, Duration totalDuration, long timestamp)
            implements RecoveryEvent {}

    default String taskName() {
        return switch (this) {
            case AttemptStarted(var t, _, _, _) -> t;
            case AttemptSucceeded(var t, _, _, _) -> t;
            case AttemptFailed(var t, _, _, _, _) -> t;
            case MaxAttemptsExceeded(var t, _, _, _) -> t;
            case CircuitBreakerTripped(var t, _, _) -> t;
            case RecoveryCompleted(var t, _, _, _) -> t;
        };
    }

    default long timestamp() {
        return switch (this) {
            case AttemptStarted(_, _, _, var ts) -> ts;
            case AttemptSucceeded(_, _, _, var ts) -> ts;
            case AttemptFailed(_, _, _, _, var ts) -> ts;
            case MaxAttemptsExceeded(_, _, _, var ts) -> ts;
            case CircuitBreakerTripped(_, _, var ts) -> ts;
            case RecoveryCompleted(_, _, _, var ts) -> ts;
        };
    }
}
