package io.github.seanchatmangpt.jotp.cluster;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaderElectionTest {

  private InMemoryLeaderElection election;

  @BeforeEach
  void setUp() {
    election = new InMemoryLeaderElection();
  }

  @Test
  void testBullyAlgorithmSingleNode() {
    var result = election.electLeader("node1", Set.of("node1"), 5000);
    assertThat(result).contains("node1");
  }

  @Test
  void testBullyAlgorithmMultipleNodes() {
    var candidates = Set.of("node1", "node2", "node3");
    var result = election.electLeader("node1", candidates, 5000);

    assertThat(result).contains("node3"); // Highest ID becomes leader
  }

  @Test
  void testAcquireLeaderLease() {
    var acquired = election.acquireLeaderLease("node1", 10000, 5000);
    assertThat(acquired).isTrue();
    assertThat(election.isLeader("node1")).isTrue();
  }

  @Test
  void testLeaseExclusion() {
    election.acquireLeaderLease("node1", 10000, 5000);

    // node2 should not be able to acquire while node1 holds it
    var acquired = election.acquireLeaderLease("node2", 10000, 100);
    assertThat(acquired).isFalse();
  }

  @Test
  void testReleaseLeaderLease() {
    election.acquireLeaderLease("node1", 10000, 5000);
    assertThat(election.isLeader("node1")).isTrue();

    election.releaseLeaderLease("node1");
    assertThat(election.isLeader("node1")).isFalse();
  }

  @Test
  void testLeaseExpiration() {
    election.acquireLeaderLease("node1", 200, 5000); // 200ms lease
    assertThat(election.isLeader("node1")).isTrue();

    // Wait for expiration
    await()
        .atMost(500, MILLISECONDS)
        .until(() -> !election.isLeader("node1"));
  }

  @Test
  void testRenewLeaderLease() {
    election.acquireLeaderLease("node1", 100, 5000);
    var renewed = election.renewLeaderLease("node1", 10000);
    assertThat(renewed).isTrue();
    assertThat(election.isLeader("node1")).isTrue();
  }

  @Test
  void testRenewNonLeaderLease() {
    election.acquireLeaderLease("node1", 10000, 5000);
    var renewed = election.renewLeaderLease("node2", 10000);
    assertThat(renewed).isFalse();
  }

  @Test
  void testGetCurrentLeader() {
    election.acquireLeaderLease("node1", 10000, 5000);
    assertThat(election.getCurrentLeader()).contains("node1");
  }

  @Test
  void testNoCurrentLeader() {
    assertThat(election.getCurrentLeader()).isEmpty();
  }

  @Test
  void testElectionInProgress() {
    assertThat(election.isElectionInProgress()).isFalse();

    var thread = new Thread(() -> {
      election.electLeader("node1", Set.of("node1", "node2"), 5000);
    });

    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Election should complete quickly
    assertThat(election.isElectionInProgress()).isFalse();
  }

  @Test
  void testMultipleLeaseHolders() {
    election.acquireLeaderLease("node1", 10000, 5000);
    var acquired = election.acquireLeaderLease("node2", 10000, 100);

    assertThat(election.isLeader("node1")).isTrue();
    assertThat(acquired).isFalse(); // node2 should not acquire
  }

  @Test
  void testLeaseAcquisitionTimeout() {
    election.acquireLeaderLease("node1", 10000, 5000);

    var acquired = election.acquireLeaderLease("node2", 10000, 100);
    assertThat(acquired).isFalse();
  }

  @Test
  void testLeaderHandoff() {
    election.acquireLeaderLease("node1", 10000, 5000);
    assertThat(election.getCurrentLeader()).contains("node1");

    // Graceful handoff
    election.releaseLeaderLease("node1");
    assertThat(election.getCurrentLeader()).isEmpty();

    // node2 can now acquire
    var acquired = election.acquireLeaderLease("node2", 10000, 5000);
    assertThat(acquired).isTrue();
    assertThat(election.getCurrentLeader()).contains("node2");
  }

  @Test
  void testElectionWithEmptyCandidates() {
    var result = election.electLeader("node1", Set.of(), 5000);
    assertThat(result).isEmpty();
  }

  @Test
  void testCloseable() {
    election.acquireLeaderLease("node1", 10000, 5000);
    election.close();

    assertThat(election.getCurrentLeader()).isEmpty();
    assertThat(election.isLeader("node1")).isFalse();
  }

  @Test
  void testConcurrentLeaseAcquisition() {
    var acquired = new CopyOnWriteArraySet<String>();

    var threads =
        new Thread[] {
          new Thread(
              () -> {
                if (election.acquireLeaderLease("node1", 10000, 1000)) {
                  acquired.add("node1");
                }
              }),
          new Thread(
              () -> {
                if (election.acquireLeaderLease("node2", 10000, 1000)) {
                  acquired.add("node2");
                }
              }),
          new Thread(
              () -> {
                if (election.acquireLeaderLease("node3", 10000, 1000)) {
                  acquired.add("node3");
                }
              })
        };

    for (var t : threads) {
      t.start();
    }

    for (var t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Only one should acquire the lease
    assertThat(acquired).hasSize(1);
  }
}
