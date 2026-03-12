package io.github.seanchatmangpt.jotp.enterprise.eventbus;

/**
 * Sealed interface for event delivery policies.
 *
 * <p>Defines guarantees for event delivery to subscribers.
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
