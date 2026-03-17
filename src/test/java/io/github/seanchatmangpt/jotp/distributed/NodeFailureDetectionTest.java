package io.github.seanchatmangpt.jotp.distributed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DtrTest
@DisplayName("NodeFailureDetector — heartbeat tracking and failure detection")
class NodeFailureDetectionTest {

  private NodeFailureDetector detector;
  private NodeId node1;
  private NodeId node2;

  @BeforeEach
  void setUp() {
    detector = new NodeFailureDetector(3);
    node1 = new NodeId("cp1", "localhost", 5001);
    node2 = new NodeId("cp2", "localhost", 5002);
  }

  @Test
  @DisplayName("Node starts healthy")
  void nodeStartsHealthy() {
    detector.recordHeartbeat(node1, true);
    assertThat(detector.isHealthy(node1)).isTrue();
    assertThat(detector.getHealthyNodes()).contains(node1);
  }

  @Test
  @DisplayName("Node marked unhealthy after threshold failures")
  void nodeMarkedUnhealthyAfterThreshold() {
    AtomicReference<NodeFailureDetector.HealthChange> lastChange = new AtomicReference<>();
    detector.onHealthChange(lastChange::set);

    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);

    assertThat(detector.isHealthy(node1)).isFalse();
    await().atMost(2, SECONDS).until(() -> lastChange.get() != null);
  }

  @Test
  @DisplayName("Successful heartbeat resets failure count")
  void successfulHeartbeatResetsFailures() {
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, true);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    assertThat(detector.isHealthy(node1)).isTrue();
    detector.recordHeartbeat(node1, false);
    assertThat(detector.isHealthy(node1)).isFalse();
  }

  @Test
  @DisplayName("Multiple nodes tracked independently")
  void multipleNodesTrackedIndependently() {
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node2, true);

    assertThat(detector.isHealthy(node1)).isFalse();
    assertThat(detector.isHealthy(node2)).isTrue();
  }

  @Test
  @DisplayName("Reset clears all heartbeat state")
  void resetClearsState() {
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.reset();
    assertThat(detector.getHealthyNodes()).isEmpty();
  }

  @Test
  @DisplayName("Threshold of 1 detects immediate failure")
  void thresholdOfOneDetectsImmediateFailure() {
    NodeFailureDetector strict = new NodeFailureDetector(1);
    strict.recordHeartbeat(node1, false);
    assertThat(strict.isHealthy(node1)).isFalse();
  }
}
