package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;

/**
 * Sealed interface for health check lifecycle events.
 *
 * <p>Broadcast via EventManager when health status changes occur.
 */
public sealed interface HealthEvent
        permits HealthEvent.HealthCheckStarted,
                HealthEvent.HealthCheckPassed,
                HealthEvent.HealthCheckFailed,
                HealthEvent.StatusTransition,
                HealthEvent.AlertTriggered,
                HealthEvent.ServiceDown {

    record HealthCheckStarted(String service, String checkType, long timestamp)
            implements HealthEvent {}

    record HealthCheckPassed(String service, String checkType, Duration duration, long timestamp)
            implements HealthEvent {}

    record HealthCheckFailed(
            String service, String checkType, String error, Duration duration, long timestamp)
            implements HealthEvent {}

    record StatusTransition(String service, HealthStatus from, HealthStatus to, long timestamp)
            implements HealthEvent {}

    record AlertTriggered(String service, String severity, String message, long timestamp)
            implements HealthEvent {}

    record ServiceDown(String service, String reason, long timestamp) implements HealthEvent {}

    default String serviceName() {
        return switch (this) {
            case HealthCheckStarted(var s, _, _) -> s;
            case HealthCheckPassed(var s, _, _, _) -> s;
            case HealthCheckFailed(var s, _, _, _, _) -> s;
            case StatusTransition(var s, _, _, _) -> s;
            case AlertTriggered(var s, _, _, _) -> s;
            case ServiceDown(var s, _, _) -> s;
        };
    }

    default long timestamp() {
        return switch (this) {
            case HealthCheckStarted(_, _, var ts) -> ts;
            case HealthCheckPassed(_, _, _, var ts) -> ts;
            case HealthCheckFailed(_, _, _, _, var ts) -> ts;
            case StatusTransition(_, _, _, var ts) -> ts;
            case AlertTriggered(_, _, _, var ts) -> ts;
            case ServiceDown(_, _, var ts) -> ts;
        };
    }
}
