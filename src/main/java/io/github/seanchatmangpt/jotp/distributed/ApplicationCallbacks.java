package io.github.seanchatmangpt.jotp.distributed;

/**
 * Lifecycle callbacks for a distributed application running on a node.
 *
 * <p>Implement this interface to define what happens when the application starts or stops on a
 * given node. The {@link DistributedNode} calls these methods as the application moves between
 * nodes according to the failover and takeover logic.
 *
 * <p>Erlang/OTP equivalent: the application callback module implementing {@code application}
 * behaviour, specifically {@code start/2} and {@code stop/1}.
 *
 * <p>All callbacks are invoked on virtual threads and must be thread-safe.
 */
public interface ApplicationCallbacks {

    /**
     * Called when this node becomes responsible for running the application.
     *
     * <p>The {@code mode} parameter distinguishes between the three startup scenarios:
     *
     * <ul>
     *   <li>{@link StartMode.Normal} — first start on highest-priority live node
     *   <li>{@link StartMode.Failover} — primary node failed; this node takes over after timeout
     *   <li>{@link StartMode.Takeover} — higher-priority node returned; reclaiming from lower node
     * </ul>
     *
     * @param mode the startup mode indicating why this node is starting the application
     */
    void onStart(StartMode mode);

    /**
     * Called when this node is no longer responsible for running the application.
     *
     * <p>This is invoked during two scenarios:
     *
     * <ul>
     *   <li>Coordinated stop via {@link DistributedNode#stop(String)} at all nodes
     *   <li>Takeover by a higher-priority node that reclaims the application
     * </ul>
     *
     * <p>Note: this is NOT called when the node itself crashes — crashes are handled by the
     * failover mechanism on peer nodes.
     */
    void onStop();
}
