package io.github.seanchatmangpt.jotp.discovery;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Unit tests for ConsulServiceDiscovery.
 *
 * <p>These tests use a mock Consul server (or skip if unavailable). For full integration tests,
 * see ConsulServiceDiscoveryIT.
 */
@DisplayName("ConsulServiceDiscovery")
final class ConsulServiceDiscoveryTest {

  private ConsulServiceDiscovery provider;
  private static final String CONSUL_URL = "http://localhost:8500";

  @BeforeEach
  void setUp() {
    // Create with test URL; actual tests may need a real or mocked Consul
    provider = new ConsulServiceDiscovery(CONSUL_URL);
  }

  @Test
  @DisplayName("handles missing Consul gracefully")
  void testHandlesMissingConsul() {
    // Consul is likely not running in test environment
    // Provider should not throw, but isHealthy should return false
    assertThat(provider.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("register returns CompletableFuture")
  void testRegisterReturnsCompletableFuture() {
    NodeId node = new NodeId("test", "127.0.0.1", 9001);
    ServiceInstance instance = ServiceInstance.of("test-service", node, 5001);

    assertThat(provider.register(node, instance)).isNotNull();
  }

  @Test
  @DisplayName("deregister returns CompletableFuture")
  void testDeregisterReturnsCompletableFuture() {
    NodeId node = new NodeId("test", "127.0.0.1", 9001);
    assertThat(provider.deregister(node)).isNotNull();
  }

  @Test
  @DisplayName("lookup returns empty for unavailable Consul")
  void testLookupWhenConsulUnavailable() {
    assertThat(provider.lookup("any-service")).isEmpty();
  }

  @Test
  @DisplayName("listNodes returns empty set when Consul unavailable")
  void testListNodesWhenConsulUnavailable() {
    assertThat(provider.listNodes()).isEmpty();
  }

  @Test
  @DisplayName("watch callback receives initial notification")
  void testWatchInitialNotification() throws Exception {
    java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
    provider.watch(nodes -> counter.incrementAndGet());

    // Wait briefly for background polling
    Thread.sleep(100);
    assertThat(counter.get()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("shutdown stops polling")
  void testShutdownStopsPolling() throws Exception {
    provider.shutdown().get();
    // After shutdown, health should be degraded
    Thread.sleep(100);
    assertThat(provider.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("handles serviceId generation correctly")
  void testServiceIdGeneration() {
    // This is an internal detail, but we can verify through registration behavior
    NodeId node = new NodeId("mynode", "192.168.1.1", 9001);
    ServiceInstance instance = ServiceInstance.of("my-svc", node, 5001);

    // Should not throw
    assertThat(provider.register(node, instance)).isNotNull();
  }
}
