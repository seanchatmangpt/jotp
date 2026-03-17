package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for global process registry with distributed discovery.
 */
class GlobalProcRegistryTest {

  private GlobalProcRegistry globalRegistry;
  private ProcessServiceDiscoveryProvider discovery;
  private NodeId currentNode;

  @BeforeEach
  void setup() {
    ApplicationController.reset();
    ProcRegistry.reset();
    discovery = new InMemoryProcessDiscovery();
    currentNode = new NodeId("local-node", "localhost", 8080);
    globalRegistry =
        new DefaultGlobalProcRegistry(
            discovery, currentNode, Duration.ofSeconds(30), Duration.ofMillis(100));
  }

  @Test
  void testRegisterLocalProcess() {
    // Arrange
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);

    // Act
    globalRegistry.register("test-process", procRef, currentNode);

    // Assert
    assertThat(ProcRegistry.whereis("test-process")).isPresent();
    assertThat(discovery.lookup("test-process")).contains(currentNode);
  }

  @Test
  void testLookupLocalProcess() {
    // Arrange
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);
    globalRegistry.register("test-process", procRef, currentNode);

    // Act
    Optional<ProcRef<?, ?>> found = globalRegistry.lookupLocal("test-process");

    // Assert
    assertThat(found).isPresent();
  }

  @Test
  void testLookupNodeIdLocal() {
    // Arrange
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);
    globalRegistry.register("test-process", procRef, currentNode);

    // Act
    Optional<NodeId> nodeId = globalRegistry.lookupNodeId("test-process");

    // Assert
    assertThat(nodeId).contains(currentNode);
  }

  @Test
  void testLookupNodeIdRemote() {
    // Arrange
    NodeId remoteNode = new NodeId("remote-node", "example.com", 9000);
    discovery.register("remote-process", remoteNode);

    // Act
    Optional<NodeId> nodeId = globalRegistry.lookupNodeId("remote-process");

    // Assert
    assertThat(nodeId).contains(remoteNode);
  }

  @Test
  void testLookupNotFound() {
    // Act
    Optional<NodeId> nodeId = globalRegistry.lookupNodeId("unknown-process");

    // Assert
    assertThat(nodeId).isEmpty();
  }

  @Test
  void testDeregister() {
    // Arrange
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);
    globalRegistry.register("test-process", procRef, currentNode);

    // Act
    globalRegistry.deregister("test-process");

    // Assert
    assertThat(ProcRegistry.whereis("test-process")).isEmpty();
    assertThat(discovery.lookup("test-process")).isEmpty();
  }

  @Test
  void testListProcesses() {
    // Arrange
    var handler = (state, msg) -> state;
    var proc1 = new Proc<>(1, handler);
    var procRef1 = new ProcRef<>(proc1);
    var proc2 = new Proc<>(2, handler);
    var procRef2 = new ProcRef<>(proc2);

    globalRegistry.register("local-1", procRef1, currentNode);
    globalRegistry.register("local-2", procRef2, currentNode);

    // Also register a remote process
    NodeId remoteNode = new NodeId("remote", "example.com", 9000);
    discovery.register("remote-1", remoteNode);

    // Act
    Set<String> processes = globalRegistry.listProcesses();

    // Assert
    assertThat(processes).contains("local-1", "local-2", "remote-1");
  }

  @Test
  void testWatchGlobalChanges() throws InterruptedException {
    // Arrange
    CountDownLatch latch = new CountDownLatch(2);
    Set<Set<String>> capturedSets = new HashSet<>();

    globalRegistry.watch(processes -> {
      capturedSets.add(new HashSet<>(processes));
      latch.countDown();
    });

    // Act: register locally and then remotely
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);
    globalRegistry.register("local-process", procRef, currentNode);

    NodeId remoteNode = new NodeId("remote", "example.com", 9000);
    discovery.register("remote-process", remoteNode);

    // Assert
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(capturedSets)
        .anySatisfy(set -> assertThat(set).contains("local-process"))
        .anySatisfy(set -> assertThat(set).contains("local-process", "remote-process"));
  }

  @Test
  void testCacheTTL() throws InterruptedException {
    // Arrange: create a registry with short TTL
    ProcessServiceDiscoveryProvider discovery2 = new InMemoryProcessDiscovery();
    GlobalProcRegistry registry =
        new DefaultGlobalProcRegistry(
            discovery2, currentNode, Duration.ofMillis(100), Duration.ofMillis(50));

    NodeId remoteNode = new NodeId("remote", "example.com", 9000);
    discovery2.register("test-process", remoteNode);

    // Act: first lookup (hits provider, cached)
    Optional<NodeId> first = registry.lookupNodeId("test-process");

    // Immediately verify it's cached
    discovery2.deregister("test-process");
    Optional<NodeId> second = registry.lookupNodeId("test-process"); // Should be cached

    // Wait for cache to expire
    Thread.sleep(150);
    Optional<NodeId> third = registry.lookupNodeId("test-process"); // Should be expired

    // Assert
    assertThat(first).contains(remoteNode);
    assertThat(second).contains(remoteNode); // Still cached
    assertThat(third).isEmpty(); // Cache expired, so not found
  }

  @Test
  void testDegradedModeOnDiscoveryFailure() {
    // Arrange: a mock discovery provider that always fails
    ProcessServiceDiscoveryProvider failingDiscovery = new FailingDiscoveryProvider();
    GlobalProcRegistry registry =
        new DefaultGlobalProcRegistry(failingDiscovery, currentNode);

    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);

    // Act: register locally (discovery provider fails, but local registration succeeds)
    globalRegistry.register("local-process", procRef, currentNode);

    // Assert: process is still found locally despite discovery failure
    assertThat(globalRegistry.lookupLocal("local-process")).isPresent();
  }

  @Test
  void testReset() {
    // Arrange
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var procRef = new ProcRef<>(proc);
    globalRegistry.register("test-process", procRef, currentNode);

    // Act
    globalRegistry.reset();

    // Assert
    assertThat(ProcRegistry.whereis("test-process")).isEmpty();
    assertThat(discovery.lookup("test-process")).isEmpty();
  }

  // ── Test utilities ──

  /** A discovery provider that always fails (for testing degraded mode). */
  private static class FailingDiscoveryProvider implements ProcessServiceDiscoveryProvider {
    @Override
    public void register(String processName, NodeId nodeId) {
      throw new RuntimeException("Discovery provider is unavailable");
    }

    @Override
    public void deregister(String processName) {
      throw new RuntimeException("Discovery provider is unavailable");
    }

    @Override
    public Optional<NodeId> lookup(String processName) {
      throw new RuntimeException("Discovery provider is unavailable");
    }

    @Override
    public Set<String> listProcesses() {
      return Set.of();
    }

    @Override
    public void watch(java.util.function.Consumer<Set<String>> onProcessesChanged) {
      // Do nothing
    }

    @Override
    public boolean isHealthy() {
      return false;
    }

    @Override
    public void shutdown() {
      // Do nothing
    }
  }
}
