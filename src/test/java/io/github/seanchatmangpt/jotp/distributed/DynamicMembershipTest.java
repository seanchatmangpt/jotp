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
@DisplayName("DynamicMembership — ServiceDiscoveryProvider integration")
class DynamicMembershipTest {

  private InMemoryDiscoveryProvider provider;
  private NodeId node1, node2;

  @BeforeEach
  void setUp() {
    provider = new InMemoryDiscoveryProvider();
    node1 = new NodeId("cp1", "localhost", 5001);
    node2 = new NodeId("cp2", "localhost", 5002);
  }

  @Test
  @DisplayName("ListNodes reflects current membership")
  void listNodesReflectsCurrentMembership() {
    provider.addHealthyNode(node1);
    provider.addHealthyNode(node2);
    assertThat(provider.listNodes()).containsExactlyInAnyOrder(node1, node2);
    provider.removeNode(node1);
    assertThat(provider.listNodes()).containsExactly(node2);
  }

  @Test
  @DisplayName("Watch receives events on membership change")
  void watchReceivesEvents() {
    AtomicReference<java.util.Set<NodeId>> lastNodes = new AtomicReference<>();
    provider.watch(lastNodes::set);
    provider.addHealthyNode(node1);
    await().atMost(2, SECONDS).until(() -> lastNodes.get() != null);
    assertThat(lastNodes.get()).contains(node1);
  }

  @Test
  @DisplayName("Multiple watches all receive events")
  void multipleWatchesReceiveEvents() {
    AtomicReference<java.util.Set<NodeId>> nodes1 = new AtomicReference<>();
    AtomicReference<java.util.Set<NodeId>> nodes2 = new AtomicReference<>();
    provider.watch(nodes1::set);
    provider.watch(nodes2::set);
    provider.addHealthyNode(node1);
    await().atMost(2, SECONDS).until(() -> nodes1.get() != null && nodes2.get() != null);
    assertThat(nodes1.get()).contains(node1);
    assertThat(nodes2.get()).contains(node1);
  }
}
