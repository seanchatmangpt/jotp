package io.github.seanchatmangpt.jotp.distributed;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import redis.clients.jedis.*;

/**
 * ETS cluster replication via Redis Pub/Sub.
 *
 * <p>Distributes table mutations across JOTP cluster nodes with vector clock consistency
 * tracking and partition-tolerant operation.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * Node 1 (owner) ──→ put("key", data) ──┐
 *                                         ├──→ [Redis Pub/Sub] ──→ Node 2 (replica)
 * Node 2 (replica) ←─ subscribe ←────────┘                    ──→ Node 3 (replica)
 * </pre>
 *
 * <p><strong>Vector Clock Merging:</strong> Each write carries a vector clock to detect
 * causality violations and partial writes during network partitions.
 */
public class EtsClusterReplication implements AutoCloseable {

    private static final String WRITE_CHANNEL = "jotp:ets:writes:";
    private static final String DELETE_CHANNEL = "jotp:ets:deletes:";
    private static final String REPLICATE_CHANNEL = "jotp:ets:replicate:";

    private final JedisPool pool;
    private final String nodeName;
    private final Map<String, VectorClock> vectorClocks;
    private final Map<String, Consumer<WriteEvent>> writeListeners;
    private final ExecutorService pubsubExecutor;
    private final Map<String, Long> lastSeenVersion;
    private volatile boolean closed = false;

    /**
     * Create a cluster replication handler.
     *
     * @param redisHost Redis host
     * @param redisPort Redis port
     * @param nodeName local node name
     */
    public EtsClusterReplication(String redisHost, int redisPort, String nodeName) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        config.setTestOnBorrow(true);

        this.pool = new JedisPool(config, redisHost, redisPort);
        this.nodeName = nodeName;
        this.vectorClocks = new ConcurrentHashMap<>();
        this.writeListeners = new ConcurrentHashMap<>();
        this.lastSeenVersion = new ConcurrentHashMap<>();
        this.pubsubExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ETS-PubSub-" + nodeName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Publish a write to peer nodes.
     *
     * @param tableName the table name
     * @param key the key written
     * @param value the value written
     * @param version monotonic version number
     */
    public void publishWrite(String tableName, String key, byte[] value, long version) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            String channel = WRITE_CHANNEL + tableName;
            WriteEvent event = new WriteEvent(
                    nodeName,
                    tableName,
                    key,
                    value,
                    version,
                    getVectorClock(tableName).increment(nodeName));
            jedis.publish(channel, serializeWriteEvent(event));
            updateVectorClock(tableName, event.vectorClock());
        } catch (Exception e) {
            System.err.println("Failed to publish write for " + tableName + "." + key + ": " + e);
        }
    }

    /**
     * Publish a delete to peer nodes.
     *
     * @param tableName the table name
     * @param key the key deleted
     * @param version monotonic version number
     */
    public void publishDelete(String tableName, String key, long version) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            String channel = DELETE_CHANNEL + tableName;
            DeleteEvent event = new DeleteEvent(
                    nodeName,
                    tableName,
                    key,
                    version,
                    getVectorClock(tableName).increment(nodeName));
            jedis.publish(channel, serializeDeleteEvent(event));
            updateVectorClock(tableName, event.vectorClock());
        } catch (Exception e) {
            System.err.println("Failed to publish delete for " + tableName + "." + key + ": " + e);
        }
    }

    /**
     * Subscribe to writes on a table.
     *
     * @param tableName the table name
     * @param listener callback for write events
     */
    public void subscribeWrites(String tableName, Consumer<WriteEvent> listener) {
        checkClosed();
        writeListeners.put(tableName, listener);
        startPubSubListener(tableName);
    }

    /**
     * Get vector clock for a table (causality tracking).
     *
     * @param tableName the table name
     * @return vector clock snapshot
     */
    public VectorClock getVectorClock(String tableName) {
        return vectorClocks.computeIfAbsent(tableName, k -> new VectorClock());
    }

    /**
     * Check if a write is causally consistent (not a duplicate).
     *
     * @param tableName table name
     * @param version write version
     * @return true if version is new
     */
    public boolean isNewWrite(String tableName, long version) {
        long lastSeen = lastSeenVersion.getOrDefault(tableName, -1L);
        return version > lastSeen;
    }

    /**
     * Record that we've seen a write version.
     *
     * @param tableName table name
     * @param version write version
     */
    public void recordWriteVersion(String tableName, long version) {
        lastSeenVersion.put(tableName, Math.max(lastSeenVersion.getOrDefault(tableName, -1L), version));
    }

    @Override
    public void close() {
        closed = true;
        pubsubExecutor.shutdownNow();
        try {
            pool.close();
        } catch (Exception e) {
            System.err.println("Error closing replication pool: " + e.getMessage());
        }
    }

    private void startPubSubListener(String tableName) {
        pubsubExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                JedisPubSub subscriber = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleWriteEvent(tableName, message);
                    }
                };

                jedis.subscribe(subscriber, WRITE_CHANNEL + tableName, DELETE_CHANNEL + tableName);
            } catch (Exception e) {
                System.err.println("Error in PubSub listener for " + tableName + ": " + e);
            }
        });
    }

    private void handleWriteEvent(String tableName, String message) {
        try {
            if (message.startsWith("WRITE:")) {
                WriteEvent event = deserializeWriteEvent(message.substring(6));
                updateVectorClock(tableName, event.vectorClock());
                recordWriteVersion(tableName, event.version());

                Consumer<WriteEvent> listener = writeListeners.get(tableName);
                if (listener != null) {
                    listener.accept(event);
                }
            } else if (message.startsWith("DELETE:")) {
                DeleteEvent event = deserializeDeleteEvent(message.substring(7));
                updateVectorClock(tableName, event.vectorClock());
                recordWriteVersion(tableName, event.version());
            }
        } catch (Exception e) {
            System.err.println("Error handling write event for " + tableName + ": " + e);
        }
    }

    private void updateVectorClock(String tableName, VectorClock incoming) {
        VectorClock current = getVectorClock(tableName);
        current.merge(incoming);
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Replication handler is closed");
        }
    }

    private String serializeWriteEvent(WriteEvent event) {
        return String.format(
                "WRITE:%s:%s:%s:%d:%s:%s",
                event.originNode(),
                event.tableName(),
                event.key(),
                event.version(),
                Base64.getEncoder().encodeToString(event.value()),
                event.vectorClock().toString());
    }

    private String serializeDeleteEvent(DeleteEvent event) {
        return String.format(
                "DELETE:%s:%s:%s:%d:%s",
                event.originNode(),
                event.tableName(),
                event.key(),
                event.version(),
                event.vectorClock().toString());
    }

    private WriteEvent deserializeWriteEvent(String data) {
        String[] parts = data.split(":", 6);
        return new WriteEvent(
                parts[0],
                parts[1],
                parts[2],
                Base64.getDecoder().decode(parts[3]),
                Long.parseLong(parts[4]),
                VectorClock.parse(parts[5]));
    }

    private DeleteEvent deserializeDeleteEvent(String data) {
        String[] parts = data.split(":", 5);
        return new DeleteEvent(
                parts[0],
                parts[1],
                parts[2],
                Long.parseLong(parts[3]),
                VectorClock.parse(parts[4]));
    }

    /** Write event replicated to other nodes. */
    public record WriteEvent(
            String originNode,
            String tableName,
            String key,
            byte[] value,
            long version,
            VectorClock vectorClock) {}

    /** Delete event replicated to other nodes. */
    public record DeleteEvent(
            String originNode,
            String tableName,
            String key,
            long version,
            VectorClock vectorClock) {}

    /** Vector clock for causality tracking in distributed writes. */
    public static class VectorClock {
        private final ConcurrentHashMap<String, Long> clock;

        public VectorClock() {
            this.clock = new ConcurrentHashMap<>();
        }

        public VectorClock increment(String node) {
            clock.put(node, clock.getOrDefault(node, 0L) + 1);
            return this;
        }

        public void merge(VectorClock other) {
            other.clock.forEach(
                    (node, version) ->
                            clock.merge(node, version, Math::max));
        }

        public boolean happensBefore(VectorClock other) {
            boolean atLeastOneLess = false;
            for (Map.Entry<String, Long> entry : clock.entrySet()) {
                long otherVersion = other.clock.getOrDefault(entry.getKey(), 0L);
                if (entry.getValue() > otherVersion) {
                    return false;
                }
                if (entry.getValue() < otherVersion) {
                    atLeastOneLess = true;
                }
            }
            return atLeastOneLess;
        }

        public boolean concurrent(VectorClock other) {
            boolean hasGreater = false;
            boolean hasLess = false;
            for (String node : clock.keySet()) {
                long thisVersion = clock.get(node);
                long otherVersion = other.clock.getOrDefault(node, 0L);
                if (thisVersion > otherVersion) {
                    hasGreater = true;
                }
                if (thisVersion < otherVersion) {
                    hasLess = true;
                }
            }
            for (String node : other.clock.keySet()) {
                if (!clock.containsKey(node)) {
                    hasLess = true;
                }
            }
            return hasGreater && hasLess;
        }

        @Override
        public String toString() {
            return clock.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .sorted()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        }

        public static VectorClock parse(String str) {
            VectorClock vc = new VectorClock();
            if (str.isEmpty()) return vc;
            for (String entry : str.split(",")) {
                String[] parts = entry.split("=");
                vc.clock.put(parts[0], Long.parseLong(parts[1]));
            }
            return vc;
        }
    }
}
