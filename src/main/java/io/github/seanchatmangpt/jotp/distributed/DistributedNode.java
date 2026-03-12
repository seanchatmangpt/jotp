package io.github.seanchatmangpt.jotp.distributed;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A named JVM node that can participate in a distributed application cluster.
 *
 * <p>Implements Erlang/OTP's distributed application controller ({@code dist_ac}) semantics:
 *
 * <ul>
 *   <li><strong>Leader election</strong> — only the highest-priority live node runs the app
 *   <li><strong>Failover</strong> — when the running node dies, the next live node restarts it
 *       after {@link DistributedAppSpec#failoverTimeout()}
 *   <li><strong>Takeover</strong> — when a higher-priority node rejoins, it reclaims the app
 * </ul>
 *
 * <p>Uses raw TCP sockets (virtual threads + {@code java.net.ServerSocket}). Project Loom parks
 * virtual threads rather than blocking OS threads during I/O, reducing total thread count. This
 * approach is simple and performs well for moderate connection counts. For very high connection
 * counts (&gt;10K concurrent connections), NIO with {@code Selector} may still be more efficient as
 * it multiplexes many connections onto fewer OS threads.
 *
 * <p>Wire protocol — newline-terminated, one command per connection:
 *
 * <pre>{@code
 * PING                                → PONG
 * STATUS appName                      → RUNNING | STOPPED
 * START appName normal                → OK | ERROR reason
 * START appName failover fromWire     → OK | ERROR reason
 * START appName takeover fromWire     → OK | ERROR reason
 * STOP appName                        → OK | ERROR reason
 * }</pre>
 *
 * <p>Usage (mirrors {@code application:start/1} at all nodes):
 *
 * <pre>{@code
 * var node1 = new DistributedNode("cp1", "localhost", 0, NodeConfig.defaults());
 * var node2 = new DistributedNode("cp2", "localhost", 0, NodeConfig.defaults());
 *
 * var spec = new DistributedAppSpec("myapp",
 *     List.of(List.of(node1.nodeId()), List.of(node2.nodeId())),
 *     Duration.ZERO);
 *
 * node1.register(spec, myCallbacks1);
 * node2.register(spec, myCallbacks2);
 *
 * node1.start("myapp");  // node1 calls onStart(Normal) — highest priority
 * node2.start("myapp");  // node2 becomes standby, monitors node1
 * }</pre>
 */
public final class DistributedNode {

    /** Timeout for each outbound TCP connection attempt. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);

    /** How often the standby monitor checks the current leader. */
    private static final Duration MONITOR_INTERVAL = Duration.ofMillis(200);

    private final NodeId self;
    private final NodeConfig config;
    private final ServerSocket serverSocket;
    private volatile boolean running = true;

    private final ConcurrentHashMap<String, AppEntry> apps = new ConcurrentHashMap<>();

    // ── Internal per-app state ────────────────────────────────────────────────

    private enum AppStatus {
        RUNNING,
        STANDBY,
        STOPPED
    }

    private static final class AppEntry {
        final DistributedAppSpec spec;
        final ApplicationCallbacks callbacks;
        final AtomicReference<AppStatus> status = new AtomicReference<>(AppStatus.STOPPED);
        volatile NodeId currentLeader = null;
        volatile Thread monitorThread = null;
        volatile boolean coordinatedStop = false;

        AppEntry(DistributedAppSpec spec, ApplicationCallbacks callbacks) {
            this.spec = spec;
            this.callbacks = callbacks;
        }
    }

    // ── Constructor ────────────────────────────────────────────────────────────

    /**
     * Create and start a distributed node.
     *
     * <p>Binds a TCP server socket immediately. Pass {@code port = 0} for OS-assigned free port;
     * retrieve the actual port via {@link #nodeId()}.
     *
     * @param name human-readable node name (e.g., {@code "cp1"})
     * @param host hostname or IP address this node listens on
     * @param port TCP port to bind ({@code 0} = OS-assigned)
     * @param config startup synchronization configuration. <strong>Note:</strong> the {@code
     *     syncNodesMandatory} and {@code syncNodesOptional} fields in {@link NodeConfig} are stored
     *     but not yet enforced at startup — the node begins leader election immediately without
     *     waiting for peer nodes to become reachable. Do not rely on synchronization guarantees
     *     from these fields in the current implementation.
     * @throws IOException if the server socket cannot be bound
     */
    public DistributedNode(String name, String host, int port, NodeConfig config)
            throws IOException {
        this.config = config;
        this.serverSocket = new ServerSocket(port);
        this.self = new NodeId(name, host, serverSocket.getLocalPort());
        Thread.ofVirtual().name("node-" + name + "-accept").start(this::acceptLoop);
    }

    /**
     * The actual {@link NodeId} of this node (includes the OS-assigned port if {@code 0} was
     * passed).
     */
    public NodeId nodeId() {
        return self;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Register a distributed application on this node.
     *
     * <p>Must be called before {@link #start(String)} at all participating nodes. The spec must be
     * identical across nodes (same app name, same priority list, same failover timeout).
     *
     * @param spec application configuration (nodes, priority, failover timeout)
     * @param callbacks lifecycle hooks called when the app starts or stops on this node
     */
    public void register(DistributedAppSpec spec, ApplicationCallbacks callbacks) {
        apps.put(spec.name(), new AppEntry(spec, callbacks));
    }

    /**
     * Start the distributed application on this node.
     *
     * <p>Mirrors Erlang's {@code application:start(Application)} — call this at <em>all</em>
     * participating nodes. Only the highest-priority live node will actually invoke {@link
     * ApplicationCallbacks#onStart}; others become standby and begin monitoring.
     *
     * @param appName the registered application name
     * @throws IllegalArgumentException if the app has not been registered
     */
    public void start(String appName) {
        AppEntry entry = apps.get(appName);
        if (entry == null) throw new IllegalArgumentException("App not registered: " + appName);
        entry.coordinatedStop = false;
        electLeader(entry, appName);
    }

    /**
     * Stop the distributed application on this node.
     *
     * <p>Mirrors Erlang's {@code application:stop(Application)} — call this at <em>all</em>
     * participating nodes. The coordinated stop flag prevents standby nodes from triggering a
     * failover when the running node stops.
     *
     * @param appName the registered application name
     */
    public void stop(String appName) {
        AppEntry entry = apps.get(appName);
        if (entry == null) return;
        entry.coordinatedStop = true;
        // Broadcast STOP to all peer nodes so they don't trigger spurious failover
        for (NodeId peer : entry.spec.priorityList()) {
            if (!peer.equals(self)) {
                send(peer, "STOP " + appName);
            }
        }
        interruptMonitor(entry);
        AppStatus prev = entry.status.getAndSet(AppStatus.STOPPED);
        if (prev == AppStatus.RUNNING) {
            entry.callbacks.onStop();
        }
    }

    /**
     * Shut down this node.
     *
     * <p>Closes the server socket, stops all monitor threads, and marks the node as offline. Does
     * not invoke {@link ApplicationCallbacks#onStop()} — the node is simply disappearing, and peer
     * nodes will handle failover according to their own schedules.
     */
    public void shutdown() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (AppEntry entry : apps.values()) {
            interruptMonitor(entry);
        }
    }

    // ── Leader Election ────────────────────────────────────────────────────────

    /**
     * Core leader election: determines whether this node should run the app, defer to a
     * higher-priority node, or take over from a lower-priority node.
     *
     * <p>Algorithm:
     *
     * <ol>
     *   <li>Scan priority list from highest to lowest priority
     *   <li>For each node before {@code self}: if alive → this node is standby (defer to it)
     *   <li>When we reach {@code self}: check if any lower-priority node is RUNNING (takeover),
     *       otherwise start as NORMAL
     * </ol>
     */
    private void electLeader(AppEntry entry, String appName) {
        if (entry.coordinatedStop) return;

        List<NodeId> priority = entry.spec.priorityList();

        int selfIdx = priority.indexOf(self);
        if (selfIdx == -1) {
            // This node is not in the priority list for this app
            return;
        }

        // Pass 1: find a higher-priority live node — defer to it
        for (NodeId candidate : priority) {
            if (candidate.equals(self)) break; // no higher-priority live node found
            if (ping(candidate)) {
                // Higher-priority node is alive — become/stay STANDBY monitoring it
                if (!candidate.equals(entry.currentLeader)
                        || entry.status.get() != AppStatus.STANDBY) {
                    entry.currentLeader = candidate;
                    entry.status.set(AppStatus.STANDBY);
                    startMonitor(entry, appName);
                }
                return;
            }
        }

        // Pass 2: I am the highest-priority live node
        if (entry.status.get() == AppStatus.RUNNING) {
            return; // already running, nothing to do
        }

        // Check for lower-priority RUNNING nodes (takeover needed)
        for (int i = selfIdx + 1; i < priority.size(); i++) {
            NodeId lower = priority.get(i);
            if (ping(lower) && "RUNNING".equals(queryStatus(lower, appName))) {
                // Take over from the lower-priority node
                if (entry.status.compareAndSet(AppStatus.STANDBY, AppStatus.RUNNING)) {
                    send(lower, "START " + appName + " takeover " + self.wire());
                    NodeId oldLeader = lower;
                    entry.currentLeader = self;
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            entry.callbacks.onStart(
                                                    new StartMode.Takeover(oldLeader)));
                }
                return;
            }
        }

        // No lower-priority node running — start normally
        entry.currentLeader = self;
        if (entry.status.compareAndSet(AppStatus.STANDBY, AppStatus.RUNNING)
                || entry.status.compareAndSet(AppStatus.STOPPED, AppStatus.RUNNING)) {
            Thread.ofVirtual().start(() -> entry.callbacks.onStart(new StartMode.Normal()));
        }
    }

    // ── Monitor (Standby → Failover Detection) ────────────────────────────────

    private void startMonitor(AppEntry entry, String appName) {
        interruptMonitor(entry);
        Thread monitor =
                Thread.ofVirtual()
                        .name("monitor-" + self.name() + "-" + appName)
                        .start(() -> monitorLoop(entry, appName));
        entry.monitorThread = monitor;
    }

    private void interruptMonitor(AppEntry entry) {
        Thread t = entry.monitorThread;
        if (t != null) {
            t.interrupt();
            entry.monitorThread = null;
        }
    }

    private void monitorLoop(AppEntry entry, String appName) {
        NodeId leaderToWatch = entry.currentLeader;

        while (running && entry.status.get() == AppStatus.STANDBY && !entry.coordinatedStop) {
            try {
                Thread.sleep(MONITOR_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                return;
            }

            if (!ping(leaderToWatch)) {
                // Leader unreachable — wait the configured failover timeout
                try {
                    Thread.sleep(entry.spec.failoverTimeout().toMillis());
                } catch (InterruptedException e) {
                    return;
                }

                if (entry.coordinatedStop) return;

                // Confirm failure (not a transient blip)
                if (ping(leaderToWatch)) continue;

                // Leader is truly dead — run election excluding dead node
                tryBecomeLeader(entry, appName, leaderToWatch);
                return;
            }

            // Leader alive — check if still running the app (handles takeover by someone else)
            String leaderStatus = queryStatus(leaderToWatch, appName);
            if (!"RUNNING".equals(leaderStatus) && !entry.coordinatedStop) {
                handleLeaderStopped(entry, appName);
                return;
            }
        }
    }

    /**
     * Called when the monitored leader is alive but no longer running the app (e.g., it was taken
     * over by a higher-priority node). Re-runs leader election to find the new leader.
     */
    private void handleLeaderStopped(AppEntry entry, String appName) {
        if (entry.coordinatedStop) return;
        electLeader(entry, appName);
    }

    /**
     * Called after confirming the monitored leader is dead. Runs deterministic election: each node
     * independently computes the same result — "who is the first live node in priority order,
     * skipping the dead node?" This avoids split-brain without distributed consensus.
     */
    private void tryBecomeLeader(AppEntry entry, String appName, NodeId deadLeader) {
        if (entry.coordinatedStop) return;

        List<NodeId> priority = entry.spec.priorityList();

        for (NodeId candidate : priority) {
            if (candidate.equals(deadLeader)) continue; // skip confirmed-dead node

            if (candidate.equals(self)) {
                // I am the first live node — become the new leader
                if (entry.status.compareAndSet(AppStatus.STANDBY, AppStatus.RUNNING)) {
                    entry.currentLeader = self;
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            entry.callbacks.onStart(
                                                    new StartMode.Failover(deadLeader)));
                }
                return;
            }

            if (ping(candidate)) {
                // A higher-priority node is alive — defer to it
                entry.currentLeader = candidate;
                entry.status.set(AppStatus.STANDBY);
                startMonitor(entry, appName);
                return;
            }
        }
    }

    // ── TCP Server ─────────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual()
                        .name("node-" + self.name() + "-conn")
                        .start(() -> handleConnection(socket));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                var writer =
                        new PrintWriter(
                                new BufferedWriter(
                                        new OutputStreamWriter(socket.getOutputStream())),
                                true)) {

            String line = reader.readLine();
            if (line == null) return;

            String[] parts = line.split(" ", 4);
            String response =
                    switch (parts[0]) {
                        case "PING" -> "PONG";
                        case "STATUS" -> {
                            if (parts.length < 2) yield "ERROR missing-appname";
                            AppEntry e = apps.get(parts[1]);
                            yield (e != null && e.status.get() == AppStatus.RUNNING)
                                    ? "RUNNING"
                                    : "STOPPED";
                        }
                        case "START" -> {
                            if (parts.length < 3) yield "ERROR missing-args";
                            String fromWire = parts.length > 3 ? parts[3] : null;
                            yield handleRemoteStart(parts[1], parts[2], fromWire);
                        }
                        case "STOP" -> {
                            if (parts.length < 2) yield "ERROR missing-appname";
                            yield handleRemoteStop(parts[1]);
                        }
                        default -> "ERROR unknown-command";
                    };

            writer.println(response);
        } catch (IOException ignored) {
        }
    }

    private String handleRemoteStart(String appName, String mode, String fromWire) {
        AppEntry entry = apps.get(appName);
        if (entry == null) return "ERROR app-not-registered";

        if ("takeover".equals(mode)) {
            // Higher-priority node is taking over — yield running status
            if (entry.status.get() == AppStatus.RUNNING) {
                NodeId newLeader = fromWire != null ? NodeId.parse(fromWire) : null;
                entry.currentLeader = newLeader;
                entry.status.set(AppStatus.STANDBY);
                // Notify app it's stopping, then watch the new leader
                Thread.ofVirtual().start(entry.callbacks::onStop);
                if (newLeader != null) startMonitor(entry, appName);
            }
        }
        return "OK";
    }

    private String handleRemoteStop(String appName) {
        AppEntry entry = apps.get(appName);
        if (entry == null) return "ERROR app-not-registered";
        entry.coordinatedStop = true;
        interruptMonitor(entry);
        AppStatus prev = entry.status.getAndSet(AppStatus.STOPPED);
        if (prev == AppStatus.RUNNING) {
            entry.callbacks.onStop();
        }
        return "OK";
    }

    // ── TCP Client ─────────────────────────────────────────────────────────────

    private boolean ping(NodeId target) {
        return "PONG".equals(send(target, "PING"));
    }

    private String queryStatus(NodeId target, String appName) {
        return send(target, "STATUS " + appName);
    }

    /**
     * Send one command to a peer and return the single-line response.
     *
     * <p>Opens a new TCP connection, writes the command, reads one response line, closes. Returns
     * {@code null} on any IOException (node unreachable, refused, timeout).
     */
    private String send(NodeId target, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(target.host(), target.port()),
                    (int) CONNECT_TIMEOUT.toMillis());
            socket.setSoTimeout((int) CONNECT_TIMEOUT.toMillis());
            var writer =
                    new PrintWriter(
                            new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())),
                            true);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println(command);
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
