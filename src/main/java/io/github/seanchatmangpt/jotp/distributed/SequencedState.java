package io.github.seanchatmangpt.jotp.distributed;

/**
 * Interface for state objects that track sequence numbers for idempotence.
 *
 * <p>Used by the global registry to detect and recover from partial writes caused by JVM crashes.
 * Each state change increments the sequence number, and on recovery, the system can detect
 * inconsistencies between the state and its ACK.
 *
 * <p><strong>Idempotence Pattern:</strong>
 *
 * <pre>{@code
 * // On write: store both state and ACK atomically
 * batch.put(stateKey, serialize(state));
 * batch.put(ackKey, state.lastProcessedSeq());
 *
 * // On read: verify consistency
 * if (state.lastProcessedSeq() != ackSeq) {
 *     // Mismatch indicates crash during write
 *     // Use lower value for idempotent recovery
 * }
 * }</pre>
 *
 * @see GlobalProcRef
 * @see GlobalRegistryBackend
 */
public interface SequencedState {

    /**
     * Get the last processed sequence number for this state.
     *
     * <p>Used for idempotent recovery after crashes. The sequence number should increment with each
     * state change.
     *
     * @return the sequence number
     */
    long lastProcessedSeq();
}
