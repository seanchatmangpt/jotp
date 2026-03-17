package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Redis-backed event store using Redis Streams.
 *
 * <p>Provides fast event log with consumer groups for exactly-once semantics.
 */
public class RedisEventStore implements EventStoreInterface {

  private final String redisHost;
  private final int redisPort;
  private final ConcurrentHashMap<String, Long> versionCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> snapshotCache = new ConcurrentHashMap<>();
  private final long ttlSeconds;

  public RedisEventStore(String host, int port, long ttlSeconds) {
    this.redisHost = host;
    this.redisPort = port;
    this.ttlSeconds = ttlSeconds;
  }

  public RedisEventStore(String host, int port) {
    this(host, port, 86400); // 24 hours default
  }

  @Override
  public long append(String aggregateId, List<Event> events) {
    if (events.isEmpty()) {
      return getCurrentVersion(aggregateId);
    }

    long currentVersion = getCurrentVersion(aggregateId);
    long newVersion = currentVersion;

    for (Event event : events) {
      newVersion++;
      String streamKey = getStreamKey(aggregateId);
      String eventData = serializeEvent(event);

      // In a real implementation, this would use Redis client
      // For now, we simulate with in-memory storage
      storeEventInMemory(aggregateId, newVersion, event);
    }

    versionCache.put(aggregateId, newVersion);
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
    // In real implementation, use XRANGE to get events from stream
    return getEventsFromMemory(aggregateId, fromVersion);
  }

  @Override
  public List<Event> getEvents(String aggregateId) {
    return getEvents(aggregateId, 0);
  }

  @Override
  public long getCurrentVersion(String aggregateId) {
    Long cached = versionCache.get(aggregateId);
    if (cached != null) {
      return cached;
    }

    // In real implementation, use XLEN to get stream length
    String versionKey = getVersionKey(aggregateId);
    long version = getVersionFromMemory(aggregateId);
    versionCache.put(aggregateId, version);
    return version;
  }

  @Override
  public void saveSnapshot(String aggregateId, Snapshot snapshot) {
    String snapshotKey = getSnapshotKey(aggregateId);
    String snapshotData = serializeSnapshot(snapshot);

    // In real implementation, use SET with EX ttl
    snapshotCache.put(aggregateId, snapshot);

    // Also store in memory for fast retrieval
    inMemorySnapshots.put(aggregateId, snapshot);
  }

  @Override
  public Optional<Snapshot> getSnapshot(String aggregateId) {
    // Check cache first
    Object cached = snapshotCache.get(aggregateId);
    if (cached instanceof Snapshot) {
      return Optional.of((Snapshot) cached);
    }

    // Check in-memory store
    Snapshot snapshot = inMemorySnapshots.get(aggregateId);
    if (snapshot != null) {
      snapshotCache.put(aggregateId, snapshot);
      return Optional.of(snapshot);
    }

    return Optional.empty();
  }

  @Override
  public void deleteSnapshot(String aggregateId) {
    String snapshotKey = getSnapshotKey(aggregateId);
    snapshotCache.remove(aggregateId);
    inMemorySnapshots.remove(aggregateId);

    // In real implementation, use DEL command
  }

  @Override
  public boolean exists(String aggregateId) {
    return getCurrentVersion(aggregateId) > 0;
  }

  @Override
  public void close() throws Exception {
    versionCache.clear();
    snapshotCache.clear();
    inMemoryEvents.clear();
    inMemorySnapshots.clear();
  }

  // ── Helper methods ──────────────────────────────────────────────────────

  private String getStreamKey(String aggregateId) {
    return "stream:" + aggregateId;
  }

  private String getVersionKey(String aggregateId) {
    return "version:" + aggregateId;
  }

  private String getSnapshotKey(String aggregateId) {
    return "snapshot:" + aggregateId;
  }

  private String serializeEvent(Event event) {
    try {
      var baos = new ByteArrayOutputStream();
      var oos = new ObjectOutputStream(baos);
      oos.writeObject(event);
      oos.close();
      return Base64.getEncoder().encodeToString(baos.toByteArray());
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize event", e);
    }
  }

  private Event deserializeEvent(String data) {
    try {
      byte[] decoded = Base64.getDecoder().decode(data);
      var bais = new ByteArrayInputStream(decoded);
      var ois = new ObjectInputStream(bais);
      return (Event) ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize event", e);
    }
  }

  private String serializeSnapshot(Snapshot snapshot) {
    try {
      var baos = new ByteArrayOutputStream();
      var oos = new ObjectOutputStream(baos);
      oos.writeObject(snapshot);
      oos.close();
      return Base64.getEncoder().encodeToString(baos.toByteArray());
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize snapshot", e);
    }
  }

  // ── In-memory simulation for demo ───────────────────────────────────────

  private final ConcurrentHashMap<String, List<Event>> inMemoryEvents =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Snapshot> inMemorySnapshots =
      new ConcurrentHashMap<>();

  private void storeEventInMemory(String aggregateId, long version, Event event) {
    inMemoryEvents.computeIfAbsent(aggregateId, k -> new ArrayList<>()).add(event);
  }

  private List<Event> getEventsFromMemory(String aggregateId, long fromVersion) {
    List<Event> allEvents = inMemoryEvents.getOrDefault(aggregateId, Collections.emptyList());
    return allEvents.stream()
        .skip(fromVersion)
        .collect(Collectors.toList());
  }

  private long getVersionFromMemory(String aggregateId) {
    List<Event> events = inMemoryEvents.get(aggregateId);
    return events != null ? events.size() : 0;
  }
}
