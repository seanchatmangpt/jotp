package io.github.seanchatmangpt.jotp.enterprise.eventbus;

import java.util.function.Function;

/**
 * Configuration for event bus.
 *
 * Immutable record specifying delivery policy, batching, and metrics.
 */
public record EventBusConfig(
    EventBusPolicy policy,
    int maxRetries,
    int batchSize,
    long batchTimeoutMs,
    boolean deadLetterQueueEnabled,
    boolean metricsEnabled,
    Function<Object, String> partitionKeyExtractor) {

  /** Validate configuration constraints. */
  public EventBusConfig {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be > 0");
    }
    if (batchTimeoutMs <= 0) {
      throw new IllegalArgumentException("batchTimeoutMs must be > 0");
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0");
    }
  }

  /** Builder pattern for convenient construction. */
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private EventBusPolicy policy = new EventBusPolicy.AtLeastOnce(3);
    private int maxRetries = 3;
    private int batchSize = 10;
    private long batchTimeoutMs = 1000;
    private boolean deadLetterQueueEnabled = true;
    private boolean metricsEnabled = true;
    private Function<Object, String> partitionKeyExtractor = e -> "";

    public Builder policy(EventBusPolicy policy) {
      this.policy = policy;
      return this;
    }

    public Builder maxRetries(int retries) {
      this.maxRetries = retries;
      return this;
    }

    public Builder batchSize(int size) {
      this.batchSize = size;
      return this;
    }

    public Builder batchTimeoutMs(long timeout) {
      this.batchTimeoutMs = timeout;
      return this;
    }

    public Builder deadLetterQueueEnabled(boolean enabled) {
      this.deadLetterQueueEnabled = enabled;
      return this;
    }

    public Builder metricsEnabled(boolean enabled) {
      this.metricsEnabled = enabled;
      return this;
    }

    public Builder partitionKeyExtractor(Function<Object, String> extractor) {
      this.partitionKeyExtractor = extractor;
      return this;
    }

    public EventBusConfig build() {
      return new EventBusConfig(
          policy,
          maxRetries,
          batchSize,
          batchTimeoutMs,
          deadLetterQueueEnabled,
          metricsEnabled,
          partitionKeyExtractor);
    }
  }
}
