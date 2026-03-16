package io.github.seanchatmangpt.jotp.enterprise.saga;

/**
 * Sealed interface representing saga transaction state and progress.
 *
 * <p>Tracks the execution progress of a distributed saga through forward execution and compensation
 * phases. Each state represents a different stage in the saga lifecycle, enabling comprehensive
 * observability and recovery support.
 *
 * <h2>State Types:</h2>
 *
 * <ul>
 *   <li><b>Pending</b>: Saga registered and awaiting execution. Initial state before saga starts.
 *       No steps executed yet. Can transition to InProgress
 *   <li><b>InProgress</b>: Currently executing forward steps. Saga is actively running. One or more
 *       steps have completed successfully. currentStep tracks progress. Can transition to Completed
 *       or Failed
 *   <li><b>Completed</b>: All steps succeeded. Saga executed successfully without failures.
 *       durationMs tracks total execution time. Terminal state (no further transitions)
 *   <li><b>Failed</b>: A step failed, starting compensation. Forward execution aborted due to step
 *       failure or timeout. failedStep indicates which step failed. error contains failure reason.
 *       Transitions to Compensated
 *   <li><b>Compensated</b>: Compensation completed after failure. Saga failed but all compensations
 *       executed (best-effort). reason explains original failure. durationMs tracks compensation
 *       time. Terminal state
 *   <li><b>Aborted</b>: User-requested abort. Saga manually cancelled via {@code abort()} call.
 *       reason contains user-provided explanation. Terminal state
 * </ul>
 *
 * <h2>State Transitions:</h2>
 *
 * <pre>
 *     ┌─────────┐
 *     │ Pending │
 *     └────┬────┘
 *          │ execute()
 *          ↓
 *     ┌────────────┐
 *     │ InProgress │──────┐
 *     └─────┬──────┘      │ abort()
 *           │             │
 *      ┌────┴────┐        │
 *      │         │        │
 *   All steps  Failure    │
 *   succeed    occurs     │
 *      │         │        │
 *      ↓         ↓        ↓
 * ┌──────────┐ ┌──────┐ ┌────────┐
 * │Completed │ │Failed │ │Aborted │
 * └──────────┘ └───┬──┘ └────────┘
 *                  │
 *                  │ compensate()
 *                  ↓
 *             ┌────────────┐
 *             │Compensated │
 *             └────────────┘
 * </pre>
 *
 * <h2>Terminal States:</h2>
 *
 * <pre>
 * Completed    → Success, no further action needed
 * Compensated  → Failure handled, system rolled back
 * Aborted      → Manual intervention, partial execution
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Check saga status
 * SagaTransaction tx = saga.getStatus(sagaId);
 * if (tx != null) {
 *     switch (tx) {
 *         case SagaTransaction.InProgress(var id, var step) ->
 *             log.info("Saga {} on step {} of {}", id, step, totalSteps);
 *
 *         case SagaTransaction.Completed(var id, var duration) ->
 *             log.info("Saga {} completed in {}ms", id, duration);
 *             metrics.record("saga.duration", duration);
 *
 *         case SagaTransaction.Failed(var id, var failedStep, var error) ->
 *             log.error("Saga {} failed at step {}: {}", id, failedStep, error);
 *             // Compensation is starting
 *
 *         case SagaTransaction.Compensated(var id, var reason, var duration) ->
 *             log.error("Saga {} compensated in {}ms: {}", id, duration, reason);
 *             alertManager.notify("Saga compensation completed");
 *
 *         case SagaTransaction.Aborted(var id, var reason) ->
 *             log.warn("Saga {} aborted: {}", id, reason);
 *             // Manual intervention may be required
 *
 *         case SagaTransaction.Pending(var id) ->
 *             log.info("Saga {} is pending execution", id);
 *     }
 * }
 *
 * // Check if terminal
 * if (tx.isTerminal()) {
 *     // Saga is done (Completed, Compensated, or Aborted)
 *     cleanup(sagaId);
 * }
 * }</pre>
 *
 * <h2>Recovery Support:</h2>
 *
 * <ul>
 *   <li>InProgress with timeout → Force to Aborted, investigate
 *   <li>Failed without Compensated → Missing compensation, manual intervention
 *   <li>Compensated → Review compensation logs for partial compensation
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Returned by {@link DistributedSagaCoordinator#getStatus(String)}
 *   <li>Emitted in {@link SagaEvent} for all state transitions
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Used for saga recovery and monitoring
 * </ul>
 *
 * @see DistributedSagaCoordinator
 * @see SagaConfig
 * @see SagaEvent
 * @since 1.0
 */
public sealed interface SagaTransaction
        permits SagaTransaction.Pending,
                SagaTransaction.InProgress,
                SagaTransaction.Completed,
                SagaTransaction.Failed,
                SagaTransaction.Compensated,
                SagaTransaction.Aborted {

    /** Awaiting execution. */
    record Pending(String sagaId) implements SagaTransaction {}

    /** Currently executing steps. */
    record InProgress(String sagaId, int currentStep) implements SagaTransaction {}

    /** All steps succeeded. */
    record Completed(String sagaId, long durationMs) implements SagaTransaction {}

    /** A step failed, starting compensation. */
    record Failed(String sagaId, int failedStep, String error) implements SagaTransaction {}

    /** Compensation completed after failure. */
    record Compensated(String sagaId, String reason, long durationMs) implements SagaTransaction {}

    /** User-requested abort. */
    record Aborted(String sagaId, String reason) implements SagaTransaction {}

    default String sagaId() {
        return switch (this) {
            case Pending(var id) -> id;
            case InProgress(var id, _) -> id;
            case Completed(var id, _) -> id;
            case Failed(var id, _, _) -> id;
            case Compensated(var id, _, _) -> id;
            case Aborted(var id, _) -> id;
        };
    }

    default boolean isTerminal() {
        return switch (this) {
            case Completed _, Compensated _, Aborted _ -> true;
            case Pending _, InProgress _, Failed _ -> false;
        };
    }
}
