package io.github.seanchatmangpt.jotp.enterprise.backpressure;

import java.time.Duration;

/**
 * Configuration for backpressure handling.
 *
 * Immutable record defining timeout behavior, success rate thresholds, and policy.
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
