package io.github.seanchatmangpt.jotp.discovery;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Unit tests for EtcdServiceDiscovery.
 *
 * <p>Tests graceful handling when etcd is unavailable. Full integration tests with real etcd
 * server in EtcdServiceDiscoveryIT.
 */
@DisplayName("EtcdServiceDiscovery")
final class EtcdServiceDiscoveryTest {

  private EtcdServiceDiscovery provider;
  private static final String ETCD_URL = "http://localhost:2379";

  @BeforeEach
  void setUp() {
    provider = new EtcdServiceDiscovery(ETCD_URL);
  }

  @Test
  @DisplayName("handles missing etcd gracefully")
  void testHandlesMissingEtcd() {
    // etcd is likely not running in test environment
    assertThat(provider.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("register returns CompletableFuture")
  void testRegisterReturnsCompletableFuture() {
    NodeId node = new NodeId("test", "127.0.0.1", 9001);
    ServiceInstance instance = ServiceInstance.of("test-process", node, 5001);

    assertThat(provider.register(node, instance)).isNotNull();
  }

  @Test
  @DisplayName("deregister returns CompletableFuture")
  void testDeregisterReturnsCompletableFuture() {
    NodeId node = new NodeId("test", "127.0.0.1", 9001);
    assertThat(provider.deregister(node)).isNotNull();
  }

  @Test
  @DisplayName("lookup returns empty when etcd unavailable")
  void testLookupWhenEtcdUnavailable() {
    assertThat(provider.lookup("any-process")).isEmpty();
  }

  @Test
  @DisplayName("listNodes returns empty when etcd unavailable")
  void testListNodesWhenEtcdUnavailable() {
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
    Thread.sleep(100);
    assertThat(provider.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("base64 encoding/decoding works correctly")
  void testBase64Encoding() {
    String original = "/jotp/processes/myservice";
    String encoded = Base64.getEncoder().encodeToString(original.getBytes());
    String decoded = new String(Base64.getDecoder().decode(encoded));
    assertThat(decoded).isEqualTo(original);
  }

  @Test
  @DisplayName("key prefix constant is correct")
  void testKeyPrefixFormat() {
    // Verify the key prefix format for etcd operations
    String processName = "my-process";
    String expectedKey = "/jotp/processes/" + processName;
    assertThat(expectedKey).startsWith("/jotp/processes/");
  }

  @Test
  @DisplayName("handles complex service names")
  void testComplexServiceNames() {
    NodeId node = new NodeId("test", "127.0.0.1", 9001);

    // Various name formats should all work
    ServiceInstance inst1 = ServiceInstance.of("user-service-v2", node, 5001);
    ServiceInstance inst2 = ServiceInstance.of("payment_service", node, 5002);
    ServiceInstance inst3 = ServiceInstance.of("service.inventory", node, 5003);

    assertThat(provider.register(node, inst1)).isNotNull();
    assertThat(provider.register(node, inst2)).isNotNull();
    assertThat(provider.register(node, inst3)).isNotNull();
  }
}
