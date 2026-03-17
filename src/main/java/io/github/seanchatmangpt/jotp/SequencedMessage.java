package io.github.seanchatmangpt.jotp;

/**
 * Interface for messages that carry a monotonically increasing sequence number.
 *
 * <p>Used in conjunction with {@link SequencedState} to implement idempotent message processing for
 * JVM crash survival. The sequence number enables handlers to detect and skip duplicate messages
 * during replay after a crash.
 *
 * <p><strong>Sequence Number Requirements:</strong>
 *
 * <ul>
 *   <li>Monotonically increasing within a single process/entity
 *   <li>Must be unique per message
 *   <li>Typically sourced from a persistent counter or timestamp-based generator
 * </ul>
 *
 * <p><strong>Implementation Example:</strong>
 *
 * <pre>{@code
 * // Message with sequence number
 * record IncrementCommand(long sequenceNumber, int amount) implements SequencedMessage {
 *     @Override
 *     public long sequenceNumber() {
 *         return sequenceNumber;
 *     }
 * }
 *
 * // Message factory ensuring monotonicity
 * class MessageFactory {
 *     private final AtomicLong seqGen = new AtomicLong(0);
 *
 *     IncrementCommand increment(int amount) {
 *         return new IncrementCommand(seqGen.incrementAndGet(), amount);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Idempotent Handler Pattern:</strong>
 *
 * <pre>{@code
 * S handle(S state, SequencedMessage msg) {
 *     // Duplicate detection
 *     if (msg.sequenceNumber() <= state.lastProcessedSeq()) {
 *         return state;  // Already processed - skip
 *     }
 *
 *     // Process message
 *     S newState = doWork(state, msg);
 *
 *     // Atomic write: state + ACK
 *     atomicWriter.writeAtomic(key, newState, msg.sequenceNumber());
 *
 *     return newState;
 * }
 * }</pre>
 *
 * @see SequencedState
 * @see DurableState
 */
public interface SequencedMessage {

    /**
     * Get the sequence number for this message.
     *
     * <p>The sequence number must be monotonically increasing within the context of the processing
     * entity. Messages with sequence numbers less than or equal to the state's {@link
     * SequencedState#lastProcessedSeq()} are considered duplicates.
     *
     * @return the unique, monotonically increasing sequence number for this message
     */
    long sequenceNumber();
}
