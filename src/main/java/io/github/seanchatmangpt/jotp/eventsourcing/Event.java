package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Base event interface for event sourcing.
 *
 * <p>All domain events must implement this interface. Events are immutable and serializable.
 */
public sealed interface Event extends Serializable {
  String aggregateId();

  String eventType();

  Instant timestamp();

  record EventMetadata(
      String eventId,
      String aggregateId,
      long version,
      String eventType,
      Instant timestamp,
      String correlationId,
      String causationId) {
    public static EventMetadata create(
        String aggregateId, long version, String eventType, String correlationId) {
      return new EventMetadata(
          UUID.randomUUID().toString(),
          aggregateId,
          version,
          eventType,
          Instant.now(),
          correlationId,
          null);
    }
  }

  record DomainEvent(EventMetadata metadata, Object payload) implements Event {
    @Override
    public String aggregateId() {
      return metadata.aggregateId();
    }

    @Override
    public String eventType() {
      return metadata.eventType();
    }

    @Override
    public Instant timestamp() {
      return metadata.timestamp();
    }
  }
}
