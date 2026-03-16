package io.github.seanchatmangpt.jotp.persistence;

import java.util.Optional;

/**
 * Backend interface for persisting process state.
 *
 * <p>Implementations provide durable storage for process state snapshots. Different backends can be
 * plugged in based on requirements (in-memory for testing, RocksDB for production, distributed
 * stores for clustering, etc.).
 *
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe. Multiple processes may
 * access the backend concurrently.
 *
 * <p><strong>Consistency:</strong> Write operations should be atomic. If a backend supports
 * transactions, writes should be transactional.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Initialize backend
 * PersistenceBackend backend = new RocksDBBackend("/data/jotp");
 *
 * // Save state
 * backend.save("counter-001", stateSnapshot);
 *
 * // Load state
 * Optional<byte[]> snapshot = backend.load("counter-001");
 *
 * // Delete state
 * backend.delete("counter-001");
 *
 * // Check existence
 * boolean exists = backend.exists("counter-001");
 *
 * // Close backend
 * backend.close();
 * }</pre>
 *
 * @see RocksDBBackend
 * @see InMemoryBackend
 */
public interface PersistenceBackend extends AutoCloseable {

    /**
     * Save a state snapshot.
     *
     * <p>If a snapshot already exists for the given key, it should be overwritten.
     *
     * @param key the unique key for this snapshot (e.g., process ID)
     * @param snapshot the serialized state data
     * @throws PersistenceException if the save operation fails
     * @throws IllegalArgumentException if key or snapshot is null
     */
    void save(String key, byte[] snapshot);

    /**
     * Load a state snapshot.
     *
     * @param key the unique key for the snapshot
     * @return Optional containing the snapshot data, or empty if not found
     * @throws PersistenceException if the load operation fails
     * @throws IllegalArgumentException if key is null
     */
    Optional<byte[]> load(String key);

    /**
     * Delete a state snapshot.
     *
     * <p>If the snapshot doesn't exist, this operation should succeed silently.
     *
     * @param key the unique key for the snapshot
     * @throws PersistenceException if the delete operation fails
     * @throws IllegalArgumentException if key is null
     */
    void delete(String key);

    /**
     * Check if a snapshot exists for the given key.
     *
     * @param key the unique key for the snapshot
     * @return true if a snapshot exists, false otherwise
     * @throws PersistenceException if the check operation fails
     * @throws IllegalArgumentException if key is null
     */
    boolean exists(String key);

    /**
     * List all keys with snapshots.
     *
     * <p>Returns an iterable of all keys that have persisted snapshots. This can be used for
     * recovery scans or bulk operations.
     *
     * @return iterable of all keys
     * @throws PersistenceException if the list operation fails
     */
    Iterable<String> listKeys();

    /**
     * Write state and ACK atomically in a single batch.
     *
     * <p>This is the critical operation for crash safety. The state and its corresponding ACK
     * marker are written in a single atomic batch. If the JVM crashes during this operation, the
     * backend's write-ahead log ensures either both are persisted or neither.
     *
     * <p><strong>The Dual-Write Problem:</strong> When a JVM crashes mid-write:
     *
     * <ul>
     *   <li>Message may be in event log
     *   <li>But ACK (processed marker) may be missing
     *   <li>On restart: Message replayed as unprocessed
     *   <li>Duplicate processing is INEVITABLE
     * </ul>
     *
     * <p><strong>The Solution:</strong> Atomic batch writes.
     *
     * @param key the entity key
     * @param stateBytes the serialized state
     * @param ackBytes the ACK marker (typically sequence number as bytes)
     * @throws PersistenceException if the write fails
     * @throws IllegalArgumentException if key or stateBytes or ackBytes is null
     */
    void writeAtomic(String key, byte[] stateBytes, byte[] ackBytes);

    /**
     * Get the ACK sequence number for an entity.
     *
     * <p>The ACK sequence is the sequence number of the last successfully processed and committed
     * message. Used for idempotence checking during message replay.
     *
     * @param key the entity key
     * @return Optional containing the sequence number, or empty if no ACK exists
     * @throws PersistenceException if the read fails
     * @throws IllegalArgumentException if key is null
     */
    Optional<Long> getAckSequence(String key);

    /**
     * Delete the ACK marker for an entity.
     *
     * <p>Use with caution - this removes idempotence protection.
     *
     * @param key the entity key
     * @throws PersistenceException if the delete fails
     * @throws IllegalArgumentException if key is null
     */
    void deleteAck(String key);

    /**
     * Close the backend and release resources.
     *
     * <p>After calling close, all other operations will throw PersistenceException. Multiple calls
     * to close should be idempotent.
     *
     * @throws Exception if closing fails
     */
    @Override
    void close() throws Exception;
}
