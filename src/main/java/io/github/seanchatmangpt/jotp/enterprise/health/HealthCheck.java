package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Sealed interface representing a single health check.
 *
 * <p>Health checks are non-invasive probes of service health. They can be synchronous or
 * asynchronous and should not kill the target service if they fail.
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
