package io.github.seanchatmangpt.jotp.enterprise.backpressure;

import java.time.Duration;

/**
 * Configuration for backpressure handling.
 *
 * <p>Immutable record defining timeout behavior, success rate thresholds, retry policies, and
 * monitoring settings for adaptive backpressure coordination.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>initialTimeout</b>: Starting timeout for requests. Increased on failures, decreased on
 *       successes. Typical values: 100-500ms
 *   <li><b>maxTimeout</b>: Maximum timeout cap. Prevents excessive waiting when service is
 *       unresponsive. Typical values: 5-30s
 *   <li><b>windowSize</b>: Number of recent requests to track for success rate calculation. Larger
 *       windows = smoother but slower adaptation. Typical values: 50-200
 *   <li><b>successRateThreshold</b>: Minimum success rate (0.0-1.0) to remain healthy. Below this,
 *       trigger backpressure. Typical values: 0.90-0.99
 *   <li><b>policy</b>: Strategy for handling timeouts (strict, adaptive, circuit-break)
 * </ul>
 *
 * <h2>Timeout Adaptation:</h2>
 *
 * <pre>
 * Failure detected: timeout = min(timeout * multiplier, maxTimeout)
 * Success detected: timeout = max(timeout * decay_factor, initialTimeout)
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * BackpressureConfig config = BackpressureConfig.builder("external-api")
 *     .initialTimeout(Duration.ofMillis(500))
 *     .maxTimeout(Duration.ofSeconds(30))
 *     .windowSize(100)
 *     .successRateThreshold(0.95)
 *     .policy(new BackpressurePolicy.Adaptive(0.95, 100))
 *     .metricsEnabled(true)
 *     .build();
 * }</pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(windowSize) for tracking request results
 *   <li>Latency: O(1) for timeout calculation, O(windowSize) for success rate
 *   <li>Throughput: No blocking, fail-fast on circuit open
 * </ul>
 *
 * @see Backpressure
 * @see BackpressurePolicy
 * @since 1.0
 * @param serviceName Name of the service being protected
 * @param initialTimeout Starting timeout for requests
 * @param maxTimeout Maximum timeout cap
 * @param windowSize Number of recent requests to track
 * @param successRateThreshold Minimum success rate to remain healthy (0.0-1.0)
 * @param policy Strategy for handling timeouts
 * @param metricsEnabled Whether to emit metrics
 */
public record BackpressureConfig(
        String serviceName,
        Duration initialTimeout,
        Duration maxTimeout,
        int windowSize,
        double successRateThreshold,
        BackpressurePolicy policy,
        boolean metricsEnabled) {

    /** Validate configuration constraints. */
    public BackpressureConfig {
        if (initialTimeout.isNegative() || initialTimeout.isZero()) {
            throw new IllegalArgumentException("initialTimeout must be positive");
        }
        if (maxTimeout.isNegative() || maxTimeout.isZero()) {
            throw new IllegalArgumentException("maxTimeout must be positive");
        }
        if (maxTimeout.compareTo(initialTimeout) < 0) {
            throw new IllegalArgumentException("maxTimeout must be >= initialTimeout");
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        if (successRateThreshold < 0 || successRateThreshold > 1.0) {
            throw new IllegalArgumentException("successRateThreshold must be between 0.0 and 1.0");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String serviceName) {
        return new Builder(serviceName);
    }

    public static class Builder {
        private final String serviceName;
        private Duration initialTimeout = Duration.ofMillis(500);
        private Duration maxTimeout = Duration.ofSeconds(30);
        private int windowSize = 100;
        private double successRateThreshold = 0.95;
        private BackpressurePolicy policy = new BackpressurePolicy.Adaptive(0.95, 100);
        private boolean metricsEnabled = true;

        public Builder(String serviceName) {
            this.serviceName = serviceName;
        }

        public Builder initialTimeout(Duration timeout) {
            this.initialTimeout = timeout;
            return this;
        }

        public Builder maxTimeout(Duration timeout) {
            this.maxTimeout = timeout;
            return this;
        }

        public Builder windowSize(int size) {
            this.windowSize = size;
            return this;
        }

        public Builder successRateThreshold(double threshold) {
            this.successRateThreshold = threshold;
            return this;
        }

        public Builder policy(BackpressurePolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public BackpressureConfig build() {
            return new BackpressureConfig(
                    serviceName,
                    initialTimeout,
                    maxTimeout,
                    windowSize,
                    successRateThreshold,
                    policy,
                    metricsEnabled);
        }
    }
}
