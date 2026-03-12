package io.github.seanchatmangpt.jotp.enterprise.saga;

/**
 * Sealed interface representing saga transaction state.
 *
 * <p>Tracks progress through execution and compensation phases.
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
