package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FailoverController}.
 *
 * <p>Verifies automatic failover behavior with process migration and node health monitoring.
 */
@DisplayName("FailoverController — OTP distributed failover semantics")
class FailoverControllerTest {


    private FailoverController failoverController;
    private GlobalProcRegistry registry;
    private NodeDiscovery nodeDiscovery;
    private InMemoryGlobalRegistryBackend backend;
    private InMemoryNodeDiscoveryBackend discoveryBackend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryGlobalRegistryBackend();
        registry = DefaultGlobalProcRegistry.getInstance();
        ((DefaultGlobalProcRegistry) registry).setBackend(backend);

        // Create a simple node discovery backend
        discoveryBackend = new InMemoryNodeDiscoveryBackend();
        nodeDiscovery = new TestNodeDiscovery(discoveryBackend);

        failoverController = new FailoverController(registry, nodeDiscovery);
    }

    @AfterEach
    void tearDown() {
        if (failoverController != null) {
            failoverController.shutdown();
        }
        if (registry instanceof DefaultGlobalProcRegistry defaultRegistry) {
            defaultRegistry.reset();
        }
    }

    @Test
    @DisplayName("Should create failover controller")
    void constructor_createsController() {
                "It integrates GlobalProcRegistry for process tracking and NodeDiscovery for health awareness.");
        assertThat(failoverController).isNotNull();
    }

    @Test
    @DisplayName("Should throw on null registry")
    void constructor_throwsOnNullRegistry() {
        assertThatThrownBy(() -> new FailoverController(null, nodeDiscovery))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw on null node discovery")
    void constructor_throwsOnNullNodeDiscovery() {
        assertThatThrownBy(() -> new FailoverController(registry, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle node down with no processes")
    void handleNodeDown_handlesNoProcesses() {
        int migratedCount = failoverController.handleNodeDown("failed-node");

        assertThat(migratedCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Should migrate processes on node down")
    void handleNodeDown_migratesProcesses() {
                "The controller iterates through failed-node's processes and reassigns them using transferGlobal().");

        // Register processes on failed-node
        Proc<String, String> proc1 = Proc.spawn("initial", (s, m) -> s);
        Proc<String, String> proc2 = Proc.spawn("initial", (s, m) -> s);

        registry.registerGlobal("proc1", new ProcRef<>(proc1), "failed-node");
        registry.registerGlobal("proc2", new ProcRef<>(proc2), "failed-node");

        // Add a healthy node
        discoveryBackend.storeNode(
                NodeInfo.create("healthy-node", "localhost:8080")
                        .withStatus(NodeInfo.NodeStatus.HEALTHY));

        int migratedCount = failoverController.handleNodeDown("failed-node");

        assertThat(migratedCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Should migrate specific process")
    void migrateProcess_migratesToTargetNode() {

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);

        registry.registerGlobal("my-proc", new ProcRef<>(proc), "node1");

        var result = failoverController.migrateProcess("my-proc", "node2");

        assertThat(result.isSuccess()).isTrue();

        var found = registry.findGlobal("my-proc");
        assertThat(found).isPresent();
        assertThat(found.get().nodeName()).isEqualTo("node2");
    }

    @Test
    @DisplayName("Should return error when migrating non-existent process")
    void migrateProcess_returnsErrorForNonExistent() {
        var result = failoverController.migrateProcess("non-existent", "node2");

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("Should check if node can accept migrations")
    void canAcceptMigrations_returnsTrueForHealthyNodes() {

        discoveryBackend.storeNode(
                NodeInfo.create("healthy-node", "localhost:8080")
                        .withStatus(NodeInfo.NodeStatus.HEALTHY));

        boolean canAccept = failoverController.canAcceptMigrations("healthy-node");

        assertThat(canAccept).isTrue();
    }

    @Test
    @DisplayName("Should check if node cannot accept migrations")
    void canAcceptMigrations_returnsFalseForUnhealthyNodes() {
        discoveryBackend.storeNode(
                NodeInfo.create("unhealthy-node", "localhost:8081")
                        .withStatus(NodeInfo.NodeStatus.DOWN));

        boolean canAccept = failoverController.canAcceptMigrations("unhealthy-node");

        assertThat(canAccept).isFalse();
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void shutdown_stopsController() {
        assertThatCode(() -> failoverController.shutdown()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle null node name in handleNodeDown")
    void handleNodeDown_throwsOnNullNodeName() {
        assertThatThrownBy(() -> failoverController.handleNodeDown(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle null process name in migrateProcess")
    void migrateProcess_throwsOnNullProcessName() {
        assertThatThrownBy(() -> failoverController.migrateProcess(null, "node2"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle null target node in migrateProcess")
    void migrateProcess_throwsOnNullTargetNode() {
        assertThatThrownBy(() -> failoverController.migrateProcess("my-proc", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle null node name in canAcceptMigrations")
    void canAcceptMigrations_throwsOnNullNodeName() {
        assertThatThrownBy(() -> failoverController.canAcceptMigrations(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should rebalance after node down")
    void rebalanceAfterNodeDown_triggersMigration() {

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        registry.registerGlobal("my-proc", new ProcRef<>(proc), "failed-node");

        discoveryBackend.storeNode(
                NodeInfo.create("healthy-node", "localhost:8080")
                        .withStatus(NodeInfo.NodeStatus.HEALTHY));

        failoverController.rebalanceAfterNodeDown("failed-node");

        var found = registry.findGlobal("my-proc");
        assertThat(found).isPresent();
        assertThat(found.get().nodeName()).isEqualTo("healthy-node");
    }

    @Test
    @DisplayName("Should handle migration when no healthy nodes available")
    void handleNodeDown_handlesNoHealthyNodes() {

        Proc<String, String> proc = Proc.spawn("initial", (s, m) -> s);
        registry.registerGlobal("my-proc", new ProcRef<>(proc), "failed-node");

        // No healthy nodes added
        int migratedCount = failoverController.handleNodeDown("failed-node");

        assertThat(migratedCount).isEqualTo(0);
    }

    // Test double implementation

    private static class TestNodeDiscovery implements NodeDiscovery {
        private final InMemoryNodeDiscoveryBackend backend;

        TestNodeDiscovery(InMemoryNodeDiscoveryBackend backend) {
            this.backend = backend;
        }

        @Override
        public io.github.seanchatmangpt.jotp.Result<Void, Exception> registerNode(
                String nodeName, String nodeAddress) {
            backend.storeNode(NodeInfo.create(nodeName, nodeAddress));
            return io.github.seanchatmangpt.jotp.Result.ok(null);
        }

        @Override
        public List<String> getHealthyNodes() {
            return backend.listNodes().stream()
                    .filter(n -> n.status() == NodeInfo.NodeStatus.HEALTHY)
                    .map(NodeInfo::nodeName)
                    .toList();
        }

        @Override
        public void onNodeDown(String nodeName) {
            // No-op for test
        }

        @Override
        public void onNodeUp(String nodeName) {
            // No-op for test
        }

        @Override
        public void startHealthChecks() {
            // No-op for test
        }

        @Override
        public void shutdown() {
            // No-op for test
        }

        public InMemoryNodeDiscoveryBackend getBackend() {
            return backend;
        }
    }
}
