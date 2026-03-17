package io.github.seanchatmangpt.jotp.distributed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DtrTest
@DisplayName("LeaderElection — deterministic leader selection")
class LeaderElectionTest {

  private InMemoryDiscoveryProvider provider;
  private NodeFailureDetector detector;
  private LeaderElection election;
  private NodeId node1, node2, node3;

  @BeforeEach
  void setUp() {
    provider = new InMemoryDiscoveryProvider();
    detector = new NodeFailureDetector(3);
    election = new LeaderElection(provider, detector, Duration.ofMillis(100));
    node1 = new NodeId("cp1", "localhost", 5001);
    node2 = new NodeId("cp2", "localhost", 5002);
    node3 = new NodeId("cp3", "localhost", 5003);
  }

  @Test
  @DisplayName("Lowest NodeId elected as leader")
  void lowestNodeIdElectedAsLeader() {
    provider.addHealthyNode(node1);
    provider.addHealthyNode(node2);
    provider.addHealthyNode(node3);
    detector.recordHeartbeat(node1, true);
    detector.recordHeartbeat(node2, true);
    detector.recordHeartbeat(node3, true);
    election.elect();
    assertThat(election.currentLeader()).contains(node1);
  }

  @Test
  @DisplayName("Leader changes when current leader fails")
  void leaderChangesWhenCurrentLeaderFails() {
    provider.addHealthyNode(node1);
    provider.addHealthyNode(node2);
    detector.recordHeartbeat(node1, true);
    detector.recordHeartbeat(node2, true);
    election.elect();
    assertThat(election.currentLeader()).contains(node1);

    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    election.elect();
    assertThat(election.currentLeader()).contains(node2);
  }

  @Test
  @DisplayName("No leader when all nodes unhealthy")
  void noLeaderWhenAllUnhealthy() {
    provider.addHealthyNode(node1);
    detector.recordHeartbeat(node1, true);
    election.elect();
    assertThat(election.currentLeader()).isPresent();

    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    detector.recordHeartbeat(node1, false);
    election.elect();
    assertThat(election.currentLeader()).isEmpty();
  }

  @Test
  @DisplayName("Single node becomes leader")
  void singleNodeBecomesLeader() {
    provider.addHealthyNode(node1);
    detector.recordHeartbeat(node1, true);
    election.elect();
    assertThat(election.currentLeader()).contains(node1);
  }

  @Test
  @DisplayName("Empty cluster has no leader")
  void emptyClusterHasNoLeader() {
    election.elect();
    assertThat(election.currentLeader()).isEmpty();
  }
}
