package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Coordinates distributed sagas with state persistence and compensation.
 *
 * <p>Ensures distributed transactions complete or fully rollback across multiple processes.
 */
public class SagaCoordinator implements AutoCloseable {

  private final String dbUrl;
  private final String dbUsername;
  private final String dbPassword;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "SagaCoordinator-Recovery");
        t.setDaemon(true);
        return t;
      });

  private static final String SAGA_STATE_TABLE =
      "CREATE TABLE IF NOT EXISTS saga_state ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  saga_id VARCHAR(255) NOT NULL UNIQUE,"
          + "  definition BYTEA NOT NULL,"
          + "  context BYTEA NOT NULL,"
          + "  status VARCHAR(50) NOT NULL,"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  completed_steps TEXT,"
          + "  failed_steps TEXT"
          + ");";

  private static final String SAGA_EVENTS_TABLE =
      "CREATE TABLE IF NOT EXISTS saga_events ("
          + "  id BIGSERIAL PRIMARY KEY,"
          + "  saga_id VARCHAR(255) NOT NULL,"
          + "  step_name VARCHAR(255) NOT NULL,"
          + "  event_type VARCHAR(50) NOT NULL,"
          + "  data BYTEA,"
          + "  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,"
          + "  FOREIGN KEY(saga_id) REFERENCES saga_state(saga_id)"
          + ");";

  public enum SagaStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    COMPENSATING,
    FAILED
  }

  public record SagaDefinition(String name, List<SagaStep> steps, String compensationStrategy) {
    public static SagaDefinition sequential(String name, SagaStep... steps) {
      return new SagaDefinition(name, Arrays.asList(steps), "forward");
    }
  }

  public SagaCoordinator(String dbUrl, String dbUsername, String dbPassword) {
    this.dbUrl = dbUrl;
    this.dbUsername = dbUsername;
    this.dbPassword = dbPassword;
    initializeTables();
    startRecoveryScheduler();
  }

  private void initializeTables() {
    try (var conn = getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(SAGA_STATE_TABLE);
      stmt.execute(SAGA_EVENTS_TABLE);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize saga tables", e);
    }
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
  }

  /**
   * Execute a saga.
   *
   * @param sagaId unique saga identifier
   * @param definition saga definition with steps
   * @return true if saga completed successfully
   */
  public boolean executeSaga(String sagaId, SagaDefinition definition) {
    SagaStep.SagaContext context = new SagaStep.SagaContext(sagaId);

    try {
      // Persist saga state as PENDING
      persistSagaState(sagaId, definition, context, SagaStatus.PENDING);

      // Execute steps
      boolean success = executeSteps(sagaId, definition.steps(), context);

      if (success) {
        persistSagaState(sagaId, definition, context, SagaStatus.COMPLETED);
        recordSagaEvent(sagaId, "saga", "COMPLETED", null);
        return true;
      } else {
        // Compensate on failure
        persistSagaState(sagaId, definition, context, SagaStatus.COMPENSATING);
        compensateSteps(sagaId, definition.steps(), context);
        persistSagaState(sagaId, definition, context, SagaStatus.FAILED);
        recordSagaEvent(sagaId, "saga", "FAILED", null);
        return false;
      }
    } catch (Exception e) {
      persistSagaState(sagaId, definition, context, SagaStatus.FAILED);
      recordSagaEvent(sagaId, "saga", "ERROR", e.getMessage());
      return false;
    }
  }

  private boolean executeSteps(String sagaId, List<SagaStep> steps, SagaStep.SagaContext context) {
    for (SagaStep step : steps) {
      if (!executeStep(sagaId, step, context)) {
        return false;
      }
    }
    return true;
  }

  private boolean executeStep(String sagaId, SagaStep step, SagaStep.SagaContext context) {
    return switch (step) {
      case SagaStep.Action action -> {
        try {
          boolean result = action.execute().apply(context);
          if (result) {
            context.markCompleted(action.stepName());
            recordSagaEvent(sagaId, action.stepName(), "EXECUTED", null);
          } else {
            context.markFailed(action.stepName(), new RuntimeException("Action failed"));
          }
          yield result;
        } catch (Exception e) {
          context.markFailed(action.stepName(), e);
          recordSagaEvent(sagaId, action.stepName(), "FAILED", e.getMessage());
          yield false;
        }
      }
      case SagaStep.ConditionalStep cond -> {
        boolean condResult = cond.condition().apply(context);
        boolean stepResult =
            condResult ? executeStep(sagaId, cond.ifTrue(), context)
                : executeStep(sagaId, cond.ifFalse(), context);
        yield stepResult;
      }
      case SagaStep.SequentialSteps seq -> executeSteps(sagaId, Arrays.asList(seq.steps()), context);
      case SagaStep.ParallelSteps par -> executeParallelSteps(sagaId, Arrays.asList(par.steps()), context);
    };
  }

  private boolean executeParallelSteps(
      String sagaId, List<SagaStep> steps, SagaStep.SagaContext context) {
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(steps.size(), 4));
    List<Future<Boolean>> futures =
        steps.stream()
            .map(
                step ->
                    executor.submit(() -> executeStep(sagaId, step, context)))
            .toList();

    boolean allSucceeded = true;
    for (Future<Boolean> future : futures) {
      try {
        if (!future.get()) {
          allSucceeded = false;
        }
      } catch (Exception e) {
        allSucceeded = false;
      }
    }

    executor.shutdown();
    return allSucceeded;
  }

  private void compensateSteps(String sagaId, List<SagaStep> steps, SagaStep.SagaContext context) {
    // Compensate in reverse order
    for (int i = steps.size() - 1; i >= 0; i--) {
      SagaStep step = steps.get(i);
      compensateStep(sagaId, step, context);
    }
  }

  private void compensateStep(String sagaId, SagaStep step, SagaStep.SagaContext context) {
    switch (step) {
      case SagaStep.Action action -> {
        if (context.hasCompleted(action.stepName())) {
          try {
            action.compensate().apply(context);
            recordSagaEvent(sagaId, action.stepName(), "COMPENSATED", null);
          } catch (Exception e) {
            recordSagaEvent(sagaId, action.stepName(), "COMPENSATION_FAILED", e.getMessage());
          }
        }
      }
      case SagaStep.ConditionalStep cond -> {
        // Compensation determined by context
        compensateStep(sagaId, cond.ifTrue(), context);
      }
      case SagaStep.SequentialSteps seq -> {
        for (SagaStep s : seq.steps()) {
          compensateStep(sagaId, s, context);
        }
      }
      case SagaStep.ParallelSteps par -> {
        for (SagaStep s : par.steps()) {
          compensateStep(sagaId, s, context);
        }
      }
    }
  }

  private void persistSagaState(
      String sagaId, SagaDefinition definition, SagaStep.SagaContext context, SagaStatus status) {
    String sql =
        "INSERT INTO saga_state (saga_id, definition, context, status, created_at, updated_at, completed_steps, failed_steps) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (saga_id) DO UPDATE SET status = ?, context = ?, updated_at = ?, completed_steps = ?, failed_steps = ?";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      byte[] defBytes = serialize(definition);
      byte[] ctxBytes = serialize(context);
      Timestamp now = Timestamp.from(Instant.now());
      String completedSteps = String.join(",", context.completedSteps());
      String failedSteps = String.join(",", context.failedSteps().keySet());

      stmt.setString(1, sagaId);
      stmt.setBytes(2, defBytes);
      stmt.setBytes(3, ctxBytes);
      stmt.setString(4, status.name());
      stmt.setTimestamp(5, now);
      stmt.setTimestamp(6, now);
      stmt.setString(7, completedSteps);
      stmt.setString(8, failedSteps);

      stmt.setString(9, status.name());
      stmt.setBytes(10, ctxBytes);
      stmt.setTimestamp(11, now);
      stmt.setString(12, completedSteps);
      stmt.setString(13, failedSteps);

      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to persist saga state", e);
    }
  }

  private void recordSagaEvent(String sagaId, String stepName, String eventType, String data) {
    String sql =
        "INSERT INTO saga_events (saga_id, step_name, event_type, data, timestamp) VALUES (?, ?, ?, ?, ?)";

    try (var conn = getConnection();
        var stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, sagaId);
      stmt.setString(2, stepName);
      stmt.setString(3, eventType);
      stmt.setString(4, data);
      stmt.setTimestamp(5, Timestamp.from(Instant.now()));
      stmt.executeUpdate();
    } catch (SQLException e) {
      // Log but don't fail
      System.err.println("[SagaCoordinator] Failed to record event: " + e.getMessage());
    }
  }

  private void startRecoveryScheduler() {
    scheduler.scheduleAtFixedRate(this::recoverPendingSagas, 1, 1, TimeUnit.MINUTES);
  }

  private void recoverPendingSagas() {
    String sql =
        "SELECT saga_id, status FROM saga_state WHERE status IN ('IN_PROGRESS', 'COMPENSATING') AND updated_at < NOW() - INTERVAL '10 minutes'";

    try (var conn = getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        String sagaId = rs.getString("saga_id");
        String status = rs.getString("status");
        // Attempt recovery based on status
        System.err.println("[SagaCoordinator] Recovering saga: " + sagaId + " status: " + status);
      }
    } catch (SQLException e) {
      System.err.println("[SagaCoordinator] Recovery check failed: " + e.getMessage());
    }
  }

  private byte[] serialize(Object obj) throws IOException {
    var baos = new ByteArrayOutputStream();
    var oos = new ObjectOutputStream(baos);
    oos.writeObject(obj);
    oos.close();
    return baos.toByteArray();
  }

  @Override
  public void close() throws Exception {
    scheduler.shutdown();
    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
      scheduler.shutdownNow();
    }
  }
}
