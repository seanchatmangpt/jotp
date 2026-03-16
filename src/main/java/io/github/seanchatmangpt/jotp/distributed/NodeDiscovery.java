package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.util.List;

/**
 * Interface for cluster membership management and node health monitoring.
 *
 * <p>Manages the discovery and health tracking of nodes in a distributed JOTP cluster. Provides
 * callbacks for node up/down events and coordinates periodic health checks across the cluster.
 *
 * <p><strong>Key responsibilities:</strong>
 *
 * <ul>
 *   <li>Node registration with unique names and addresses
 *   <li>Health monitoring via periodic heartbeats
 *   <li>Event notification when nodes join or leave the cluster
 *   <li>Cluster membership queries for healthy nodes
 * </ul>
 *
 * <p><strong>Integration with Failover:</strong> When a node goes down, the {@link
 * #onNodeDown(String)} callback triggers process migration via {@link FailoverController}. The
 * discovery system provides the cluster view needed to select target nodes for migration.
 *
 * <p><strong>Thread Safety:</strong> All implementations must be thread-safe. Callbacks may be
 * invoked concurrently from health check threads.
 *
 * @see StaticNodeDiscovery
 * @see FailoverController
 * @see NodeDiscoveryBackend
 */
public interface NodeDiscovery {

    /**
     * Register this node with the cluster.
     *
     * <p>Stores the node's information in the discovery backend and begins health monitoring. The
     * node name must be unique across the cluster. If a node with the same name already exists,
     * this may indicate a stale registration from a previous incarnation.
     *
     * @param nodeName unique name for this node (e.g., {@code "node1@host"})
     * @param nodeAddress connection address (host:port format, e.g., {@code "localhost:8080"})
     * @return {@link Result#ok()} on successful registration, or {@link Result#err(Exception)} if
     *     registration fails
     * @throws NullPointerException if nodeName or nodeAddress is null
     * @throws IllegalArgumentException if nodeName is empty
     */
    Result<Void, Exception> registerNode(String nodeName, String nodeAddress);

    /**
     * Get all currently healthy nodes in the cluster.
     *
     * <p>Returns a snapshot of nodes that have recent heartbeats and are considered available for
     * hosting processes. The list excludes nodes marked as {@link NodeInfo.NodeStatus#DOWN} or
     * {@link NodeInfo.NodeStatus#DEGRADED}.
     *
     * <p>This method is called by {@link FailoverController} to select target nodes for process
     * migration.
     *
     * @return list of healthy node names (empty list if no nodes are healthy)
     */
    List<String> getHealthyNodes();

    /**
     * Callback when a node goes down (detected by health check).
     *
     * <p>Invoked when a node's heartbeat exceeds the configured timeout threshold. This typically
     * indicates a JVM crash, network partition, or other failure. The implementation should:
     *
     * <ol>
     *   <li>Mark the node as down in the discovery backend
     *   <li>Notify registered {@code nodeDownListeners}
     *   <li>Trigger failover for processes hosted on the failed node
     * </ol>
     *
     * <p><strong>Note:</strong> This callback may be invoked multiple times for the same node if
     * health checks continue while the node is down. Implementations should be idempotent.
     *
     * @param nodeName the node that went down (will not be null)
     */
    void onNodeDown(String nodeName);

    /**
     * Callback when a node comes back up.
     *
     * <p>Invoked when a previously down node resumes sending heartbeats. This may indicate a
     * restarted JVM, recovered network connection, or other recovery. The implementation should:
     *
     * <ol>
     *   <li>Mark the node as healthy in the discovery backend
     *   <li>Notify registered {@code nodeUpListeners}
     *   <li>Allow the node to participate in future migrations
     * </ol>
     *
     * <p><strong>Note:</strong> A recovered node may have lost its in-memory state. Processes that
     * were migrated away during the downtime are not automatically migrated back.
     *
     * @param nodeName the node that recovered (will not be null)
     */
    void onNodeUp(String nodeName);

    /**
     * Start periodic health checks of all nodes.
     *
     * <p>Begins a background scheduler that periodically checks the heartbeat timestamps of all
     * registered nodes. Nodes with stale heartbeats (older than the configured timeout) trigger
     * {@link #onNodeDown(String)} callbacks. Nodes that resume heartbeating trigger {@link
     * #onNodeUp(String)} callbacks.
     *
     * <p>The health check interval should be configurable via the implementation constructor. A
     * typical interval is 1-5 seconds with a timeout threshold of 2-3 missed heartbeats.
     *
     * <p><strong>Thread Safety:</strong> This method must be safe to call multiple times.
     * Subsequent calls after the first should be no-ops.
     */
    void startHealthChecks();

    /**
     * Stop health checks and cleanup resources.
     *
     * <p>Stops the background health check scheduler and releases any resources held by the
     * discovery system. After shutdown, no further node up/down callbacks will be invoked.
     *
     * <p>This method should be called from a JVM shutdown hook registered with {@link
     * io.github.seanchatmangpt.jotp.JvmShutdownManager} to ensure clean shutdown.
     *
     * <p><strong>Thread Safety:</strong> This method must be idempotent. Multiple calls should have
     * no additional effect beyond the first.
     */
    void shutdown();
}
