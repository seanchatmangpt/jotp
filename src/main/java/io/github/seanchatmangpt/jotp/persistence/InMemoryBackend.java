package io.github.seanchatmangpt.jotp.persistence;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory persistence backend for testing and development.
 *
 * <p>This backend stores state snapshots in a ConcurrentHashMap. It's useful for:
 *
 * <ul>
 *   <li>Unit tests (fast, no disk I/O)
 *   <li>Development and prototyping
 *   <li>Scenarios where durability is not required
 * </ul>
 *
 * <p><strong>Warning:</strong> This backend is NOT durable. All data is lost when the JVM exits or
 * the backend is closed. Do not use in production.
 *
 * <p><strong>Thread Safety:</strong> This implementation is thread-safe and can be shared across
 * multiple processes.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create backend
 * PersistenceBackend backend = new InMemoryBackend();
 *
 * // Use it
 * backend.save("counter-001", snapshot);
 * Optional<byte[]> loaded = backend.load("counter-001");
 *
 * // Close when done
 * backend.close();
 * }</pre>
 *
 * @see PersistenceBackend
 * @see RocksDBBackend
 */
public final class InMemoryBackend implements PersistenceBackend {

    private final ConcurrentHashMap<String, byte[]> store;
    private volatile boolean closed = false;

    /** Create a new in-memory backend. */
    public InMemoryBackend() {
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public void save(String key, byte[] snapshot) {
        requireNonNull(key, "key must not be null");
        requireNonNull(snapshot, "snapshot must not be null");
        ensureOpen();
        store.put(key, snapshot);
    }

    @Override
    public Optional<byte[]> load(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();
        store.remove(key);
    }

    @Override
    public boolean exists(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();
        return store.containsKey(key);
    }

    @Override
    public Iterable<String> listKeys() {
        ensureOpen();
        return store.keySet();
    }

    @Override
    public void writeAtomic(String key, byte[] stateBytes, byte[] ackBytes) {
        requireNonNull(key, "key must not be null");
        requireNonNull(stateBytes, "stateBytes must not be null");
        requireNonNull(ackBytes, "ackBytes must not be null");
        ensureOpen();

        // In-memory implementation: simple put operations
        // ConcurrentHashMap provides atomicity per-key
        store.put(key, stateBytes);
        store.put(key + ":ack", ackBytes);
    }

    @Override
    public Optional<Long> getAckSequence(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        byte[] ackBytes = store.get(key + ":ack");
        if (ackBytes == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(new String(ackBytes)));
        } catch (NumberFormatException e) {
            throw new PersistenceException("Invalid ACK sequence format for key: " + key, e);
        }
    }

    @Override
    public void deleteAck(String key) {
        requireNonNull(key, "key must not be null");
        ensureOpen();

        store.remove(key + ":ack");
    }

    @Override
    public void close() {
        closed = true;
        store.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new PersistenceException("Backend is closed");
        }
    }
}
