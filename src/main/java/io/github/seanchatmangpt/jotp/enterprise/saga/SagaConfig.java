package io.github.seanchatmangpt.jotp.enterprise.saga;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for a distributed saga.
 *
 * Immutable record specifying steps, timeouts, and execution behavior.
 */
public record SagaConfig(
    String sagaId,
    List<SagaStep> steps,
    Duration timeout,
    Duration compensationTimeout,
    int maxRetries,
    boolean asyncOutput,
    boolean metricsEnabled) {

  /** Validate configuration constraints. */
  public SagaConfig {
    if (sagaId == null || sagaId.isEmpty()) {
      throw new IllegalArgumentException("sagaId must not be empty");
    }
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("steps must not be empty");
    }
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (compensationTimeout.isNegative() || compensationTimeout.isZero()) {
      throw new IllegalArgumentException("compensationTimeout must be positive");
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0");
    }
  }

  /** Builder pattern for convenient construction. */
  public static Builder builder(String sagaId) {
    return new Builder(sagaId);
  }

  public static class Builder {
    private final String sagaId;
    private List<SagaStep> steps = List.of();
    private Duration timeout = Duration.ofMinutes(10);
    private Duration compensationTimeout = Duration.ofMinutes(5);
    private int maxRetries = 3;
    private boolean asyncOutput = false;
    private boolean metricsEnabled = true;

    public Builder(String sagaId) {
      this.sagaId = sagaId;
    }

    public Builder steps(List<SagaStep> steps) {
      this.steps = steps;
      return this;
    }

    public Builder timeout(Duration duration) {
      this.timeout = duration;
      return this;
    }

    public Builder compensationTimeout(Duration duration) {
      this.compensationTimeout = duration;
      return this;
    }

    public Builder maxRetries(int retries) {
      this.maxRetries = retries;
      return this;
    }

    public Builder asyncOutput(boolean async) {
      this.asyncOutput = async;
      return this;
    }

    public Builder metricsEnabled(boolean enabled) {
      this.metricsEnabled = enabled;
      return this;
    }

    public SagaConfig build() {
      return new SagaConfig(
          sagaId,
          steps,
          timeout,
          compensationTimeout,
          maxRetries,
          asyncOutput,
          metricsEnabled);
    }
  }
}
