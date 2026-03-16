package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for bulkhead isolation.
 *
 * <p>Immutable record defining resource limits, queue behavior, alert thresholds, and monitoring
 * settings for bulkhead pattern implementation.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>featureName</b>: Unique identifier for the feature being isolated. Used in metrics,
 *       logs, and alerts. Must be non-empty
 *   <li><b>strategy</b>: Resource allocation strategy (ThreadPool, Process-based, Weighted,
 *       Adaptive). Determines how resources are isolated between features
 *   <li><b>limits</b>: List of resource constraints (MaxConcurrentRequests, MaxQueueSize,
 *       MaxMemoryBytes, MaxCPUPercent, Composite). At least one limit required
 *   <li><b>queueTimeout</b>: Maximum time to wait for available capacity. After this, requests are
 *       rejected. Typical values: 10-60 seconds
 *   <li><b>alertThreshold</b>: Utilization percentage (0.0-1.0) for triggering DEGRADED state.
 *       Typical values: 0.70-0.90
 * </ul>
 *
 * <h2>Resource Limits:</h2>
 *
 * <pre>
 * MaxConcurrentRequests(10)    // Max 10 concurrent requests
 * MaxQueueSize(100)            // Max 100 queued requests
 * MaxMemoryBytes(1024*1024*100) // Max 100MB heap
 * MaxCPUPercent(80.0)          // Max 80% CPU
 * Composite(List.of(...))      // Multiple limits (all must pass)
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * BulkheadConfig config = BulkheadConfig.builder("video-encoding")
 *     .strategy(new BulkheadStrategy.ProcessBased())
 *     .limits(List.of(
 *         new ResourceLimit.MaxConcurrentRequests(5),
 *         new ResourceLimit.MaxQueueSize(50)
 *     ))
 *     .queueTimeout(Duration.ofSeconds(30))
 *     .alertThreshold(0.80)
 *     .metricsEnabled(true)
 *     .build();
 * }</pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(maxQueueSize) for pending requests
 *   <li>Latency: O(1) for permit acquire, O(1) for task execution
 *   <li>Throughput: Limited by maxConcurrentRequests setting
 * </ul>
 *
 * @see BulkheadIsolationEnterprise
 * @see BulkheadStrategy
 * @see ResourceLimit
 * @since 1.0
 * @param featureName Unique identifier for the feature being isolated
 * @param strategy Resource allocation strategy
 * @param limits List of resource constraints
 * @param queueTimeout Maximum time to wait for available capacity
 * @param alertThreshold Utilization percentage for triggering alerts (0.0-1.0)
 * @param metricsEnabled Whether to emit metrics
 */
public record BulkheadConfig(
        String featureName,
        BulkheadStrategy strategy,
        List<ResourceLimit> limits,
        Duration queueTimeout,
        double alertThreshold,
        boolean metricsEnabled) {

    /** Validate configuration constraints. */
    public BulkheadConfig {
        if (featureName == null || featureName.isEmpty()) {
            throw new IllegalArgumentException("featureName must not be empty");
        }
        if (queueTimeout.isNegative() || queueTimeout.isZero()) {
            throw new IllegalArgumentException("queueTimeout must be positive");
        }
        if (alertThreshold < 0 || alertThreshold > 1.0) {
            throw new IllegalArgumentException("alertThreshold must be between 0.0 and 1.0");
        }
        if (limits.isEmpty()) {
            throw new IllegalArgumentException("must define at least one resource limit");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String featureName) {
        return new Builder(featureName);
    }

    public static class Builder {
        private final String featureName;
        private BulkheadStrategy strategy = new BulkheadStrategy.ProcessBased();
        private List<ResourceLimit> limits = List.of(new ResourceLimit.MaxConcurrentRequests(10));
        private Duration queueTimeout = Duration.ofSeconds(30);
        private double alertThreshold = 0.80;
        private boolean metricsEnabled = true;

        public Builder(String featureName) {
            this.featureName = featureName;
        }

        public Builder strategy(BulkheadStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder limits(List<ResourceLimit> limits) {
            this.limits = limits;
            return this;
        }

        public Builder queueTimeout(Duration timeout) {
            this.queueTimeout = timeout;
            return this;
        }

        public Builder alertThreshold(double threshold) {
            this.alertThreshold = threshold;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public BulkheadConfig build() {
            return new BulkheadConfig(
                    featureName, strategy, limits, queueTimeout, alertThreshold, metricsEnabled);
        }
    }
}
