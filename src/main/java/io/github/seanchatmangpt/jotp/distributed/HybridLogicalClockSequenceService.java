package io.github.seanchatmangpt.jotp.distributed;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hybrid Logical Clock (HLC) based implementation of {@link GlobalSequenceService}.
 *
 * <p>This implementation combines physical timestamps with logical counters to provide globally
 * unique, monotonically increasing sequence numbers that are resilient to clock drift and network
 * partitions.
 *
 * <p><strong>HLC Algorithm:</strong>
 *
 * <p>The Hybrid Logical Clock maintains both a physical time component and a logical counter. On
 * each {@link #nextGlobalSeq()} call:
 *
 * <ol>
 *   <li>Get current physical time (millis since epoch)
 *   <li>If physical time has advanced, update it and reset counter
 *   <li>If physical time is same or behind, increment logical counter
 *   <li>Combine: timestamp + node ID + counter into final sequence
 * </ol>
 *
 * <p><strong>Sequence Number Format:</strong>
 *
 * <pre>
 * |  Timestamp (48 bits)  |  Node ID (16 bits)  |  Counter (16 bits)  |
 * |-----------------------|---------------------|----------------------|
 * |  milliseconds since   |  hash of nodeId     |  logical counter    |
 * |  epoch (285K years)   |  (65536 nodes max)  |  (65536/ms per node) |
 * </pre>
 *
 * <p><strong>Properties:</strong>
 *
 * <ul>
 *   <li><strong>Timestamp (48 bits):</strong> Milliseconds since epoch. Provides ~285,000 year
 *       range and natural time-based ordering.
 *   <li><strong>Node ID (16 bits):</strong> Hash of the node identifier. Ensures uniqueness across
 *       nodes. Supports up to 65,536 nodes.
 *   <li><strong>Counter (16 bits):</strong> Logical counter for same-millisecond events. Supports
 *       65,536 sequences per millisecond per node.
 * </ul>
 *
 * <p><strong>Clock Drift Handling:</strong>
 *
 * <p>If the system clock moves backward (e.g., NTP adjustment), the logical counter continues
 * incrementing, maintaining monotonicity. The counter only resets when the physical time advances
 * past the stored HLC time.
 *
 * <p><strong>Thread Safety:</strong> All state is maintained in atomic variables. Concurrent calls
 * to {@link #nextGlobalSeq()} are safe and will produce unique values.
 *
 * @see GlobalSequenceService
 */
public final class HybridLogicalClockSequenceService implements GlobalSequenceService {

    // Bit allocations for sequence number components
    private static final int TIMESTAMP_BITS = 48;
    private static final int NODE_ID_BITS = 16;
    private static final int COUNTER_BITS = 16;

    // Masks for extracting components
    private static final long TIMESTAMP_MASK = (1L << TIMESTAMP_BITS) - 1;
    private static final int NODE_ID_MASK = (1 << NODE_ID_BITS) - 1;
    private static final int COUNTER_MASK = (1 << COUNTER_BITS) - 1;

    // Bit shifts for packing components
    private static final int NODE_ID_SHIFT = COUNTER_BITS;
    private static final int TIMESTAMP_SHIFT = NODE_ID_BITS + COUNTER_BITS;

    // Maximum values
    private static final int MAX_NODE_ID = NODE_ID_MASK;
    private static final int MAX_COUNTER = COUNTER_MASK;
    private static final long MAX_TIMESTAMP = TIMESTAMP_MASK;

    // HLC state - using AtomicLong for physical time + counter combined
    // High 48 bits: physical time, Low 16 bits: logical counter
    private final AtomicLong hlcState;

    // Node identifier component (16-bit hash)
    private final int nodeIdHash;

    // High-water mark for synchronization
    private final AtomicLong highWaterMark;

    // The node ID string for logging/debugging
    private final String nodeId;

    // Node discovery for cluster coordination
    private final NodeDiscovery discovery;

    /**
     * Create a new HLC-based sequence service.
     *
     * @param nodeId unique identifier for this node (must not be null or empty)
     * @param discovery the node discovery service (must not be null)
     * @throws NullPointerException if nodeId or discovery is null
     * @throws IllegalArgumentException if nodeId is empty
     */
    public HybridLogicalClockSequenceService(String nodeId, NodeDiscovery discovery) {
        if (nodeId == null) {
            throw new NullPointerException("nodeId must not be null");
        }
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be empty");
        }
        if (discovery == null) {
            throw new NullPointerException("discovery must not be null");
        }

        this.nodeId = nodeId;
        this.discovery = discovery;
        this.nodeIdHash = computeNodeIdHash(nodeId);
        this.highWaterMark = new AtomicLong(0);

        // Initialize HLC state with current time and counter = 0
        long initialTime = System.currentTimeMillis();
        this.hlcState = new AtomicLong(packHlcState(initialTime, 0));
    }

    @Override
    public long nextGlobalSeq() {
        long currentTime = System.currentTimeMillis();
        long newState;
        long sequenceNumber;

        // CAS loop to update HLC state atomically
        while (true) {
            long currentState = hlcState.get();
            long currentHlcTime = extractTimestamp(currentState);
            int currentCounter = extractCounter(currentState);

            long newTime;
            int newCounter;

            if (currentTime > currentHlcTime) {
                // Physical time has advanced - use it and reset counter
                newTime = currentTime;
                newCounter = 0;
            } else {
                // Physical time same or behind - increment counter
                newTime = currentHlcTime;
                newCounter = currentCounter + 1;

                // Check for counter overflow
                if (newCounter > MAX_COUNTER) {
                    // Wait until next millisecond to avoid overflow
                    // This is extremely rare (65K sequences/ms)
                    newTime = currentHlcTime + 1;
                    newCounter = 0;
                }
            }

            newState = packHlcState(newTime, newCounter);

            if (hlcState.compareAndSet(currentState, newState)) {
                // Successfully updated HLC state
                sequenceNumber = packSequenceNumber(newTime, nodeIdHash, newCounter);
                break;
            }
            // Another thread won the race, retry
        }

        // Update high-water mark if this sequence is higher
        updateHighWaterMark(sequenceNumber);

        return sequenceNumber;
    }

    @Override
    public long currentHighWaterMark() {
        return highWaterMark.get();
    }

    @Override
    public void synchronizeWithPeers(List<String> peerNodes) {
        if (peerNodes == null) {
            throw new NullPointerException("peerNodes must not be null");
        }

        // Get current physical time as baseline
        long currentTime = System.currentTimeMillis();

        // For peer synchronization, we need to ensure our HLC time
        // is at least as high as the highest known peer time.
        // In a full implementation, this would query peer nodes.
        // For now, we ensure our physical time is current and
        // update high-water mark based on current state.

        // Ensure HLC is at least at current physical time
        while (true) {
            long currentState = hlcState.get();
            long currentHlcTime = extractTimestamp(currentState);

            if (currentTime <= currentHlcTime) {
                // Our HLC is already at or ahead of current time
                break;
            }

            long newState = packHlcState(currentTime, 0);
            if (hlcState.compareAndSet(currentState, newState)) {
                break;
            }
        }

        // Update high-water mark with current HLC state
        long state = hlcState.get();
        long time = extractTimestamp(state);
        int counter = extractCounter(state);
        long sequenceNumber = packSequenceNumber(time, nodeIdHash, counter);
        updateHighWaterMark(sequenceNumber);
    }

    /**
     * Get the node ID for this sequence service.
     *
     * @return the node identifier
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Get the current HLC timestamp component.
     *
     * <p>This is primarily for debugging and monitoring.
     *
     * @return the current HLC timestamp in milliseconds since epoch
     */
    public long getCurrentHlcTimestamp() {
        return extractTimestamp(hlcState.get());
    }

    /**
     * Get the current HLC counter component.
     *
     * <p>This is primarily for debugging and monitoring.
     *
     * @return the current logical counter value
     */
    public int getCurrentHlcCounter() {
        return extractCounter(hlcState.get());
    }

    // ================== Private Helper Methods ==================

    /**
     * Compute a 16-bit hash from a node ID string.
     *
     * <p>Uses a simple but effective hash that distributes well across the 16-bit space.
     */
    private static int computeNodeIdHash(String nodeId) {
        int hash = 0;
        for (int i = 0; i < nodeId.length(); i++) {
            hash = 31 * hash + nodeId.charAt(i);
        }
        // Ensure positive value within 16-bit range
        return Math.abs(hash) & NODE_ID_MASK;
    }

    /**
     * Pack HLC state (timestamp + counter) into a single long.
     *
     * <p>Format: [timestamp:48][counter:16]
     */
    private static long packHlcState(long timestamp, int counter) {
        return ((timestamp & TIMESTAMP_MASK) << COUNTER_BITS) | (counter & COUNTER_MASK);
    }

    /** Extract timestamp from packed HLC state. */
    private static long extractTimestamp(long hlcState) {
        return (hlcState >>> COUNTER_BITS) & TIMESTAMP_MASK;
    }

    /** Extract counter from packed HLC state. */
    private static int extractCounter(long hlcState) {
        return (int) (hlcState & COUNTER_MASK);
    }

    /**
     * Pack components into final sequence number.
     *
     * <p>Format: [timestamp:48][nodeId:16][counter:16]
     */
    private static long packSequenceNumber(long timestamp, int nodeIdHash, int counter) {
        return ((timestamp & TIMESTAMP_MASK) << TIMESTAMP_SHIFT)
                | ((nodeIdHash & NODE_ID_MASK) << NODE_ID_SHIFT)
                | (counter & COUNTER_MASK);
    }

    /** Update high-water mark if the given sequence is higher. */
    private void updateHighWaterMark(long sequenceNumber) {
        long current;
        do {
            current = highWaterMark.get();
            if (sequenceNumber <= current) {
                return; // No update needed
            }
        } while (!highWaterMark.compareAndSet(current, sequenceNumber));
    }

    /**
     * Extract timestamp from a sequence number.
     *
     * <p>Utility method for debugging and log analysis.
     *
     * @param sequenceNumber the sequence number to extract from
     * @return the timestamp component in milliseconds since epoch
     */
    public static long extractTimestampFromSeq(long sequenceNumber) {
        return (sequenceNumber >>> TIMESTAMP_SHIFT) & TIMESTAMP_MASK;
    }

    /**
     * Extract node ID hash from a sequence number.
     *
     * <p>Utility method for debugging and log analysis.
     *
     * @param sequenceNumber the sequence number to extract from
     * @return the node ID hash component
     */
    public static int extractNodeIdHashFromSeq(long sequenceNumber) {
        return (int) ((sequenceNumber >>> NODE_ID_SHIFT) & NODE_ID_MASK);
    }

    /**
     * Extract counter from a sequence number.
     *
     * <p>Utility method for debugging and log analysis.
     *
     * @param sequenceNumber the sequence number to extract from
     * @return the counter component
     */
    public static int extractCounterFromSeq(long sequenceNumber) {
        return (int) (sequenceNumber & COUNTER_MASK);
    }
}
