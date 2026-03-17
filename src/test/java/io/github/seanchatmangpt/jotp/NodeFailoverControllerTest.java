package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NodeFailoverController: process migration on node failure")
class NodeFailoverControllerTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    private NodeFailoverController controller;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        GlobalProcRegistry.reset();
        controller = NodeFailoverController.create();
    }

    // ── registerSpec ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registerSpec: adds process name to managedProcs()")
    void registerSpec_addsTomanagedProcs() {
        controller.registerSpec("counter", "node2", () -> Proc.spawn(0, (s, m) -> s));
        assertThat(controller.managedProcs()).containsExactly("counter");
    }

    @Test
    @DisplayName("registerSpec: initial status is HEALTHY")
    void registerSpec_initialStatusIsHealthy() {
        controller.registerSpec("worker", "node3", () -> Proc.spawn(0, (s, m) -> s));
        assertThat(controller.statusOf("worker"))
                .isPresent()
                .hasValue(NodeFailoverController.NodeProcStatus.HEALTHY);
    }

    @Test
    @DisplayName("registerSpec: multiple procs tracked independently")
    void registerSpec_multipleProcsTrackedIndependently() {
        controller.registerSpec("proc-a", "node1", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("proc-b", "node1", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("proc-c", "node2", () -> Proc.spawn(0, (s, m) -> s));

        assertThat(controller.managedProcs())
                .containsExactlyInAnyOrder("proc-a", "proc-b", "proc-c");
    }

    // ── onNodeDown ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onNodeDown: triggers failover for all procs on that node")
    void onNodeDown_triggersFailoverForAllProcsOnNode() {
        controller.registerSpec("proc-a", "node1", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("proc-b", "node1", () -> Proc.spawn(0, (s, m) -> s));

        controller.onNodeDown("node1");

        await().atMost(AWAIT)
                .until(
                        () ->
                                controller.statusOf("proc-a").orElse(null)
                                                == NodeFailoverController.NodeProcStatus.RECOVERED
                                        && controller.statusOf("proc-b").orElse(null)
                                                == NodeFailoverController.NodeProcStatus.RECOVERED);
    }

    @Test
    @DisplayName("onNodeDown: proc from a different node is not affected")
    void onNodeDown_doesNotAffectProcsOnOtherNodes() {
        controller.registerSpec("on-node1", "node1", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("on-node2", "node2", () -> Proc.spawn(0, (s, m) -> s));

        controller.onNodeDown("node1");

        await().atMost(AWAIT)
                .until(
                        () ->
                                controller.statusOf("on-node1").orElse(null)
                                        == NodeFailoverController.NodeProcStatus.RECOVERED);

        // node2 proc was not touched — still HEALTHY
        assertThat(controller.statusOf("on-node2"))
                .hasValue(NodeFailoverController.NodeProcStatus.HEALTHY);
    }

    @Test
    @DisplayName("onNodeDown: unknown node is a no-op (no exception)")
    void onNodeDown_unknownNode_isNoOp() {
        assertThatCode(() -> controller.onNodeDown("ghost-node")).doesNotThrowAnyException();
    }

    // ── GlobalProcRegistry registration ──────────────────────────────────────────

    @Test
    @DisplayName("after failover: proc is registered in GlobalProcRegistry")
    void afterFailover_procRegisteredInGlobalRegistry() {
        controller.registerSpec("counter", "node2", () -> Proc.spawn(0, (s, m) -> (Integer) s + 1));
        controller.onNodeDown("node2");

        await().atMost(AWAIT).until(() -> GlobalProcRegistry.whereis("counter").isPresent());

        assertThat(GlobalProcRegistry.<Integer, Integer>whereis("counter")).isPresent();
    }

    @Test
    @DisplayName("after failover: multiple procs on same node all appear in GlobalProcRegistry")
    void afterFailover_multipleProcsAllRegistered() {
        controller.registerSpec("alpha", "node3", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("beta", "node3", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("gamma", "node3", () -> Proc.spawn(0, (s, m) -> s));

        controller.onNodeDown("node3");

        await().atMost(AWAIT)
                .until(
                        () ->
                                GlobalProcRegistry.whereis("alpha").isPresent()
                                        && GlobalProcRegistry.whereis("beta").isPresent()
                                        && GlobalProcRegistry.whereis("gamma").isPresent());
    }

    // ── statusOf ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("statusOf: returns RECOVERED after successful failover")
    void statusOf_recoveredAfterFailover() {
        controller.registerSpec("srv", "nodeX", () -> Proc.spawn(0, (s, m) -> s));
        controller.onNodeDown("nodeX");

        await().atMost(AWAIT)
                .until(
                        () ->
                                controller.statusOf("srv").orElse(null)
                                        == NodeFailoverController.NodeProcStatus.RECOVERED);
    }

    @Test
    @DisplayName("statusOf: returns empty for unknown proc name")
    void statusOf_unknownProc_returnsEmpty() {
        assertThat(controller.statusOf("no-such-proc")).isEmpty();
    }

    // ── onFailover listener ───────────────────────────────────────────────────────

    @Test
    @DisplayName("onFailover: listener fires with correct FailoverEvent fields")
    void onFailover_listenerFiresWithCorrectEvent() {
        List<NodeFailoverController.FailoverEvent> events = new CopyOnWriteArrayList<>();
        controller.onFailover(events::add);

        controller.registerSpec("counter", "node2", () -> Proc.spawn(0, (s, m) -> s));
        controller.onNodeDown("node2");

        await().atMost(AWAIT).until(() -> !events.isEmpty());

        NodeFailoverController.FailoverEvent event = events.get(0);
        assertThat(event.procName()).isEqualTo("counter");
        assertThat(event.fromNode()).isEqualTo("node2");
        assertThat(event.toNode()).isEqualTo(GlobalProcRegistry.nodeName());
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("onFailover: multiple listeners all fire")
    void onFailover_multipleListenersAllFire() {
        List<NodeFailoverController.FailoverEvent> first = new CopyOnWriteArrayList<>();
        List<NodeFailoverController.FailoverEvent> second = new CopyOnWriteArrayList<>();
        List<NodeFailoverController.FailoverEvent> third = new CopyOnWriteArrayList<>();

        controller.onFailover(first::add);
        controller.onFailover(second::add);
        controller.onFailover(third::add);

        controller.registerSpec("worker", "nodeA", () -> Proc.spawn(0, (s, m) -> s));
        controller.onNodeDown("nodeA");

        await().atMost(AWAIT)
                .until(() -> !first.isEmpty() && !second.isEmpty() && !third.isEmpty());

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(third).hasSize(1);
    }

    // ── failoverHistory ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("failoverHistory: records event after failover completes")
    void failoverHistory_recordsEvent() {
        controller.registerSpec("srv", "nodeB", () -> Proc.spawn(0, (s, m) -> s));
        controller.onNodeDown("nodeB");

        await().atMost(AWAIT).until(() -> !controller.failoverHistory().isEmpty());

        List<NodeFailoverController.FailoverEvent> history = controller.failoverHistory();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).procName()).isEqualTo("srv");
        assertThat(history.get(0).fromNode()).isEqualTo("nodeB");
    }

    @Test
    @DisplayName("failoverHistory: accumulates events across multiple node failures")
    void failoverHistory_accumulatesAcrossMultipleFailures() {
        controller.registerSpec("svc-a", "n1", () -> Proc.spawn(0, (s, m) -> s));
        controller.registerSpec("svc-b", "n2", () -> Proc.spawn(0, (s, m) -> s));

        controller.onNodeDown("n1");
        await().atMost(AWAIT).until(() -> controller.failoverHistory().size() >= 1);

        controller.onNodeDown("n2");
        await().atMost(AWAIT).until(() -> controller.failoverHistory().size() >= 2);

        assertThat(controller.failoverHistory()).hasSize(2);
    }

    // ── skip re-registration if already alive ─────────────────────────────────────

    @Test
    @DisplayName("onNodeDown: skips re-registration if proc is already alive in GlobalProcRegistry")
    void onNodeDown_skipsIfProcAlreadyAlive() {
        // Pre-register a living proc
        var existingProc = Proc.spawn(0, (s, m) -> s);
        GlobalProcRegistry.register("existing", existingProc);

        controller.registerSpec(
                "existing",
                "nodeC",
                () -> {
                    throw new AssertionError(
                            "factory must not be called for an already-living proc");
                });

        controller.onNodeDown("nodeC");

        // Status transitions to RECOVERED (skip path) without throwing
        await().atMost(AWAIT)
                .until(
                        () ->
                                controller.statusOf("existing").orElse(null)
                                        == NodeFailoverController.NodeProcStatus.RECOVERED);

        // The original proc is still the registered one — use await() to tolerate minor timing
        await().atMost(AWAIT)
                .untilAsserted(
                        () ->
                                assertThat(GlobalProcRegistry.<Integer, Integer>whereis("existing"))
                                        .isPresent()
                                        .hasValueSatisfying(
                                                p -> assertThat(p).isSameAs(existingProc)));

        try {
            existingProc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── stop ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop: can be called without error at any lifecycle stage")
    void stop_isIdempotentAndSafe() {
        controller.registerSpec("proc", "nodeD", () -> Proc.spawn(0, (s, m) -> s));
        assertThatCode(() -> controller.stop()).doesNotThrowAnyException();
        controller.onNodeDown("nodeD");
        await().atMost(AWAIT)
                .until(
                        () ->
                                controller.statusOf("proc").orElse(null)
                                        == NodeFailoverController.NodeProcStatus.RECOVERED);
        assertThatCode(() -> controller.stop()).doesNotThrowAnyException();
    }
}
