package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.JvmShutdownManager;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Static configuration implementation of {@link NodeDiscovery}.
 *
 * <p>Implements node discovery using a pre-configured list of cluster members. Nodes are registered
 * at startup and monitored via periodic health checks. This implementation is suitable for:
 *
 * <ul>
 *   <li>Static cluster configurations where nodes are known in advance
 *   <li>On-premise deployments with fixed node lists
 *   <li>Testing and development environments
 * </ul>
 *
 * <p><strong>Health Check Algorithm:</strong>
 *
 * <pre>{@code
 * Every healthCheckInterval:
 *   For each known node:
 *     if (now - lastHeartbeat > heartbeatTimeout):
 *       if (status == HEALTHY): mark as DEGRADED
 *       if (status == DEGRADED && timeout exceeded): mark as DOWN, trigger onNodeDown
 *     else if (status == DEGRADED): mark as HEALTHY, trigger onNodeUp
 * }</pre>
 *
 * <p><strong>Graceful Shutdown:</strong> This implementation registers with {@link
 * JvmShutdownManager} to ensure health checks stop cleanly during JVM shutdown. The shutdown hook
 * runs at {@link JvmShutdownManager.Priority#BEST_EFFORT_SAVE} priority.
 *
 * <p><strong>Thread Safety:</strong> This implementation is thread-safe. All state is protected by
 * {@link ConcurrentHashMap} and {@link CopyOnWriteArrayList}. Callbacks may be invoked concurrently
 * from health check threads.
 *
 * @see NodeDiscovery
 * @see NodeDiscoveryBackend
 */
public final class StaticNodeDiscovery implements NodeDiscovery {

    private final Map<String, NodeInfo> knownNodes = new ConcurrentHashMap<>();
    private final NodeDiscoveryBackend backend;
    private final ScheduledExecutorService healthCheckScheduler;
    private final List<Consumer<String>> nodeDownListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> nodeUpListeners = new CopyOnWriteArrayList<>();
    private final String thisNodeName;
    private final Duration healthCheckInterval;
    private final Duration heartbeatTimeout;
    private final Duration degradedTimeout;
    private volatile boolean running = false;

    /**
     * Create a new static node discovery instance.
     *
     * @param thisNodeName the name of this node (must not be null)
     * @param nodeNames list of all node names in the cluster (must not be null, must contain
     *     thisNodeName)
     * @param nodeAddresses map of node name to address (must not be null, must contain entry for
     *     each node in nodeNames)
     * @param backend the storage backend for node information (must not be null)
     * @param healthCheckInterval how often to run health checks (must be positive, e.g., {@code
     *     Duration.ofSeconds(5)})
     * @param heartbeatTimeout threshold for marking a node as DEGRADED (must be positive, e.g.,
     *     {@code Duration.ofSeconds(10)})
     * @param degradedTimeout threshold for marking a DEGRADED node as DOWN (must be positive, e.g.,
     *     {@code Duration.ofSeconds(30)})
     * @throws NullPointerException if any required parameter is null
     * @throws IllegalArgumentException if thisNodeName is not in nodeNames, or if nodeAddresses is
     *     missing entries, or if any duration is not positive
     */
    public StaticNodeDiscovery(
            String thisNodeName,
            List<String> nodeNames,
            Map<String, String> nodeAddresses,
            NodeDiscoveryBackend backend,
            Duration healthCheckInterval,
            Duration heartbeatTimeout,
            Duration degradedTimeout) {
        if (thisNodeName == null) throw new NullPointerException("thisNodeName must not be null");
        if (nodeNames == null) throw new NullPointerException("nodeNames must not be null");
        if (nodeAddresses == null) throw new NullPointerException("nodeAddresses must not be null");
        if (backend == null) throw new NullPointerException("backend must not be null");
        if (healthCheckInterval == null
                || healthCheckInterval.isNegative()
                || healthCheckInterval.isZero()) {
            throw new IllegalArgumentException("healthCheckInterval must be positive");
        }
        if (heartbeatTimeout == null
                || heartbeatTimeout.isNegative()
                || heartbeatTimeout.isZero()) {
            throw new IllegalArgumentException("heartbeatTimeout must be positive");
        }
        if (degradedTimeout == null || degradedTimeout.isNegative() || degradedTimeout.isZero()) {
            throw new IllegalArgumentException("degradedTimeout must be positive");
        }
        if (!nodeNames.contains(thisNodeName)) {
            throw new IllegalArgumentException("thisNodeName must be in nodeNames");
        }

        this.thisNodeName = thisNodeName;
        this.backend = backend;
        this.healthCheckInterval = healthCheckInterval;
        this.heartbeatTimeout = heartbeatTimeout;
        this.degradedTimeout = degradedTimeout;

        // Create scheduler with virtual thread factory
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(1, Thread.ofVirtual().factory());
        scheduler.setRemoveOnCancelPolicy(true);
        this.healthCheckScheduler = scheduler;

        // Initialize all known nodes
        for (String nodeName : nodeNames) {
            String address = nodeAddresses.get(nodeName);
            if (address == null) {
                throw new IllegalArgumentException("Missing address for node: " + nodeName);
            }
            NodeInfo node = NodeInfo.create(nodeName, address);
            knownNodes.put(nodeName, node);
            backend.storeNode(node);
        }

        // Register shutdown hook
        JvmShutdownManager.getInstance()
                .registerCallback(
                        JvmShutdownManager.Priority.BEST_EFFORT_SAVE,
                        this::shutdown,
                        Duration.ofSeconds(2));
    }

    /**
     * Create a new static node discovery instance with default timeouts.
     *
     * <p>Uses the following defaults:
     *
     * <ul>
     *   <li>healthCheckInterval: 5 seconds
     *   <li>heartbeatTimeout: 10 seconds
     *   <li>degradedTimeout: 30 seconds
     * </ul>
     *
     * @param thisNodeName the name of this node
     * @param nodeNames list of all node names in the cluster
     * @param nodeAddresses map of node name to address
     * @param backend the storage backend for node information
     */
    public StaticNodeDiscovery(
            String thisNodeName,
            List<String> nodeNames,
            Map<String, String> nodeAddresses,
            NodeDiscoveryBackend backend) {
        this(
                thisNodeName,
                nodeNames,
                nodeAddresses,
                backend,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }

    @Override
    public Result<Void, Exception> registerNode(String nodeName, String nodeAddress) {
        try {
            NodeInfo node = NodeInfo.create(nodeName, nodeAddress);
            knownNodes.put(nodeName, node);
            backend.storeNode(node);
            return Result.ok(null);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    @Override
    public List<String> getHealthyNodes() {
        return knownNodes.values().stream()
                .filter(node -> node.status() == NodeInfo.NodeStatus.HEALTHY)
                .map(NodeInfo::nodeName)
                .toList();
    }

    @Override
    public void onNodeDown(String nodeName) {
        NodeInfo current = knownNodes.get(nodeName);
        if (current != null && current.status() != NodeInfo.NodeStatus.DOWN) {
            NodeInfo downNode = current.withStatus(NodeInfo.NodeStatus.DOWN);
            knownNodes.put(nodeName, downNode);
            backend.storeNode(downNode);

            // Notify listeners
            for (Consumer<String> listener : nodeDownListeners) {
                try {
                    listener.accept(nodeName);
                } catch (Exception e) {
                    // Log and continue — don't let one listener failure stop others
                    System.err.println(
                            "[StaticNodeDiscovery] nodeDownListener failed for "
                                    + nodeName
                                    + ": "
                                    + e.getMessage());
                }
            }
        }
    }

    @Override
    public void onNodeUp(String nodeName) {
        NodeInfo current = knownNodes.get(nodeName);
        if (current != null && current.status() != NodeInfo.NodeStatus.HEALTHY) {
            NodeInfo upNode = current.withStatus(NodeInfo.NodeStatus.HEALTHY);
            knownNodes.put(nodeName, upNode);
            backend.storeNode(upNode);

            // Notify listeners
            for (Consumer<String> listener : nodeUpListeners) {
                try {
                    listener.accept(nodeName);
                } catch (Exception e) {
                    // Log and continue
                    System.err.println(
                            "[StaticNodeDiscovery] nodeUpListener failed for "
                                    + nodeName
                                    + ": "
                                    + e.getMessage());
                }
            }
        }
    }

    @Override
    public void startHealthChecks() {
        if (running) {
            return; // Already started
        }
        running = true;

        healthCheckScheduler.scheduleAtFixedRate(
                this::runHealthChecks,
                healthCheckInterval.toMillis(),
                healthCheckInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Add a listener for node down events.
     *
     * <p>Listeners are called in the order they were added. Each listener is invoked in a try-catch
     * block — failures in one listener do not prevent other listeners from running.
     *
     * @param listener the callback to invoke when a node goes down (must not be null)
     * @throws NullPointerException if listener is null
     */
    public void addNodeDownListener(Consumer<String> listener) {
        if (listener == null) throw new NullPointerException("listener must not be null");
        nodeDownListeners.add(listener);
    }

    /**
     * Add a listener for node up events.
     *
     * <p>Listeners are called in the order they were added. Each listener is invoked in a try-catch
     * block — failures in one listener do not prevent other listeners from running.
     *
     * @param listener the callback to invoke when a node comes up (must not be null)
     * @throws NullPointerException if listener is null
     */
    public void addNodeUpListener(Consumer<String> listener) {
        if (listener == null) throw new NullPointerException("listener must not be null");
        nodeUpListeners.add(listener);
    }

    /**
     * Update the heartbeat timestamp for this node.
     *
     * <p>Should be called periodically by the application to indicate this node is still alive.
     * Typically called every 1-2 seconds from a background thread.
     */
    public void sendHeartbeat() {
        backend.updateHeartbeat(thisNodeName, Instant.now());
    }

    @Override
    public void shutdown() {
        if (!running) {
            return; // Already stopped
        }
        running = false;

        healthCheckScheduler.shutdown();
        try {
            if (!healthCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                healthCheckScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthCheckScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Run health checks on all known nodes. */
    private void runHealthChecks() {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(heartbeatTimeout);
        Instant downThreshold = now.minus(degradedTimeout);

        // Find nodes with stale heartbeats
        List<NodeInfo> staleNodes = backend.findStaleNodes(staleThreshold);

        for (NodeInfo node : staleNodes) {
            if (node.lastHeartbeat().isBefore(downThreshold)) {
                // Node has exceeded degraded timeout — mark as DOWN
                onNodeDown(node.nodeName());
            } else if (node.status() == NodeInfo.NodeStatus.HEALTHY) {
                // Node has missed heartbeats but not yet timed out — mark as DEGRADED
                NodeInfo degradedNode = node.withStatus(NodeInfo.NodeStatus.DEGRADED);
                knownNodes.put(node.nodeName(), degradedNode);
                backend.storeNode(degradedNode);
            }
        }

        // Check for recovered nodes
        for (NodeInfo node : knownNodes.values()) {
            if (node.status() == NodeInfo.NodeStatus.DEGRADED
                    && !node.lastHeartbeat().isBefore(staleThreshold)) {
                // Node has resumed heartbeats — mark as HEALTHY
                onNodeUp(node.nodeName());
            }
        }
    }

    /**
     * Get the name of this node.
     *
     * @return this node's name
     */
    public String thisNodeName() {
        return thisNodeName;
    }

    /**
     * Check if health checks are currently running.
     *
     * @return true if health checks are active
     */
    public boolean isRunning() {
        return running;
    }
}
