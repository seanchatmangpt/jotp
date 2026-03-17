package io.github.seanchatmangpt.jotp.eventsourcing;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event deduplicator using Redis cache and PostgreSQL historical store.
 *
 * <p>Prevents duplicate processing via idempotency keys (event IDs).
 */
public class EventDeduplicator implements AutoCloseable {

  private final String dbUrl;
  private final String dbUsername;
  private final String dbPassword;
  private final long cacheTTLMillis;
  private final ConcurrentHashMap<String, DeduplicateEntry> recentEventsCache =
      new ConcurrentHashMap<>();

  private static final String DEDUP_TABLE =
      "CREATE TABLE IF NOT EXISTS event_deduplication ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  event_id VARCHAR(255) NOT NULL UNIQUE,"
          + "  aggregate_id VARCHAR(255) NOT NULL,"
          + "  processed BOOLEAN DEFAULT FALSE,"
          + "  result BYTEA,"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  processed_at TIMESTAMP WITH TIME ZONE"
          + ");";

  private static final String DEDUP_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_dedup_event_id ON event_deduplication(event_id);"
          + "CREATE INDEX IF NOT EXISTS idx_dedup_aggregate_id ON event_deduplication(aggregate_id);";

  private record DeduplicateEntry(String eventId, Object result, long expiresAt) {
    boolean isExpired() {
      return System.currentTimeMillis() > expiresAt;
    }
  }

  public EventDeduplicator(String dbUrl, String dbUsername, String dbPassword, long cacheTTLMillis) {
    this.dbUrl = dbUrl;
    this.dbUsername = dbUsername;
    this.dbPassword = dbPassword;
    this.cacheTTLMillis = cacheTTLMillis;
    initializeTables();
  }

  public EventDeduplicator(String dbUrl, String dbUsername, String dbPassword) {
    this(dbUrl, dbUsername, dbPassword, 3600000); // 1 hour default
  }

  private void initializeTables() {
    try (var conn = getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(DEDUP_TABLE);
      stmt.execute(DEDUP_INDEX);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize deduplication tables", e);
    }
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
  }

  /**
   * Check if an event has been processed.
   *
   * @param eventId the idempotency key
   * @return true if event was already processed
   */
  public boolean isDuplicate(String eventId) {
    // Check hot cache first
    DeduplicateEntry cached = recentEventsCache.get(eventId);
    if (cached != null && !cached.isExpired()) {
      return true;
    }

    // Check persistent store
    String sql = "SELECT processed FROM event_deduplication WHERE event_id = ? LIMIT 1";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, eventId);

      try (var rs = stmt.executeQuery()) {
        return rs.next() && rs.getBoolean("processed");
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to check duplicate", e);
    }
  }

  /**
   * Register an event for deduplication.
   *
   * @param eventId the idempotency key
   * @param aggregateId the aggregate ID
   */
  public void register(String eventId, String aggregateId) {
    String sql =
        "INSERT INTO event_deduplication (event_id, aggregate_id, created_at) "
            + "VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, eventId);
      stmt.setString(2, aggregateId);
      stmt.setTimestamp(3, Timestamp.from(Instant.now()));
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to register event", e);
    }
  }

  /**
   * Mark an event as processed and store result.
   *
   * @param eventId the idempotency key
   * @param result the processing result to cache
   */
  public void markProcessed(String eventId, Object result) {
    String sql =
        "UPDATE event_deduplication SET processed = TRUE, result = ?, processed_at = ? WHERE event_id = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setBytes(1, serialize(result));
      stmt.setTimestamp(2, Timestamp.from(Instant.now()));
      stmt.setString(3, eventId);
      stmt.executeUpdate();

      // Cache for fast retrieval
      long expiresAt = System.currentTimeMillis() + cacheTTLMillis;
      recentEventsCache.put(eventId, new DeduplicateEntry(eventId, result, expiresAt));
    } catch (SQLException e) {
      throw new RuntimeException("Failed to mark as processed", e);
    }
  }

  /**
   * Retrieve cached result for a processed event.
   *
   * @param eventId the idempotency key
   * @return the cached result if present
   */
  public Optional<Object> getResult(String eventId) {
    // Check hot cache
    DeduplicateEntry cached = recentEventsCache.get(eventId);
    if (cached != null && !cached.isExpired()) {
      return Optional.of(cached.result());
    }

    // Retrieve from persistent store
    String sql = "SELECT result FROM event_deduplication WHERE event_id = ? AND processed = TRUE";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, eventId);

      try (var rs = stmt.executeQuery()) {
        if (rs.next()) {
          byte[] resultBytes = rs.getBytes("result");
          if (resultBytes != null) {
            Object result = deserialize(resultBytes);
            // Refresh cache
            long expiresAt = System.currentTimeMillis() + cacheTTLMillis;
            recentEventsCache.put(eventId, new DeduplicateEntry(eventId, result, expiresAt));
            return Optional.of(result);
          }
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get result", e);
    }

    return Optional.empty();
  }

  /**
   * Clean up expired cache entries.
   */
  public void cleanupExpired() {
    recentEventsCache.entrySet().removeIf(e -> e.getValue().isExpired());

    // Also clean old entries from database
    String sql =
        "DELETE FROM event_deduplication WHERE created_at < NOW() - INTERVAL '7 days' AND processed = TRUE";

    try (var conn = getConnection();
        var stmt = conn.createStatement()) {
      int deleted = stmt.executeUpdate(sql);
      if (deleted > 0) {
        System.out.println("[Deduplicator] Cleaned " + deleted + " expired entries");
      }
    } catch (SQLException e) {
      System.err.println("[Deduplicator] Failed to cleanup: " + e.getMessage());
    }
  }

  /**
   * Get deduplication statistics.
   *
   * @return stats record
   */
  public record DeduplicationStats(long totalTracked, long processed, long cached) {}

  public DeduplicationStats getStats() {
    String sql =
        "SELECT COUNT(*) as total, SUM(CASE WHEN processed THEN 1 ELSE 0 END) as processed FROM event_deduplication";

    try (var conn = getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery(sql)) {
      if (rs.next()) {
        long total = rs.getLong("total");
        long processed = rs.getLong("processed");
        long cached = recentEventsCache.size();
        return new DeduplicationStats(total, processed, cached);
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get stats", e);
    }

    return new DeduplicationStats(0, 0, 0);
  }

  @Override
  public void close() throws Exception {
    recentEventsCache.clear();
  }

  private byte[] serialize(Object obj) {
    try {
      var baos = new java.io.ByteArrayOutputStream();
      var oos = new java.io.ObjectOutputStream(baos);
      oos.writeObject(obj);
      oos.close();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize", e);
    }
  }

  private Object deserialize(byte[] data) {
    try {
      var bais = new java.io.ByteArrayInputStream(data);
      var ois = new java.io.ObjectInputStream(bais);
      return ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize", e);
    }
  }
}
