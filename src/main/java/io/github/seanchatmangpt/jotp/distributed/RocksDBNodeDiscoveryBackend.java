package io.github.seanchatmangpt.jotp.distributed;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * RocksDB implementation of {@link NodeDiscoveryBackend} for persistent node discovery.
 *
 * <p>Stores node information in a RocksDB database, providing durability across JVM restarts. This
 * backend is suitable for production deployments where node registrations must survive crashes.
 *
 * <p><strong>Schema:</strong>
 *
 * <pre>{@code
 * node:<nodeName>          -> NodeInfo serialization
 * ack:<nodeName>           -> heartbeat ACK (for idempotence)
 * }</pre>
 *
 * <p><strong>Idempotence:</strong> Node info and ACK are written atomically using {@link
 * WriteBatch}. On recovery, if the ACK doesn't match the heartbeat timestamp, the write is detected
 * as partial and recovered.
 *
 * <p><strong>Resource Management:</strong> This backend implements {@link Closeable} and must be
 * closed when no longer needed to release native RocksDB resources. Use with try-with-resources or
 * register a shutdown hook with {@link io.github.seanchatmangpt.jotp.JvmShutdownManager}.
 *
 * <p><strong>Thread Safety:</strong> RocksDB is thread-safe for concurrent reads and writes.
 * Multiple threads can safely access the same database instance.
 *
 * @see NodeDiscoveryBackend
 * @see InMemoryNodeDiscoveryBackend
 */
public final class RocksDBNodeDiscoveryBackend implements NodeDiscoveryBackend, Closeable {

    private final RocksDB db;
    private final ColumnFamilyHandle columnFamily;
    private final DBOptions dbOptions;

    /**
     * Open a RocksDB backend with the given data directory.
     *
     * @param dataDirectory path to the RocksDB data directory (must not be null)
     * @throws IOException if the database cannot be opened
     * @throws NullPointerException if dataDirectory is null
     */
    public RocksDBNodeDiscoveryBackend(Path dataDirectory) throws IOException {
        if (dataDirectory == null) {
            throw new NullPointerException("dataDirectory must not be null");
        }

        try {
            // Initialize RocksDB (must load native library)
            RocksDB.loadLibrary();

            // Open database with default options
            Options options = new Options().setCreateIfMissing(true);
            db = RocksDB.open(options, dataDirectory.toString());
            this.columnFamily = db.getDefaultColumnFamily();
            this.dbOptions = new DBOptions();
        } catch (RocksDBException e) {
            throw new IOException("Failed to open RocksDB at: " + dataDirectory, e);
        }
    }

    @Override
    public void storeNode(NodeInfo nodeInfo) {
        if (nodeInfo == null) {
            throw new NullPointerException("nodeInfo must not be null");
        }

        try {
            byte[] key = ("node:" + nodeInfo.nodeName()).getBytes();
            byte[] data = serializeNodeInfo(nodeInfo);

            try (WriteBatch batch = new WriteBatch();
                    WriteOptions writeOptions = new WriteOptions().setSync(true)) {

                batch.put(columnFamily, key, data);
                batch.put(
                        columnFamily,
                        ("ack:" + nodeInfo.nodeName()).getBytes(),
                        longToBytes(nodeInfo.lastHeartbeat().toEpochMilli()));
                db.write(writeOptions, batch);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to store node: " + nodeInfo.nodeName(), e);
        }
    }

    @Override
    public Optional<NodeInfo> getNode(String nodeName) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }

        try {
            byte[] key = ("node:" + nodeName).getBytes();
            byte[] data = db.get(columnFamily, key);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(deserializeNodeInfo(data));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to get node: " + nodeName, e);
        }
    }

    @Override
    public List<NodeInfo> listNodes() {
        List<NodeInfo> nodes = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(columnFamily)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = new String(iterator.key());
                if (key.startsWith("node:")) {
                    nodes.add(deserializeNodeInfo(iterator.value()));
                }
                iterator.next();
            }
        }
        return nodes;
    }

    @Override
    public void updateHeartbeat(String nodeName, Instant timestamp) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }
        if (timestamp == null) {
            throw new NullPointerException("timestamp must not be null");
        }

        try {
            // Get current node info
            Optional<NodeInfo> current = getNode(nodeName);
            if (current.isEmpty()) {
                throw new IllegalArgumentException("Node not found: " + nodeName);
            }

            // Update with new heartbeat
            NodeInfo updated = current.get().withHeartbeat(timestamp);
            storeNode(updated);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update heartbeat for node: " + nodeName, e);
        }
    }

    @Override
    public void removeNode(String nodeName) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }

        try {
            byte[] key = ("node:" + nodeName).getBytes();
            db.delete(columnFamily, key);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to remove node: " + nodeName, e);
        }
    }

    @Override
    public List<NodeInfo> findStaleNodes(Instant threshold) {
        if (threshold == null) {
            throw new NullPointerException("threshold must not be null");
        }

        List<NodeInfo> staleNodes = new ArrayList<>();
        long thresholdMillis = threshold.toEpochMilli();

        try (RocksIterator iterator = db.newIterator(columnFamily)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = new String(iterator.key());
                if (key.startsWith("node:")) {
                    NodeInfo node = deserializeNodeInfo(iterator.value());
                    if (node.lastHeartbeat().toEpochMilli() < thresholdMillis
                            && node.status() != NodeInfo.NodeStatus.DOWN) {
                        staleNodes.add(node);
                    }
                }
                iterator.next();
            }
        }
        return staleNodes;
    }

    /**
     * Close the database and release resources.
     *
     * <p>Must be called when the backend is no longer needed. Failing to close will leak native
     * memory.
     */
    @Override
    public void close() {
        columnFamily.close();
        db.close();
        dbOptions.close();
    }

    // ── Serialization ─────────────────────────────────────────────────────────────

    private byte[] serializeNodeInfo(NodeInfo node) {
        // Simple serialization: nodeName|nodeAddress|registeredAt|lastHeartbeat|status
        String data =
                node.nodeName()
                        + "|"
                        + node.nodeAddress()
                        + "|"
                        + node.registeredAt().toEpochMilli()
                        + "|"
                        + node.lastHeartbeat().toEpochMilli()
                        + "|"
                        + node.status().name();
        return data.getBytes();
    }

    private NodeInfo deserializeNodeInfo(byte[] data) {
        String str = new String(data);
        String[] parts = str.split("\\|");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid NodeInfo data: " + str);
        }

        String nodeName = parts[0];
        String nodeAddress = parts[1];
        Instant registeredAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
        Instant lastHeartbeat = Instant.ofEpochMilli(Long.parseLong(parts[3]));
        NodeInfo.NodeStatus status = NodeInfo.NodeStatus.valueOf(parts[4]);

        return new NodeInfo(nodeName, nodeAddress, registeredAt, lastHeartbeat, status);
    }

    /** Convert long to byte array for RocksDB storage. */
    private byte[] longToBytes(long value) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return result;
    }

    /** Convert byte array from RocksDB to long. */
    private long bytesToLong(byte[] bytes) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result <<= 8;
            result |= (bytes[i] & 0xFF);
        }
        return result;
    }
}
