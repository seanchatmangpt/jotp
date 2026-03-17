package io.github.seanchatmangpt.jotp.cluster;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClusterIntegrationTest {

  private InMemoryClusterManager clusterManager;
  private InMemoryNodeHealthChecker healthChecker;
  private InMemoryPartitionDetector partitionDetector;
  private InMemoryLeaderElection leaderElection;

  @BeforeEach
  void setUp() {
    clusterManager = new InMemoryClusterManager(2000);
    healthChecker = new InMemoryNodeHealthChecker();
    partitionDetector = new InMemoryPartitionDetector(5, healthChecker);
    leaderElection = new InMemoryLeaderElection();
  }

  @Test
  void testFullClusterLifecycle() {
    var metadata = Map.of("region", "us-east", "capacity", "10");

    clusterManager.registerNode("node1", 9000, metadata);
    clusterManager.registerNode("node2", 9001, metadata);
    clusterManager.registerNode("node3", 9002, metadata);

    assertThat(clusterManager.getAliveNodes()).hasSize(3);
    assertThat(clusterManager.getLeader()).isPresent();

    clusterManager.deregisterNode("node1");
    assertThat(clusterManager.getAliveNodes()).hasSize(2);
  }

  @Test
  void testLeaderElectionIntegration() {
    clusterManager.registerNode("node1", 9000, Map.of());
    clusterManager.registerNode("node2", 9001, Map.of());
    clusterManager.registerNode("node3", 9002, Map.of());

    var leader1 = clusterManager.getLeader();
    assertThat(leader1).isPresent();

    // Try to acquire leader lease
    var acquired = leaderElection.acquireLeaderLease(leader1.get(), 10000, 5000);
    assertThat(acquired).isTrue();
  }

  @Test
  void testHealthCheckingIntegration() {
    healthChecker.startMonitoring("node1", 500, 250);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    assertThat(healthChecker.isHealthy("node1")).isTrue();

    // Simulate failure
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);
    assertThat(healthChecker.isHealthy("node1")).isFalse();
  }

  @Test
  void testPartitionDetectionIntegration() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.startMonitoring("node2", 1000, 500);
    healthChecker.startMonitoring("node3", 1000, 500);

    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    healthChecker.setHealthStatus("node2", NodeHealthChecker.Status.HEALTHY);
    healthChecker.setHealthStatus("node3", NodeHealthChecker.Status.HEALTHY);

    assertThat(partitionDetector.hasQuorum()).isTrue();
  }

  @Test
  void testClusterWithHealthChecker() {
    var events = new CopyOnWriteArrayList<ClusterManager.NodeEvent>();
    clusterManager.watchNodeChanges(events::add);

    clusterManager.registerNode("node1", 9000, Map.of());
    clusterManager.registerNode("node2", 9001, Map.of());

    await()
        .atMost(1, SECONDS)
        .untilAsserted(() -> assertThat(events).isNotEmpty());

    assertThat(clusterManager.getAliveCount()).isGreaterThan(0);
  }

  @Test
  void testGracefulShutdown() {
    clusterManager.registerNode("node1", 9000, Map.of());
    clusterManager.registerNode("node2", 9001, Map.of());

    clusterManager.deregisterNode("node1");
    assertThat(clusterManager.getAliveNodes()).contains("node2");
    assertThat(clusterManager.getAliveNodes()).doesNotContain("node1");
  }

  @Test
  void testMetadataFiltering() {
    clusterManager.registerNode("node1", 9000, Map.of("region", "us-east", "tier", "gold"));
    clusterManager.registerNode("node2", 9001, Map.of("region", "us-west", "tier", "silver"));
    clusterManager.registerNode("node3", 9002, Map.of("region", "us-east", "tier", "silver"));

    assertThat(clusterManager.getNodesByMetadata("region", "us-east"))
        .containsExactlyInAnyOrder("node1", "node3");
    assertThat(clusterManager.getNodesByMetadata("tier", "gold")).contains("node1");
  }

  @Test
  void testLeaderFailoverWithElection() {
    clusterManager.registerNode("node1", 9000, Map.of());
    clusterManager.registerNode("node2", 9001, Map.of());
    clusterManager.registerNode("node3", 9002, Map.of());

    var leader = clusterManager.getLeader();
    assertThat(leader).isPresent();

    clusterManager.deregisterNode(leader.get());

    await()
        .atMost(500, MILLISECONDS)
        .untilAsserted(
            () -> {
              var newLeader = clusterManager.getLeader();
              assertThat(newLeader).isPresent();
              // New leader should be different
              assertThat(newLeader.get()).isNotEqualTo(leader.get());
            });
  }

  @Test
  void testHealthAndPartitionDetection() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.startMonitoring("node2", 1000, 500);
    healthChecker.startMonitoring("node3", 1000, 500);
    healthChecker.startMonitoring("node4", 1000, 500);
    healthChecker.startMonitoring("node5", 1000, 500);

    // All healthy
    for (int i = 1; i <= 5; i++) {
      healthChecker.setHealthStatus("node" + i, NodeHealthChecker.Status.HEALTHY);
    }

    assertThat(partitionDetector.hasQuorum()).isTrue();

    // Simulate node failures
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);
    healthChecker.setHealthStatus("node2", NodeHealthChecker.Status.DEAD);
    healthChecker.setHealthStatus("node3", NodeHealthChecker.Status.DEAD);

    // Now we should have minority (2 nodes healthy vs 5 total = minority)
    assertThat(partitionDetector.isMinorityPartition()).isTrue();
  }

  @Test
  void testMultipleListenerNotifications() {
    var clusterEvents = new CopyOnWriteArrayList<ClusterManager.NodeEvent>();
    var healthEvents = new CopyOnWriteArrayList<NodeHealthChecker.HealthEvent>();
    var partitionEvents = new CopyOnWriteArrayList<PartitionDetector.PartitionEvent>();

    clusterManager.watchNodeChanges(clusterEvents::add);
    healthChecker.watchHealthChanges(healthEvents::add);
    partitionDetector.watchPartitionChanges(partitionEvents::add);

    clusterManager.registerNode("node1", 9000, Map.of());

    await()
        .atMost(1, SECONDS)
        .untilAsserted(() -> assertThat(clusterEvents).isNotEmpty());
  }

  @Test
  void testLeaseRenewalPattern() {
    leaderElection.acquireLeaderLease("node1", 500, 5000);
    assertThat(leaderElection.isLeader("node1")).isTrue();

    var renewed = leaderElection.renewLeaderLease("node1", 10000);
    assertThat(renewed).isTrue();
    assertThat(leaderElection.isLeader("node1")).isTrue();
  }

  @Test
  void testCompleteClusterScenario() {
    // Scenario: 5-node cluster with leader election and health checking
    var nodes = new String[] {"node1", "node2", "node3", "node4", "node5"};

    // Register all nodes
    for (int i = 0; i < nodes.length; i++) {
      var metadata = Map.of(
          "region", i < 3 ? "us-east" : "us-west",
          "index", String.valueOf(i));
      clusterManager.registerNode(nodes[i], 9000 + i, metadata);
      healthChecker.startMonitoring(nodes[i], 1000, 500);
      healthChecker.setHealthStatus(nodes[i], NodeHealthChecker.Status.HEALTHY);
    }

    // All nodes registered
    assertThat(clusterManager.getAliveNodes()).hasSize(5);
    assertThat(clusterManager.getNodesByMetadata("region", "us-east")).hasSize(3);
    assertThat(clusterManager.getNodesByMetadata("region", "us-west")).hasSize(2);

    // Leader elected
    assertThat(clusterManager.getLeader()).isPresent();

    // Simulate leader lease
    var leader = clusterManager.getLeader().get();
    leaderElection.acquireLeaderLease(leader, 30000, 5000);
    assertThat(leaderElection.isLeader(leader)).isTrue();

    // Node failure
    clusterManager.deregisterNode(nodes[0]);
    healthChecker.setHealthStatus(nodes[0], NodeHealthChecker.Status.DEAD);

    assertThat(clusterManager.getAliveNodes()).hasSize(4);
    assertThat(healthChecker.getAllHealthStatus()).hasSize(5); // Still monitoring

    // Leader still valid
    assertThat(leaderElection.getCurrentLeader()).contains(leader);

    // Recovery: bring node back
    clusterManager.registerNode(nodes[0], 9000, Map.of());
    healthChecker.setHealthStatus(nodes[0], NodeHealthChecker.Status.HEALTHY);

    assertThat(clusterManager.getAliveNodes()).hasSize(5);
  }

  @Test
  void testCloseAllResources() {
    clusterManager.registerNode("node1", 9000, Map.of());
    healthChecker.startMonitoring("node1", 1000, 500);

    clusterManager.close();
    healthChecker.close();
    partitionDetector.close();
    leaderElection.close();

    assertThat(clusterManager.getAliveNodes()).isEmpty();
  }
}
