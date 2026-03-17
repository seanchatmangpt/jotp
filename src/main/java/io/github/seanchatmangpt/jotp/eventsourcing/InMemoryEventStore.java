package io.github.seanchatmangpt.jotp.eventsourcing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory event store implementation for testing and development.
 *
 * <p>Fast but non-durable; suitable for unit tests and demos.
 */
public class InMemoryEventStore implements EventStoreInterface {

  private final ConcurrentHashMap<String, List<Event>> streams = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> versions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

  @Override
  public long append(String aggregateId, List<Event> events) {
    if (events.isEmpty()) {
      return getCurrentVersion(aggregateId);
    }

    long currentVersion = getCurrentVersion(aggregateId);
    streams.computeIfAbsent(aggregateId, k -> new ArrayList<>()).addAll(events);
    long newVersion = currentVersion + events.size();
    versions.put(aggregateId, newVersion);
    return newVersion;
  }

  @Override
  public long append(String aggregateId, List<Event> events, long expectedVersion) {
    long currentVersion = getCurrentVersion(aggregateId);
    if (currentVersion != expectedVersion) {
      throw new IllegalStateException(
          "Version mismatch: expected " + expectedVersion + " but was " + currentVersion);
    }
    return append(aggregateId, events);
  }

  @Override
  public List<Event> getEvents(String aggregateId, long fromVersion) {
    List<Event> allEvents = streams.getOrDefault(aggregateId, Collections.emptyList());
    return allEvents.stream()
        .skip(Math.max(0, fromVersion))
        .toList();
  }

  @Override
  public List<Event> getEvents(String aggregateId) {
    return new ArrayList<>(streams.getOrDefault(aggregateId, Collections.emptyList()));
  }

  @Override
  public long getCurrentVersion(String aggregateId) {
    return versions.getOrDefault(aggregateId, 0L);
  }

  @Override
  public void saveSnapshot(String aggregateId, Snapshot snapshot) {
    snapshots.put(aggregateId, snapshot);
  }

  @Override
  public Optional<Snapshot> getSnapshot(String aggregateId) {
    return Optional.ofNullable(snapshots.get(aggregateId));
  }

  @Override
  public void deleteSnapshot(String aggregateId) {
    snapshots.remove(aggregateId);
  }

  @Override
  public boolean exists(String aggregateId) {
    return versions.getOrDefault(aggregateId, 0L) > 0;
  }

  @Override
  public void close() throws Exception {
    streams.clear();
    versions.clear();
    snapshots.clear();
  }
}
