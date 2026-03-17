package io.github.seanchatmangpt.jotp.distributed;

import java.time.Instant;

/**
 * Information about a node in the distributed cluster.
 *
 * <p>This record encapsulates all metadata tracked by the node discovery system for each cluster
 * member. It includes identification, addressing, timing, and health status information.
 *
 * <p><strong>Immutability:</strong> This is an immutable record. To update node information, create
 * a new {@code NodeInfo} instance with the updated fields. The discovery backend replaces the old
 * record with the new one via {@link NodeDiscoveryBackend#storeNode(NodeInfo)}.
 *
 * <p><strong>State Transitions:</strong>
 *
 * <pre>{@code
 * REGISTERED → HEALTHY (after first successful heartbeat)
 * HEALTHY → DEGRADED (after missed heartbeats but before timeout)
 * DEGRADED → DOWN (after timeout threshold exceeded)
 * DEGRADED → HEALTHY (if heartbeat resumes)
 * DOWN → HEALTHY (if node restarts and re-registers)
 * }</pre>
 *
 * @param nodeName unique name for this node (e.g., {@code "node1@host"})
 * @param nodeAddress connection address in {@code host:port} format
 * @param registeredAt timestamp when the node first registered with the cluster
 * @param lastHeartbeat timestamp of the most recent heartbeat (used for health detection)
 * @param status current health status of the node
 * @see NodeDiscoveryBackend
 * @see NodeInfo.NodeStatus
 */
public record NodeInfo(
        String nodeName,
        String nodeAddress,
        Instant registeredAt,
        Instant lastHeartbeat,
        NodeStatus status) {

    /**
     * Create a new NodeInfo with the current time as the registered timestamp.
     *
     * <p>Convenience factory for new node registrations. Sets both {@code registeredAt} and {@code
     * lastHeartbeat} to {@link Instant#now()} and status to {@link NodeStatus#HEALTHY}.
     *
     * @param nodeName unique name for this node
     * @param nodeAddress connection address in {@code host:port} format
     * @return a new NodeInfo with current timestamps and HEALTHY status
     * @throws NullPointerException if nodeName or nodeAddress is null
     */
    public static NodeInfo create(String nodeName, String nodeAddress) {
        Instant now = Instant.now();
        return new NodeInfo(nodeName, nodeAddress, now, now, NodeStatus.HEALTHY);
    }

    /**
     * Create a new NodeInfo with an updated heartbeat timestamp.
     *
     * <p>Convenience method for health check updates. Preserves all other fields and updates only
     * the heartbeat timestamp. The status remains unchanged — status transitions are handled
     * separately by the health check logic.
     *
     * @param newHeartbeat the new heartbeat timestamp
     * @return a new NodeInfo with the updated heartbeat
     * @throws NullPointerException if newHeartbeat is null
     */
    public NodeInfo withHeartbeat(Instant newHeartbeat) {
        return new NodeInfo(nodeName, nodeAddress, registeredAt, newHeartbeat, status);
    }

    /**
     * Create a new NodeInfo with an updated status.
     *
     * <p>Convenience method for status transitions. Preserves all other fields and updates only the
     * status. Used when marking nodes as DEGRADED, DOWN, or recovered to HEALTHY.
     *
     * @param newStatus the new status
     * @return a new NodeInfo with the updated status
     * @throws NullPointerException if newStatus is null
     */
    public NodeInfo withStatus(NodeStatus newStatus) {
        return new NodeInfo(nodeName, nodeAddress, registeredAt, lastHeartbeat, newStatus);
    }

    /**
     * Health status of a node in the cluster.
     *
     * <p>Tracks the operational state of each node as observed by the discovery system. Status
     * transitions are driven by heartbeat monitoring and health check policies.
     *
     * <p><strong>Status meanings:</strong>
     *
     * <ul>
     *   <li>{@code HEALTHY} - Node is actively sending heartbeats and available for work
     *   <li>{@code DEGRADED} - Node has missed recent heartbeats but hasn't timed out yet
     *   <li>{@code DOWN} - Node has exceeded the heartbeat timeout threshold and is considered
     *       failed
     * </ul>
     */
    public enum NodeStatus {
        /**
         * Node is healthy and actively participating in the cluster.
         *
         * <p>The node has recent heartbeats (within the normal interval) and is available to host
         * processes. This is the normal operating state for all cluster members.
         */
        HEALTHY,

        /**
         * Node is degraded due to missed heartbeats.
         *
         * <p>The node has failed to send recent heartbeats but hasn't exceeded the failure timeout
         * yet. This is a warning state — the node may recover (transient network issue) or may be
         * failing. Degraded nodes are not selected for new process allocations but existing
         * processes remain.
         */
        DEGRADED,

        /**
         * Node is down and considered failed.
         *
         * <p>The node has exceeded the heartbeat timeout threshold and is presumed crashed or
         * partitioned. Processes hosted on this node are migrated to healthy nodes via {@link
         * FailoverController}. A DOWN node can return to HEALTHY status if it restarts and
         * re-registers.
         */
        DOWN
    }
}
