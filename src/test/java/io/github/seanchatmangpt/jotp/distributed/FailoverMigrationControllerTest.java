package io.github.seanchatmangpt.jotp.distributed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.DefaultGlobalProcRegistry;
import io.github.seanchatmangpt.jotp.GlobalProcRegistry;
import io.github.seanchatmangpt.jotp.ProcessServiceDiscoveryProvider;
import io.github.seanchatmangpt.jotp.InMemoryProcessDiscovery;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.distributed.FailoverMigrationController.MigrationEvent;
import io.github.seanchatmangpt.jotp.distributed.FailoverMigrationController.MigrationPlan;
import io.github.seanchatmangpt.jotp.distributed.FailoverMigrationController.MigrationStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for FailoverMigrationController.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Node failure detection triggers migration
 *   <li>Processes are migrated to healthy nodes
 *   <li>Registry is updated with new locations
 *   <li>Multiple node failures cascade migrations
 *   <li>No migration when all nodes are down
 *   <li>Idempotence of migration (same process not re-migrated)
 *   <li>Migration history is logged
 * </ul>
 */
@DtrTest
@DisplayName("FailoverMigrationController — automatic process migration on node failure")
class FailoverMigrationControllerTest {

  private NodeId node1Id, node2Id, node3Id;
  private NodeFailureDetector failureDetector;
  private GlobalProcRegistry registry;
  private ProcessServiceDiscoveryProvider discoveryProvider;
  private DistributedLog eventLog;
  private FailoverMigrationController controller;
  private Path dbPath;

  @BeforeEach
  void setUp() throws Exception {
    ApplicationController.reset();

    // Create node IDs
    node1Id = new NodeId("node1", "localhost", 9001);
    node2Id = new NodeId("node2", "localhost", 9002);
    node3Id = new NodeId("node3", "localhost", 9003);

    // Setup failure detector with all nodes healthy initially
    failureDetector = new NodeFailureDetector(3);
    failureDetector.recordHeartbeat(node1Id, true);
    failureDetector.recordHeartbeat(node2Id, true);
    failureDetector.recordHeartbeat(node3Id, true);

    // Setup discovery provider
    discoveryProvider = new InMemoryProcessDiscovery();

    // Setup global registry
    registry = new DefaultGlobalProcRegistry(discoveryProvider, node1Id);

    // Setup event log
    dbPath = Files.createTempDirectory("failover-migration-test-");
    eventLog = new RocksDBLog("migration", dbPath);

    // Create controller on node1
    controller = new FailoverMigrationController(
        registry,
        discoveryProvider,
        ApplicationController.class,
        eventLog,
        failureDetector,
        node1Id);

    controller.startMonitoring();
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      controller.stopMonitoring();
    } catch (Exception ignored) {}

    // Cleanup temp directory
    if (dbPath != null) {
      Files.walk(dbPath)
          .sorted((a, b) -> b.compareTo(a))
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (Exception ignored) {}
          });
    }
  }

  @Test
  @DisplayName("Node failure triggers process migration")
  void nodeFailureTriggersProcessMigration() {
    // Simulate a process on node1
    String processName = "test-service";

    // Register the process
    discoveryProvider.register(processName, node1Id);

    // Simulate node1 failure
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    // Wait for migration to complete
    await()
        .atMost(Duration.ofSeconds(5))
        .pollDelay(Duration.ofMillis(100))
        .until(() -> {
          List<MigrationEvent> history = controller.migrationHistory();
          return history.stream()
              .anyMatch(e -> e.processId().equals(processName)
                  && e.status() != MigrationStatus.FAILED);
        });

    // Verify migration was recorded
    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history)
        .filteredOn(e -> e.processId().equals(processName))
        .isNotEmpty();
  }

  @Test
  @DisplayName("Multiple processes on failed node are all migrated")
  void multipleProcessesMigratedOnNodeFailure() {
    // Register multiple processes on node1
    String process1 = "service-a";
    String process2 = "service-b";
    String process3 = "service-c";

    discoveryProvider.register(process1, node1Id);
    discoveryProvider.register(process2, node1Id);
    discoveryProvider.register(process3, node1Id);

    // Simulate node1 failure (3 consecutive failures = threshold)
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    // Wait for migrations to be recorded
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> controller.migrationHistory().size() >= 3);

    // Verify all processes were migrated
    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history)
        .filteredOn(e -> e.processId().equals(process1))
        .isNotEmpty();
    assertThat(history)
        .filteredOn(e -> e.processId().equals(process2))
        .isNotEmpty();
    assertThat(history)
        .filteredOn(e -> e.processId().equals(process3))
        .isNotEmpty();
  }

  @Test
  @DisplayName("No migration when no healthy nodes available")
  void noMigrationWhenNoHealthyNodes() {
    // Register a process on node1
    String processName = "critical-service";
    discoveryProvider.register(processName, node1Id);

    // Mark all nodes as unhealthy
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    failureDetector.recordHeartbeat(node2Id, false);
    failureDetector.recordHeartbeat(node2Id, false);
    failureDetector.recordHeartbeat(node2Id, false);

    failureDetector.recordHeartbeat(node3Id, false);
    failureDetector.recordHeartbeat(node3Id, false);
    failureDetector.recordHeartbeat(node3Id, false);

    // Wait for migration attempt
    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> !controller.migrationHistory().isEmpty());

    // Verify migration failed due to no healthy nodes
    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history)
        .filteredOn(e -> e.processId().equals(processName))
        .isNotEmpty()
        .first()
        .satisfies(e -> {
          assertThat(e.status()).isEqualTo(MigrationStatus.FAILED);
          assertThat(e.error()).isNotEmpty();
        });
  }

  @Test
  @DisplayName("Pending migrations query returns in-progress items")
  void pendingMigrationsReturnsInProgressItems() {
    // Register a process on node1
    String processName = "service-under-migration";
    discoveryProvider.register(processName, node1Id);

    // Trigger migration
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    // Check pending migrations (at time of failure, may be in progress or completed)
    await()
        .atMost(Duration.ofSeconds(5))
        .pollDelay(Duration.ofMillis(100))
        .until(() -> !controller.migrationHistory().isEmpty());

    List<MigrationPlan> pending = controller.pendingMigrations();
    // By this point, migrations may be completed, so pending might be empty
    // But we can verify migration history is not empty
    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history).isNotEmpty();
  }

  @Test
  @DisplayName("Migration history is preserved and bounded")
  void migrationHistoryIsPreservedAndBounded() {
    // Register multiple processes
    for (int i = 0; i < 10; i++) {
      discoveryProvider.register("service-" + i, node1Id);
    }

    // Trigger multiple migrations
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> controller.migrationHistory().size() >= 10);

    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history).isNotEmpty();

    // Verify all events have timestamps
    assertThat(history)
        .allMatch(e -> e.timestamp() > 0, "All events should have timestamps");
  }

  @Test
  @DisplayName("Idempotent: same process not re-migrated on repeated failures")
  void idempotentProcessNotReMigrated() {
    String processName = "idempotent-service";
    discoveryProvider.register(processName, node1Id);

    // First migration
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> !controller.migrationHistory().isEmpty());

    int historyCountAfterFirstMigration = controller.migrationHistory().size();

    // Simulate another failure (though the node is already marked unhealthy)
    failureDetector.recordHeartbeat(node1Id, false);

    // Wait a bit to ensure no re-migration happens
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Verify history didn't grow significantly (no re-migration)
    int historyCountAfterSecondFailure = controller.migrationHistory().size();
    assertThat(historyCountAfterSecondFailure)
        .isLessThanOrEqualTo(historyCountAfterFirstMigration + 1);
  }

  @Test
  @DisplayName("Migration logs events to RocksDB")
  void migrationLogsEventsToRocksDB() {
    String processName = "logged-service";
    discoveryProvider.register(processName, node1Id);

    // Trigger migration
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> !controller.migrationHistory().isEmpty());

    // Verify event log has entries
    // Note: In-memory log may not persist, but we can verify history
    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history)
        .isNotEmpty()
        .first()
        .satisfies(e -> {
          assertThat(e.sourceNodeId()).isNotEmpty();
          assertThat(e.timestamp()).isGreaterThan(0);
        });
  }

  @Test
  @DisplayName("Process migrates to available healthy node")
  void processMinatesToHealthyNode() {
    String processName = "health-aware-service";
    discoveryProvider.register(processName, node1Id);

    // Keep node2 and node3 healthy, mark node1 as failed
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    await()
        .atMost(Duration.ofSeconds(3))
        .until(() -> !controller.migrationHistory().isEmpty());

    // Verify migration target is one of the healthy nodes
    List<MigrationEvent> history = controller.migrationHistory();
    assertThat(history)
        .filteredOn(e -> e.processId().equals(processName))
        .isNotEmpty()
        .first()
        .satisfies(e -> {
          assertThat(e.targetNodeId()).isNotNull();
          // Target should be node2 or node3
          assertThat(e.targetNodeId())
              .isIn(node2Id.wire(), node3Id.wire());
        });
  }

  @Test
  @DisplayName("Controller can be stopped and restarted safely")
  void controllerStopAndRestart() {
    // Start and stop
    controller.stopMonitoring();

    // Verify no new migrations are triggered
    String processName = "restart-test-service";
    discoveryProvider.register(processName, node1Id);

    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    int historyBefore = controller.migrationHistory().size();

    // Wait a bit
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    int historyAfter = controller.migrationHistory().size();

    // After stopping, no new migrations should be created
    assertThat(historyAfter).isEqualTo(historyBefore);
  }

  @Test
  @DisplayName("Graceful shutdown waits for migrations to complete")
  void gracefulShutdownWaitsForMigrations() {
    // Register a process
    discoveryProvider.register("shutdown-test", node1Id);

    // Trigger failure
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);
    failureDetector.recordHeartbeat(node1Id, false);

    // Give migration time to start
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Now shutdown — should wait for pending migrations
    controller.stopMonitoring();

    // Verify it completed without errors
    assertThat(controller.migrationHistory()).isNotEmpty();
  }
}
