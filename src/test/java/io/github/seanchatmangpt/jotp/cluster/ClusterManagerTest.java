package io.github.seanchatmangpt.jotp.cluster;

import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClusterManagerTest {

  private InMemoryClusterManager manager;

  @BeforeEach
  void setUp() {
    manager = new InMemoryClusterManager(2000); // 2 second timeout
  }

  @Test
  void testNodeRegistration() {
    var metadata = Map.of("region", "us-east", "capacity", "10");
    manager.registerNode("node1", 9000, metadata);

    assertThat(manager.getAliveNodes()).contains("node1");
    assertThat(manager.getClusterSize()).isEqualTo(1);
    assertThat(manager.isNodeAlive("node1")).isTrue();
    assertThat(manager.getNodeMetadata("node1")).containsAllEntriesOf(metadata);
  }

  @Test
  void testNodeDeregistration() {
    manager.registerNode("node1", 9000, Map.of());
    manager.deregisterNode("node1");

    assertThat(manager.getAliveNodes()).doesNotContain("node1");
    assertThat(manager.getClusterSize()).isEqualTo(0);
  }

  @Test
  void testMultipleNodeRegistration() {
    manager.registerNode("node1", 9000, Map.of());
    manager.registerNode("node2", 9001, Map.of());
    manager.registerNode("node3", 9002, Map.of());

    assertThat(manager.getAliveNodes()).containsExactlyInAnyOrder("node1", "node2", "node3");
    assertThat(manager.getAliveCount()).isEqualTo(3);
  }

  @Test
  void testLeaderElection() {
    manager.registerNode("node1", 9000, Map.of());
    assertThat(manager.getLeader()).contains("node1");

    manager.registerNode("node2", 9001, Map.of());
    manager.registerNode("node3", 9002, Map.of());

    // Leader is elected (arbitrary, but one is chosen)
    assertThat(manager.getLeader()).isPresent();
  }

  @Test
  void testLeaderChangeOnDeregistration() {
    manager.registerNode("node1", 9000, Map.of());
    var initialLeader = manager.getLeader();
    assertThat(initialLeader).isPresent();

    manager.registerNode("node2", 9001, Map.of());
    manager.deregisterNode(initialLeader.get());

    // New leader should be elected
    await()
        .atMost(500, MILLISECONDS)
        .untilAsserted(
            () -> {
              var leader = manager.getLeader();
              assertThat(leader).isPresent().containsAnyOf("node2");
            });
  }

  @Test
  void testHeartbeatTimeout() {
    manager = new InMemoryClusterManager(500); // 500ms timeout
    manager.registerNode("node1", 9000, Map.of());
    assertThat(manager.isNodeAlive("node1")).isTrue();

    // Wait for timeout
    await()
        .atMost(1, SECONDS)
        .pollInterval(100, MILLISECONDS)
        .untilAsserted(() -> assertThat(manager.isNodeAlive("node1")).isFalse());
  }

  @Test
  void testNodeEventListener() {
    var events = new CopyOnWriteArrayList<ClusterManager.NodeEvent>();
    manager.watchNodeChanges(events::add);

    manager.registerNode("node1", 9000, Map.of("region", "us"));

    await()
        .atMost(1, SECONDS)
        .untilAsserted(() -> assertThat(events).isNotEmpty());

    assertThat(events.getFirst()).isInstanceOf(ClusterManager.NodeUp.class);
    var upEvent = (ClusterManager.NodeUp) events.getFirst();
    assertThat(upEvent.nodeName()).isEqualTo("node1");
    assertThat(upEvent.metadata()).containsEntry("region", "us");
  }

  @Test
  void testPartitionDetection() {
    manager.registerNode("node1", 9000, Map.of());
    manager.registerNode("node2", 9001, Map.of());
    manager.registerNode("node3", 9002, Map.of());

    assertThat(manager.isPartitioned()).isFalse();

    // Simulate partition by setting timeout to very low and waiting
    manager = new InMemoryClusterManager(100);
    manager.registerNode("node1", 9000, Map.of());
    manager.registerNode("node2", 9001, Map.of());
    manager.registerNode("node3", 9002, Map.of());

    await()
        .atMost(2, SECONDS)
        .untilAsserted(() -> assertThat(manager.isPartitioned()).isTrue());
  }

  @Test
  void testGetNodesByMetadata() {
    manager.registerNode(
        "node1", 9000, Map.of("region", "us-east", "role", "server"));
    manager.registerNode(
        "node2", 9001, Map.of("region", "us-west", "role", "server"));
    manager.registerNode(
        "node3", 9002, Map.of("region", "eu-west", "role", "worker"));

    assertThat(manager.getNodesByMetadata("region", "us-east")).contains("node1");
    assertThat(manager.getNodesByMetadata("role", "server"))
        .containsExactlyInAnyOrder("node1", "node2");
    assertThat(manager.getNodesByMetadata("region", "ap-south")).isEmpty();
  }

  @Test
  void testMultipleListeners() {
    var events1 = new CopyOnWriteArrayList<ClusterManager.NodeEvent>();
    var events2 = new CopyOnWriteArrayList<ClusterManager.NodeEvent>();

    manager.watchNodeChanges(events1::add);
    manager.watchNodeChanges(events2::add);

    manager.registerNode("node1", 9000, Map.of());

    await()
        .atMost(1, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(events1).isNotEmpty();
              assertThat(events2).isNotEmpty();
            });
  }

  @Test
  void testClusterSizeTracking() {
    assertThat(manager.getClusterSize()).isZero();

    manager.registerNode("node1", 9000, Map.of());
    assertThat(manager.getClusterSize()).isEqualTo(1);

    manager.registerNode("node2", 9001, Map.of());
    assertThat(manager.getClusterSize()).isEqualTo(2);

    manager.deregisterNode("node1");
    assertThat(manager.getClusterSize()).isEqualTo(1);
  }

  @Test
  void testNodeDownEventOnTimeout() {
    var events = new CopyOnWriteArrayList<ClusterManager.NodeEvent>();
    manager = new InMemoryClusterManager(300);
    manager.watchNodeChanges(events::add);

    manager.registerNode("node1", 9000, Map.of());

    // Wait for timeout and DOWN event
    await()
        .atMost(2, SECONDS)
        .pollInterval(50, MILLISECONDS)
        .untilAsserted(
            () -> {
              var downEvents =
                  events.stream()
                      .filter(e -> e instanceof ClusterManager.NodeDown)
                      .toList();
              assertThat(downEvents).isNotEmpty();
            });
  }

  @Test
  void testEmptyNodeMetadata() {
    manager.registerNode("node1", 9000, Map.of());
    assertThat(manager.getNodeMetadata("node1")).isEmpty();
  }

  @Test
  void testUnregisteredNodeMetadata() {
    assertThat(manager.getNodeMetadata("non-existent")).isEmpty();
  }

  @Test
  void testQuorumCalculation() {
    // 5 nodes: quorum = 3
    manager.registerNode("node1", 9000, Map.of());
    manager.registerNode("node2", 9001, Map.of());
    manager.registerNode("node3", 9002, Map.of());
    manager.registerNode("node4", 9003, Map.of());
    manager.registerNode("node5", 9004, Map.of());

    var quorum = (manager.getClusterSize() / 2) + 1;
    assertThat(quorum).isEqualTo(3);
  }

  @Test
  void testPartitionWithOddNodeCount() {
    manager.registerNode("node1", 9000, Map.of());
    manager.registerNode("node2", 9001, Map.of());
    manager.registerNode("node3", 9002, Map.of());
    manager.registerNode("node4", 9003, Map.of());
    manager.registerNode("node5", 9004, Map.of());

    assertThat(manager.isPartitioned()).isFalse();
    // 5 nodes alive: need 3 for quorum
    // Simulating 2 nodes down would be partitioned
  }

  @Test
  void testCloseable() {
    manager.registerNode("node1", 9000, Map.of());
    manager.close();

    // Should not throw
    assertThat(manager.getAliveNodes()).isEmpty();
  }
}
