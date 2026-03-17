package io.github.seanchatmangpt.jotp.distributed;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Distributed message log for cross-node message replication with quorum-based writes.
 *
 * <p>Provides a replicated log abstraction that ensures messages are durably stored across multiple
 * nodes before acknowledging to the sender. This implements the replicated log pattern common in
 * distributed systems for fault-tolerant message delivery.
 *
 * <p><strong>Key Features:</strong>
 *
 * <ul>
 *   <li>Quorum-based writes for fault tolerance
 *   <li>Global sequence numbers for total ordering
 *   <li>Subscription-based message consumption
 *   <li>Replay capability for recovery
 * </ul>
 *
 * <p><strong>Quorum Semantics:</strong>
 *
 * <p>When appending a message with quorum=N, the operation blocks until N nodes (including the
 * local node) have durably written the message. The returned {@link CompletableFuture} completes
 * when the quorum is reached, or fails if the quorum cannot be achieved.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * DistributedMessageLog<OrderEvent> log = new RocksDBDistributedMessageLog<>(
 *     rocksDbBackend,
 *     nodeId,
 *     nodeDiscovery
 * );
 *
 * // Append with quorum of 2 (requires 2 nodes to acknowledge)
 * CompletableFuture<AckResult> future = log.append(orderEvent, 2);
 *
 * // Subscribe to incoming messages
 * log.subscribe(entry -> {
 *     System.out.println("Received: " + entry.message() + " from " + entry.sourceNode());
 * });
 *
 * // Replay missed messages after restart
 * long lastSeq = loadLastProcessedSeq();
 * List<LogEntry<OrderEvent>> missed = log.getMessagesAfter(lastSeq);
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> All implementations must be thread-safe. Multiple threads may
 * append concurrently, and subscriptions may be added/removed at any time.
 *
 * @param <M> the message type
 * @see LogEntry
 * @see ReplicationStatus
 * @see AckResult
 */
public interface DistributedMessageLog<M> {

    /**
     * Append a message to the distributed log with quorum-based replication.
     *
     * <p>Replicates the message to the specified quorum of nodes before completing the future. The
     * message is assigned a global sequence number that provides total ordering across the cluster.
     *
     * <p><strong>Quorum Behavior:</strong>
     *
     * <ul>
     *   <li>quorum=1: Local write only (fastest, least durable)
     *   <li>quorum=2: Requires 2 nodes (balances speed and durability)
     *   <li>quorum=N: Requires N nodes to acknowledge
     * </ul>
     *
     * <p>If the quorum cannot be reached (e.g., not enough healthy nodes), the future completes
     * exceptionally with {@link QuorumNotReachedException}.
     *
     * @param message the message to append (must not be null)
     * @param quorum the number of nodes that must acknowledge (must be >= 1)
     * @return a future that completes with the acknowledgment result when quorum is reached
     * @throws NullPointerException if message is null
     * @throws IllegalArgumentException if quorum < 1
     */
    CompletableFuture<AckResult> append(M message, int quorum);

    /**
     * Subscribe to messages from the distributed log.
     *
     * <p>The handler is invoked for each message that is committed to the log. Messages are
     * delivered in global sequence order. The handler may be called concurrently from multiple
     * threads for different messages.
     *
     * <p><strong>Error Handling:</strong> If the handler throws an exception, the exception is
     * logged but does not affect other handlers or message delivery. Implementations should handle
     * their own errors appropriately.
     *
     * @param handler the handler to invoke for each committed message (must not be null)
     * @throws NullPointerException if handler is null
     */
    void subscribe(Consumer<LogEntry<M>> handler);

    /**
     * Get messages after a given sequence number for replay.
     *
     * <p>Retrieves all committed messages with sequence numbers greater than the specified value.
     * This is used for recovery after a node restart to catch up on missed messages.
     *
     * <p><strong>Ordering:</strong> Messages are returned in ascending sequence number order.
     *
     * <p><strong>Availability:</strong> Only committed messages (status={@link
     * ReplicationStatus.Committed}) are returned. Pending or failed messages are excluded.
     *
     * @param sequenceNumber the sequence number to start after (exclusive)
     * @return list of committed messages after the sequence number, ordered by sequence
     */
    List<LogEntry<M>> getMessagesAfter(long sequenceNumber);

    /**
     * Get the last globally committed sequence number.
     *
     * <p>Returns the highest sequence number that has been committed to the quorum. This can be
     * used to track how far behind a consumer is, or to determine the starting point for replay.
     *
     * @return the last committed sequence number, or 0 if no messages have been committed
     */
    long lastCommittedSeq();

    /**
     * A single entry in the distributed message log.
     *
     * @param <M> the message type
     * @param globalSeq the globally unique sequence number for total ordering
     * @param message the actual message payload
     * @param sourceNode the node that originally appended this message
     * @param timestamp when the message was appended
     * @param status the current replication status
     */
    record LogEntry<M>(
            long globalSeq,
            M message,
            String sourceNode,
            Instant timestamp,
            ReplicationStatus status) {}

    /**
     * Sealed interface for message replication status.
     *
     * <p>Tracks the lifecycle of a message through the replication process:
     *
     * <ol>
     *   <li>{@link Pending} - Message is being replicated to nodes
     *   <li>{@link Committed} - Quorum has been reached, message is durable
     *   <li>{@link Failed} - Replication failed, message is not durable
     * </ol>
     *
     * <p>Use pattern matching to handle each status:
     *
     * <pre>{@code
     * switch (entry.status()) {
     *     case ReplicationStatus.Pending p -> handlePending(entry, p.acknowledgedCount());
     *     case ReplicationStatus.Committed c -> processMessage(entry);
     *     case ReplicationStatus.Failed f -> handleFailure(entry, f.reason());
     * }
     * }</pre>
     */
    sealed interface ReplicationStatus
            permits ReplicationStatus.Pending,
                    ReplicationStatus.Committed,
                    ReplicationStatus.Failed {

        /**
         * Message is pending replication to quorum.
         *
         * @param acknowledgedCount number of nodes that have acknowledged
         * @param requiredQuorum the quorum required for commit
         */
        record Pending(int acknowledgedCount, int requiredQuorum) implements ReplicationStatus {}

        /**
         * Message has been committed to quorum and is durable.
         *
         * @param acknowledgedCount final number of nodes that acknowledged
         * @param committedAt when the quorum was reached
         */
        record Committed(int acknowledgedCount, Instant committedAt) implements ReplicationStatus {}

        /**
         * Replication failed - quorum could not be reached.
         *
         * @param acknowledgedCount number of nodes that acknowledged before failure
         * @param reason description of why replication failed
         */
        record Failed(int acknowledgedCount, String reason) implements ReplicationStatus {}
    }

    /**
     * Result of a quorum-based append operation.
     *
     * @param globalSeq the assigned global sequence number
     * @param acknowledgedCount number of nodes that acknowledged
     * @param committedAt when the quorum was reached
     */
    record AckResult(long globalSeq, int acknowledgedCount, Instant committedAt) {}
}
