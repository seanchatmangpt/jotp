package io.github.seanchatmangpt.jotp.enterprise.eventbus;

import java.util.function.Function;

/**
 * Configuration for event bus delivery guarantees and behavior.
 *
 * <p>Immutable record defining the delivery semantics, batching behavior, retry policies, and
 * monitoring settings for the enterprise event bus. This configuration controls the trade-offs
 * between reliability, latency, and throughput.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>policy</b>: Delivery guarantee (FireAndForget, AtLeastOnce, ExactlyOnce, Partitioned).
 *       Determines retry and deduplication behavior
 *   <li><b>maxRetries</b>: Maximum retry attempts for AtLeastOnce policy. Typical values: 3-5. Too
 *       high = delay, too low = data loss
 *   <li><b>batchSize</b>: Number of events to batch before delivery. Higher = better throughput but
 *       higher latency. Typical values: 10-100
 *   <li><b>batchTimeoutMs</b>: Maximum time to wait before flushing partial batch. Prevents
 *       starvation on low-volume. Typical values: 100-5000ms
 *   <li><b>deadLetterQueueEnabled</b>: Whether to persist failed events. Required for manual
 *       recovery and debugging
 *   <li><b>metricsEnabled</b>: Whether to emit performance metrics for monitoring
 *   <li><b>partitionKeyExtractor</b>: Function to extract partition key from events. Used with
 *       Partitioned policy for ordering guarantees
 * </ul>
 *
 * <h2>Delivery Policy Selection:</h2>
 *
 * <pre>
 * FireAndForget     → No retries, fastest (analytics, telemetry)
 * AtLeastOnce       → Retry on failure (critical events, can tolerate duplicates)
 * ExactlyOnce       → Deduplication window (financial transactions, payments)
 * Partitioned       → Per-key ordering (orders per customer, updates per entity)
 * </pre>
 *
 * <h2>Batching Trade-offs:</h2>
 *
 * <ul>
 *   <li><b>Large batch + long timeout</b>: High throughput, high latency (bulk processing)
 *   <li><b>Small batch + short timeout</b>: Low latency, lower throughput (real-time)
 *   <li><b>Batch size = 1</b>: No batching, lowest latency (synchronous delivery)
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // At-least-once delivery with batching
 * EventBusConfig config = EventBusConfig.builder()
 *     .policy(new EventBusPolicy.AtLeastOnce(3))
 *     .batchSize(50)
 *     .batchTimeoutMs(1000)
 *     .deadLetterQueueEnabled(true)
 *     .metricsEnabled(true)
 *     .build();
 *
 * // Exactly-once with deduplication
 * EventBusConfig config = EventBusConfig.builder()
 *     .policy(new EventBusPolicy.ExactlyOnce(60)) // 60s dedup window
 *     .batchSize(10)
 *     .deadLetterQueueEnabled(true)
 *     .build();
 *
 * // Partitioned ordering per customer
 * EventBusConfig config = EventBusConfig.builder()
 *     .policy(new EventBusPolicy.Partitioned("customerId"))
 *     .partitionKeyExtractor(e -> ((OrderEvent) e).customerId())
 *     .batchSize(20)
 *     .build();
 * }</pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(batchSize) for buffering events
 *   <li>Latency: O(batchSize * avgProcessingTime) for batch delivery
 *   <li>Throughput: Proportional to batchSize, inversely to batchTimeoutMs
 * </ul>
 *
 * @see EventBus
 * @see EventBusPolicy
 * @since 1.0
 * @param policy Delivery guarantee policy
 * @param maxRetries Maximum retry attempts (AtLeastOnce policy)
 * @param batchSize Number of events to batch before delivery
 * @param batchTimeoutMs Maximum time to wait before flushing partial batch
 * @param deadLetterQueueEnabled Whether to persist failed events
 * @param metricsEnabled Whether to emit performance metrics
 * @param partitionKeyExtractor Function to extract partition key from events
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
