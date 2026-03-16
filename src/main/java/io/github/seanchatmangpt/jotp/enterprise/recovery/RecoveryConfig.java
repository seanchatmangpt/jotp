package io.github.seanchatmangpt.jotp.enterprise.recovery;

import java.time.Duration;

/**
 * Configuration for enterprise recovery with retry and backoff behavior.
 *
 * <p>Immutable record defining retry limits, backoff strategy, jitter settings, circuit breaker
 * integration, and monitoring settings for enterprise-grade failure recovery. This configuration
 * controls the trade-offs between recovery speed, resource usage, and thundering herd prevention.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>taskName</b>: Name of the task being retried. Used in logs, metrics, and alerts. Must be
 *       non-empty for observability
 *   <li><b>maxAttempts</b>: Maximum number of retry attempts. Higher = more resilience but longer
 *       delay before final failure. Typical values: 3-10
 *   <li><b>initialDelay</b>: Starting delay before first retry. Too short = retry storm, too long =
 *       slow recovery. Typical values: 100-500ms
 *   <li><b>maxDelay</b>: Maximum delay cap. Prevents excessive waiting on long failure streaks.
 *       Typical values: 5-60 seconds
 *   <li><b>jitterFactor</b>: Randomization factor (0.0-1.0) to spread out retries. Prevents
 *       thundering herd. Typical values: 0.1-0.3
 *   <li><b>backoffMultiplier</b>: Exponential growth factor (must be > 1.0). Controls how fast
 *       delays increase. Typical values: 1.5-3.0
 *   <li><b>circuitBreakerThreshold</b>: Consecutive failures before tripping circuit breaker.
 *       Prevents retrying dead services. Typical values: 5-10
 *   <li><b>policy</b>: Backoff strategy (Exponential, Linear, Fixed, Capped). Determines delay
 *       calculation
 *   <li><b>metricsEnabled</b>: Whether to emit performance metrics for monitoring
 * </ul>
 *
 * <h2>Backoff Calculation:</h2>
 *
 * <pre>
 * // ExponentialBackoff
 * delay = initialDelay * 2^(attempt-1)
 * cappedDelay = min(delay, maxDelay)
 *
 * // ExponentialCapped (with jitter)
 * delay = min(initialDelay * 2^(attempt-1), maxDelay)
 * jitteredDelay = delay * (1 ± jitterFactor)
 *
 * // LinearBackoff
 * delay = initialDelay * attempt
 *
 * // FixedDelay
 * delay = initialDelay (constant)
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Aggressive retry (fast recovery, low resilience)
 * RecoveryConfig aggressive = RecoveryConfig.builder("cache-miss")
 *     .maxAttempts(3)
 *     .initialDelay(Duration.ofMillis(50))
 *     .maxDelay(Duration.ofSeconds(1))
 *     .jitterFactor(0.2)
 *     .backoffMultiplier(2.0)
 *     .policy(new RetryPolicy.ExponentialBackoff())
 *     .build();
 *
 * // Conservative retry (slow recovery, high resilience)
 * RecoveryConfig conservative = RecoveryConfig.builder("database-connection")
 *     .maxAttempts(10)
 *     .initialDelay(Duration.ofSeconds(1))
 *     .maxDelay(Duration.ofMinutes(5))
 *     .jitterFactor(0.1)
 *     .backoffMultiplier(2.0)
 *     .circuitBreakerThreshold(5)
 *     .policy(new RetryPolicy.ExponentialCapped())
 *     .build();
 *
 * // Fixed delay (predictable, no exponential growth)
 * RecoveryConfig fixed = RecoveryConfig.builder("api-request")
 *     .maxAttempts(5)
 *     .initialDelay(Duration.ofSeconds(2))
 *     .maxDelay(Duration.ofSeconds(2))
 *     .policy(new RetryPolicy.FixedDelay())
 *     .build();
 * }</pre>
 *
 * <h2>Performance vs Resilience Trade-offs:</h2>
 *
 * <pre>
 * Fast recovery (aggressive):     Low maxAttempts, low initialDelay, high multiplier
 * High resilience (conservative):  High maxAttempts, high initialDelay, low multiplier
 * Predictable behavior:           FixedDelay policy, low jitterFactor
 * Thundering herd prevention:     High jitterFactor (0.3-0.5)
 * </pre>
 *
 * @see EnterpriseRecovery
 * @see RetryPolicy
 * @since 1.0
 * @param taskName Name of the task being retried
 * @param maxAttempts Maximum number of retry attempts
 * @param initialDelay Starting delay before first retry
 * @param maxDelay Maximum delay cap
 * @param jitterFactor Randomization factor (0.0-1.0)
 * @param backoffMultiplier Exponential growth factor (> 1.0)
 * @param circuitBreakerThreshold Consecutive failures before tripping circuit
 * @param policy Backoff strategy
 * @param metricsEnabled Whether to emit performance metrics
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
