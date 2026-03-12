package io.github.seanchatmangpt.jotp.enterprise.backpressure;

/**
 * Sealed interface for backpressure lifecycle events.
 *
 * Broadcast via EventManager to track request flow, timeouts, and circuit breaker state changes.
 */
public sealed interface BackpressureEvent permits
    BackpressureEvent.RequestEnqueued,
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
