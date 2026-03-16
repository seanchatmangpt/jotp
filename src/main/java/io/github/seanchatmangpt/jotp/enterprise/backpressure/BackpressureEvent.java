package io.github.seanchatmangpt.jotp.enterprise.backpressure;

/**
 * Sealed interface for backpressure lifecycle events.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track request flow,
 * timeouts, circuit breaker state changes, and threshold crossings. These events provide
 * observability into backpressure behavior for monitoring, alerting, and debugging.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>RequestEnqueued</b>: New request added to queue (backpressure active)
 *   <li><b>RequestCompleted</b>: Request succeeded (updates success rate)
 *   <li><b>RequestTimedOut</b>: Request exceeded timeout (triggers adaptation)
 *   <li><b>ThresholdExceeded</b>: Success rate dropped below threshold (warning)
 *   <li><b>CircuitTripped</b>: Circuit opened (fail-fast activated)
 *   <li><b>CircuitRecovered</b>: Circuit closed again (service recovered)
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to backpressure events
 * EventManager<BackpressureEvent> events = EventManager.create();
 * events.subscribe(BackpressureEvent.class, event -> {
 *     switch (event) {
 *         case BackpressureEvent.CircuitTripped(var service, var reason, var retryAfter, var ts) ->
 *             log.warn("Circuit tripped for {}: {} (retry after {}ms)", service, reason, retryAfter);
 *         case BackpressureEvent.CircuitRecovered(var service, var ts) ->
 *             log.info("Circuit recovered for {}", service);
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link Backpressure} coordinator on state changes
 *   <li>Consumed by monitoring systems, alerting pipelines, dashboards
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see Backpressure
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface BackpressureEvent
        permits BackpressureEvent.RequestEnqueued,
                BackpressureEvent.RequestCompleted,
                BackpressureEvent.RequestTimedOut,
                BackpressureEvent.ThresholdExceeded,
                BackpressureEvent.CircuitTripped,
                BackpressureEvent.CircuitRecovered {

    record RequestEnqueued(String requestId, String serviceName, long timestamp)
            implements BackpressureEvent {}

    record RequestCompleted(String requestId, String serviceName, long durationMs, long timestamp)
            implements BackpressureEvent {}

    record RequestTimedOut(String requestId, String serviceName, long timeoutMs, long timestamp)
            implements BackpressureEvent {}

    record ThresholdExceeded(String serviceName, double successRate, int windowSize, long timestamp)
            implements BackpressureEvent {}

    record CircuitTripped(String serviceName, String reason, long retryAfterMs, long timestamp)
            implements BackpressureEvent {}

    record CircuitRecovered(String serviceName, long timestamp) implements BackpressureEvent {}

    default String serviceName() {
        return switch (this) {
            case RequestEnqueued(_, var s, _) -> s;
            case RequestCompleted(_, var s, _, _) -> s;
            case RequestTimedOut(_, var s, _, _) -> s;
            case ThresholdExceeded(var s, _, _, _) -> s;
            case CircuitTripped(var s, _, _, _) -> s;
            case CircuitRecovered(var s, _) -> s;
        };
    }

    default long timestamp() {
        return switch (this) {
            case RequestEnqueued(_, _, var ts) -> ts;
            case RequestCompleted(_, _, _, var ts) -> ts;
            case RequestTimedOut(_, _, _, var ts) -> ts;
            case ThresholdExceeded(_, _, _, var ts) -> ts;
            case CircuitTripped(_, _, _, var ts) -> ts;
            case CircuitRecovered(_, var ts) -> ts;
        };
    }
}
