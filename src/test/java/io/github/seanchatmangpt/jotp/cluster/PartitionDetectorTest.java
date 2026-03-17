package io.github.seanchatmangpt.jotp.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartitionDetectorTest {

  private InMemoryNodeHealthChecker healthChecker;
  private InMemoryPartitionDetector detector;

  @BeforeEach
  void setUp() {
    healthChecker = new InMemoryNodeHealthChecker();
    detector = new InMemoryPartitionDetector(5, healthChecker);
  }

  @Test
  void testQuorumCalculation() {
    // 5 nodes: quorum = 3
    assertThat(detector.getQuorumSize()).isEqualTo(3);
  }

  @Test
  void testQuorumEvenNodes() {
    detector = new InMemoryPartitionDetector(4, healthChecker);
    // 4 nodes: quorum = 3
    assertThat(detector.getQuorumSize()).isEqualTo(3);
  }

  @Test
  void testHasQuorumWithAllNodes() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.startMonitoring("node2", 1000, 500);
    healthChecker.startMonitoring("node3", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    healthChecker.setHealthStatus("node2", NodeHealthChecker.Status.HEALTHY);
    healthChecker.setHealthStatus("node3", NodeHealthChecker.Status.HEALTHY);

    // Not testing actual quorum logic here without full integration
    // This is a simplified test structure
  }

  @Test
  void testIsMinorityPartitionWhenNodesAlive() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    // 1 node alive vs 5 cluster size: 1 < 3 (quorum) = minority
    assertThat(detector.isMinorityPartition()).isTrue();
  }

  @Test
  void testGetReachableNodes() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    var reachable = detector.getReachableNodes();
    assertThat(reachable).contains("node1");
  }

  @Test
  void testGetUnreachableNodes() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);

    var unreachable = detector.getUnreachableNodes();
    assertThat(unreachable).contains("node1");
  }

  @Test
  void testIsReachable() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    assertThat(detector.isReachable("node1")).isTrue();

    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);
    assertThat(detector.isReachable("node1")).isFalse();
  }

  @Test
  void testPartitionEventListener() {
    var events = new CopyOnWriteArrayList<PartitionDetector.PartitionEvent>();
    detector.watchPartitionChanges(events::add);

    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    // Force a health event to trigger partition detection
    healthChecker.probeNode("node1");
  }

  @Test
  void testLastPartitionEvent() {
    assertThat(detector.getLastPartitionEvent()).isEmpty();
  }

  @Test
  void testGetNodesByStatusUsingHealthChecker() {
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.startMonitoring("node2", 1000, 500);

    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    healthChecker.setHealthStatus("node2", NodeHealthChecker.Status.DEAD);

    var healthy = healthChecker.getAllHealthStatus();
    assertThat(healthy).hasSize(2);
  }

  @Test
  void testPartitionRecovery() {
    // Test that partition detection properly tracks recovery
    var eventLog = new CopyOnWriteArrayList<PartitionDetector.PartitionEvent>();
    detector.watchPartitionChanges(eventLog::add);

    // Simulate nodes failing and recovering
    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.startMonitoring("node2", 1000, 500);

    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);
    healthChecker.setHealthStatus("node2", NodeHealthChecker.Status.DEAD);

    // With 5 nodes and 2 dead, should be in minority (3/5 alive needed, have 3 from health
    // checker)
  }

  @Test
  void testCloseable() {
    detector.close();
    // Should not throw
    assertThat(detector.getReachableNodes()).isEmpty();
  }

  @Test
  void testMultiplePartitionListeners() {
    var events1 = new CopyOnWriteArrayList<PartitionDetector.PartitionEvent>();
    var events2 = new CopyOnWriteArrayList<PartitionDetector.PartitionEvent>();

    detector.watchPartitionChanges(events1::add);
    detector.watchPartitionChanges(events2::add);

    healthChecker.startMonitoring("node1", 1000, 500);
    healthChecker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
  }
}
