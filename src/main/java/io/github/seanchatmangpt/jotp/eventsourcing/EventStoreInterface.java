package io.github.seanchatmangpt.jotp.eventsourcing;

import java.util.List;
import java.util.Optional;

/**
 * Append-only event store interface for event sourcing.
 *
 * <p>Implementations provide durable, immutable event logs with projection support.
 */
public interface EventStoreInterface extends AutoCloseable {

  /**
   * Append events to an aggregate stream.
   *
   * @param aggregateId the aggregate identifier
   * @param events events to append
   * @return the new aggregate version
   */
  long append(String aggregateId, List<Event> events);

  /**
   * Append events with expected version (optimistic concurrency control).
   *
   * @param aggregateId the aggregate identifier
   * @param events events to append
   * @param expectedVersion the expected current version
   * @return the new aggregate version
   * @throws IllegalStateException if version mismatch
   */
  long append(String aggregateId, List<Event> events, long expectedVersion);

  /**
   * Retrieve events from an aggregate stream.
   *
   * @param aggregateId the aggregate identifier
   * @param fromVersion start version (inclusive)
   * @return list of events
   */
  List<Event> getEvents(String aggregateId, long fromVersion);

  /**
   * Retrieve all events for an aggregate.
   *
   * @param aggregateId the aggregate identifier
   * @return list of all events
   */
  List<Event> getEvents(String aggregateId);

  /**
   * Get the current version of an aggregate.
   *
   * @param aggregateId the aggregate identifier
   * @return the current version, or 0 if not found
   */
  long getCurrentVersion(String aggregateId);

  /**
   * Save a snapshot for optimization.
   *
   * @param aggregateId the aggregate identifier
   * @param snapshot the snapshot to save
   */
  void saveSnapshot(String aggregateId, Snapshot snapshot);

  /**
   * Get the latest snapshot for an aggregate.
   *
   * @param aggregateId the aggregate identifier
   * @return the snapshot if present
   */
  Optional<Snapshot> getSnapshot(String aggregateId);

  /**
   * Delete a snapshot.
   *
   * @param aggregateId the aggregate identifier
   */
  void deleteSnapshot(String aggregateId);

  /**
   * Check if aggregate exists.
   *
   * @param aggregateId the aggregate identifier
   * @return true if aggregate has events
   */
  boolean exists(String aggregateId);

  /** Close the store and release resources. */
  @Override
  void close() throws Exception;
}
