package io.github.seanchatmangpt.jotp.enterprise.saga;

/**
 * Sealed interface for saga lifecycle events.
 *
 * <p>Broadcast via EventManager to track saga execution, failures, and compensation.
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
