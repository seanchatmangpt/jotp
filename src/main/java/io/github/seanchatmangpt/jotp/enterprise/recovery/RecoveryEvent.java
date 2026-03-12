package io.github.seanchatmangpt.jotp.enterprise.recovery;

import java.time.Duration;

/**
 * Sealed interface for recovery/retry lifecycle events.
 *
 * <p>Broadcast via EventManager to track retry attempts and outcomes.
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
