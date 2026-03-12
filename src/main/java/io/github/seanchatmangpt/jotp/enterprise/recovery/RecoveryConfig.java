package io.github.seanchatmangpt.jotp.enterprise.recovery;

import java.time.Duration;

/**
 * Configuration for enterprise recovery (retry with backoff).
 *
 * <p>Immutable record specifying retry behavior, backoff strategy, and circuit breaker integration.
 */
public record RecoveryConfig(
        String taskName,
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double jitterFactor,
        double backoffMultiplier,
        int circuitBreakerThreshold,
        RetryPolicy policy,
        boolean metricsEnabled) {

    /** Validate configuration constraints. */
    public RecoveryConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maxDelay.isNegative() || maxDelay.isZero()) {
            throw new IllegalArgumentException("maxDelay must be positive");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= initialDelay");
        }
        if (jitterFactor < 0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
        }
        if (backoffMultiplier <= 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be > 1.0");
        }
        if (circuitBreakerThreshold < 1) {
            throw new IllegalArgumentException("circuitBreakerThreshold must be >= 1");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String taskName) {
        return new Builder(taskName);
    }

    public static class Builder {
        private final String taskName;
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofMinutes(5);
        private double jitterFactor = 0.1;
        private double backoffMultiplier = 2.0;
        private int circuitBreakerThreshold = 5;
        private RetryPolicy policy = new RetryPolicy.ExponentialBackoff();
        private boolean metricsEnabled = true;

        public Builder(String taskName) {
            this.taskName = taskName;
        }

        public Builder maxAttempts(int attempts) {
            this.maxAttempts = attempts;
            return this;
        }

        public Builder initialDelay(Duration delay) {
            this.initialDelay = delay;
            return this;
        }

        public Builder maxDelay(Duration delay) {
            this.maxDelay = delay;
            return this;
        }

        public Builder jitterFactor(double factor) {
            this.jitterFactor = factor;
            return this;
        }

        public Builder backoffMultiplier(double multiplier) {
            this.backoffMultiplier = multiplier;
            return this;
        }

        public Builder circuitBreakerThreshold(int threshold) {
            this.circuitBreakerThreshold = threshold;
            return this;
        }

        public Builder policy(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public RecoveryConfig build() {
            return new RecoveryConfig(
                    taskName,
                    maxAttempts,
                    initialDelay,
                    maxDelay,
                    jitterFactor,
                    backoffMultiplier,
                    circuitBreakerThreshold,
                    policy,
                    metricsEnabled);
        }
    }
}
