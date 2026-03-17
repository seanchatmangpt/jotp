package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NodeHeartbeat} and {@link NodeFailureDetector}.
 *
 * <p>Uses short timeouts (100 ms heartbeat interval, 3 misses) to keep the suite fast.
 */
class NodeHeartbeatTest {

    /** Detector created in individual tests — stopped in teardown. */
    private NodeFailureDetector detector;

    /** Heartbeat servers created in individual tests — stopped in teardown. */
    private final CopyOnWriteArrayList<NodeHeartbeat> heartbeats = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @AfterEach
    void tearDown() {
        if (detector != null) {
            detector.stop();
        }
        heartbeats.forEach(NodeHeartbeat::stop);
        heartbeats.clear();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private NodeHeartbeat startHeartbeat(String name) throws Exception {
        NodeHeartbeat hb = NodeHeartbeat.start(name, 0);
        heartbeats.add(hb);
        return hb;
    }

    private NodeFailureDetector fastDetector() {
        return NodeFailureDetector.create(Duration.ofMillis(100), 3);
    }

    // ── Test 1: NodeHeartbeat.start() with port 0 ─────────────────────────────

    @Test
    void heartbeatStartsAndIsRunning() throws Exception {
        NodeHeartbeat hb = startHeartbeat("test-node");

        assertThat(hb.isRunning()).isTrue();
        assertThat(hb.nodeName()).isEqualTo("test-node");
        assertThat(hb.port()).isGreaterThan(0);
    }

    // ── Test 2: Detector detects healthy node as UP ───────────────────────────

    @Test
    void detectorDetectsHealthyNodeAsUp() throws Exception {
        NodeHeartbeat hb = startHeartbeat("healthy-node");
        detector = fastDetector();
        detector.monitor("healthy-node", "localhost", hb.port());
        detector.start();

        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("healthy-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));
    }

    // ── Test 3: Detector detects stopped node as DOWN ─────────────────────────

    @Test
    void detectorDetectsStoppedNodeAsDown() throws Exception {
        NodeHeartbeat hb = startHeartbeat("stopping-node");
        detector = fastDetector();
        detector.monitor("stopping-node", "localhost", hb.port());
        detector.start();

        // wait until UP first
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("stopping-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));

        // now stop the heartbeat server
        hb.stop();

        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("stopping-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.DOWN)
                                        .orElse(false));
    }

    // ── Test 4: onNodeDown callback fires when node goes silent ───────────────

    @Test
    void onNodeDownCallbackFiresWhenNodeGosSilent() throws Exception {
        NodeHeartbeat hb = startHeartbeat("silent-node");
        detector = fastDetector();
        detector.monitor("silent-node", "localhost", hb.port());

        CopyOnWriteArrayList<String> downEvents = new CopyOnWriteArrayList<>();
        detector.onNodeDown(downEvents::add);
        detector.start();

        // ensure UP first
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("silent-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));

        hb.stop();

        await().atMost(Duration.ofSeconds(5)).until(() -> downEvents.contains("silent-node"));
        assertThat(downEvents).contains("silent-node");
    }

    // ── Test 5: onNodeUp callback fires when node comes back ─────────────────

    @Test
    void onNodeUpCallbackFiresWhenNodeComesBack() throws Exception {
        NodeHeartbeat hb = startHeartbeat("recovering-node");
        int port = hb.port();

        detector = fastDetector();
        detector.monitor("recovering-node", "localhost", port);

        CopyOnWriteArrayList<String> downEvents = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> upEvents = new CopyOnWriteArrayList<>();
        detector.onNodeDown(downEvents::add);
        detector.onNodeUp(upEvents::add);
        detector.start();

        // 1. wait UP
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("recovering-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));

        // 2. take down
        hb.stop();
        await().atMost(Duration.ofSeconds(5)).until(() -> downEvents.contains("recovering-node"));

        // 3. bring back up on the same port
        NodeHeartbeat hb2 = NodeHeartbeat.start("recovering-node", port);
        heartbeats.add(hb2);

        // 4. wait for UP callback
        await().atMost(Duration.ofSeconds(10)).until(() -> upEvents.contains("recovering-node"));
        assertThat(upEvents).contains("recovering-node");
    }

    // ── Test 6: statusOf returns UNKNOWN for unmonitored node ────────────────

    @Test
    void statusOfReturnsEmptyForUnmonitoredNode() {
        detector = fastDetector();
        assertThat(detector.statusOf("ghost-node")).isEmpty();
    }

    // ── Test 7: Multiple nodes monitored simultaneously ───────────────────────

    @Test
    void multipleNodesMonitoredSimultaneously() throws Exception {
        NodeHeartbeat hb1 = startHeartbeat("node-alpha");
        NodeHeartbeat hb2 = startHeartbeat("node-beta");
        NodeHeartbeat hb3 = startHeartbeat("node-gamma");

        detector = fastDetector();
        detector.monitor("node-alpha", "localhost", hb1.port());
        detector.monitor("node-beta", "localhost", hb2.port());
        detector.monitor("node-gamma", "localhost", hb3.port());
        detector.start();

        // all three should come UP
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                detector.statusOf("node-alpha")
                                                .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                                .orElse(false)
                                        && detector.statusOf("node-beta")
                                                .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                                .orElse(false)
                                        && detector.statusOf("node-gamma")
                                                .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                                .orElse(false));

        Set<NodeFailureDetector.MonitoredNode> nodes = detector.monitoredNodes();
        assertThat(nodes).hasSize(3);
    }

    // ── Test 8: detector.stop() stops all monitoring threads ─────────────────

    @Test
    void detectorStopHaltsMonitoring() throws Exception {
        NodeHeartbeat hb = startHeartbeat("stoppable-node");
        detector = fastDetector();
        detector.monitor("stoppable-node", "localhost", hb.port());
        detector.start();

        // let it run briefly
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("stoppable-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));

        detector.stop();

        // take down the heartbeat after stopping detector; status should NOT change to DOWN
        hb.stop();

        // give the detector time that it would have used to detect the failure
        Thread.sleep(500);

        // status should remain UP (detector is stopped, no more checks)
        assertThat(detector.statusOf("stoppable-node")).hasValue(NodeFailureDetector.NodeStatus.UP);
    }

    // ── Test 9: NodeHeartbeat auto-closes via try-with-resources ──────────────

    @Test
    void heartbeatAutoClosesViaAutoCloseable() throws Exception {
        NodeHeartbeat hb;
        try (NodeHeartbeat resource = NodeHeartbeat.start("auto-close-node", 0)) {
            hb = resource;
            assertThat(hb.isRunning()).isTrue();
        }
        // after try-with-resources block, stop() has been called
        assertThat(hb.isRunning()).isFalse();
    }

    // ── Test 10: UNKNOWN status becomes UP after first successful ping ─────────

    @Test
    void initialStatusIsUnknownThenBecomesUp() throws Exception {
        NodeHeartbeat hb = startHeartbeat("transition-node");
        detector = fastDetector();
        detector.monitor("transition-node", "localhost", hb.port());

        // before start: status is UNKNOWN
        assertThat(detector.statusOf("transition-node"))
                .hasValue(NodeFailureDetector.NodeStatus.UNKNOWN);

        detector.start();

        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("transition-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));

        assertThat(detector.statusOf("transition-node"))
                .hasValue(NodeFailureDetector.NodeStatus.UP);
    }

    // ── Test 11: onNodeDown fires exactly once per DOWN transition ─────────────

    @Test
    void onNodeDownFiresExactlyOncePerTransition() throws Exception {
        NodeHeartbeat hb = startHeartbeat("counted-node");
        detector = fastDetector();
        detector.monitor("counted-node", "localhost", hb.port());

        AtomicInteger downCount = new AtomicInteger(0);
        detector.onNodeDown(name -> downCount.incrementAndGet());
        detector.start();

        // wait UP
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                detector.statusOf("counted-node")
                                        .map(s -> s == NodeFailureDetector.NodeStatus.UP)
                                        .orElse(false));

        hb.stop();

        // wait for the DOWN callback
        await().atMost(Duration.ofSeconds(5)).until(() -> downCount.get() > 0);

        // wait a bit more to confirm it doesn't fire again
        Thread.sleep(400);

        assertThat(downCount.get()).isEqualTo(1);
    }
}
