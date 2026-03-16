package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

/**
 * Sealed interface for bulkhead lifecycle events.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track resource
 * utilization, request rejections, state transitions, and performance metrics. These events provide
 * observability into bulkhead behavior for monitoring, alerting, and capacity planning.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>RequestEnqueued</b>: Request queued (waiting for available capacity)
 *   <li><b>RequestRejected</b>: Request rejected (queue timeout or capacity exceeded)
 *   <li><b>BulkheadHealthy</b>: Utilization dropped below alert threshold
 *   <li><b>BulkheadDegraded</b>: Utilization exceeded alert threshold (warning)
 *   <li><b>BulkheadExhausted</b>: At 100% capacity (rejecting requests)
 *   <li><b>RequestCompleted</b>: Request finished successfully (updates utilization)
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to bulkhead events
 * EventManager<BulkheadEvent> events = EventManager.create();
 * events.subscribe(BulkheadEvent.class, event -> {
 *     switch (event) {
 *         case BulkheadEvent.BulkheadExhausted(var feature, var reason, var ts) ->
 *             log.error("Bulkhead exhausted for {}: {}", feature, reason);
 *         case BulkheadEvent.BulkheadDegraded(var feature, var utilization, var ts) ->
 *             log.warn("Bulkhead degraded for {}: {}% utilized", feature, utilization);
 *         case BulkheadEvent.RequestRejected(var feature, var reason, var ts) ->
 *             metrics.counter("bulkhead.rejections", "feature", feature).increment();
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link BulkheadIsolationEnterprise} coordinator on state changes
 *   <li>Consumed by monitoring systems, alerting pipelines, dashboards
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see BulkheadIsolationEnterprise
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface BulkheadEvent
        permits BulkheadEvent.RequestEnqueued,
                BulkheadEvent.RequestRejected,
                BulkheadEvent.BulkheadHealthy,
                BulkheadEvent.BulkheadDegraded,
                BulkheadEvent.BulkheadExhausted,
                BulkheadEvent.RequestCompleted {

    record RequestEnqueued(String featureName, String requestId, int queueSize, long timestamp)
            implements BulkheadEvent {}

    record RequestRejected(String featureName, String reason, long timestamp)
            implements BulkheadEvent {}

    record BulkheadHealthy(String featureName, int utilizationPercent, long timestamp)
            implements BulkheadEvent {}

    record BulkheadDegraded(String featureName, int utilizationPercent, long timestamp)
            implements BulkheadEvent {}

    record BulkheadExhausted(String featureName, String reason, long timestamp)
            implements BulkheadEvent {}

    record RequestCompleted(String featureName, long durationMs, long timestamp)
            implements BulkheadEvent {}

    default String featureName() {
        return switch (this) {
            case RequestEnqueued(var f, _, _, _) -> f;
            case RequestRejected(var f, _, _) -> f;
            case BulkheadHealthy(var f, _, _) -> f;
            case BulkheadDegraded(var f, _, _) -> f;
            case BulkheadExhausted(var f, _, _) -> f;
            case RequestCompleted(var f, _, _) -> f;
        };
    }

    default long timestamp() {
        return switch (this) {
            case RequestEnqueued(_, _, _, var ts) -> ts;
            case RequestRejected(_, _, var ts) -> ts;
            case BulkheadHealthy(_, _, var ts) -> ts;
            case BulkheadDegraded(_, _, var ts) -> ts;
            case BulkheadExhausted(_, _, var ts) -> ts;
            case RequestCompleted(_, _, var ts) -> ts;
        };
    }
}
