package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

/**
 * Sealed interface for bulkhead lifecycle events.
 *
 * Broadcast via EventManager to track resource utilization and rejections.
 */
public sealed interface BulkheadEvent permits
    BulkheadEvent.RequestEnqueued,
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
