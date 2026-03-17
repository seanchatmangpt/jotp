package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.persistence.PersistenceBackend;
import io.github.seanchatmangpt.jotp.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * ETS-style backend for JOTP processes.
 *
 * <p>Implements Erlang Term Storage patterns for distributed in-memory tables with:
 *
 * <ul>
 *   <li><strong>Set/Bag/OrderedSet</strong> table types with ETS semantics
 *   <li><strong>Pattern Matching</strong>: ets:match(Pattern) queries
 *   <li><strong>TTL-based Cleanup</strong>: Automatic expiration of old entries
 *   <li><strong>Redis Replication</strong>: Multi-node consistency via Pub/Sub
 *   <li><strong>Crash Recovery</strong>: Vector clocks prevent duplicates
 * </ul>
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │     EtsBackend (Owner Node)             │
 * │  ┌──────────────────────────────────┐   │
 * │  │ tables: Map<tableName, EtsTable> │   │
 * │  │ - Set("users")                   │   │
 * │  │ - Bag("events")                  │   │
 * │  │ - OrderedSet("timeseries")       │   │
 * │  └──────────────────────────────────┘   │
 * │        ↓ put/delete               ↓     │
 * │   [Local Update]          [Redis Pub]   │
 * │        ↓                       ↓         │
 * │   [TTL Cleanup]        [Vector Clock]   │
 * │                                         │
 * └─────────────────────────────────────────┘
 *         ↓ (via Redis Pub/Sub)
 * ┌──────────────────────┬──────────────────┐
 * │ Node 2 (Replica)     │ Node 3 (Replica) │
 * │ tables: Map<...>     │ tables: Map<...> │
 * └──────────────────────┴──────────────────┘
 * </pre>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Create backend
 * EtsBackend backend = new EtsBackend("node-1", 3600, "localhost", 6379);
 *
 * // Create tables
 * backend.createTable("users", EtsTable.TableType.SET);
 * backend.createTable("events", EtsTable.TableType.BAG);
 *
 * // Persist process state (implements PersistenceBackend)
 * backend.save("proc-001", stateBytes);
 * Optional<byte[]> state = backend.load("proc-001");
 *
 * // Query tables (ETS-style)
 * List<String> users = backend.match("users", "user:*");
 * backend.delete("users", "user-42");
 *
 * // Subscribe to remote writes
 * backend.subscribeTable("events", event -> {
 *     System.out.println("Replicated event: " + event);
 * });
 *
 * backend.close();
 * }</pre>
 *
 * @see PersistenceBackend
 * @see EtsTable
 */
public class EtsBackend implements PersistenceBackend {

    private static final String TABLE_PREFIX = "jotp:ets:";
    private static final String STATE_PREFIX = "jotp:state:";
    private static final String ACK_PREFIX = "jotp:ack:";

    private final String nodeName;
    private final long ttlSeconds;
    private final Map<String, EtsTable> tables;
    private final Map<String, Long> tableCreatedAt;
    private final Map<String, Long> writeVersions;
    private final EtsClusterReplication replication;
    private final ScheduledExecutorService cleanupExecutor;
    private volatile boolean closed = false;

    /**
     * Create an ETS backend with cluster replication.
     *
     * @param nodeName local node name (e.g., "node-1")
     * @param ttlSeconds time-to-live for all entries
     * @param redisHost Redis host for replication
     * @param redisPort Redis port for replication
     */
    public EtsBackend(String nodeName, long ttlSeconds, String redisHost, int redisPort) {
        this.nodeName = nodeName;
        this.ttlSeconds = ttlSeconds;
        this.tables = new ConcurrentHashMap<>();
        this.tableCreatedAt = new ConcurrentHashMap<>();
        this.writeVersions = new ConcurrentHashMap<>();
        this.replication = new EtsClusterReplication(redisHost, redisPort, nodeName);
        this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ETS-Cleanup-" + nodeName);
            t.setDaemon(true);
            return t;
        });

        // Start TTL cleanup task
        startCleanupTask();
    }

    /**
     * Create a new ETS table.
     *
     * @param tableName table name
     * @param type table type (SET, BAG, ORDERED_SET)
     */
    public void createTable(String tableName, EtsTable.TableType type) {
        checkClosed();
        EtsTable table = switch (type) {
            case SET -> new EtsTable.Set(tableName);
            case BAG -> new EtsTable.Bag(tableName);
            case ORDERED_SET -> new EtsTable.OrderedSet(tableName);
        };
        tables.put(tableName, table);
        tableCreatedAt.put(tableName, System.currentTimeMillis());
    }

    /**
     * Put a tuple into a table.
     *
     * @param tableName table name
     * @param key the key
     * @param value the value
     */
    public void put(String tableName, String key, byte[] value) {
        checkClosed();
        EtsTable table = getTable(tableName);
        table.put(key, value);

        // Replicate to peer nodes
        long version = writeVersions.compute(tableName, (k, v) -> (v == null ? 0L : v) + 1);
        replication.publishWrite(tableName, key, value, version);
    }

    /**
     * Get value(s) from a table.
     *
     * @param tableName table name
     * @param key the key
     * @return list of values (may be multiple for bag tables)
     */
    public List<byte[]> get(String tableName, String key) {
        checkClosed();
        return getTable(tableName).get(key);
    }

    /**
     * Delete a key from a table.
     *
     * @param tableName table name
     * @param key the key to delete
     * @return number of objects deleted
     */
    public int delete(String tableName, String key) {
        checkClosed();
        EtsTable table = getTable(tableName);
        int deleted = table.delete(key);

        // Replicate to peer nodes
        if (deleted > 0) {
            long version = writeVersions.compute(tableName, (k, v) -> (v == null ? 0L : v) + 1);
            replication.publishDelete(tableName, key, version);
        }

        return deleted;
    }

    /**
     * Match tuples against a pattern (ETS ets:match equivalent).
     *
     * <p>Pattern syntax:
     *
     * <ul>
     *   <li><code>"$1:*"</code> — prefix match on first part
     *   <li><code>"key:*:end"</code> — sandwich match
     *   <li><code>"exact-key"</code> — exact match
     * </ul>
     *
     * @param tableName table name
     * @param pattern match pattern
     * @return list of matching keys
     */
    public List<String> match(String tableName, String pattern) {
        checkClosed();
        return getTable(tableName).match(pattern);
    }

    /**
     * Select keys where predicate returns true.
     *
     * @param tableName table name
     * @param predicate filter function
     * @return list of matching keys
     */
    public List<String> select(String tableName, java.util.function.Predicate<String> predicate) {
        checkClosed();
        return getTable(tableName).select(predicate);
    }

    /**
     * Get all keys from a table.
     *
     * @param tableName table name
     * @return list of keys
     */
    public List<String> keys(String tableName) {
        checkClosed();
        return getTable(tableName).keys();
    }

    /**
     * Get table statistics.
     *
     * @param tableName table name
     * @return table metadata and stats
     */
    public EtsTable.TableStats stats(String tableName) {
        checkClosed();
        return getTable(tableName).stats();
    }

    /**
     * Subscribe to remote writes on a table.
     *
     * @param tableName table name
     * @param listener callback for write events
     */
    public void subscribeTable(String tableName, Consumer<EtsClusterReplication.WriteEvent> listener) {
        checkClosed();
        replication.subscribeWrites(tableName, event -> {
            if (!event.originNode().equals(nodeName)) {
                // Update local table with remote write
                getTable(tableName).put(event.key(), event.value());
            }
            listener.accept(event);
        });
    }

    /**
     * Get list of all table names.
     *
     * @return table names
     */
    public Set<String> listTables() {
        checkClosed();
        return tables.keySet();
    }

    /**
     * Get the cluster replication handler.
     *
     * @return replication handler for vector clock queries
     */
    public EtsClusterReplication getReplication() {
        checkClosed();
        return replication;
    }

    /**
     * Clear all data from a table.
     *
     * @param tableName table name
     */
    public void clearTable(String tableName) {
        checkClosed();
        getTable(tableName).clear();
    }

    /**
     * Delete an entire table.
     *
     * @param tableName table name
     */
    public void dropTable(String tableName) {
        checkClosed();
        tables.remove(tableName);
        tableCreatedAt.remove(tableName);
        writeVersions.remove(tableName);
    }

    // ====== PersistenceBackend implementation ======

    @Override
    public void save(String key, byte[] snapshot) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // Use internal "state" table for process snapshots
        put("__state__", key, snapshot);
    }

    @Override
    public Optional<byte[]> load(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");

        List<byte[]> values = get("__state__", key);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    @Override
    public void delete(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");

        delete("__state__", key);
    }

    @Override
    public boolean exists(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");

        return getTable("__state__").contains(key);
    }

    @Override
    public Iterable<String> listKeys() {
        checkClosed();

        return getTable("__state__").keys();
    }

    @Override
    public void writeAtomic(String key, byte[] stateBytes, byte[] ackBytes) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(stateBytes, "stateBytes must not be null");
        Objects.requireNonNull(ackBytes, "ackBytes must not be null");

        // Write state and ACK in batch (simulated atomicity)
        put("__state__", key, stateBytes);
        put("__acks__", key, ackBytes);
    }

    @Override
    public Optional<Long> getAckSequence(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");

        List<byte[]> values = get("__acks__", key);
        if (values.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(bytesToLong(values.get(0)));
        } catch (Exception e) {
            throw new PersistenceException("Invalid ACK sequence format for key: " + key, e);
        }
    }

    @Override
    public void deleteAck(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key must not be null");

        delete("__acks__", key);
    }

    @Override
    public void close() {
        closed = true;
        cleanupExecutor.shutdownNow();
        try {
            replication.close();
        } catch (Exception e) {
            System.err.println("Error closing replication: " + e.getMessage());
        }
        tables.clear();
        tableCreatedAt.clear();
        writeVersions.clear();
    }

    // ====== Private helpers ======

    private EtsTable getTable(String tableName) {
        EtsTable table = tables.get(tableName);
        if (table == null) {
            // Auto-create as SET if not exists
            createTable(tableName, EtsTable.TableType.SET);
            table = tables.get(tableName);
        }
        return table;
    }

    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, ttlSeconds, ttlSeconds, TimeUnit.SECONDS);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        long ttlMillis = ttlSeconds * 1000;

        for (Map.Entry<String, EtsTable> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            long createdAt = tableCreatedAt.getOrDefault(tableName, now);

            // Clear expired entries
            if (now - createdAt > ttlMillis) {
                entry.getValue().clear();
            }
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new PersistenceException("Backend is closed");
        }
    }

    private long bytesToLong(byte[] bytes) {
        if (bytes.length != 8) {
            throw new IllegalArgumentException("ACK bytes must be 8 bytes long");
        }
        return ((long) bytes[0] & 0xFFL) << 56
                | ((long) bytes[1] & 0xFFL) << 48
                | ((long) bytes[2] & 0xFFL) << 40
                | ((long) bytes[3] & 0xFFL) << 32
                | ((long) bytes[4] & 0xFFL) << 24
                | ((long) bytes[5] & 0xFFL) << 16
                | ((long) bytes[6] & 0xFFL) << 8
                | bytes[7] & 0xFFL;
    }
}
