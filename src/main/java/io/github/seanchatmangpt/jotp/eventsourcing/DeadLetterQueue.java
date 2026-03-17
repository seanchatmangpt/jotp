package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Dead Letter Queue for handling failed event processing.
 *
 * <p>Stores events that couldn't be processed, with metrics and retry mechanisms.
 */
public class DeadLetterQueue implements AutoCloseable {

  private final String dbUrl;
  private final String dbUsername;
  private final String dbPassword;
  private final long maxQueueSize;
  private final Consumer<DLQMessage> alertHandler;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "DLQ-Monitor");
        t.setDaemon(true);
        return t;
      });

  private static final String DLQ_TABLE =
      "CREATE TABLE IF NOT EXISTS dead_letter_queue ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  message_id VARCHAR(255) NOT NULL UNIQUE,"
          + "  aggregate_id VARCHAR(255),"
          + "  event_data BYTEA NOT NULL,"
          + "  error_message TEXT,"
          + "  error_stack BYTEA,"
          + "  retry_count INT DEFAULT 0,"
          + "  max_retries INT DEFAULT 3,"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  last_retry_at TIMESTAMP WITH TIME ZONE,"
          + "  status VARCHAR(50) DEFAULT 'PENDING'"
          + ");";

  private static final String DLQ_METRICS_TABLE =
      "CREATE TABLE IF NOT EXISTS dlq_metrics ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  queue_size BIGINT,"
          + "  total_failed INT,"
          + "  avg_retry_count FLOAT,"
          + "  oldest_message_age_minutes INT"
          + ");";

  public record DLQMessage(
      String messageId,
      String aggregateId,
      Object eventData,
      String errorMessage,
      Throwable cause,
      int retryCount,
      int maxRetries,
      Instant createdAt) {

    public static DLQMessage of(String messageId, Object event, Exception cause) {
      return new DLQMessage(
          messageId, null, event, cause.getMessage(), cause, 0, 3, Instant.now());
    }

    public static DLQMessage of(String messageId, String aggregateId, Object event,
        Exception cause) {
      return new DLQMessage(
          messageId, aggregateId, event, cause.getMessage(), cause, 0, 3, Instant.now());
    }
  }

  public record DLQStats(long queueSize, int totalFailed, double avgRetryCount,
      int oldestMessageAgeMinutes, List<String> failureReasons) {}

  public DeadLetterQueue(String dbUrl, String dbUsername, String dbPassword) {
    this(dbUrl, dbUsername, dbPassword, 10000, msg -> {});
  }

  public DeadLetterQueue(
      String dbUrl, String dbUsername, String dbPassword, long maxQueueSize,
      Consumer<DLQMessage> alertHandler) {
    this.dbUrl = dbUrl;
    this.dbUsername = dbUsername;
    this.dbPassword = dbPassword;
    this.maxQueueSize = maxQueueSize;
    this.alertHandler = alertHandler;
    initializeTables();
    startMonitoring();
  }

  private void initializeTables() {
    try (var conn = getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(DLQ_TABLE);
      stmt.execute(DLQ_METRICS_TABLE);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize DLQ tables", e);
    }
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
  }

  /**
   * Send a message to the dead letter queue.
   *
   * @param message the DLQ message
   */
  public void send(DLQMessage message) {
    String sql =
        "INSERT INTO dead_letter_queue (message_id, aggregate_id, event_data, error_message, error_stack, created_at, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, message.messageId());
      stmt.setString(2, message.aggregateId());
      stmt.setBytes(3, serialize(message.eventData()));
      stmt.setString(4, message.errorMessage());
      stmt.setBytes(5, serializeThrowable(message.cause()));
      stmt.setTimestamp(6, Timestamp.from(message.createdAt()));
      stmt.executeUpdate();

      checkQueueSize();
      alertHandler.accept(message);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to send to DLQ", e);
    }
  }

  /**
   * Retrieve messages from the DLQ.
   *
   * @param limit max messages to retrieve
   * @return list of DLQ messages
   */
  public List<DLQMessage> receive(int limit) {
    List<DLQMessage> messages = new ArrayList<>();
    String sql =
        "SELECT message_id, aggregate_id, event_data, error_message, error_stack, retry_count, max_retries, created_at "
            + "FROM dead_letter_queue WHERE status = 'PENDING' ORDER BY created_at DESC LIMIT ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setInt(1, limit);

      try (var rs = stmt.executeQuery()) {
        while (rs.next()) {
          messages.add(
              new DLQMessage(
                  rs.getString("message_id"),
                  rs.getString("aggregate_id"),
                  deserialize(rs.getBytes("event_data")),
                  rs.getString("error_message"),
                  deserializeThrowable(rs.getBytes("error_stack")),
                  rs.getInt("retry_count"),
                  rs.getInt("max_retries"),
                  rs.getTimestamp("created_at").toInstant()));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to receive from DLQ", e);
    }

    return messages;
  }

  /**
   * Retry processing a message.
   *
   * @param messageId the message ID to retry
   * @return true if retry was scheduled
   */
  public boolean retry(String messageId) {
    String selectSql =
        "SELECT retry_count, max_retries FROM dead_letter_queue WHERE message_id = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(selectSql)) {
      stmt.setString(1, messageId);

      try (var rs = stmt.executeQuery()) {
        if (rs.next()) {
          int retryCount = rs.getInt("retry_count");
          int maxRetries = rs.getInt("max_retries");

          if (retryCount < maxRetries) {
            String updateSql =
                "UPDATE dead_letter_queue SET retry_count = ?, last_retry_at = ? WHERE message_id = ?";
            try (var updateStmt = conn.prepareStatement(updateSql)) {
              updateStmt.setInt(1, retryCount + 1);
              updateStmt.setTimestamp(2, Timestamp.from(Instant.now()));
              updateStmt.setString(3, messageId);
              updateStmt.executeUpdate();
              return true;
            }
          } else {
            String updateSql =
                "UPDATE dead_letter_queue SET status = 'EXHAUSTED' WHERE message_id = ?";
            try (var updateStmt = conn.prepareStatement(updateSql)) {
              updateStmt.setString(1, messageId);
              updateStmt.executeUpdate();
            }
            return false;
          }
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to retry message", e);
    }

    return false;
  }

  /**
   * Mark a message as processed.
   *
   * @param messageId the message ID to acknowledge
   */
  public void acknowledge(String messageId) {
    String sql = "UPDATE dead_letter_queue SET status = 'PROCESSED' WHERE message_id = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, messageId);
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to acknowledge message", e);
    }
  }

  /**
   * Get DLQ statistics.
   *
   * @return queue statistics
   */
  public DLQStats getStats() {
    String sql =
        "SELECT COUNT(*) as queue_size, SUM(retry_count) as total_retries, COUNT(*) as total_failed, "
            + "EXTRACT(EPOCH FROM (NOW() - MIN(created_at)))/60 as oldest_age_minutes "
            + "FROM dead_letter_queue WHERE status = 'PENDING'";

    try (var conn = getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery(sql)) {
      if (rs.next()) {
        long queueSize = rs.getLong("queue_size");
        int totalFailed = rs.getInt("total_failed");
        double avgRetry = queueSize > 0 ? (rs.getLong("total_retries") / (double) queueSize) : 0;
        int oldestAge = rs.getInt("oldest_age_minutes");

        return new DLQStats(
            queueSize,
            totalFailed,
            avgRetry,
            oldestAge,
            getFailureReasons());
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get DLQ stats", e);
    }

    return new DLQStats(0, 0, 0, 0, Collections.emptyList());
  }

  /**
   * Get failure reasons from DLQ.
   *
   * @return list of error messages
   */
  public List<String> getFailureReasons() {
    List<String> reasons = new ArrayList<>();
    String sql =
        "SELECT DISTINCT error_message FROM dead_letter_queue WHERE status = 'PENDING' LIMIT 10";

    try (var conn = getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        reasons.add(rs.getString("error_message"));
      }
    } catch (SQLException e) {
      System.err.println("[DLQ] Failed to get failure reasons: " + e.getMessage());
    }

    return reasons;
  }

  private void checkQueueSize() {
    String sql = "SELECT COUNT(*) as size FROM dead_letter_queue WHERE status = 'PENDING'";

    try (var conn = getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery(sql)) {
      if (rs.next()) {
        long size = rs.getLong("size");
        if (size > maxQueueSize) {
          System.err.println("[DLQ] WARNING: Queue size " + size + " exceeds limit " + maxQueueSize);
        }
      }
    } catch (SQLException e) {
      System.err.println("[DLQ] Failed to check queue size: " + e.getMessage());
    }
  }

  private void startMonitoring() {
    scheduler.scheduleAtFixedRate(
        this::recordMetrics, 1, 5, TimeUnit.MINUTES);
  }

  private void recordMetrics() {
    DLQStats stats = getStats();
    String sql =
        "INSERT INTO dlq_metrics (timestamp, queue_size, total_failed, avg_retry_count, oldest_message_age_minutes) "
            + "VALUES (?, ?, ?, ?, ?)";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setTimestamp(1, Timestamp.from(Instant.now()));
      stmt.setLong(2, stats.queueSize());
      stmt.setInt(3, stats.totalFailed());
      stmt.setDouble(4, stats.avgRetryCount());
      stmt.setInt(5, stats.oldestMessageAgeMinutes());
      stmt.executeUpdate();
    } catch (SQLException e) {
      System.err.println("[DLQ] Failed to record metrics: " + e.getMessage());
    }
  }

  private byte[] serialize(Object obj) throws IOException {
    var baos = new ByteArrayOutputStream();
    var oos = new ObjectOutputStream(baos);
    oos.writeObject(obj);
    oos.close();
    return baos.toByteArray();
  }

  private Object deserialize(byte[] data) {
    try {
      var bais = new ByteArrayInputStream(data);
      var ois = new ObjectInputStream(bais);
      return ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize", e);
    }
  }

  private byte[] serializeThrowable(Throwable t) {
    if (t == null) return null;
    try {
      var baos = new ByteArrayOutputStream();
      var oos = new ObjectOutputStream(baos);
      oos.writeObject(t);
      oos.close();
      return baos.toByteArray();
    } catch (Exception e) {
      return null;
    }
  }

  private Throwable deserializeThrowable(byte[] data) {
    if (data == null) return null;
    try {
      var bais = new ByteArrayInputStream(data);
      var ois = new ObjectInputStream(bais);
      return (Throwable) ois.readObject();
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public void close() throws Exception {
    scheduler.shutdown();
    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
      scheduler.shutdownNow();
    }
  }
}
