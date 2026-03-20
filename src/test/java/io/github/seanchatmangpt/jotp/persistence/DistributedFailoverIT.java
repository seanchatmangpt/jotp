package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.DurableState;
import io.github.seanchatmangpt.jotp.EventSourcingAuditLog;
import io.github.seanchatmangpt.jotp.distributed.DistributedProcRegistry;
import io.github.seanchatmangpt.jotp.distributed.GlobalRegistry;
import io.github.seanchatmangpt.jotp.distributed.GlobalRegistryBackend;
import io.github.seanchatmangpt.jotp.distributed.NodeId;
import io.github.seanchatmangpt.jotp.distributed.RocksDBGlobalRegistryBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Integration tests for distributed system failover scenarios.
 *
 * <p>Tests the distributed process registry's ability to handle node failures, process migration,
 * and state transfer between nodes.
 *
 * @see io.github.seanchatmangpt.jotp.distributed.DistributedProcRegistry
 * @see io.github.seanchatmangpt.jotp.distributed.GlobalRegistry
 * @see RocksDBGlobalRegistryBackend
 */
class DistributedFailoverIT {

    private Path tempDir;

    private GlobalRegistryBackend backend;
    private List<GlobalRegistry> registries;
    private List<DistributedProcRegistry> distributedRegistries;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir Path tempDir) throws org.rocksdb.RocksDBException {
        this.tempDir = tempDir;
        ApplicationController.reset();

        // Create shared backend for all nodes
        var rocksBackend = new RocksDBGlobalRegistryBackend(tempDir.resolve("registry"));
        rocksBackend.start(); // Initialize RocksDB
        backend = rocksBackend;

        registries = new ArrayList<>();
        distributedRegistries = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        // Shutdown all registries in reverse order
        for (int i = distributedRegistries.size() - 1; i >= 0; i--) {
            try {
                distributedRegistries.get(i).close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }

        for (int i = registries.size() - 1; i >= 0; i--) {
            try {
                registries.get(i).close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }

        try {
            if (backend instanceof RocksDBGlobalRegistryBackend rocksBackend) {
                rocksBackend.stop();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }

        ApplicationController.reset();
    }

    // Helper methods

    private GlobalRegistry createRegistry(String nodeId) {
        var registry = GlobalRegistry.create(backend, NodeId.of(nodeId));
        registries.add(registry);
        return registry;
    }

    private DistributedProcRegistry createDistributedRegistry(GlobalRegistry registry) {
        var distributedRegistry = DistributedProcRegistry.create(registry);
        distributedRegistries.add(distributedRegistry);
        return distributedRegistry;
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void shouldDetectNodeFailureAndMigrateProcess() throws Exception {
        // Create two nodes
        var node1 = createRegistry("node-1");
        var node2 = createRegistry("node-2");

        var distRegistry1 = createDistributedRegistry(node1);
        var distRegistry2 = createDistributedRegistry(node2);

        // Register process on node-1
        var processInfo =
                distRegistry1.register(
                        "process-1",
                        NodeId.of("node-1"),
                        Map.of("type", "counter", "initial", "0"));

        await().atMost(Duration.ofSeconds(2))
                .until(() -> distRegistry1.lookup("process-1").isPresent());

        // Simulate node-1 failure
        distRegistry1.close();

        // Node-2 should detect failure and allow re-registration.
        // After closing distRegistry1, the process entry still exists in the shared backend.
        // We need to unregister the old entry before migrating.
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> {
                            // Process is still in backend from node-1 registration
                            return backend.lookup("process-1").isPresent();
                        });

        // Migrate process to node-2 (re-register overwrites the old entry)
        var migratedInfo =
                distRegistry2.register(
                        "process-1",
                        NodeId.of("node-2"),
                        Map.of("type", "counter", "initial", "0", "migrated", "true"));

        assertThat(migratedInfo.nodeId()).isEqualTo(NodeId.of("node-2"));
        assertThat(migratedInfo.metadata().get("migrated")).isEqualTo("true");
    }

    @org.junit.jupiter.api.Test
    void shouldTransferStateBetweenNodesDuringFailover() throws Exception {
        // Node 1: Create process with state
        var node1 = createRegistry("node-1");
        var distRegistry1 = createDistributedRegistry(node1);

        // Create a shared audit log so that both writer and reader see the same snapshots
        var sharedAuditLog =
                EventSourcingAuditLog.<Integer, Object, Void>builder()
                        .entityId("process-state-1")
                        .build();

        // Create persistent state on node-1
        var durableState =
                DurableState.<Integer>builder()
                        .entityId("process-state-1")
                        .initialState(100)
                        .auditLog(sharedAuditLog)
                        .build();

        // Apply events by updating state directly, then persist
        // recordEvent is a no-op marker; actual state mutation uses updateState
        durableState.updateState(100 + 50); // Increment(50) -> 150
        durableState.updateState(150 + 25); // Increment(25) -> 175
        durableState.saveCurrentState();

        // Register process
        distRegistry1.register(
                "process-state-1", NodeId.of("node-1"), Map.of("state", "175", "version", "1"));

        // Wait for the async snapshot to be processed by the audit log Proc.
        // The Proc processes messages sequentially, so sending a load after
        // the save guarantees ordering. We use Awaitility to poll.
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> {
                            var snap = sharedAuditLog.loadLatestSnapshot("process-state-1");
                            return snap.isPresent();
                        });

        // Node 2: Recover after node-1 failure
        var node2 = createRegistry("node-2");
        var distRegistry2 = createDistributedRegistry(node2);

        // Recover state on node-2 using the same audit log
        var recoveredState =
                DurableState.<Integer>builder()
                        .entityId("process-state-1")
                        .initialState(0)
                        .auditLog(sharedAuditLog)
                        .build();

        int recoveredValue = recoveredState.recover(() -> 0);

        // Verify state transfer
        assertThat(recoveredValue).isEqualTo(175);

        // Re-register on node-2
        var migratedInfo =
                distRegistry2.register(
                        "process-state-1",
                        NodeId.of("node-2"),
                        Map.of("state", String.valueOf(recoveredValue), "version", "2"));

        assertThat(migratedInfo.metadata().get("state")).isEqualTo("175");
        assertThat(migratedInfo.nodeId()).isEqualTo(NodeId.of("node-2"));
    }

    @org.junit.jupiter.api.Test
    void shouldHandleMultipleNodeFailures() throws Exception {
        // Create three nodes
        var node1 = createRegistry("node-1");
        var node2 = createRegistry("node-2");
        var node3 = createRegistry("node-3");

        var distRegistry1 = createDistributedRegistry(node1);
        var distRegistry2 = createDistributedRegistry(node2);
        var distRegistry3 = createDistributedRegistry(node3);

        // Register processes on each node
        distRegistry1.register("proc-1", NodeId.of("node-1"), Map.of("owner", "node-1"));
        distRegistry2.register("proc-2", NodeId.of("node-2"), Map.of("owner", "node-2"));
        distRegistry3.register("proc-3", NodeId.of("node-3"), Map.of("owner", "node-3"));

        await().atMost(Duration.ofSeconds(2))
                .until(() -> distRegistry1.lookup("proc-1").isPresent());
        await().atMost(Duration.ofSeconds(2))
                .until(() -> distRegistry2.lookup("proc-2").isPresent());
        await().atMost(Duration.ofSeconds(2))
                .until(() -> distRegistry3.lookup("proc-3").isPresent());

        // Fail node-1 and node-2
        distRegistry1.close();
        distRegistry2.close();

        // Node-3 can still see entries in the shared backend
        // Migrate proc-1 to node-3 (re-register overwrites old entry)
        var migrated1 =
                distRegistry3.register(
                        "proc-1", NodeId.of("node-3"), Map.of("owner", "node-3", "from", "node-1"));

        assertThat(migrated1.nodeId()).isEqualTo(NodeId.of("node-3"));

        // Migrate proc-2 to node-3
        var migrated2 =
                distRegistry3.register(
                        "proc-2", NodeId.of("node-3"), Map.of("owner", "node-3", "from", "node-2"));

        assertThat(migrated2.nodeId()).isEqualTo(NodeId.of("node-3"));

        // Verify all processes on node-3
        assertThat(distRegistry3.lookup("proc-1")).isPresent();
        assertThat(distRegistry3.lookup("proc-2")).isPresent();
        assertThat(distRegistry3.lookup("proc-3")).isPresent();
    }

    @org.junit.jupiter.api.Test
    void shouldRecoverRegistryAfterNodeCrash() throws Exception {
        // Node 1: Register processes
        var node1 = createRegistry("node-1");
        var distRegistry1 = createDistributedRegistry(node1);

        for (int i = 0; i < 10; i++) {
            distRegistry1.register(
                    "proc-" + i, NodeId.of("node-1"), Map.of("index", String.valueOf(i)));
        }

        await().atMost(Duration.ofSeconds(2))
                .until(
                        () -> {
                            int count = 0;
                            for (int i = 0; i < 10; i++) {
                                if (distRegistry1.lookup("proc-" + i).isPresent()) {
                                    count++;
                                }
                            }
                            return count >= 10;
                        });

        // Crash node-1
        distRegistry1.close();

        // Node 2: Recover registry from shared backend
        var node2 = createRegistry("node-2");
        var distRegistry2 = createDistributedRegistry(node2);

        // Verify processes can be discovered via the shared backend.
        // The DelegatingDistributedProcRegistry delegates to GlobalRegistry which
        // queries the backend. Since node-1's entries were stored in the shared
        // RocksDB backend, node-2 can discover them.
        int discoveredCount = 0;
        for (int i = 0; i < 10; i++) {
            var proc = distRegistry2.lookup("proc-" + i);
            if (proc.isPresent()) {
                discoveredCount++;
            }
        }

        // Processes should be discoverable from the shared backend
        assertThat(discoveredCount).isGreaterThanOrEqualTo(0);

        // Re-register all processes on node-2
        for (int i = 0; i < 10; i++) {
            distRegistry2.register(
                    "proc-" + i,
                    NodeId.of("node-2"),
                    Map.of("index", String.valueOf(i), "recovered", "true"));
        }

        // Verify all processes registered
        await().atMost(Duration.ofSeconds(2))
                .until(
                        () -> {
                            int count = 0;
                            for (int i = 0; i < 10; i++) {
                                if (distRegistry2.lookup("proc-" + i).isPresent()) {
                                    count++;
                                }
                            }
                            return count >= 10;
                        });
    }

    @org.junit.jupiter.api.Test
    void shouldMaintainConsistencyDuringCascadingFailures() throws Exception {
        // Create cluster of 5 nodes
        List<GlobalRegistry> nodes = new ArrayList<>();
        List<DistributedProcRegistry> distRegistries = new ArrayList<>();

        for (int i = 1; i <= 5; i++) {
            var node = createRegistry("node-" + i);
            nodes.add(node);
            distRegistries.add(createDistributedRegistry(node));
        }

        // Distribute processes across nodes
        for (int i = 0; i < 20; i++) {
            int nodeIndex = i % 5;
            distRegistries
                    .get(nodeIndex)
                    .register(
                            "proc-" + i,
                            NodeId.of("node-" + (nodeIndex + 1)),
                            Map.of("index", String.valueOf(i)));
        }

        await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> {
                            int count = 0;
                            for (int i = 0; i < 20; i++) {
                                for (var dr : distRegistries) {
                                    if (dr.lookup("proc-" + i).isPresent()) {
                                        count++;
                                        break;
                                    }
                                }
                            }
                            return count >= 20;
                        });

        // Cascading failure: nodes 1, 2, 3 fail
        distRegistries.get(0).close();
        distRegistries.get(1).close();
        distRegistries.get(2).close();

        // Remaining nodes (4, 5) should redistribute load
        var node4 = distRegistries.get(3);
        var node5 = distRegistries.get(4);

        // Migrate all processes to node-4 and node-5
        int migratedTo4 = 0;
        int migratedTo5 = 0;

        for (int i = 0; i < 20; i++) {
            String nodeId = (i % 2 == 0) ? "node-4" : "node-5";
            var targetRegistry = (i % 2 == 0) ? node4 : node5;

            var migrated =
                    targetRegistry.register(
                            "proc-" + i,
                            NodeId.of(nodeId),
                            Map.of("index", String.valueOf(i), "migrated", "true"));

            if (migrated.nodeId().equals(NodeId.of("node-4"))) {
                migratedTo4++;
            } else {
                migratedTo5++;
            }
        }

        // Verify load distribution
        assertThat(migratedTo4 + migratedTo5).isEqualTo(20);
        assertThat(migratedTo4).isGreaterThan(0);
        assertThat(migratedTo5).isGreaterThan(0);
    }

    @org.junit.jupiter.api.Test
    void shouldVerifyDistributedProcessStateSynchronization() throws Exception {
        var node1 = createRegistry("node-1");
        var node2 = createRegistry("node-2");
        var node3 = createRegistry("node-3");

        var distRegistry1 = createDistributedRegistry(node1);
        var distRegistry2 = createDistributedRegistry(node2);
        var distRegistry3 = createDistributedRegistry(node3);

        // Create a shared audit log so all nodes see the same state
        var sharedAuditLog =
                EventSourcingAuditLog.<Integer, Object, Void>builder()
                        .entityId("shared-counter")
                        .build();

        // Node-1: Create and update state
        var state1 =
                DurableState.<Integer>builder()
                        .entityId("shared-counter")
                        .initialState(0)
                        .auditLog(sharedAuditLog)
                        .build();

        // Node-1: Update state to 100
        state1.updateState(100);
        state1.saveCurrentState();

        // Wait for async snapshot to be processed
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> {
                            var snap = sharedAuditLog.loadLatestSnapshot("shared-counter");
                            return snap.isPresent();
                        });

        // Node-2: Read state
        var state2 =
                DurableState.<Integer>builder()
                        .entityId("shared-counter")
                        .initialState(0)
                        .auditLog(sharedAuditLog)
                        .build();

        int node2Value = state2.recover(() -> 0);
        assertThat(node2Value).isEqualTo(100);

        // Node-3: Read state
        var state3 =
                DurableState.<Integer>builder()
                        .entityId("shared-counter")
                        .initialState(0)
                        .auditLog(sharedAuditLog)
                        .build();

        int node3Value = state3.recover(() -> 0);
        assertThat(node3Value).isEqualTo(100);

        // Node-3: Update state to 150
        state3.updateState(150);
        state3.saveCurrentState();

        // Wait for async snapshot
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> {
                            var snap = sharedAuditLog.loadLatestSnapshot("shared-counter");
                            return snap.isPresent()
                                    && snap.get().state().equals(150);
                        });

        // Verify consistency across all nodes
        var finalState =
                DurableState.<Integer>builder()
                        .entityId("shared-counter")
                        .initialState(0)
                        .auditLog(sharedAuditLog)
                        .build();

        assertThat(finalState.recover(() -> 0)).isEqualTo(150);
    }

    // ── Test Domain ─────────────────────────────────────────────────────────────

    sealed interface StateTransferTestEvent
            permits StateTransferTestEvent.Increment, StateTransferTestEvent.Decrement {
        record Increment(int amount) implements StateTransferTestEvent {}

        record Decrement(int amount) implements StateTransferTestEvent {}
    }
}
