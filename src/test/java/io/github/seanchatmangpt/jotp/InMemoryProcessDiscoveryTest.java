package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for in-memory process discovery provider.
 */
class InMemoryProcessDiscoveryTest {

  private InMemoryProcessDiscovery discovery;
  private NodeId node1;
  private NodeId node2;

  @BeforeEach
  void setup() {
    ApplicationController.reset();
    discovery = new InMemoryProcessDiscovery();
    node1 = new NodeId("node1", "localhost", 9001);
    node2 = new NodeId("node2", "localhost", 9002);
  }

  @Test
  void testRegisterAndLookup() {
    // Arrange
    String processName = "payment-service";

    // Act
    discovery.register(processName, node1);

    // Assert
    Optional<NodeId> result = discovery.lookup(processName);
    assertThat(result).contains(node1);
  }

  @Test
  void testLookupNotFound() {
    // Arrange
    String processName = "unknown-service";

    // Act & Assert
    Optional<NodeId> result = discovery.lookup(processName);
    assertThat(result).isEmpty();
  }

  @Test
  void testDeregister() {
    // Arrange
    String processName = "payment-service";
    discovery.register(processName, node1);

    // Act
    discovery.deregister(processName);

    // Assert
    Optional<NodeId> result = discovery.lookup(processName);
    assertThat(result).isEmpty();
  }

  @Test
  void testListProcesses() {
    // Arrange
    discovery.register("service-1", node1);
    discovery.register("service-2", node2);
    discovery.register("service-3", node1);

    // Act
    Set<String> processes = discovery.listProcesses();

    // Assert
    assertThat(processes)
        .containsExactlyInAnyOrder("service-1", "service-2", "service-3");
  }

  @Test
  void testWatch() throws InterruptedException {
    // Arrange
    CountDownLatch latch = new CountDownLatch(2);
    Set<Set<String>> capturedSets = new HashSet<>();

    discovery.watch(processes -> {
      capturedSets.add(new HashSet<>(processes));
      latch.countDown();
    });

    // Act
    discovery.register("service-1", node1);
    discovery.register("service-2", node2);

    // Assert
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedSets)
        .anySatisfy(set -> assertThat(set).contains("service-1"))
        .anySatisfy(set -> assertThat(set).contains("service-1", "service-2"));
  }

  @Test
  void testWatchNotificationOnDeregister() throws InterruptedException {
    // Arrange
    discovery.register("service-1", node1);
    CountDownLatch latch = new CountDownLatch(1);

    discovery.watch(processes -> {
      if (!processes.contains("service-1")) {
        latch.countDown();
      }
    });

    // Act
    discovery.deregister("service-1");

    // Assert
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void testIsHealthy() {
    // Assert
    assertThat(discovery.isHealthy()).isTrue();
  }

  @Test
  void testShutdown() {
    // Arrange
    discovery.register("service-1", node1);

    // Act
    discovery.shutdown();

    // Assert
    assertThat(discovery.listProcesses()).isEmpty();
  }

  @Test
  void testReset() {
    // Arrange
    discovery.register("service-1", node1);
    discovery.register("service-2", node2);

    // Act
    discovery.reset();

    // Assert
    assertThat(discovery.listProcesses()).isEmpty();
    assertThat(discovery.lookup("service-1")).isEmpty();
  }

  @Test
  void testWatcherErrorHandling() throws InterruptedException {
    // Arrange: set up a watcher that throws an exception
    CountDownLatch latch = new CountDownLatch(1);
    discovery.watch(processes -> {
      throw new RuntimeException("Watcher failed");
    });

    // Also add a good watcher to verify it still gets called
    discovery.watch(processes -> {
      if (processes.contains("service-1")) {
        latch.countDown();
      }
    });

    // Act: register a service (first watcher throws, but second should still be called)
    discovery.register("service-1", node1);

    // Assert
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
