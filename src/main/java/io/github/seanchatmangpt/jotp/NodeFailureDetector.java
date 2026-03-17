package io.github.seanchatmangpt.jotp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Monitors remote nodes by sending periodic TCP heartbeat pings and fires callbacks when a node
 * goes DOWN or comes back UP.
 *
 * <p>Erlang analogue: {@code net_kernel} ticker + {@code nodedown}/{@code nodeup} notifications
 * delivered via {@code erlang:monitor_node/2}.
 *
 * <p>For each monitored node a dedicated virtual thread opens a fresh TCP connection every {@code
 * heartbeatInterval}, sends a 1-byte ping (0x01), and reads the response from {@link
 * NodeHeartbeat}. On consecutive failures the node is marked {@link NodeStatus#DOWN}; on recovery
 * it is marked {@link NodeStatus#UP}.
 *
 * <pre>{@code
 * NodeFailureDetector detector = NodeFailureDetector.create(
 *     Duration.ofMillis(500),   // heartbeat interval
 *     3                          // max missed before DOWN
 * );
 *
 * detector.monitor("node1", "localhost", 9000);
 * detector.onNodeDown(name -> System.out.println("DOWN: " + name));
 * detector.onNodeUp(name  -> System.out.println("UP: "   + name));
 * detector.start();
 *
 * // later …
 * detector.stop();
 * }</pre>
 *
 * @see NodeHeartbeat
 */
public final class NodeFailureDetector {

    /** Status of a monitored node. */
    public enum NodeStatus {
        /** Node has not yet been contacted (initial state). */
        UNKNOWN,
        /** Node responded successfully to the last heartbeat. */
        UP,
        /** Node missed {@code maxMisses} consecutive heartbeats. */
        DOWN
    }

    /**
     * Immutable descriptor for a monitored remote node.
     *
     * @param name logical node name
     * @param host TCP hostname or IP address
     * @param port TCP port where {@link NodeHeartbeat} is listening
     */
    public record MonitoredNode(String name, String host, int port) {}

    // ── Internal per-node state ────────────────────────────────────────────────

    private static final class NodeState {
        final MonitoredNode node;
        volatile NodeStatus status = NodeStatus.UNKNOWN;
        volatile int missCount = 0;

        NodeState(MonitoredNode node) {
            this.node = node;
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final Duration heartbeatInterval;
    private final int maxMisses;
    private final ConcurrentHashMap<String, NodeState> states = new ConcurrentHashMap<>();
    private final List<Consumer<String>> downCallbacks = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> upCallbacks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Thread> monitorThreads = new ConcurrentHashMap<>();

    private NodeFailureDetector(Duration heartbeatInterval, int maxMisses) {
        this.heartbeatInterval = heartbeatInterval;
        this.maxMisses = maxMisses;
    }

    /**
     * Create a new detector with the given heartbeat interval and miss threshold.
     *
     * @param heartbeatInterval how often to ping each monitored node
     * @param maxMisses number of consecutive missed pings before declaring a node DOWN
     * @return a new (not yet started) {@code NodeFailureDetector}
     */
    public static NodeFailureDetector create(Duration heartbeatInterval, int maxMisses) {
        return new NodeFailureDetector(heartbeatInterval, maxMisses);
    }

    // ── Configuration ──────────────────────────────────────────────────────────

    /**
     * Register a remote node to monitor. May be called before or after {@link #start()}.
     *
     * @param name logical node name (used in callbacks and {@link #statusOf})
     * @param host TCP hostname or IP
     * @param port TCP port of the target {@link NodeHeartbeat}
     */
    public void monitor(String name, String host, int port) {
        MonitoredNode node = new MonitoredNode(name, host, port);
        states.putIfAbsent(name, new NodeState(node));
        if (running.get()) {
            startMonitorThread(states.get(name));
        }
    }

    /**
     * Register a callback to be invoked when a node transitions to {@link NodeStatus#DOWN}.
     *
     * <p>Callbacks execute in a dedicated virtual thread and must not throw unchecked exceptions
     * that are expected to propagate.
     *
     * @param callback consumer receiving the node name
     */
    public void onNodeDown(Consumer<String> callback) {
        downCallbacks.add(callback);
    }

    /**
     * Register a callback to be invoked when a previously DOWN node comes back {@link
     * NodeStatus#UP}.
     *
     * @param callback consumer receiving the node name
     */
    public void onNodeUp(Consumer<String> callback) {
        upCallbacks.add(callback);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Start monitoring all registered nodes. Idempotent — calling twice has no effect. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            states.values().forEach(this::startMonitorThread);
        }
    }

    /**
     * Stop all monitoring threads. After stopping, {@link #statusOf} still returns the last known
     * status.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            monitorThreads.values().forEach(Thread::interrupt);
            monitorThreads.clear();
        }
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    /**
     * Return the current status of the named node, or {@link Optional#empty()} if the node has
     * never been registered with {@link #monitor}.
     *
     * @param name the logical node name
     * @return the current status, or empty if unknown
     */
    public Optional<NodeStatus> statusOf(String name) {
        NodeState state = states.get(name);
        return state == null ? Optional.empty() : Optional.of(state.status);
    }

    /**
     * Returns an unmodifiable snapshot of all currently monitored nodes.
     *
     * @return set of monitored node descriptors
     */
    public Set<MonitoredNode> monitoredNodes() {
        return states.values().stream()
                .map(s -> s.node)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void startMonitorThread(NodeState state) {
        Thread t =
                Thread.ofVirtual()
                        .name("jotp-monitor-" + state.node.name())
                        .start(() -> monitorLoop(state));
        monitorThreads.put(state.node.name(), t);
    }

    private void monitorLoop(NodeState state) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            boolean alive = ping(state.node);
            recordResult(state, alive);

            try {
                Thread.sleep(heartbeatInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean ping(MonitoredNode node) {
        try (Socket socket = new Socket(node.host(), node.port())) {
            socket.setSoTimeout((int) heartbeatInterval.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(0x01);
            out.flush();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String responseName = reader.readLine();
            return node.name().equals(responseName);
        } catch (IOException e) {
            return false;
        }
    }

    private void recordResult(NodeState state, boolean alive) {
        if (alive) {
            state.missCount = 0;
            if (state.status != NodeStatus.UP) {
                NodeStatus previous = state.status;
                state.status = NodeStatus.UP;
                if (previous == NodeStatus.DOWN) {
                    fireCallbacks(upCallbacks, state.node.name());
                }
            }
        } else {
            state.missCount++;
            if (state.missCount >= maxMisses && state.status != NodeStatus.DOWN) {
                state.status = NodeStatus.DOWN;
                fireCallbacks(downCallbacks, state.node.name());
            }
        }
    }

    private void fireCallbacks(List<Consumer<String>> callbacks, String nodeName) {
        for (Consumer<String> cb : callbacks) {
            Thread.ofVirtual()
                    .name("jotp-monitor-callback-" + nodeName)
                    .start(() -> cb.accept(nodeName));
        }
    }
}
