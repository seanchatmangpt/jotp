package io.github.seanchatmangpt.jotp.distributed;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for distributed applications across multiple {@link DistributedNode} instances.
 *
 * <p>Each test spawns three nodes on OS-assigned localhost ports, registers an application with
 * priority [node1 > node2 > node3], and verifies the OTP failover/takeover semantics.
 *
 * <p>Nodes communicate via TCP on loopback — same physical machine, different ports — faithfully
 * simulating the multi-JVM distributed application lifecycle.
 */
@DisplayName("DistributedNode — OTP distributed application semantics")
class DistributedNodeTest {

    private DistributedNode node1;
    private DistributedNode node2;
    private DistributedNode node3;

    private final List<DistributedNode> allNodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        // OS assigns free ports — no conflicts across test runs
        node1 = new DistributedNode("cp1", "localhost", 0, NodeConfig.defaults());
        node2 = new DistributedNode("cp2", "localhost", 0, NodeConfig.defaults());
        node3 = new DistributedNode("cp3", "localhost", 0, NodeConfig.defaults());
        allNodes.addAll(List.of(node1, node2, node3));
    }

    @AfterEach
    void tearDown() {
        for (DistributedNode n : allNodes) {
            try {
                n.shutdown();
            } catch (Exception ignored) {
            }
        }
        allNodes.clear();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Build a spec with priority: node1 > node2 > node3, immediate failover. */
    private DistributedAppSpec immediateSpec() {
        return new DistributedAppSpec(
                "myapp",
                List.of(List.of(node1.nodeId()), List.of(node2.nodeId()), List.of(node3.nodeId())),
                Duration.ZERO);
    }

    /** Build a spec with a custom failover timeout. */
    private DistributedAppSpec specWithTimeout(Duration failoverTimeout) {
        return new DistributedAppSpec(
                "myapp",
                List.of(List.of(node1.nodeId()), List.of(node2.nodeId()), List.of(node3.nodeId())),
                failoverTimeout);
    }

    /** Simple tracking callbacks — records which node started/stopped and in what mode. */
    private static final class TrackingCallbacks implements ApplicationCallbacks {
        final AtomicReference<StartMode> startMode = new AtomicReference<>();
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final AtomicLong startTimeMs = new AtomicLong(0);

        @Override
        public void onStart(StartMode mode) {
            startTimeMs.set(System.currentTimeMillis());
            startMode.set(mode);
        }

        @Override
        public void onStop() {
            stopped.set(true);
        }

        boolean hasStarted() {
            return startMode.get() != null;
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Application starts on highest-priority node only")
    void startOnHighestPriorityNode() {
        DistributedAppSpec spec = immediateSpec();
        TrackingCallbacks cb1 = new TrackingCallbacks();
        TrackingCallbacks cb2 = new TrackingCallbacks();
        TrackingCallbacks cb3 = new TrackingCallbacks();

        node1.register(spec, cb1);
        node2.register(spec, cb2);
        node3.register(spec, cb3);

        node1.start("myapp");
        node2.start("myapp");
        node3.start("myapp");

        // node1 is highest priority — it should start as Normal
        await().atMost(5, SECONDS).until(cb1::hasStarted);
        assertThat(cb1.startMode.get()).isInstanceOf(StartMode.Normal.class);

        // node2 and node3 should remain standby (not started)
        assertThat(cb2.hasStarted()).isFalse();
        assertThat(cb3.hasStarted()).isFalse();
    }

    @Test
    @DisplayName("Failover to node2 when node1 goes down")
    void failoverWhenPrimaryGoesDown() {
        DistributedAppSpec spec = immediateSpec();
        TrackingCallbacks cb1 = new TrackingCallbacks();
        TrackingCallbacks cb2 = new TrackingCallbacks();
        TrackingCallbacks cb3 = new TrackingCallbacks();

        node1.register(spec, cb1);
        node2.register(spec, cb2);
        node3.register(spec, cb3);

        node1.start("myapp");
        node2.start("myapp");
        node3.start("myapp");

        await().atMost(5, SECONDS).until(cb1::hasStarted);

        // Simulate node1 crash
        node1.shutdown();

        // node2 should failover (it is next in priority)
        await().atMost(10, SECONDS).until(cb2::hasStarted);
        assertThat(cb2.startMode.get()).isInstanceOf(StartMode.Failover.class);
        StartMode.Failover failover = (StartMode.Failover) cb2.startMode.get();
        assertThat(failover.from()).isEqualTo(node1.nodeId());

        // node3 should remain standby (node2 is running)
        assertThat(cb3.hasStarted()).isFalse();
    }

    @Test
    @DisplayName("Takeover by node1 when it rejoins after node2 ran the app")
    void takeoverWhenHigherPriorityReturns() throws IOException {
        DistributedAppSpec spec = immediateSpec();
        TrackingCallbacks cb1 = new TrackingCallbacks();
        TrackingCallbacks cb2 = new TrackingCallbacks();
        TrackingCallbacks cb3 = new TrackingCallbacks();

        node1.register(spec, cb1);
        node2.register(spec, cb2);
        node3.register(spec, cb3);

        node1.start("myapp");
        node2.start("myapp");
        node3.start("myapp");

        await().atMost(5, SECONDS).until(cb1::hasStarted);

        // node1 crashes — node2 should fail over
        node1.shutdown();
        await().atMost(10, SECONDS).until(cb2::hasStarted);
        assertThat(cb2.startMode.get()).isInstanceOf(StartMode.Failover.class);

        // Restart node1 with same coordinates — it should take over from node2
        DistributedNode node1b =
                new DistributedNode(
                        node1.nodeId().name(),
                        node1.nodeId().host(),
                        node1.nodeId().port(),
                        NodeConfig.defaults());
        allNodes.add(node1b);

        TrackingCallbacks cb1b = new TrackingCallbacks();
        node1b.register(spec, cb1b);
        node1b.start("myapp");

        // node1b should take over
        await().atMost(10, SECONDS).until(cb1b::hasStarted);
        assertThat(cb1b.startMode.get()).isInstanceOf(StartMode.Takeover.class);
        StartMode.Takeover takeover = (StartMode.Takeover) cb1b.startMode.get();
        assertThat(takeover.from()).isEqualTo(node2.nodeId());

        // node2 should have been stopped
        await().atMost(5, SECONDS).until(() -> cb2.stopped.get());
    }

    @Test
    @DisplayName("Failover respects the configured failoverTimeout")
    void failoverRespectsTimeout() {
        Duration failoverTimeout = Duration.ofSeconds(1);
        DistributedAppSpec spec = specWithTimeout(failoverTimeout);

        TrackingCallbacks cb1 = new TrackingCallbacks();
        TrackingCallbacks cb2 = new TrackingCallbacks();
        TrackingCallbacks cb3 = new TrackingCallbacks();

        node1.register(spec, cb1);
        node2.register(spec, cb2);
        node3.register(spec, cb3);

        node1.start("myapp");
        node2.start("myapp");
        node3.start("myapp");

        await().atMost(5, SECONDS).until(cb1::hasStarted);

        long shutdownTimeMs = System.currentTimeMillis();
        node1.shutdown();

        // Wait for failover
        await().atMost(15, SECONDS).until(cb2::hasStarted);

        // Elapsed time must be at least the configured timeout
        long elapsedMs = cb2.startTimeMs.get() - shutdownTimeMs;
        assertThat(elapsedMs)
                .as("failover should not happen before failoverTimeout elapses")
                .isGreaterThanOrEqualTo(failoverTimeout.toMillis());
    }

    @Test
    @DisplayName("Coordinated stop at all nodes does not trigger failover")
    void coordinatedStopDoesNotTriggerFailover() throws InterruptedException {
        DistributedAppSpec spec = immediateSpec();
        TrackingCallbacks cb1 = new TrackingCallbacks();
        TrackingCallbacks cb2 = new TrackingCallbacks();
        TrackingCallbacks cb3 = new TrackingCallbacks();

        node1.register(spec, cb1);
        node2.register(spec, cb2);
        node3.register(spec, cb3);

        node1.start("myapp");
        node2.start("myapp");
        node3.start("myapp");

        await().atMost(5, SECONDS).until(cb1::hasStarted);

        // Stop at all nodes — coordinated, no failover expected
        node1.stop("myapp");
        node2.stop("myapp");
        node3.stop("myapp");

        // node1 should have received onStop
        assertThat(cb1.stopped.get()).isTrue();

        // Coordinated stop should be instant — shortened wait
        Thread.sleep(500);

        // No standby node should have started after coordinated stop
        assertFalse(cb2.hasStarted(), "node2 should not start after coordinated stop");
        assertFalse(cb3.hasStarted(), "node3 should not start after coordinated stop");
    }

    @Test
    @DisplayName("Cascading failover: node3 takes over after both node1 and node2 go down")
    void cascadingFailover() {
        DistributedAppSpec spec = immediateSpec();
        TrackingCallbacks cb1 = new TrackingCallbacks();
        TrackingCallbacks cb2 = new TrackingCallbacks();
        TrackingCallbacks cb3 = new TrackingCallbacks();

        node1.register(spec, cb1);
        node2.register(spec, cb2);
        node3.register(spec, cb3);

        node1.start("myapp");
        node2.start("myapp");
        node3.start("myapp");

        await().atMost(5, SECONDS).until(cb1::hasStarted);

        // Kill node1 — node2 fails over
        node1.shutdown();
        await().atMost(10, SECONDS).until(cb2::hasStarted);
        assertThat(cb2.startMode.get()).isInstanceOf(StartMode.Failover.class);

        // Kill node2 — node3 should fail over
        node2.shutdown();
        await().atMost(10, SECONDS).until(cb3::hasStarted);
        assertThat(cb3.startMode.get()).isInstanceOf(StartMode.Failover.class);
        StartMode.Failover failover3 = (StartMode.Failover) cb3.startMode.get();
        assertThat(failover3.from()).isEqualTo(node2.nodeId());
    }
}
