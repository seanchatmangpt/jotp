package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.Serializable;
import java.time.Instant;

/**
 * Snapshot record for optimizing event replay.
 *
 * <p>Snapshots store the state at a particular version to avoid replaying all events from the
 * beginning.
 */
public record Snapshot(
    String aggregateId, long version, Instant timestamp, Object state, String metadata)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public static Snapshot of(String aggregateId, long version, Object state) {
    return new Snapshot(aggregateId, version, Instant.now(), state, "");
  }

  public static Snapshot of(String aggregateId, long version, Object state, String metadata) {
    return new Snapshot(aggregateId, version, Instant.now(), state, metadata);
  }
}
