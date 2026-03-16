package io.github.seanchatmangpt.jotp.enterprise.eventbus;

/**
 * Sealed interface for event delivery policies and guarantees.
 *
 * <p>Defines the delivery semantics and guarantees for event bus messaging. Each policy represents
 * a different trade-off between reliability, latency, and complexity. Policies determine retry
 * behavior, deduplication, and ordering guarantees.
 *
 * <h2>Policy Types:</h2>
 *
 * <ul>
 *   <li><b>FireAndForget</b>: No delivery guarantee. Event published once, no retries. May be lost
 *       if subscriber crashes or is unavailable. Fastest option, lowest reliability. Best for:
 *       Analytics, telemetry, non-critical notifications where speed > completeness
 *   <li><b>AtLeastOnce</b>: Event delivered at least once, possibly multiple times. Retry on
 *       failure with exponential backoff up to maxRetries. Guarantees delivery but allows
 *       duplicates. Subscribers must be idempotent. Best for: Critical events where duplicates are
 *       acceptable (notifications, webhooks, logging)
 *   <li><b>ExactlyOnce</b>: Event delivered exactly once, no duplicates. Deduplication window
 *       ensures duplicate events are filtered. Higher latency and memory overhead. Best for:
 *       Financial transactions, payments, inventory updates where duplicates cause problems
 *   <li><b>Partitioned</b>: Events with same partition key delivered in order. Per-partition
 *       sequencing guarantees. No ordering across partitions. Best for: Orders per customer,
 *       updates per entity, any domain requiring per-key ordering
 * </ul>
 *
 * <h2>Selection Guide:</h2>
 *
 * <pre>
 * Speed over reliability          → FireAndForget
 * Must deliver, duplicates OK    → AtLeastOnce
 * No duplicates allowed          → ExactlyOnce
 * Per-key ordering required       → Partitioned
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Fire and forget: fastest, no guarantees
 * EventBusPolicy fireAndForget = new EventBusPolicy.FireAndForget();
 *
 * // At least once: retry up to 5 times
 * EventBusPolicy atLeastOnce = new EventBusPolicy.AtLeastOnce(5);
 *
 * // Exactly once: 60 second deduplication window
 * EventBusPolicy exactlyOnce = new EventBusPolicy.ExactlyOnce(60);
 *
 * // Partitioned: order by customer ID
 * EventBusPolicy partitioned = new EventBusPolicy.Partitioned("customerId");
 * }</pre>
 *
 * <h2>Performance Comparison:</h2>
 *
 * <ul>
 *   <li><b>FireAndForget</b>: Lowest latency, lowest memory, no retry overhead
 *   <li><b>AtLeastOnce</b>: Medium latency (retry backoff), low memory
 *   <li><b>ExactlyOnce</b>: Higher latency (dedup check), medium memory (event IDs)
 *   <li><b>Partitioned</b>: Medium latency (per-partition queue), medium memory
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link EventBusConfig} to configure delivery behavior
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Compatible with {@link io.github.seanchatmangpt.jotp.Proc} for fault-tolerant delivery
 * </ul>
 *
 * @see EventBus
 * @see EventBusConfig
 * @since 1.0
 */
public sealed interface EventBusPolicy
        permits EventBusPolicy.FireAndForget,
                EventBusPolicy.AtLeastOnce,
                EventBusPolicy.ExactlyOnce,
                EventBusPolicy.Partitioned {

    /** No delivery guarantee; event may be lost. */
    record FireAndForget() implements EventBusPolicy {}

    /** Event delivered at least once (may be duplicated). */
    record AtLeastOnce(int maxRetries) implements EventBusPolicy {}

    /** Event delivered exactly once (deduplication enabled). */
    record ExactlyOnce(int deduplicationWindowSeconds) implements EventBusPolicy {}

    /** Events with same partition key delivered in order. */
    record Partitioned(String partitionKeyField) implements EventBusPolicy {}
}
