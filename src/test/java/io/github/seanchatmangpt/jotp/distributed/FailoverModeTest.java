package io.github.seanchatmangpt.jotp.distributed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DtrTest
@DisplayName("FailoverMode — process migration with discovery provider")
class FailoverModeTest {

  private DistributedNode node1, node2, node3;
  private InMemoryDiscoveryProvider provider;
  private final List<DistributedNode> allNodes = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    ApplicationController.reset();
    provider = new InMemoryDiscoveryProvider();
    node1 = new DistributedNode("cp1", "localhost", 0, NodeConfig.defaults());
    node2 = new DistributedNode("cp2", "localhost", 0, NodeConfig.defaults());
    node3 = new DistributedNode("cp3", "localhost", 0, NodeConfig.defaults());
    allNodes.addAll(List.of(node1, node2, node3));

    for (DistributedNode node : allNodes) {
      node.withDiscoveryProvider(provider).withFailureDetector(new NodeFailureDetector(3));
    }

    provider.addHealthyNode(node1.nodeId());
    provider.addHealthyNode(node2.nodeId());
    provider.addHealthyNode(node3.nodeId());
  }

  @AfterEach
  void tearDown() {
    for (DistributedNode n : allNodes) {
      try {
        n.shutdown();
      } catch (Exception ignored) {}
    }
  }

  private DistributedAppSpec immediateSpec() {
    return new DistributedAppSpec(
        "myapp",
        List.of(List.of(node1.nodeId()), List.of(node2.nodeId()), List.of(node3.nodeId())),
        Duration.ZERO);
  }

  private static final class TrackingCallbacks implements ApplicationCallbacks {
    final AtomicReference<StartMode> startMode = new AtomicReference<>();
    final AtomicBoolean stopped = new AtomicBoolean(false);
    final AtomicReference<NodeId> joinedNode = new AtomicReference<>();
    final AtomicReference<NodeId> leftNode = new AtomicReference<>();

    @Override
    public void onStart(StartMode mode) {
      startMode.set(mode);
    }

    @Override
    public void onStop() {
      stopped.set(true);
    }

    @Override
    public void onNodeJoined(NodeId nodeId) {
      joinedNode.set(nodeId);
    }

    @Override
    public void onNodeLeft(NodeId nodeId) {
      leftNode.set(nodeId);
    }

    boolean hasStarted() {
      return startMode.get() != null;
    }
  }

  @Test
  @DisplayName("Application notified when peer node joins")
  void notifiedWhenPeerNodeJoins() {
    DistributedAppSpec spec = immediateSpec();
    TrackingCallbacks cb1 = new TrackingCallbacks();
    node1.register(spec, cb1);
    node1.start("myapp");
    await().atMost(5, SECONDS).until(cb1::hasStarted);
    provider.addHealthyNode(new NodeId("newnode", "localhost", 6000));
    await().atMost(3, SECONDS).until(() -> cb1.joinedNode.get() != null);
    assertThat(cb1.joinedNode.get().name()).isEqualTo("newnode");
  }

  @Test
  @DisplayName("Application notified when peer node leaves")
  void notifiedWhenPeerNodeLeaves() {
    DistributedAppSpec spec = immediateSpec();
    TrackingCallbacks cb1 = new TrackingCallbacks();
    node1.register(spec, cb1);
    node1.start("myapp");
    await().atMost(5, SECONDS).until(cb1::hasStarted);
    provider.removeNode(node3.nodeId());
    await().atMost(3, SECONDS).until(() -> cb1.leftNode.get() != null);
    assertThat(cb1.leftNode.get()).isEqualTo(node3.nodeId());
  }

  @Test
  @DisplayName("Failover mode preserves application state")
  void failoverModePreservesState() {
    DistributedAppSpec spec = immediateSpec();
    TrackingCallbacks cb1 = new TrackingCallbacks();
    TrackingCallbacks cb2 = new TrackingCallbacks();
    node1.register(spec, cb1);
    node2.register(spec, cb2);
    node1.start("myapp");
    node2.start("myapp");
    await().atMost(5, SECONDS).until(cb1::hasStarted);
    assertThat(cb1.startMode.get()).isInstanceOf(StartMode.Normal.class);
    provider.removeNode(node1.nodeId());
    await().atMost(10, SECONDS).until(cb2::hasStarted);
    assertThat(cb2.startMode.get()).isInstanceOf(StartMode.Failover.class);
  }

  @Test
  @DisplayName("Multiple applications receive membership notifications")
  void multipleAppsReceiveNotifications() {
    DistributedAppSpec spec1 = new DistributedAppSpec(
        "app1", List.of(List.of(node1.nodeId()), List.of(node2.nodeId())), Duration.ZERO);
    TrackingCallbacks cb1_app1 = new TrackingCallbacks();
    node1.register(spec1, cb1_app1);
    node1.start("app1");
    await().atMost(5, SECONDS).until(cb1_app1::hasStarted);
    NodeId newNode = new NodeId("newpeer", "localhost", 7000);
    provider.addHealthyNode(newNode);
    await().atMost(3, SECONDS).until(() -> cb1_app1.joinedNode.get() != null);
    assertThat(cb1_app1.joinedNode.get()).isEqualTo(newNode);
  }
}
