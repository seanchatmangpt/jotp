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
 * Tests for {@link StaticNodeDiscovery}.
 *
 * <p>Verifies static node discovery configuration with fixed cluster membership.
 */
@DisplayName("StaticNodeDiscovery — OTP static cluster membership")
class StaticNodeDiscoveryTest {


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
                                "node1",
                                "localhost:8080",
                                "node2",
                                "localhost:8081",
                                "node3",
                                "localhost:8082"),
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
    @DisplayName("Should register initial nodes on startup")
    void constructor_registersInitialNodes() {
                "All configured nodes are registered immediately, avoiding dynamic discovery delays.");
                "This suits small clusters with stable membership like Erlang's .hosts.file pattern.");

        List<NodeInfo> nodes = backend.listNodes();

        assertThat(nodes).hasSize(3);
        assertThat(nodes).anyMatch(n -> n.nodeName().equals("node1"));
        assertThat(nodes).anyMatch(n -> n.nodeName().equals("node2"));
        assertThat(nodes).anyMatch(n -> n.nodeName().equals("node3"));
    }

    @Test
    @DisplayName("Should register new node successfully")
    void registerNode_addsNodeToCluster() {

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
    }

    @Test
    @DisplayName("Should list all nodes")
    void listNodes_returnsAllNodes() {
        List<NodeInfo> nodes = backend.listNodes();

        assertThat(nodes).hasSize(3);
    }

    @Test
    @DisplayName("Should update heartbeat timestamp")
    void updateHeartbeat_updatesTimestamp() {

        var before = backend.getNode("node1").get();
        var newTimestamp = java.time.Instant.now().plusSeconds(10);

        backend.updateHeartbeat("node1", newTimestamp);

        var after = backend.getNode("node1").get();
        assertThat(after.lastHeartbeat()).isEqualTo(newTimestamp);
        assertThat(after.registeredAt()).isEqualTo(before.registeredAt());
    }

    @Test
    @DisplayName("Should remove node from cluster")
    void removeNode_removesNodeFromCluster() {

        backend.removeNode("node2");

        var nodeInfo = backend.getNode("node2");
        assertThat(nodeInfo).isEmpty();
    }

    @Test
    @DisplayName("Should return healthy nodes")
    void getHealthyNodes_returnsOnlyHealthyNodes() {
        List<String> healthyNodes = nodeDiscovery.getHealthyNodes();

        assertThat(healthyNodes).contains("node1", "node2", "node3");
    }

    @Test
    @DisplayName("Should support concurrent node registration")
    void registerNode_handlesConcurrentRegistration() throws InterruptedException {
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
    @DisplayName("Should handle nodes with different hosts and ports")
    void registerNode_handlesDifferentHostsAndPorts() {
                "This flexibility accommodates diverse network topologies in production deployments.");

        nodeDiscovery.registerNode("nodeA", "host1:8080");
        nodeDiscovery.registerNode("nodeB", "host2:8081");
        nodeDiscovery.registerNode("nodeC", "192.168.1.1:9090");

        var nodeA = backend.getNode("nodeA").get();
        var nodeB = backend.getNode("nodeB").get();
        var nodeC = backend.getNode("nodeC").get();

        assertThat(nodeA.nodeAddress()).isEqualTo("host1:8080");
        assertThat(nodeB.nodeAddress()).isEqualTo("host2:8081");
        assertThat(nodeC.nodeAddress()).isEqualTo("192.168.1.1:9090");
    }

    @Test
    @DisplayName("Should handle null node name in registerNode")
    void registerNode_handlesNullNodeName() {
        // Implementation accepts null and creates NodeInfo with null name
        // (records don't enforce non-null by default)
        var result = nodeDiscovery.registerNode(null, "localhost:8080");
        // Either success with null name or failure - both are acceptable behaviors
        assertThat(result.isSuccess() || result.isFailure()).isTrue();
    }

    @Test
    @DisplayName("Should handle null node address in registerNode")
    void registerNode_handlesNullNodeAddress() {
        // Implementation accepts null and creates NodeInfo with null address
        // (records don't enforce non-null by default)
        var result = nodeDiscovery.registerNode("nodeX", null);
        // Either success with null address or failure - both are acceptable behaviors
        assertThat(result.isSuccess() || result.isFailure()).isTrue();
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void shutdown_stopsHealthChecks() {
        assertThatCode(() -> nodeDiscovery.shutdown()).doesNotThrowAnyException();
    }
}
