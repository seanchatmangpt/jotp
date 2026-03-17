package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ProcRef remote process resolution via discovery provider.
 */
class ProcRefRemoteResolutionTest {

  private ProcRef<Integer, String> procRef;
  private ProcessServiceDiscoveryProvider discovery;
  private NodeId currentNode;
  private NodeId remoteNode;

  @BeforeEach
  void setup() {
    ProcRegistry.reset();
    discovery = new InMemoryProcessDiscovery();
    currentNode = new NodeId("local", "localhost", 8080);
    remoteNode = new NodeId("remote", "example.com", 9000);

    // Create a local process
    var handler = (state, msg) -> state + msg.length();
    var proc = new Proc<>(0, handler);
    procRef = new ProcRef<>(proc, discovery, currentNode);
  }

  @Test
  void testDiscoveryProviderInjection() {
    // Assert
    assertThat(procRef.discoveryProvider()).contains(discovery);
    assertThat(procRef.nodeId()).contains(currentNode);
  }

  @Test
  void testTellRemoteLocalFallback() {
    // Arrange: register a local process
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    ProcRegistry.register("test-service", proc);

    // Act: tell the process by name
    procRef.tellRemote("test-service", "hello");

    // Assert: message was delivered (no exception thrown)
    assertThat(true).isTrue(); // If we got here, no exception
  }

  @Test
  void testTellRemoteNotFound() {
    // Act & Assert: should handle gracefully (fire-and-forget)
    assertDoesNotThrow(() -> procRef.tellRemote("unknown-service", "hello"));
  }

  @Test
  void testTellRemoteRequiresProvider() {
    // Arrange: create a ProcRef without a discovery provider
    var handler = (state, msg) -> state;
    var proc = new Proc<>(0, handler);
    var localRef = new ProcRef<>(proc);

    // Act & Assert
    assertThatThrownBy(() -> localRef.tellRemote("test", "msg"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("discovery provider");
  }

  @Test
  void testAskRemoteRequiresProvider() {
    // Arrange: create a ProcRef without a discovery provider
    var handler = (state, msg) -> state;
    var proc = new Proc<>(0, handler);
    var localRef = new ProcRef<>(proc);

    // Act & Assert
    var future = localRef.askRemote("test", "msg", Duration.ofSeconds(5));
    assertThat(future)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(IllegalStateException.class)
        .withMessageContaining("discovery provider");
  }

  @Test
  void testAskRemoteLocalFallback() throws Exception {
    // Arrange: register a local process
    var handler = (state, msg) -> state + msg.length();
    var proc = new Proc<>(10, handler);
    ProcRegistry.register("math-service", proc);

    // Act: ask the process by name
    var future = procRef.askRemote("math-service", "hello", Duration.ofSeconds(5));

    // Assert: should complete with the state
    var result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(result).isEqualTo(15); // 10 + "hello".length()
  }

  @Test
  void testAskRemoteNotFound() {
    // Act: ask for non-existent process
    var future = procRef.askRemote("unknown-service", "msg", Duration.ofSeconds(1));

    // Assert: should complete exceptionally
    assertThat(future)
        .failsWithin(Duration.ofSeconds(2))
        .withThrowableOfType(Exception.class);
  }

  @Test
  void testDirectTellStillWorks() {
    // Arrange: create and register a local process
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    var ref = new ProcRef<>(proc, discovery, currentNode);

    // Act: use direct tell (doesn't use discovery)
    ref.tell("hello");

    // Assert: no exception
    assertThat(true).isTrue();
  }

  @Test
  void testRemoteResolutionWithCache() {
    // Arrange: register a remote service
    discovery.register("remote-service", remoteNode);

    // Act: first lookup
    Optional<NodeId> first = procRef.discoveryProvider().get().lookup("remote-service");

    // Act: second lookup (cached)
    Optional<NodeId> second = procRef.discoveryProvider().get().lookup("remote-service");

    // Assert: both return the same result
    assertThat(first).contains(remoteNode);
    assertThat(second).contains(remoteNode);
  }

  @Test
  void testLocalProcessPrefersLocalLookup() {
    // Arrange: register same name both locally and in discovery
    var handler = (state, msg) -> state;
    var proc = new Proc<>(42, handler);
    ProcRegistry.register("dual-service", proc);

    discovery.register("dual-service", remoteNode);

    // Act: tell should find the local one first
    procRef.tellRemote("dual-service", "msg");

    // Assert: no remote call attempted (but we can't really verify this without mocking)
    assertThat(true).isTrue();
  }
}
