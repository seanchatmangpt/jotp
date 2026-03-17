package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NodeDiscovery}.
 *
 * <p>Verifies distributed node discovery with backend abstraction and cluster management.
 */
@DisplayName("NodeDiscovery — OTP distributed cluster membership")
class NodeDiscoveryTest {


    private StaticNodeDiscovery nodeDiscovery;
    private InMemoryNodeDiscoveryBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryNodeDiscoveryBackend();

        // Create a StaticNodeDiscovery with test configuration
        nodeDiscovery =
                new StaticNodeDiscovery(
                        "node1",
                        List.of("node1", "node2", "node3"),
                        Map.of(
                                "node1", "localhost:8080",
                                "node2", "localhost:8081",
                                "node3", "localhost:8082"),
                        backend,
                        Duration.ofMillis(100), // Fast health checks for testing
                        Duration.ofMillis(200), // Heartbeat timeout
                        Duration.ofMillis(300) // Degraded timeout
                        );
    }

    @AfterEach
    void tearDown() {
        if (nodeDiscovery != null) {
            nodeDiscovery.shutdown();
        }
    }

    @Test
    @DisplayName("Should get healthy nodes from the cluster")
    void getHealthyNodes_returnsAllHealthyNodes() {

        List<String> healthyNodes = nodeDiscovery.getHealthyNodes();

        assertThat(healthyNodes).hasSize(3);
        assertThat(healthyNodes).contains("node1", "node2", "node3");
    }

    @Test
    @DisplayName("Should return empty list when no nodes are healthy")
    void getHealthyNodes_returnsEmptyListWhenNoNodes() {
        // Mark all nodes as down
        nodeDiscovery.onNodeDown("node1");
        nodeDiscovery.onNodeDown("node2");
        nodeDiscovery.onNodeDown("node3");

        List<String> healthyNodes = nodeDiscovery.getHealthyNodes();

        assertThat(healthyNodes).isEmpty();
    }

    @Test
    @DisplayName("Should start health check service")
    void startHealthChecks_activatesService() {

        assertThatCode(() -> nodeDiscovery.startHealthChecks()).doesNotThrowAnyException();
        assertThat(nodeDiscovery.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Should stop health check service")
    void shutdown_deactivatesService() {
        nodeDiscovery.startHealthChecks();

        assertThatCode(() -> nodeDiscovery.shutdown()).doesNotThrowAnyException();
        assertThat(nodeDiscovery.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should handle multiple start calls gracefully")
    void startHealthChecks_handlesMultipleCalls() {
        nodeDiscovery.startHealthChecks();

        assertThatCode(() -> nodeDiscovery.startHealthChecks()).doesNotThrowAnyException();
        assertThat(nodeDiscovery.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Should handle multiple shutdown calls gracefully")
    void shutdown_handlesMultipleCalls() {
        nodeDiscovery.startHealthChecks();
        nodeDiscovery.shutdown();

        assertThatCode(() -> nodeDiscovery.shutdown()).doesNotThrowAnyException();
        assertThat(nodeDiscovery.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should mark node as down")
    void onNodeDown_marksNodeAsDown() {

        nodeDiscovery.onNodeDown("node2");

        var nodeInfo = backend.getNode("node2");
        assertThat(nodeInfo).isPresent();
        assertThat(nodeInfo.get().status()).isEqualTo(NodeInfo.NodeStatus.DOWN);
    }

    @Test
    @DisplayName("Should mark node as healthy after recovery")
    void onNodeUp_marksNodeAsHealthy() {

        nodeDiscovery.onNodeDown("node2");
        nodeDiscovery.onNodeUp("node2");

        var nodeInfo = backend.getNode("node2");
        assertThat(nodeInfo).isPresent();
        assertThat(nodeInfo.get().status()).isEqualTo(NodeInfo.NodeStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should filter out unhealthy nodes")
    void getHealthyNodes_filtersUnhealthyNodes() {

        nodeDiscovery.onNodeDown("node2");

        List<String> healthyNodes = nodeDiscovery.getHealthyNodes();

        assertThat(healthyNodes).hasSize(2);
        assertThat(healthyNodes).contains("node1", "node3");
        assertThat(healthyNodes).doesNotContain("node2");
    }

    @Test
    @DisplayName("Should register new node")
    void registerNode_addsNodeToCluster() {
                "New nodes enter with HEALTHY status and immediately participate in cluster operations.");

        var result = nodeDiscovery.registerNode("node4", "localhost:8083");

        assertThat(result.isSuccess()).isTrue();

        var nodeInfo = backend.getNode("node4");
        assertThat(nodeInfo).isPresent();
        assertThat(nodeInfo.get().nodeName()).isEqualTo("node4");
        assertThat(nodeInfo.get().nodeAddress()).isEqualTo("localhost:8083");
    }

    @Test
    @DisplayName("Should get node information from backend")
    void getNode_returnsNodeInfo() {
        var nodeInfo = backend.getNode("node1");

        assertThat(nodeInfo).isPresent();
        assertThat(nodeInfo.get().nodeName()).isEqualTo("node1");
        assertThat(nodeInfo.get().nodeAddress()).isEqualTo("localhost:8080");
        assertThat(nodeInfo.get().status()).isEqualTo(NodeInfo.NodeStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should return empty for non-existent node")
    void getNode_returnsEmptyForNonExistent() {
        var nodeInfo = backend.getNode("non-existent");

        assertThat(nodeInfo).isEmpty();
    }

    @Test
    @DisplayName("Should list all nodes from backend")
    void listNodes_returnsAllNodes() {
        var nodes = backend.listNodes();

        assertThat(nodes).hasSize(3);
        assertThat(nodes).anyMatch(n -> n.nodeName().equals("node1"));
        assertThat(nodes).anyMatch(n -> n.nodeName().equals("node2"));
        assertThat(nodes).anyMatch(n -> n.nodeName().equals("node3"));
    }

    @Test
    @DisplayName("Should handle concurrent node registration")
    void registerNode_handlesConcurrentAccess() throws InterruptedException {
                "Multiple nodes may join simultaneously during cluster bootstrap or scaling events.");

        var threads = new java.util.ArrayList<Thread>();
        var latch = new java.util.concurrent.CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            final int index = i;
            var thread =
                    new Thread(
                            () -> {
                                try {
                                    nodeDiscovery.registerNode(
                                            "node" + (10 + index), "localhost:" + (8090 + index));
                                } finally {
                                    latch.countDown();
                                }
                            });
            threads.add(thread);
            thread.start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(backend.listNodes()).hasSize(13); // 3 initial + 10 new
    }

    @Test
    @DisplayName("Should send heartbeat for this node")
    void sendHeartbeat_updatesTimestamp() {

        var before = backend.getNode("node1").get().lastHeartbeat();

        // Wait a bit to ensure timestamp difference
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        nodeDiscovery.sendHeartbeat();

        var after = backend.getNode("node1").get().lastHeartbeat();
        assertThat(after).isAfter(before);
    }

    @Test
    @DisplayName("Should add node down listener")
    void addNodeDownListener_notifiesOnNodeDown() {

        var listenerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        nodeDiscovery.addNodeDownListener(
                nodeName -> {
                    if (nodeName.equals("node2")) {
                        listenerCalled.set(true);
                    }
                });

        nodeDiscovery.onNodeDown("node2");

        assertThat(listenerCalled.get()).isTrue();
    }

    @Test
    @DisplayName("Should add node up listener")
    void addNodeUpListener_notifiesOnNodeUp() {

        var listenerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        nodeDiscovery.addNodeUpListener(
                nodeName -> {
                    if (nodeName.equals("node2")) {
                        listenerCalled.set(true);
                    }
                });

        nodeDiscovery.onNodeDown("node2");
        nodeDiscovery.onNodeUp("node2");

        assertThat(listenerCalled.get()).isTrue();
    }

    @Test
    @DisplayName("Should get this node name")
    void thisNodeName_returnsCorrectName() {
        assertThat(nodeDiscovery.thisNodeName()).isEqualTo("node1");
    }
}
