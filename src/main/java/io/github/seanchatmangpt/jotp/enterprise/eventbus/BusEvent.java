package io.github.seanchatmangpt.jotp.enterprise.eventbus;

/**
 * Sealed interface for event bus lifecycle events and observability.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track event publishing,
 * delivery success/failure, subscriber lifecycle, dead letter queueing, and shutdown metrics. These
 * events provide comprehensive observability into event bus behavior for monitoring, alerting,
 * debugging, and capacity planning.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>EventPublished</b>: New event published to bus. Tracks publish rate and event types
 *   <li><b>EventDelivered</b>: Event successfully delivered to subscriber. Tracks delivery latency
 *   <li><b>EventFailed</b>: Event delivery failed to subscriber. Tracks failures and retry count
 *   <li><b>SubscriberAdded</b>: New subscriber registered. Tracks subscription growth
 *   <li><b>SubscriberRemoved</b>: Subscriber deregistered. Tracks subscription churn
 *   <li><b>DeadLetterQueued</b>: Event moved to DLQ after retry exhaustion. Tracks data loss
 *   <li><b>EventBusShutdown</b>: Bus shutdown with summary stats. Tracks lifecycle
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to event bus events
 * EventManager<BusEvent> events = EventManager.create();
 * events.subscribe(BusEvent.class, event -> {
 *     switch (event) {
 *         case BusEvent.EventFailed(var id, var sub, var error, var retries, var ts) ->
 *             log.error("Event {} failed for subscriber {} after {} retries: {}",
 *                 id, sub, retries, error);
 *         case BusEvent.DeadLetterQueued(var id, var lastError, var ts) ->
 *             alerts.fire("Event moved to DLQ: " + id + ", error: " + lastError);
 *         case BusEvent.EventDelivered(var id, var sub, var latency, var ts) ->
 *             metrics.record("eventbus.delivery_latency", latency);
 *         case BusEvent.EventBusShutdown(var total, var failed, var ts) ->
 *             log.info("EventBus shutdown: {} processed, {} failed", total, failed);
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Monitoring Metrics:</h2>
 *
 * <pre>
 * EventPublished rate      → Publish throughput (events/sec)
 * EventDelivered latency   → Delivery performance (p50, p95, p99)
 * EventFailed rate         → Failure rate (errors/sec, %)
 * DeadLetterQueued count   → Data loss indicator (DLQ size)
 * SubscriberAdded/Removed  → Subscription churn rate
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link EventBus} coordinator on all state changes
 *   <li>Consumed by monitoring systems (Prometheus, Grafana, Datadog)
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Non-blocking emission (doesn't slow down event delivery)
 * </ul>
 *
 * @see EventBus
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface BusEvent
        permits BusEvent.EventPublished,
                BusEvent.EventDelivered,
                BusEvent.EventFailed,
                BusEvent.SubscriberAdded,
                BusEvent.SubscriberRemoved,
                BusEvent.DeadLetterQueued,
                BusEvent.EventBusShutdown {

    record EventPublished(String eventId, String eventType, long timestamp) implements BusEvent {}

    record EventDelivered(String eventId, String subscriberId, long latencyMs, long timestamp)
            implements BusEvent {}

    record EventFailed(
            String eventId, String subscriberId, String error, int retryCount, long timestamp)
            implements BusEvent {}

    record SubscriberAdded(String subscriberId, String eventType, long timestamp)
            implements BusEvent {}

    record SubscriberRemoved(String subscriberId, String reason, long timestamp)
            implements BusEvent {}

    record DeadLetterQueued(String eventId, String lastError, long timestamp) implements BusEvent {}

    record EventBusShutdown(long totalProcessed, long totalFailed, long timestamp)
            implements BusEvent {}

    default long timestamp() {
        return switch (this) {
            case EventPublished(_, _, var ts) -> ts;
            case EventDelivered(_, _, _, var ts) -> ts;
            case EventFailed(_, _, _, _, var ts) -> ts;
            case SubscriberAdded(_, _, var ts) -> ts;
            case SubscriberRemoved(_, _, var ts) -> ts;
            case DeadLetterQueued(_, _, var ts) -> ts;
            case EventBusShutdown(_, _, var ts) -> ts;
        };
    }
}
