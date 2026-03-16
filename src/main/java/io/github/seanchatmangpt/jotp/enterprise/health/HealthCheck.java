package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Sealed interface representing a single health check for service monitoring.
 *
 * <p>Health checks are non-invasive probes of service health that can be synchronous or
 * asynchronous. They are designed to detect service degradation without killing the target service
 * on failures (unlike {@link io.github.seanchatmangpt.jotp.ProcLink} crash propagation).
 *
 * <h2>Check Types:</h2>
 *
 * <ul>
 *   <li><b>Liveness</b>: Is the service process still running? Implemented via {@link
 *       io.github.seanchatmangpt.jotp.ProcMonitor} to detect process crashes without stopping the
 *       target. Essential for detecting deadlocked or crashed processes
 *   <li><b>Readiness</b>: Can the service handle requests? Typically involves sending a test HTTP
 *       request to a health endpoint and validating the response. Used by load balancers for
 *       traffic routing decisions
 *   <li><b>Startup</b>: Has the service completed initialization? Can query service state via
 *       {@link io.github.seanchatmangpt.jotp.ProcSys} without stopping the service. Useful for
 *       detecting slow startup or initialization failures
 *   <li><b>Custom</b>: User-provided async check function for domain-specific health logic. Allows
 *       checking databases, external APIs, file systems, or any other dependency
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Liveness check: process alive
 * HealthCheck liveness = new HealthCheck.Liveness("process-alive");
 *
 * // Readiness check: HTTP endpoint
 * HealthCheck readiness = new HealthCheck.Readiness(
 *     "http-ready",
 *     "http://localhost:8080/health/ready"
 * );
 *
 * // Startup check: state query
 * HealthCheck startup = new HealthCheck.Startup(
 *     "initialized",
 *     "is_initialized"
 * );
 *
 * // Custom check: database connection
 * HealthCheck dbCheck = new HealthCheck.Custom(
 *     "database",
 *     timeout -> CompletableFuture.supplyAsync(() -> {
 *         try (Connection conn = dataSource.getConnection()) {
 *             return conn.isValid(5);
 *         } catch (SQLException e) {
 *             return false;
 *         }
 *     })
 * );
 * }</pre>
 *
 * <h2>Check Function Interface:</h2>
 *
 * The {@link CheckFunction} interface defines async check execution:
 *
 * <ul>
 *   <li>Input: {@link Duration} timeout for the check
 *   <li>Output: {@link CompletableFuture<Boolean>} where true = healthy, false = unhealthy
 *   <li>Exceptions: Treated as unhealthy (caught by framework)
 * </ul>
 *
 * <h2>Performance Considerations:</h2>
 *
 * <ul>
 *   <li>Checks should be fast (< 5 seconds typical)
 *   <li>Checks should be idempotent (can run multiple times)
 *   <li>Checks should not have side effects
 *   <li>Custom checks must respect timeout parameter
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Liveness checks use {@link io.github.seanchatmangpt.jotp.ProcMonitor} for non-invasive
 *       monitoring
 *   <li>Startup checks can use {@link io.github.seanchatmangpt.jotp.ProcSys} for state queries
 *   <li>All checks are executed by {@link HealthCheckManager} in parallel
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see HealthCheckManager
 * @see HealthCheckResult
 * @see io.github.seanchatmangpt.jotp.ProcMonitor
 * @see io.github.seanchatmangpt.jotp.ProcSys
 * @since 1.0
 */
public sealed interface HealthCheck
        permits HealthCheck.Liveness,
                HealthCheck.Readiness,
                HealthCheck.Startup,
                HealthCheck.Custom {

    /**
     * Liveness check: Is the service process still running?
     *
     * <p>Implemented via ProcessMonitor to detect process crashes without killing the target.
     */
    record Liveness(String name) implements HealthCheck {}

    /**
     * Readiness check: Can the service handle requests?
     *
     * <p>Typically involves sending a test request and validating response.
     */
    record Readiness(String name, String endpoint) implements HealthCheck {}

    /**
     * Startup check: Has the service completed initialization?
     *
     * <p>Can query service state via ProcSys without stopping it.
     */
    record Startup(String name, String stateQuery) implements HealthCheck {}

    /**
     * Custom check with user-provided logic.
     *
     * @param name Check identifier
     * @param fn Async check function returning success (true) or failure (false)
     */
    record Custom(String name, CheckFunction fn) implements HealthCheck {}

    /** Get the check name/identifier. */
    default String name() {
        return switch (this) {
            case Liveness(var n) -> n;
            case Readiness(var n, _) -> n;
            case Startup(var n, _) -> n;
            case Custom(var n, _) -> n;
        };
    }

    /** Function interface for custom health checks. */
    @FunctionalInterface
    interface CheckFunction {
        /**
         * Execute the health check.
         *
         * @param timeout Maximum time to wait for result
         * @return CompletableFuture<Boolean>: true if healthy, false if unhealthy
         */
        CompletableFuture<Boolean> check(Duration timeout);
    }
}
