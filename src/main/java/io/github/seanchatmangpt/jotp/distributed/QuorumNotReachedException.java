package io.github.seanchatmangpt.jotp.distributed;

/**
 * Exception thrown when a quorum cannot be reached for a distributed operation.
 *
 * <p>This exception indicates that the required number of nodes did not acknowledge the operation
 * within the timeout period, or there are insufficient healthy nodes in the cluster to form a
 * quorum.
 *
 * <p><strong>Common Causes:</strong>
 *
 * <ul>
 *   <li>Network partition isolating nodes
 *   <li>Node failures reducing available nodes below quorum
 *   <li>Timeout before all acknowledgments received
 *   <li>Misconfigured quorum size exceeding cluster size
 * </ul>
 *
 * <p><strong>Recovery:</strong> The caller may:
 *
 * <ul>
 *   <li>Retry with a smaller quorum (trades durability for availability)
 *   <li>Wait for more nodes to become healthy
 *   <li>Fail the operation and propagate the error upstream
 * </ul>
 */
public class QuorumNotReachedException extends Exception {

    private final int requiredQuorum;
    private final int acknowledgedCount;
    private final int availableNodes;

    /**
     * Create a new quorum not reached exception.
     *
     * @param message description of the failure
     * @param requiredQuorum the quorum that was required
     * @param acknowledgedCount the number of nodes that actually acknowledged
     * @param availableNodes the total number of available nodes in the cluster
     */
    public QuorumNotReachedException(
            String message, int requiredQuorum, int acknowledgedCount, int availableNodes) {
        super(message);
        this.requiredQuorum = requiredQuorum;
        this.acknowledgedCount = acknowledgedCount;
        this.availableNodes = availableNodes;
    }

    /**
     * Create a new quorum not reached exception with a cause.
     *
     * @param message description of the failure
     * @param cause the underlying cause
     * @param requiredQuorum the quorum that was required
     * @param acknowledgedCount the number of nodes that actually acknowledged
     * @param availableNodes the total number of available nodes in the cluster
     */
    public QuorumNotReachedException(
            String message,
            Throwable cause,
            int requiredQuorum,
            int acknowledgedCount,
            int availableNodes) {
        super(message, cause);
        this.requiredQuorum = requiredQuorum;
        this.acknowledgedCount = acknowledgedCount;
        this.availableNodes = availableNodes;
    }

    /**
     * Get the quorum size that was required.
     *
     * @return the required quorum
     */
    public int getRequiredQuorum() {
        return requiredQuorum;
    }

    /**
     * Get the number of nodes that acknowledged before timeout.
     *
     * @return the acknowledged count
     */
    public int getAcknowledgedCount() {
        return acknowledgedCount;
    }

    /**
     * Get the total number of available nodes in the cluster.
     *
     * @return the available node count
     */
    public int getAvailableNodes() {
        return availableNodes;
    }

    @Override
    public String toString() {
        return String.format(
                "QuorumNotReachedException[required=%d, acknowledged=%d, available=%d]: %s",
                requiredQuorum, acknowledgedCount, availableNodes, getMessage());
    }
}
