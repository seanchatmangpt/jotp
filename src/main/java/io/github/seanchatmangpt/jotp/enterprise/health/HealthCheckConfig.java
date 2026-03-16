package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for health check manager execution and status transitions.
 *
 * <p>Immutable record defining how health checks are executed, thresholds for status transitions,
 * timing parameters, and monitoring settings. This configuration controls the sensitivity and
 * behavior of health monitoring.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>serviceName</b>: Name of the service being monitored. Used in metrics, logs, and alerts.
 *       Must be non-empty
 *   <li><b>checks</b>: List of health checks to execute. At least one required. Each check defines
 *       a different aspect of service health (liveness, readiness, startup, custom)
 *   <li><b>checkInterval</b>: How often to run all checks. Shorter = faster detection but higher
 *       load. Typical values: 10-60 seconds
 *   <li><b>timeout</b>: Maximum time to wait for each check. Must be <= checkInterval. Prevents
 *       slow checks from overlapping. Typical values: 5-30 seconds
 *   <li><b>passThreshold</b>: Consecutive successful checks required to transition HEALTHY.
 *       Prevents flapping on transient recovery. Typical values: 1-3
 *   <li><b>failThreshold</b>: Consecutive failed checks required to transition UNHEALTHY. Prevents
 *       flapping on transient failures. Typical values: 2-5
 *   <li><b>metricsEnabled</b>: Whether to emit performance metrics for monitoring dashboards
 * </ul>
 *
 * <h2>Status Transition Logic:</h2>
 *
 * <pre>
 * Any check passes                  → consecutiveSuccesses++
 * Any check fails                   → consecutiveFailures++
 * consecutiveSuccesses >= passThreshold → HEALTHY
 * consecutiveFailures >= failThreshold  → UNHEALTHY
 * Mixed results                     → DEGRADED
 * </pre>
 *
 * <h2>Threshold Selection Guide:</h2>
 *
 * <pre>
 * Fast response required            → passThreshold=1, failThreshold=2
 * Prevent flapping                  → passThreshold=2-3, failThreshold=3-5
 * Critical service                  → passThreshold=1, failThreshold=1 (fail fast)
 * Fault-tolerant service            → passThreshold=3, failThreshold=5 (tolerate transient issues)
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Fast response configuration (critical service)
 * HealthCheckConfig config = HealthCheckConfig.builder("payment-api")
 *     .checks(List.of(
 *         new HealthCheck.Liveness("process-alive"),
 *         new HealthCheck.Readiness("http-ready", "http://localhost:8080/health")
 *     ))
 *     .checkInterval(Duration.ofSeconds(10))
 *     .timeout(Duration.ofSeconds(5))
 *     .passThreshold(1)
 *     .failThreshold(2)
 *     .metricsEnabled(true)
 *     .build();
 *
 * // Fault-tolerant configuration (resilient service)
 * HealthCheckConfig config = HealthCheckConfig.builder("cache-service")
 *     .checks(List.of(
 *         new HealthCheck.Liveness("process-alive"),
 *         new HealthCheck.Custom("cache-hit", timeout -> {
 *             return CompletableFuture.supplyAsync(() ->
 *                 cache.get("health-check-key") != null
 *             );
 *         })
 *     ))
 *     .checkInterval(Duration.ofSeconds(30))
 *     .timeout(Duration.ofSeconds(10))
 *     .passThreshold(3)  // Require 3 consecutive passes
 *     .failThreshold(5)  // Allow 4 consecutive failures
 *     .metricsEnabled(true)
 *     .build();
 * }</pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(checks) for storing last results and history
 *   <li>Latency: O(checks * timeout) for parallel execution
 *   <li>Throughput: Inversely proportional to checkInterval
 *   <li>Network: O(checks) requests per interval (for HTTP checks)
 * </ul>
 *
 * @see HealthCheckManager
 * @see HealthCheck
 * @see HealthStatus
 * @since 1.0
 * @param serviceName Name of the service being monitored
 * @param checks List of health checks to execute
 * @param checkInterval How often to run all checks
 * @param timeout Maximum time to wait for each check
 * @param passThreshold Consecutive successful checks required to transition HEALTHY
 * @param failThreshold Consecutive failed checks required to transition UNHEALTHY
 * @param metricsEnabled Whether to emit performance metrics
 */
public record HealthCheckConfig(
        String serviceName,
        List<HealthCheck> checks,
        Duration checkInterval,
        Duration timeout,
        int passThreshold,
        int failThreshold,
        boolean metricsEnabled) {

    /** Validate configuration constraints. */
    public HealthCheckConfig {
        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (timeout.compareTo(checkInterval) > 0) {
            throw new IllegalArgumentException("timeout must be <= checkInterval");
        }
        if (passThreshold <= 0) {
            throw new IllegalArgumentException("passThreshold must be > 0");
        }
        if (failThreshold <= 0) {
            throw new IllegalArgumentException("failThreshold must be > 0");
        }
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("must register at least one check");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String serviceName) {
        return new Builder(serviceName);
    }

    public static class Builder {
        private final String serviceName;
        private List<HealthCheck> checks = List.of();
        private Duration checkInterval = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(5);
        private int passThreshold = 1;
        private int failThreshold = 2;
        private boolean metricsEnabled = true;

        public Builder(String serviceName) {
            this.serviceName = serviceName;
        }

        public Builder checks(List<HealthCheck> checks) {
            this.checks = checks;
            return this;
        }

        public Builder checkInterval(Duration interval) {
            this.checkInterval = interval;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder passThreshold(int threshold) {
            this.passThreshold = threshold;
            return this;
        }

        public Builder failThreshold(int threshold) {
            this.failThreshold = threshold;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public HealthCheckConfig build() {
            return new HealthCheckConfig(
                    serviceName,
                    checks,
                    checkInterval,
                    timeout,
                    passThreshold,
                    failThreshold,
                    metricsEnabled);
        }
    }
}
