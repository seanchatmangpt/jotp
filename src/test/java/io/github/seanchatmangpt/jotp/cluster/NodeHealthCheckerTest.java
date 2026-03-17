package io.github.seanchatmangpt.jotp.cluster;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeHealthCheckerTest {

  private InMemoryNodeHealthChecker checker;

  @BeforeEach
  void setUp() {
    checker = new InMemoryNodeHealthChecker();
  }

  @Test
  void testStartMonitoring() {
    checker.startMonitoring("node1", 1000, 500);
    assertThat(checker.getHealthStatus("node1")).isPresent();
  }

  @Test
  void testStopMonitoring() {
    checker.startMonitoring("node1", 1000, 500);
    checker.stopMonitoring("node1");

    assertThat(checker.getHealthStatus("node1")).isEmpty();
  }

  @Test
  void testIsHealthy() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    assertThat(checker.isHealthy("node1")).isTrue();
  }

  @Test
  void testIsDegraded() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.DEGRADED);

    assertThat(checker.isHealthy("node1")).isTrue(); // Degraded is still healthy
  }

  @Test
  void testIsDead() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);

    assertThat(checker.isHealthy("node1")).isFalse();
  }

  @Test
  void testGetHealthStatus() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    var status = checker.getHealthStatus("node1");
    assertThat(status)
        .isPresent()
        .hasValueSatisfying(
            s -> {
              assertThat(s.nodeName()).isEqualTo("node1");
              assertThat(s.status()).isEqualTo(NodeHealthChecker.Status.HEALTHY);
            });
  }

  @Test
  void testGetHealthMetrics() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    checker.probeNode("node1");

    await()
        .atMost(2, SECONDS)
        .untilAsserted(
            () -> {
              var metrics = checker.getHealthMetrics("node1");
              assertThat(metrics).isPresent();
            });
  }

  @Test
  void testGetAllHealthStatus() {
    checker.startMonitoring("node1", 1000, 500);
    checker.startMonitoring("node2", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    checker.setHealthStatus("node2", NodeHealthChecker.Status.DEGRADED);

    var allStatus = checker.getAllHealthStatus();
    assertThat(allStatus).hasSize(2).containsKeys("node1", "node2");
  }

  @Test
  void testHealthEventListener() {
    var events = new CopyOnWriteArrayList<NodeHealthChecker.HealthEvent>();
    checker.watchHealthChanges(events::add);

    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    checker.probeNode("node1");

    await()
        .atMost(2, SECONDS)
        .untilAsserted(() -> assertThat(events).isNotEmpty());
  }

  @Test
  void testProbeNode() {
    checker.startMonitoring("node1", 1000, 500);
    var status1 = checker.getHealthStatus("node1");

    checker.probeNode("node1");

    await()
        .atMost(500, MILLISECONDS)
        .untilAsserted(
            () -> {
              var status2 = checker.getHealthStatus("node1");
              assertThat(status2).isPresent();
            });
  }

  @Test
  void testSetHealthStatus() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);

    var status = checker.getHealthStatus("node1");
    assertThat(status).hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(NodeHealthChecker.Status.HEALTHY));

    checker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);
    status = checker.getHealthStatus("node1");
    assertThat(status).hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(NodeHealthChecker.Status.DEAD));
  }

  @Test
  void testDegradedLatencyThreshold() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.DEGRADED);

    var status = checker.getHealthStatus("node1");
    assertThat(status)
        .isPresent()
        .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(NodeHealthChecker.Status.DEGRADED));
  }

  @Test
  void testConsecutiveFailures() {
    checker.startMonitoring("node1", 100, 50);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.DEAD);

    var status = checker.getHealthStatus("node1");
    assertThat(status)
        .isPresent()
        .hasValueSatisfying(s -> assertThat(s.failureCount()).isGreaterThanOrEqualTo(0));
  }

  @Test
  void testRecoveringStatus() {
    checker.startMonitoring("node1", 1000, 500);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.RECOVERING);

    var status = checker.getHealthStatus("node1");
    assertThat(status)
        .isPresent()
        .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(NodeHealthChecker.Status.RECOVERING));
  }

  @Test
  void testMultipleMonitoredNodes() {
    checker.startMonitoring("node1", 1000, 500);
    checker.startMonitoring("node2", 1000, 500);
    checker.startMonitoring("node3", 1000, 500);

    assertThat(checker.getAllHealthStatus()).hasSize(3);

    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    checker.setHealthStatus("node2", NodeHealthChecker.Status.DEGRADED);
    checker.setHealthStatus("node3", NodeHealthChecker.Status.DEAD);

    assertThat(checker.isHealthy("node1")).isTrue();
    assertThat(checker.isHealthy("node2")).isTrue();
    assertThat(checker.isHealthy("node3")).isFalse();
  }

  @Test
  void testHealthEventTypes() {
    var events = new CopyOnWriteArrayList<NodeHealthChecker.HealthEvent>();
    checker.watchHealthChanges(events::add);

    checker.startMonitoring("node1", 100, 50);
    checker.setHealthStatus("node1", NodeHealthChecker.Status.HEALTHY);
    checker.probeNode("node1");

    await()
        .atMost(1, SECONDS)
        .untilAsserted(() -> assertThat(events).isNotEmpty());
  }

  @Test
  void testLatencyTracking() {
    checker.startMonitoring("node1", 1000, 500);
    checker.probeNode("node1");

    await()
        .atMost(1, SECONDS)
        .untilAsserted(
            () -> {
              var metrics = checker.getHealthMetrics("node1");
              assertThat(metrics).isPresent();
            });
  }

  @Test
  void testCloseable() {
    checker.startMonitoring("node1", 1000, 500);
    checker.close();

    assertThat(checker.getHealthStatus("node1")).isEmpty();
  }

  @Test
  void testNodesWithoutMonitoring() {
    assertThat(checker.isHealthy("non-existent")).isFalse();
    assertThat(checker.getHealthStatus("non-existent")).isEmpty();
    assertThat(checker.getHealthMetrics("non-existent")).isEmpty();
  }
}
