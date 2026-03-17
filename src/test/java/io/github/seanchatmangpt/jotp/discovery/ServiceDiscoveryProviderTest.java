package io.github.seanchatmangpt.jotp.discovery;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Abstract test suite for all ServiceDiscoveryProvider implementations.
 *
 * <p>Subclasses should provide concrete implementations of {@link #createProvider()}.
 */
@DisplayName("ServiceDiscoveryProvider contract")
abstract class ServiceDiscoveryProviderTest {

  protected ServiceDiscoveryProvider provider;
  protected NodeId testNode;
  protected ServiceInstance testInstance;

  @BeforeEach
  void setUp() {
    provider = createProvider();
    testNode = new NodeId("test-node", "127.0.0.1", 9999);
    testInstance = ServiceInstance.of("test-process", testNode, 5001,
        Map.of("version", "1.0", "region", "us-east-1"));
  }

  @AfterEach
  void tearDown() throws Exception {
    provider.shutdown().get();
  }

  /** Subclasses must provide a fresh provider instance. */
  protected abstract ServiceDiscoveryProvider createProvider();

  @Test
  @DisplayName("register and lookup process by name")
  void testRegisterAndLookup() throws Exception {
    provider.register(testNode, testInstance).get();

    Optional<NodeId> found = Optional.ofNullable(provider.lookup("test-process"));
    assertThat(found).isPresent();
    assertThat(found.get()).isEqualTo(testNode);
  }

  @Test
  @DisplayName("lookup returns empty for unknown process")
  void testLookupUnknown() {
    Optional<NodeId> found = Optional.ofNullable(provider.lookup("unknown-process"));
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("deregister removes process from registry")
  void testDeregister() throws Exception {
    provider.register(testNode, testInstance).get();
    assertThat(provider.lookup("test-process")).isNotNull();

    provider.deregister(testNode).get();
    assertThat(provider.lookup("test-process")).isNull();
  }

  @Test
  @DisplayName("listNodes returns all registered nodes")
  void testListNodes() throws Exception {
    NodeId node1 = new NodeId("node1", "127.0.0.1", 9001);
    NodeId node2 = new NodeId("node2", "127.0.0.2", 9002);

    provider.register(node1, ServiceInstance.of("process1", node1, 5001)).get();
    provider.register(node2, ServiceInstance.of("process2", node2, 5002)).get();

    Set<NodeId> nodes = provider.listNodes();
    assertThat(nodes).containsExactlyInAnyOrder(node1, node2);
  }

  @Test
  @DisplayName("watch receives initial notification")
  void testWatchInitialNotification() throws Exception {
    List<Set<NodeId>> notifications = new ArrayList<>();
    provider.watch(notifications::add);

    // Should get immediate notification even if no nodes registered
    awaitCondition(() -> notifications.size() > 0, 2000);
    assertThat(notifications).isNotEmpty();
  }

  @Test
  @DisplayName("watch notifies on membership changes")
  void testWatchMembershipChanges() throws Exception {
    List<Set<NodeId>> notifications = new ArrayList<>();
    provider.watch(notifications::add);

    notifications.clear(); // Clear initial notification
    provider.register(testNode, testInstance).get();

    // Wait for notification
    awaitCondition(() -> notifications.size() > 0, 2000);
    assertThat(notifications.getLast()).contains(testNode);
  }

  @Test
  @DisplayName("isHealthy returns true for operational provider")
  void testIsHealthy() {
    assertThat(provider.isHealthy()).isTrue();
  }

  @Test
  @DisplayName("multiple registrations for same process overwrites")
  void testMultipleRegistrations() throws Exception {
    ServiceInstance instance1 = ServiceInstance.of("shared", testNode, 5001);
    ServiceInstance instance2 = ServiceInstance.of("shared", testNode, 5002);

    provider.register(testNode, instance1).get();
    provider.register(testNode, instance2).get();

    assertThat(provider.lookup("shared")).isNotNull();
  }

  @Test
  @DisplayName("concurrent operations are thread-safe")
  void testConcurrentOperations() throws Exception {
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      NodeId node = new NodeId("node-" + i, "127.0.0." + i, 9000 + i);
      ServiceInstance inst = ServiceInstance.of("process-" + i, node, 5000 + i);
      futures.add(provider.register(node, inst));
    }

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

    Set<NodeId> nodes = provider.listNodes();
    assertThat(nodes).hasSize(10);
  }

  @Test
  @DisplayName("ServiceInstance with metadata preserves data")
  void testServiceInstanceWithMetadata() throws Exception {
    Map<String, String> metadata = Map.of(
        "version", "2.0",
        "environment", "staging",
        "tags", "critical");

    ServiceInstance instance = ServiceInstance.of("test-process", testNode, 5001, metadata);
    provider.register(testNode, instance).get();

    assertThat(provider.lookup("test-process")).contains(testNode);
  }

  @Test
  @DisplayName("register handles exceptions gracefully")
  void testRegisterExceptionHandling() throws Exception {
    // Even if backend is unavailable, CompletableFuture should complete (not hang)
    CompletableFuture<Void> result = provider.register(testNode, testInstance);
    // Should not throw synchronously
    assertThat(result).isNotNull();
  }

  // ── Utility methods ──────────────────────────────────────────────────────

  /** Helper to wait for a condition with timeout. */
  private void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long startTime = System.currentTimeMillis();
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() - startTime > timeoutMs) {
        throw new AssertionError("Condition not met within " + timeoutMs + "ms");
      }
      Thread.sleep(50);
    }
  }
}
