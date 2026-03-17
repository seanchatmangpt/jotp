package io.github.seanchatmangpt.jotp.discovery;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

@DisplayName("StaticNodeProvider")
final class StaticNodeProviderTest extends ServiceDiscoveryProviderTest {

  private StaticNodeProvider staticProvider;

  @Override
  @BeforeEach
  void setUp() {
    super.setUp();
    staticProvider = (StaticNodeProvider) provider;
  }

  @Override
  protected ServiceDiscoveryProvider createProvider() {
    return new StaticNodeProvider(List.of(
        new NodeId("node1", "127.0.0.1", 9001),
        new NodeId("node2", "127.0.0.2", 9002)
    ));
  }

  @Test
  @DisplayName("initializes with static node list")
  void testInitialNodeList() {
    Set<NodeId> nodes = provider.listNodes();
    assertThat(nodes).hasSize(2);
    assertThat(nodes).contains(
        new NodeId("node1", "127.0.0.1", 9001),
        new NodeId("node2", "127.0.0.2", 9002)
    );
  }

  @Test
  @DisplayName("addNode at runtime")
  void testAddNodeRuntime() {
    NodeId newNode = new NodeId("node3", "127.0.0.3", 9003);
    staticProvider.addNode(newNode);

    Set<NodeId> nodes = provider.listNodes();
    assertThat(nodes).hasSize(3);
    assertThat(nodes).contains(newNode);
  }

  @Test
  @DisplayName("removeNode at runtime")
  void testRemoveNodeRuntime() {
    NodeId node = new NodeId("node1", "127.0.0.1", 9001);
    staticProvider.removeNode(node);

    Set<NodeId> nodes = provider.listNodes();
    assertThat(nodes).hasSize(1);
    assertThat(nodes).doesNotContain(node);
  }

  @Test
  @DisplayName("ignores duplicate adds")
  void testDuplicateAdd() {
    NodeId node = new NodeId("node1", "127.0.0.1", 9001);
    staticProvider.addNode(node);

    Set<NodeId> nodes = provider.listNodes();
    assertThat(nodes).hasSize(2); // Still 2, not 3
  }

  @Test
  @DisplayName("isHealthy always returns true")
  void testIsHealthyAlwaysTrue() {
    assertThat(provider.isHealthy()).isTrue();
    provider.shutdown();
    assertThat(provider.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("in-memory registry persists across calls")
  void testInMemoryPersistence() throws Exception {
    NodeId testNode = new NodeId("test", "127.0.0.1", 9001);
    ServiceInstance instance = ServiceInstance.of("my-service", testNode, 5001);

    provider.register(testNode, instance).get();
    provider.register(testNode, instance).get(); // Register again

    assertThat(provider.lookup("my-service")).contains(testNode);
  }
}
