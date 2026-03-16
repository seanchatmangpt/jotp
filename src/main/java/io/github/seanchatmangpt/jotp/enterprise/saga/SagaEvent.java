package io.github.seanchatmangpt.jotp.enterprise.saga;

/**
 * Sealed interface for saga lifecycle events and observability.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track saga execution, step
 * completion, failures, compensation, and aborts. These events provide comprehensive observability
 * into distributed transaction behavior for monitoring, alerting, debugging, and audit logging.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>SagaStarted</b>: Saga execution initiated. Tracks saga rate
 *   <li><b>StepExecuted</b>: Step completed successfully. Tracks step latency and output
 *   <li><b>StepFailed</b>: Step execution failed. Tracks failure reasons and triggers compensation
 *   <li><b>CompensationStarted</b>: Compensation phase initiated. Tracks rollback start
 *   <li><b>CompensationCompleted</b>: Compensation phase completed. Tracks rollback duration
 *   <li><b>SagaCompleted</b>: All steps succeeded. Tracks successful saga duration
 *   <li><b>SagaCompensated</b>: Saga failed, compensation completed. Tracks failure recovery
 *   <li><b>SagaAborted</b>: User-requested abort. Tracks manual intervention
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to saga events
 * EventManager<SagaEvent> events = EventManager.create();
 * events.subscribe(SagaEvent.class, event -> {
 *     switch (event) {
 *         case SagaEvent.SagaStarted(var id, var steps, var ts) ->
 *             log.info("Saga {} started with {} steps", id, steps);
 *
 *         case SagaEvent.StepFailed(var id, var step, var error, var ts) ->
 *             log.error("Saga {} step {} failed: {}", id, step, error);
 *             metrics.counter("saga.step.failures", "saga", id, "step", step).increment();
 *
 *         case SagaEvent.CompensationStarted(var id, var fromStep, var ts) ->
 *             log.warn("Saga {} starting compensation from step {}", id, fromStep);
 *             alerts.fire("Saga compensation initiated: " + id);
 *
 *         case SagaEvent.SagaCompensated(var id, var reason, var dur, var ts) ->
 *             log.error("Saga {} compensated after {}ms: {}", id, dur, reason);
 *             metrics.record("saga.compensation.duration", dur, "saga", id);
 *
 *         case SagaEvent.SagaCompleted(var id, var dur, var ts) ->
 *             log.info("Saga {} completed successfully in {}ms", id, dur);
 *             metrics.record("saga.duration", dur, "saga", id);
 *
 *         case SagaEvent.SagaAborted(var id, var userReason, var ts) ->
 *             log.warn("Saga {} aborted by user: {}", id, userReason);
 *
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Monitoring Metrics:</h2>
 *
 * <pre>
 * SagaStarted rate            → Saga throughput (sagas/min)
 * StepExecuted latency        → Step performance (p50, p95, p99 per step)
 * StepFailed rate             → Failure rate (failures/saga, failures/step)
 * CompensationStarted rate    → Rollback rate (compensations/min)
 * SagaCompleted duration      → Success latency (p50, p95, p99)
 * SagaCompensated duration    → Rollback latency (p50, p95, p99)
 * SagaAborted count           → Manual intervention rate (aborts/day)
 * </pre>
 *
 * <h2>Alerting Rules:</h2>
 *
 * <pre>
 * StepFailed                  → WARNING (compensation starting)
 * CompensationStarted         → WARNING (data rollback in progress)
 * 5+ SagaCompensated in 1min  → CRITICAL (systemic failure)
 * SagaCompleted duration>5min → WARNING (performance degradation)
 * </pre>
 *
 * <h2>Audit Logging:</h2>
 *
 * <pre>
 * SagaStarted                 → Log transaction initiation
 * StepExecuted                → Log each step output (for replay)
 * StepFailed                  → Log failure reason (for debugging)
 * SagaCompensated             → Log rollback details (for audit)
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link DistributedSagaCoordinator} on all state changes
 *   <li>Consumed by monitoring systems (Prometheus, Grafana, Datadog)
 *   <li>Consumed by audit logging systems for compliance
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see DistributedSagaCoordinator
 * @see SagaConfig
 * @see SagaTransaction
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface SagaEvent
        permits SagaEvent.SagaStarted,
                SagaEvent.StepExecuted,
                SagaEvent.StepFailed,
                SagaEvent.CompensationStarted,
                SagaEvent.CompensationCompleted,
                SagaEvent.SagaCompleted,
                SagaEvent.SagaCompensated,
                SagaEvent.SagaAborted {

    record SagaStarted(String sagaId, int stepCount, long timestamp) implements SagaEvent {}

    record StepExecuted(String sagaId, String stepName, Object output, long timestamp)
            implements SagaEvent {}

    record StepFailed(String sagaId, String stepName, String error, long timestamp)
            implements SagaEvent {}

    record CompensationStarted(String sagaId, int fromStep, long timestamp) implements SagaEvent {}

    record CompensationCompleted(String sagaId, long durationMs, long timestamp)
            implements SagaEvent {}

    record SagaCompleted(String sagaId, long durationMs, long timestamp) implements SagaEvent {}

    record SagaCompensated(String sagaId, String reason, long durationMs, long timestamp)
            implements SagaEvent {}

    record SagaAborted(String sagaId, String userReason, long timestamp) implements SagaEvent {}

    default String sagaId() {
        return switch (this) {
            case SagaStarted(var id, _, _) -> id;
            case StepExecuted(var id, _, _, _) -> id;
            case StepFailed(var id, _, _, _) -> id;
            case CompensationStarted(var id, _, _) -> id;
            case CompensationCompleted(var id, _, _) -> id;
            case SagaCompleted(var id, _, _) -> id;
            case SagaCompensated(var id, _, _, _) -> id;
            case SagaAborted(var id, _, _) -> id;
        };
    }

    default long timestamp() {
        return switch (this) {
            case SagaStarted(_, _, var ts) -> ts;
            case StepExecuted(_, _, _, var ts) -> ts;
            case StepFailed(_, _, _, var ts) -> ts;
            case CompensationStarted(_, _, var ts) -> ts;
            case CompensationCompleted(_, _, var ts) -> ts;
            case SagaCompleted(_, _, var ts) -> ts;
            case SagaCompensated(_, _, _, var ts) -> ts;
            case SagaAborted(_, _, var ts) -> ts;
        };
    }
}
