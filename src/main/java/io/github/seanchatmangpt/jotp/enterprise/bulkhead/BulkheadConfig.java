package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for bulkhead isolation.
 *
 * <p>Immutable record defining resource limits, queue behavior, and alert thresholds.
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
