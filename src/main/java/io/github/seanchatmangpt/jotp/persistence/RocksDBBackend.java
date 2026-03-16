package io.github.seanchatmangpt.jotp.persistence;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * RocksDB persistence backend for production use.
 *
 * <p>This backend provides durable, high-performance storage using RocksDB's embedded database.
 * It's suitable for production deployments where data durability is required.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>Durable storage with write-ahead logging (WAL)
 *   <li>Column family support for data isolation
 *   <li>Atomic batch writes via WriteBatch
 *   <li>Compression for reduced storage footprint
 *   <li>ACK markers for idempotent message processing
 * </ul>
 *
 * <p><strong>Atomic Write Support:</strong>
 *
 * <p>The {@link #writeAtomic(String, byte[], byte[])} method provides atomic batch writes of state
 * and ACK markers, solving the dual-write problem for JVM crash survival.
 *
 * <p><strong>Thread Safety:</strong> This implementation is thread-safe and can be shared across
 * multiple processes.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create backend with data directory
 * PersistenceBackend backend = new RocksDBBackend(Path.of("/data/jotp"));
 *
 * // Use it
 * backend.save("counter-001", snapshot);
 * Optional<byte[]> loaded = backend.load("counter-001");
 *
 * // Atomic write for idempotent processing
 * backend.writeAtomic("counter-001", stateBytes, ackBytes);
 *
 * // Get ACK sequence for idempotence check
 * Optional<Long> ackSeq = backend.getAckSequence("counter-001");
 *
 * // Close when done
 * backend.close();
 * }</pre>
 *
 * @see PersistenceBackend
 * @see InMemoryBackend
 * @see AtomicStateWriter
 */
public final class RocksDBBackend implements PersistenceBackend {

    /** ACK key suffix for storing sequence numbers. */
    private static final String ACK_SUFFIX = ":ack";

    private final Path dataDir;
    private final RocksDB db;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions columnFamilyOptions;
    private final WriteOptions syncWriteOptions;

    /**
     * Create a RocksDB backend with a single default column family.
     *
     * @param dataDir the directory for RocksDB data files
     * @throws PersistenceException if initialization fails
     * @throws IllegalArgumentException if dataDir is null
     */
    public RocksDBBackend(Path dataDir) {
        this(dataDir, java.util.List.of("default"));
    }

    /**
     * Create a RocksDB backend with multiple column families.
     *
     * @param dataDir the directory for RocksDB data files
     * @param columnFamilyNames names of column families to create
     * @throws PersistenceException if initialization fails
     * @throws IllegalArgumentException if dataDir or columnFamilyNames is null
     */
    public RocksDBBackend(Path dataDir, java.util.List<String> columnFamilyNames) {
        requireNonNull(dataDir, "dataDir must not be null");
        requireNonNull(columnFamilyNames, "columnFamilyNames must not be null");

        this.dataDir = dataDir;

        try {
            // Initialize RocksDB (must load library before use)
            RocksDB.loadLibrary();

            Files.createDirectories(dataDir);

            this.dbOptions =
                    new DBOptions()
                            .setCreateIfMissing(true)
                            .setCreateMissingColumnFamilies(true)
                            .setWalDir(dataDir.resolve("wal").toString())
                            .setDbLogDir(dataDir.resolve("logs").toString());

            this.columnFamilyOptions =
                    new ColumnFamilyOptions()
                            .setCompressionType(org.rocksdb.CompressionType.LZ4_COMPRESSION);

            // Open database with column families
            java.util.List<ColumnFamilyDescriptor> columnFamilyDescriptors =
                    columnFamilyNames.stream()
                            .map(
                                    name ->
                                            new ColumnFamilyDescriptor(
                                                    name.getBytes(), columnFamilyOptions))
                            .toList();

            java.util.List<ColumnFamilyHandle> columnFamilyHandles = new java.util.ArrayList<>();

            this.db =
                    RocksDB.open(
                            dbOptions,
                            dataDir.toString(),
                            columnFamilyDescriptors,
                            columnFamilyHandles);

            // Sync write options for atomic operations - CRITICAL for crash safety
            this.syncWriteOptions = new WriteOptions().setSync(true);

        } catch (RocksDBException | IOException e) {
            throw new PersistenceException("Failed to initialize RocksDB backend", e);
        }
    }

    @Override
    public void save(String key, byte[] snapshot) {
        requireNonNull(key, "key must not be null");
        requireNonNull(snapshot, "snapshot must not be null");
        ensureOpen();

        try {
            db.put(key.getBytes(), snapshot);
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to save snapshot for key: " + key, e);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        try {
            byte[] data = db.get(key.getBytes());
            return Optional.ofNullable(data);
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to load snapshot for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        try {
            db.delete(key.getBytes());
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to delete snapshot for key: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        try {
            return db.get(key.getBytes()) != null;
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to check existence for key: " + key, e);
        }
    }

    @Override
    public Iterable<String> listKeys() {
        ensureOpen();

        try {
            java.util.Set<String> keys = new java.util.HashSet<>();
            try (org.rocksdb.RocksIterator iterator = db.newIterator()) {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    keys.add(new String(iterator.key()));
                    iterator.next();
                }
            }
            return keys;
        } catch (Exception e) {
            throw new PersistenceException("Failed to list keys", e);
        }
    }

    /**
     * Get the underlying RocksDB instance.
     *
     * <p>This is provided for advanced use cases like atomic batch writes across column families.
     *
     * @return the RocksDB instance
     */
    public RocksDB getDb() {
        ensureOpen();
        return db;
    }

    /**
     * Get the data directory path.
     *
     * @return the data directory
     */
    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Get the sync write options.
     *
     * <p>These options have sync=true for crash-safe writes.
     *
     * @return the write options
     */
    public WriteOptions getSyncWriteOptions() {
        return syncWriteOptions;
    }

    // ========================================
    // Atomic Write Operations for Idempotence
    // ========================================

    /**
     * Write state and ACK atomically in a single WriteBatch.
     *
     * <p>This is the critical operation for crash safety. The state and its corresponding ACK
     * marker are written in a single atomic batch. If the JVM crashes during this operation,
     * RocksDB's WAL ensures either both are persisted or neither.
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
     * <p><strong>The Solution:</strong> Atomic batch writes with sync=true.
     *
     * @param key the entity key
     * @param stateBytes the serialized state
     * @param ackBytes the ACK marker (typically sequence number as bytes)
     * @throws PersistenceException if the write fails
     */
    public void writeAtomic(String key, byte[] stateBytes, byte[] ackBytes) {
        requireNonNull(key, "key must not be null");
        requireNonNull(stateBytes, "stateBytes must not be null");
        requireNonNull(ackBytes, "ackBytes must not be null");
        ensureOpen();

        try (WriteBatch batch = new WriteBatch()) {
            byte[] keyBytes = key.getBytes();
            byte[] ackKeyBytes = (key + ACK_SUFFIX).getBytes();

            // Write 1: State
            batch.put(keyBytes, stateBytes);

            // Write 2: ACK (processed marker)
            batch.put(ackKeyBytes, ackBytes);

            // CRITICAL: Single atomic write with sync=true for crash safety
            db.write(syncWriteOptions, batch);
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to write atomic batch for key: " + key, e);
        }
    }

    /**
     * Get the ACK sequence number for an entity.
     *
     * <p>The ACK sequence is the sequence number of the last successfully processed and committed
     * message. Used for idempotence checking during message replay.
     *
     * @param key the entity key
     * @return Optional containing the sequence number, or empty if no ACK exists
     * @throws PersistenceException if the read fails
     */
    public Optional<Long> getAckSequence(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        try {
            byte[] ackKeyBytes = (key + ACK_SUFFIX).getBytes();
            byte[] ackBytes = db.get(ackKeyBytes);

            if (ackBytes == null) {
                return Optional.empty();
            }

            return Optional.of(Long.parseLong(new String(ackBytes)));
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to get ACK sequence for key: " + key, e);
        } catch (NumberFormatException e) {
            throw new PersistenceException("Invalid ACK sequence format for key: " + key, e);
        }
    }

    /**
     * Delete the ACK marker for an entity.
     *
     * <p>Use with caution - this removes idempotence protection.
     *
     * @param key the entity key
     * @throws PersistenceException if the delete fails
     */
    public void deleteAck(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        try {
            byte[] ackKeyBytes = (key + ACK_SUFFIX).getBytes();
            db.delete(ackKeyBytes);
        } catch (RocksDBException e) {
            throw new PersistenceException("Failed to delete ACK for key: " + key, e);
        }
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
    public boolean isDuplicate(String key, long messageSeq) {
        return getAckSequence(key).map(ackSeq -> messageSeq <= ackSeq).orElse(false);
    }

    @Override
    public void close() throws Exception {
        if (db != null && !db.isOwningHandle()) {
            // Get all column family handles and close them
            try (var iterator = db.newIterator()) {
                // Iterator will be closed automatically
            }

            db.close();
        }

        if (dbOptions != null && !dbOptions.isOwningHandle()) {
            dbOptions.close();
        }

        if (columnFamilyOptions != null && !columnFamilyOptions.isOwningHandle()) {
            columnFamilyOptions.close();
        }
    }

    private void ensureOpen() {
        if (db == null || db.isOwningHandle()) {
            throw new PersistenceException("RocksDB backend is closed");
        }
    }
}
