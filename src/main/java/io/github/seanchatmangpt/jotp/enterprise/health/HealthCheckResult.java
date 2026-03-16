package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;

/**
 * Result of a single health check execution.
 *
 * <p>Sealed interface representing the outcome of executing a health check. Provides detailed
 * information about check success/failure, execution duration, and error messages for debugging.
 * Results are emitted as {@link HealthEvent} for observability.
 *
 * <h2>Result Types:</h2>
 *
 * <ul>
 *   <li><b>Pass</b>: Check executed successfully, service is healthy. Contains check name,
 *       execution duration, and timestamp. Indicates the checked component is operational
 *   <li><b>Fail</b>: Check execution failed or returned unhealthy result. Contains check name,
 *       error message, execution duration, and timestamp. Indicates the checked component is
 *       degraded or down. Error message provides diagnostic information
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Process health check results
 * HealthCheckResult result = manager.getLastResult("database-connection");
 * if (result != null) {
 *     switch (result) {
 *         case HealthCheckResult.Pass(var name, var duration, var ts) ->
 *             log.info("Check {} passed in {}ms", name, duration.toMillis());
 *         case HealthCheckResult.Fail(var name, var error, var duration, var ts) ->
 *             log.error("Check {} failed in {}ms: {}", name, duration.toMillis(), error);
 *     }
 * }
 *
 * // Check if healthy
 * if (result != null && result.isPassed()) {
 *     // Service is healthy
 * }
 * }</pre>
 *
 * <h2>Metrics Collection:</h2>
 *
 * <pre>
 * Pass rate        → (passCount / totalCount) * 100
 * Avg duration     → sum(pass.duration + fail.duration) / totalCount
 * P95 duration     → percentile of all durations
 * Failure rate     → (failCount / totalCount) * 100
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Returned by {@link HealthCheckManager#getLastResult(String)} for each check
 *   <li>Wrapped in {@link HealthEvent.HealthCheckPassed} or {@link HealthEvent.HealthCheckFailed}
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Used by {@link HealthCheckManager} to track consecutive passes/failures for thresholds
 * </ul>
 *
 * @see HealthCheck
 * @see HealthCheckManager
 * @see HealthEvent
 * @since 1.0
 */
public sealed interface HealthCheckResult permits HealthCheckResult.Pass, HealthCheckResult.Fail {

    record Pass(String checkName, Duration duration, long timestamp) implements HealthCheckResult {}

    record Fail(String checkName, String error, Duration duration, long timestamp)
            implements HealthCheckResult {
        public Fail(String checkName, String error, Duration duration) {
            this(checkName, error, duration, System.currentTimeMillis());
        }
    }

    default String checkName() {
        return switch (this) {
            case Pass(var name, _, _) -> name;
            case Fail(var name, _, _, _) -> name;
        };
    }

    default Duration duration() {
        return switch (this) {
            case Pass(_, var d, _) -> d;
            case Fail(_, _, var d, _) -> d;
        };
    }

    default boolean isPassed() {
        return this instanceof Pass;
    }
}
