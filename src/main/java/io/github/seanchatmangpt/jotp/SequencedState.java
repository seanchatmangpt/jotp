package io.github.seanchatmangpt.jotp;

/**
 * Interface for state that tracks the last processed sequence number.
 *
 * <p>Used in conjunction with {@link SequencedMessage} to implement idempotent message processing
 * for JVM crash survival. When a JVM crashes mid-write and messages are replayed, the sequence
 * number allows handlers to detect and skip duplicates.
 *
 * <p><strong>Idempotence Pattern:</strong>
 *
 * <pre>{@code
 * // State implementation
 * record CounterState(long value, long lastProcessedSeq) implements SequencedState {
 *     @Override
 *     public long lastProcessedSeq() {
 *         return lastProcessedSeq;
 *     }
 * }
 *
 * // Handler with idempotence check
 * CounterState handle(CounterState state, SequencedMessage msg) {
 *     if (msg.sequenceNumber() <= state.lastProcessedSeq()) {
 *         return state;  // Skip duplicate
 *     }
 *     return new CounterState(state.value() + 1, msg.sequenceNumber());
 * }
 * }</pre>
 *
 * @see SequencedMessage
 * @see DurableState
 */
public interface SequencedState {

    /**
     * Get the sequence number of the last successfully processed message.
     *
     * <p>This value is used to detect duplicate messages during recovery or retry scenarios.
     * Messages with sequence numbers less than or equal to this value should be skipped.
     *
     * @return the last processed sequence number, or 0 if no messages have been processed
     */
    long lastProcessedSeq();
}
