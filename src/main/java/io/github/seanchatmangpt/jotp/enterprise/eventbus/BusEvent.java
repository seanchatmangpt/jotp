package io.github.seanchatmangpt.jotp.enterprise.eventbus;

/**
 * Sealed interface for event bus lifecycle events.
 *
 * Broadcast via EventManager to track publishing, delivery, and failures.
 */
public sealed interface BusEvent permits
    BusEvent.EventPublished,
    BusEvent.EventDelivered,
    BusEvent.EventFailed,
    BusEvent.SubscriberAdded,
    BusEvent.SubscriberRemoved,
    BusEvent.DeadLetterQueued,
    BusEvent.EventBusShutdown {

  record EventPublished(String eventId, String eventType, long timestamp) implements BusEvent {}

  record EventDelivered(String eventId, String subscriberId, long latencyMs, long timestamp)
      implements BusEvent {}

  record EventFailed(String eventId, String subscriberId, String error, int retryCount, long timestamp)
      implements BusEvent {}

  record SubscriberAdded(String subscriberId, String eventType, long timestamp)
      implements BusEvent {}

  record SubscriberRemoved(String subscriberId, String reason, long timestamp)
      implements BusEvent {}

  record DeadLetterQueued(String eventId, String lastError, long timestamp)
      implements BusEvent {}

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
