package io.github.seanchatmangpt.jotp.persistence;

import static java.util.Objects.requireNonNull;

import io.github.seanchatmangpt.jotp.SequencedState;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Writes state and ACK (processed marker) atomically to prevent the dual-write problem.
 *
 * <p><strong>The Dual-Write Problem:</strong> When a JVM crashes mid-write to RocksDB:
 *
 * <ul>
 *   <li>Message may be in the event log
 *   <li>But ACK (processed marker) may be missing
 *   <li>On restart: Message is replayed as unprocessed
 *   <li>Duplicate processing is INEVITABLE
 * </ul>
 *
 * <p><strong>The Solution:</strong> Atomic batch writes with idempotence checks.
 *
 * <p>This class ensures that state updates and their corresponding ACK markers are written in a
 * single atomic batch via RocksDB's WriteBatch. If the JVM crashes mid-write, RocksDB's Write-Ahead
 * Log (WAL) ensures either both are persisted or neither.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create writer with RocksDB backend
 * AtomicStateWriter<CounterState> writer = new AtomicStateWriter<>(
 *     rocksDbBackend,
 *     new JsonSnapshotCodec<>(CounterState.class)
 * );
 *
 * // Atomic write: state + ACK in single batch
 * writer.writeAtomic("counter-001", newState, 42L);
 *
 * // Read with ACK verification
 * Optional<CounterState> state = writer.readWithAck("counter-001");
 *
 * // Check last processed sequence
 * long lastSeq = writer.getLastProcessed("counter-001");
 * }</pre>
 *
 * <p><strong>Consistency Guarantees:</strong>
 *
 * <ul>
 *   <li>If ACK exists but state doesn't: treated as corrupt, returns empty
 *   <li>If state exists but ACK doesn't: treated as uncommitted, returns empty
 *   <li>If state.seq != ACK seq: uses ACK as source of truth (state may be from crashed write)
 * </ul>
 *
 * @param <S> the state type, must implement {@link SequencedState}
 * @see SequencedState
 * @see RocksDBBackend
 */
public final class AtomicStateWriter<S extends SequencedState> {

    private final RocksDBBackend backend;
    private final SnapshotCodec<S> codec;

    /**
     * Create a new AtomicStateWriter.
     *
     * @param backend the RocksDB backend with ACK support
     * @param codec the codec for serializing/deserializing state
     * @throws NullPointerException if backend or codec is null
     */
    public AtomicStateWriter(RocksDBBackend backend, SnapshotCodec<S> codec) {
        this.backend = requireNonNull(backend, "backend must not be null");
        this.codec = requireNonNull(codec, "codec must not be null");
    }

    /**
     * Write state and ACK atomically in a single WriteBatch.
     *
     * <p>This is the critical operation for crash safety. The state and its corresponding ACK
     * marker are written in a single atomic batch. If the JVM crashes during this operation,
     * RocksDB's WAL ensures either both are persisted or neither.
     *
     * <p><strong>Important:</strong> The sequence number in the ACK must match the sequence number
     * stored in the state for consistency verification during recovery.
     *
     * @param key the entity key
     * @param state the state to write
     * @param seqNum the sequence number to record in the ACK
     * @throws PersistenceException if the write fails
     */
    public void writeAtomic(String key, S state, long seqNum) throws PersistenceException {
        requireNonNull(key, "key must not be null");
        requireNonNull(state, "state must not be null");

        try {
            byte[] stateBytes = codec.encode(state);
            byte[] ackBytes = String.valueOf(seqNum).getBytes();

            backend.writeAtomic(key, stateBytes, ackBytes);
        } catch (Exception e) {
            throw new PersistenceException("Failed to write atomic state for key: " + key, e);
        }
    }

    /**
     * Read state with ACK verification.
     *
     * <p>Loads both the state and its ACK marker, verifying consistency. If the ACK exists but the
     * state doesn't (or vice versa), the data is considered corrupt and empty is returned.
     *
     * <p><strong>Consistency Check:</strong> If the state's sequence number doesn't match the ACK's
     * sequence number, the state may be from a partially completed write. In this case, the ACK is
     * considered the source of truth.
     *
     * @param key the entity key
     * @return Optional containing the verified state, or empty if inconsistent/not found
     * @throws PersistenceException if the read fails
     */
    public Optional<S> readWithAck(String key) throws PersistenceException {
        requireNonNull(key, "key must not be null");

        try {
            // Read ACK first - if no ACK, no committed state exists
            Optional<Long> ackSeq = backend.getAckSequence(key);
            if (ackSeq.isEmpty()) {
                return Optional.empty();
            }

            // Read state
            Optional<byte[]> stateBytes = backend.load(key);
            if (stateBytes.isEmpty()) {
                // ACK exists but no state - inconsistent, treat as not found
                return Optional.empty();
            }

            S state = codec.decode(stateBytes.get());

            // Verify consistency: state sequence should match ACK
            long stateSeq = state.lastProcessedSeq();
            long ackSeqValue = ackSeq.get();

            if (stateSeq != ackSeqValue) {
                // State and ACK mismatch - this can happen if JVM crashed during write
                // The ACK is the source of truth for what was fully processed
                // Log warning but return empty - caller should rebuild from events
                System.err.println(
                        "[JOTP] State/ACK mismatch for key "
                                + key
                                + ": stateSeq="
                                + stateSeq
                                + ", ackSeq="
                                + ackSeqValue
                                + ". Treating as uncommitted.");
                return Optional.empty();
            }

            return Optional.of(state);
        } catch (Exception e) {
            throw new PersistenceException("Failed to read state with ACK for key: " + key, e);
        }
    }

    /**
     * Get the last processed sequence number for an entity.
     *
     * <p>This is the ACK value - the sequence number of the last successfully processed and
     * committed message. Messages with sequence numbers less than or equal to this should be
     * skipped during replay.
     *
     * @param key the entity key
     * @return the last processed sequence number, or 0 if no ACK exists
     * @throws PersistenceException if the read fails
     */
    public long getLastProcessed(String key) throws PersistenceException {
        requireNonNull(key, "key must not be null");

        return backend.getAckSequence(key).orElse(0L);
    }

    /**
     * Check if a message with the given sequence number is a duplicate.
     *
     * <p>A message is a duplicate if its sequence number is less than or equal to the last
     * processed sequence number stored in the ACK.
     *
     * @param key the entity key
     * @param messageSeq the message sequence number to check
     * @return true if the message is a duplicate and should be skipped
     * @throws PersistenceException if the read fails
     */
    public boolean isDuplicate(String key, long messageSeq) throws PersistenceException {
        long lastProcessed = getLastProcessed(key);
        return messageSeq <= lastProcessed;
    }

    /**
     * Clear the ACK marker for an entity.
     *
     * <p>Use with caution - this removes the idempotence protection and may cause messages to be
     * reprocessed during recovery.
     *
     * @param key the entity key
     * @throws PersistenceException if the delete fails
     */
    public void clearAck(String key) throws PersistenceException {
        requireNonNull(key, "key must not be null");
        backend.deleteAck(key);
    }

    /**
     * Get the backup path for a given file path.
     *
     * <p>Static utility method for generating backup file paths.
     *
     * @param filePath the original file path
     * @return the backup file path with .bak extension
     */
    public static Path getBackupPath(Path filePath) {
        if (filePath == null) {
            throw new NullPointerException("filePath must not be null");
        }
        return filePath.resolveSibling(filePath.getFileName().toString() + ".bak");
    }
}
