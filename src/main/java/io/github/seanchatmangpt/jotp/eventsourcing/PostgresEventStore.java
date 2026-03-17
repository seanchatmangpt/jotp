package io.github.seanchatmangpt.jotp.eventsourcing;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL-backed event store with append-only semantics.
 *
 * <p>Provides durable, transactional event storage with full ACID guarantees.
 */
public class PostgresEventStore implements EventStoreInterface {

  private final String connectionUrl;
  private final String username;
  private final String password;
  private final ConcurrentHashMap<String, Long> versionCache = new ConcurrentHashMap<>();
  private static final String EVENTS_TABLE =
      "CREATE TABLE IF NOT EXISTS events ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  event_id UUID NOT NULL UNIQUE,"
          + "  aggregate_id VARCHAR(255) NOT NULL,"
          + "  version BIGINT NOT NULL,"
          + "  event_type VARCHAR(255) NOT NULL,"
          + "  data BYTEA NOT NULL,"
          + "  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  correlation_id VARCHAR(255),"
          + "  UNIQUE(aggregate_id, version)"
          + ");";

  private static final String SNAPSHOTS_TABLE =
      "CREATE TABLE IF NOT EXISTS snapshots ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  aggregate_id VARCHAR(255) NOT NULL UNIQUE,"
          + "  version BIGINT NOT NULL,"
          + "  data BYTEA NOT NULL,"
          + "  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  metadata TEXT"
          + ");";

  private static final String INDEXES =
      "CREATE INDEX IF NOT EXISTS idx_events_aggregate_id ON events(aggregate_id);"
          + "CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);"
          + "CREATE INDEX IF NOT EXISTS idx_events_type ON events(event_type);";

  public PostgresEventStore(String url, String username, String password) {
    this.connectionUrl = url;
    this.username = username;
    this.password = password;
    initializeTables();
  }

  private void initializeTables() {
    try (var conn = getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(EVENTS_TABLE);
      stmt.execute(SNAPSHOTS_TABLE);
      stmt.execute(INDEXES);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize tables", e);
    }
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(connectionUrl, username, password);
  }

  @Override
  public long append(String aggregateId, List<Event> events) {
    if (events.isEmpty()) {
      return getCurrentVersion(aggregateId);
    }

    try (var conn = getConnection()) {
      conn.setAutoCommit(false);
      try {
        long currentVersion = getCurrentVersion(aggregateId);
        long newVersion = currentVersion;

        String insertSql =
            "INSERT INTO events (event_id, aggregate_id, version, event_type, data, timestamp, correlation_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (var stmt = conn.prepareStatement(insertSql)) {
          for (Event event : events) {
            newVersion++;
            stmt.setObject(1, UUID.randomUUID());
            stmt.setString(2, aggregateId);
            stmt.setLong(3, newVersion);
            stmt.setString(4, event.eventType());
            stmt.setBytes(5, serializeEvent(event));
            stmt.setTimestamp(6, Timestamp.from(event.timestamp()));
            stmt.setString(7, "correlation-id");
            stmt.addBatch();
          }
          stmt.executeBatch();
        }

        conn.commit();
        versionCache.put(aggregateId, newVersion);
        return newVersion;
      } catch (SQLException e) {
        conn.rollback();
        throw e;
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to append events", e);
    }
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
    List<Event> events = new ArrayList<>();
    String sql =
        "SELECT data FROM events WHERE aggregate_id = ? AND version > ? ORDER BY version ASC";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, aggregateId);
      stmt.setLong(2, fromVersion);

      try (var rs = stmt.executeQuery()) {
        while (rs.next()) {
          byte[] data = rs.getBytes("data");
          events.add(deserializeEvent(data));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get events", e);
    }

    return events;
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

    String sql = "SELECT MAX(version) as max_version FROM events WHERE aggregate_id = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, aggregateId);

      try (var rs = stmt.executeQuery()) {
        if (rs.next()) {
          long version = rs.getLong("max_version");
          if (rs.wasNull()) {
            version = 0;
          }
          versionCache.put(aggregateId, version);
          return version;
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get version", e);
    }

    return 0;
  }

  @Override
  public void saveSnapshot(String aggregateId, Snapshot snapshot) {
    String sql =
        "INSERT INTO snapshots (aggregate_id, version, data, timestamp, metadata) "
            + "VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (aggregate_id) DO UPDATE SET version = ?, data = ?, timestamp = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      byte[] data = serializeSnapshot(snapshot);

      stmt.setString(1, aggregateId);
      stmt.setLong(2, snapshot.version());
      stmt.setBytes(3, data);
      stmt.setTimestamp(4, Timestamp.from(snapshot.timestamp()));
      stmt.setString(5, snapshot.metadata());

      stmt.setLong(6, snapshot.version());
      stmt.setBytes(7, data);
      stmt.setTimestamp(8, Timestamp.from(snapshot.timestamp()));

      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to save snapshot", e);
    }
  }

  @Override
  public Optional<Snapshot> getSnapshot(String aggregateId) {
    String sql = "SELECT version, data, timestamp, metadata FROM snapshots WHERE aggregate_id = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, aggregateId);

      try (var rs = stmt.executeQuery()) {
        if (rs.next()) {
          long version = rs.getLong("version");
          byte[] data = rs.getBytes("data");
          Instant timestamp = rs.getTimestamp("timestamp").toInstant();
          String metadata = rs.getString("metadata");

          Object state = deserializeSnapshotData(data);
          return Optional.of(
              new Snapshot(aggregateId, version, timestamp, state, metadata != null ? metadata : ""));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get snapshot", e);
    }

    return Optional.empty();
  }

  @Override
  public void deleteSnapshot(String aggregateId) {
    String sql = "DELETE FROM snapshots WHERE aggregate_id = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, aggregateId);
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to delete snapshot", e);
    }
  }

  @Override
  public boolean exists(String aggregateId) {
    return getCurrentVersion(aggregateId) > 0;
  }

  @Override
  public void close() throws Exception {
    versionCache.clear();
  }

  private byte[] serializeEvent(Event event) {
    try {
      var baos = new java.io.ByteArrayOutputStream();
      var oos = new java.io.ObjectOutputStream(baos);
      oos.writeObject(event);
      oos.close();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize event", e);
    }
  }

  private Event deserializeEvent(byte[] data) {
    try {
      var bais = new java.io.ByteArrayInputStream(data);
      var ois = new java.io.ObjectInputStream(bais);
      return (Event) ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize event", e);
    }
  }

  private byte[] serializeSnapshot(Snapshot snapshot) {
    try {
      var baos = new java.io.ByteArrayOutputStream();
      var oos = new java.io.ObjectOutputStream(baos);
      oos.writeObject(snapshot);
      oos.close();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize snapshot", e);
    }
  }

  private Object deserializeSnapshotData(byte[] data) {
    try {
      var bais = new java.io.ByteArrayInputStream(data);
      var ois = new java.io.ObjectInputStream(bais);
      return ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize snapshot", e);
    }
  }
}
