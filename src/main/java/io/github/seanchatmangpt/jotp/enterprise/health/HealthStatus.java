package io.github.seanchatmangpt.jotp.enterprise.health;

/**
 * Sealed interface representing the health status of a monitored service.
 *
 * <p>Used by HealthCheckManager to track transitions: Healthy → Degraded → Unhealthy. Each status
 * provides exhaustiveness checking via pattern matching in Java 26.
 */
public sealed interface HealthStatus
        permits HealthStatus.Healthy,
                HealthStatus.Degraded,
                HealthStatus.Unhealthy,
                HealthStatus.Unreachable {

    /** All health checks passed; service operational. */
    record Healthy(long timestamp) implements HealthStatus {}

    /** Some checks failed but service still functional. */
    record Degraded(long timestamp, String reason, double failureRate) implements HealthStatus {}

    /** Critical checks failed; service degraded. */
    record Unhealthy(long timestamp, String reason, Throwable lastError) implements HealthStatus {}

    /** Service unreachable; cannot connect. */
    record Unreachable(long timestamp, String reason) implements HealthStatus {}

    /** Get timestamp of last status change. */
    default long timestamp() {
        return switch (this) {
            case Healthy(var ts) -> ts;
            case Degraded(var ts, _, _) -> ts;
            case Unhealthy(var ts, _, _) -> ts;
            case Unreachable(var ts, _) -> ts;
        };
    }

    /** Check if service is operational (Healthy or Degraded). */
    default boolean isOperational() {
        return switch (this) {
            case Healthy _, Degraded _ -> true;
            case Unhealthy _, Unreachable _ -> false;
        };
    }
}
