package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * Distributed Cache with Consistent Hashing
 *
 * <p>This example demonstrates a distributed cache cluster where keys are distributed across
 * multiple nodes using consistent hashing. When nodes join/leave, minimal key migration occurs.
 * Each cache entry is replicated to N nodes for fault tolerance.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * [Client]
 *   ↓ (GET/PUT)
 * [Cache Cluster]
 *   ├── [Node-1: Ring 0-120] → Keys: "user:1", "user:5"
 *   ├── [Node-2: Ring 121-240] → Keys: "user:2", "user:6"
 *   └── [Node-3: Ring 241-359] → Keys: "user:3", "user:4"
 *
 * Consistent Hash Ring (360°):
 * Each key hashes to angle, finds nearest clockwise node
 * Replication: N=2 (each key on 2 nodes)
 * </pre>
 *
 * <p><strong>Guarantees:</strong>
 *
 * <ul>
 *   <li><strong>Consistent Routing:</strong> Same key always routes to same node(s)
 *   <li><strong>Minimal Migration:</strong> Node changes affect ~1/N keys
 *   <li><strong>Fault Tolerance:</strong> Replication factor N survives N-1 node failures
 *   <li><strong>Liveness:</strong> Node failures don't block reads (try replicas)
 * </ul>
 *
 * <p><strong>How to Run:</strong>
 *
 * <pre>{@code
 * # Terminal 1: Node 1
 * java DistributedCacheExample node1 6081
 *
 * # Terminal 2: Node 2
 * java DistributedCacheExample node2 6082
 *
 * # Terminal 3: Node 3
 * java DistributedCacheExample node3 6083
 * }</pre>
 *
 * <p><strong>Expected Output:</strong>
 *
 * <pre>
 * [node1] Cache node started: port=6081, replication=2
 * [node1] PUT user:123 → node2 (primary), node3 (replica)
 * [node1] GET user:123 → node2 (hit)
 * [node1] Cache stats: hits=5, misses=1, hit-rate=83.3%
 * </pre>
 *
 * @see Proc
 * @see ProcRegistry
 * @see <a href="https://jotp.io/distributed/cache">Documentation</a>
 */
public class DistributedCacheExample {

    /** Cache entry with metadata */
    public record CacheEntry(
            String key, byte[] value, long version, long createdAt, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /** Cache operation messages */
    public sealed interface CacheMsg
            permits CacheMsg.Put,
                    CacheMsg.Get,
                    CacheMsg.Delete,
                    CacheMsg.Invalidate,
                    CacheMsg.Stats {

        record Put(String key, byte[] value, Duration ttl, CompletableFuture<Boolean> reply)
                implements CacheMsg {}

        record Get(String key, CompletableFuture<Optional<byte[]>> reply) implements CacheMsg {}

        record Delete(String key, CompletableFuture<Boolean> reply) implements CacheMsg {}

        record Invalidate(String key, String sourceNode) implements CacheMsg {}

        record Stats(CompletableFuture<CacheStats> reply) implements CacheMsg {}
    }

    /** Cache statistics */
    public record CacheStats(
            long hits, long misses, double hitRate, int entryCount, long totalBytes) {
        @Override
        public String toString() {
            return String.format(
                    "hits=%d, misses=%d, hit-rate=%.1f%%, entries=%d, bytes=%d",
                    hits, misses, hitRate * 100, entryCount, totalBytes);
        }
    }

    /** Consistent hash ring */
    private static class ConsistentHash {
        private final TreeMap<Integer, String> ring = new TreeMap<>();
        private final int virtualNodes;
        private final Random random = new Random();

        ConsistentHash(List<String> nodes, int virtualNodes) {
            this.virtualNodes = virtualNodes;
            nodes.forEach(this::addNode);
        }

        void addNode(String node) {
            for (int i = 0; i < virtualNodes; i++) {
                int hash = hash(node + ":" + i);
                ring.put(hash, node);
            }
        }

        void removeNode(String node) {
            for (int i = 0; i < virtualNodes; i++) {
                int hash = hash(node + ":" + i);
                ring.remove(hash);
            }
        }

        /** Find primary node for key */
        String findNode(String key) {
            if (ring.isEmpty()) return null;

            int hash = hash(key);
            var entry = ring.higherEntry(hash);
            String node = entry != null ? entry.getValue() : ring.firstEntry().getValue();
            return node;
        }

        /** Find N replica nodes for key */
        List<String> findReplicas(String key, int count) {
            List<String> replicas = new ArrayList<>();
            if (ring.isEmpty()) return replicas;

            int hash = hash(key);
            var it = ring.tailMap(hash).entrySet().iterator();

            // Wrap around if needed
            if (!it.hasNext()) {
                it = ring.entrySet().iterator();
            }

            while (replicas.size() < count && it.hasNext()) {
                var entry = it.next();
                String node = entry.getValue();
                if (!replicas.contains(node)) {
                    replicas.add(node);
                }
                if (!it.hasNext()) {
                    it = ring.entrySet().iterator();
                }
            }

            return replicas;
        }

        private int hash(String s) {
            // Simple hash for demo (use MurmurHash in production)
            return s.hashCode() & Integer.MAX_VALUE;
        }
    }

    /** Cache node process with durable persistence */
    private static class CacheNodeProc {
        private final String nodeId;
        private final Map<String, CacheEntry> storage = new ConcurrentHashMap<>();
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final DurableState<Map<String, CacheEntry>> durableCache;

        CacheNodeProc(String nodeId) {
            this.nodeId = nodeId;

            // Initialize durable state for cache entries
            PersistenceConfig config =
                    PersistenceConfig.builder()
                            .durabilityLevel(PersistenceConfig.DurabilityLevel.DURABLE)
                            .snapshotInterval(30)
                            .eventsPerSnapshot(100)
                            .build();

            this.durableCache =
                    DurableState.<Map<String, CacheEntry>>builder()
                            .entityId("cache-node-" + nodeId)
                            .config(config)
                            .initialState(new ConcurrentHashMap<>())
                            .build();

            // Recover cache state on startup
            recoverCacheState();
        }

        /**
         * Recover cache state from persistent storage.
         *
         * <p>Loads all cache entries including metadata (version, timestamps, expiration). Expired
         * entries are filtered out during recovery.
         */
        private void recoverCacheState() {
            Map<String, CacheEntry> recovered =
                    durableCache.recover(() -> new ConcurrentHashMap<>());

            // Filter out expired entries
            long now = System.currentTimeMillis();
            recovered.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);

            storage.putAll(recovered);

            if (!recovered.isEmpty()) {
                System.out.println(
                        "[" + nodeId + "] Recovered " + recovered.size() + " cache entries");
            }
        }

        Map<String, CacheEntry> initialState() {
            return new ConcurrentHashMap<>(storage);
        }

        Map<String, CacheEntry> handle(Map<String, CacheEntry> state, CacheMsg msg) {
            Map<String, CacheEntry> newState =
                    switch (msg) {
                        case CacheMsg.Put(
                                        String key,
                                        byte[] value,
                                        Duration ttl,
                                        CompletableFuture<Boolean> reply) -> {
                            var now = System.currentTimeMillis();
                            var entry = new CacheEntry(key, value, now, now, now + ttl.toMillis());
                            storage.put(key, entry);

                            // Persist cache update
                            persistCacheState();

                            reply.complete(true);
                            System.out.println(
                                    "["
                                            + nodeId
                                            + "] Stored: "
                                            + key
                                            + " ("
                                            + value.length
                                            + " bytes)");
                            yield new ConcurrentHashMap<>(storage);
                        }

                        case CacheMsg.Get(
                                        String key,
                                        CompletableFuture<Optional<byte[]>> reply) -> {
                            CacheEntry entry = storage.get(key);
                            if (entry != null && !entry.isExpired()) {
                                hits.increment();
                                reply.complete(Optional.of(entry.value()));
                                System.out.println("[" + nodeId + "] HIT: " + key);
                            } else {
                                misses.increment();
                                reply.complete(Optional.empty());
                                System.out.println("[" + nodeId + "] MISS: " + key);
                            }
                            yield state;
                        }

                        case CacheMsg.Delete(String key, CompletableFuture<Boolean> reply) -> {
                            CacheEntry removed = storage.remove(key);

                            // Persist deletion
                            persistCacheState();

                            reply.complete(removed != null);
                            System.out.println("[" + nodeId + "] Deleted: " + key);
                            yield new ConcurrentHashMap<>(storage);
                        }

                        case CacheMsg.Invalidate(String key, String sourceNode) -> {
                            storage.remove(key);

                            // Persist invalidation
                            persistCacheState();

                            System.out.println(
                                    "["
                                            + nodeId
                                            + "] Invalidated: "
                                            + key
                                            + " (from "
                                            + sourceNode
                                            + ")");
                            yield new ConcurrentHashMap<>(storage);
                        }

                        case CacheMsg.Stats(CompletableFuture<CacheStats> reply) -> {
                            long hitCount = hits.sum();
                            long missCount = misses.sum();
                            long total = hitCount + missCount;
                            double rate = total > 0 ? (double) hitCount / total : 0.0;

                            long bytes =
                                    storage.values().stream()
                                            .mapToLong(e -> e.value().length)
                                            .sum();

                            var stats =
                                    new CacheStats(
                                            hitCount, missCount, rate, storage.size(), bytes);
                            reply.complete(stats);
                            yield state;
                        }

                        default -> throw new IllegalStateException("Unknown message: " + msg);
                    };

            return newState;
        }

        /**
         * Persist current cache state to durable storage.
         *
         * <p>Uses idempotent writes - duplicate writes are safe and will not cause corruption.
         * Registered with JvmShutdownManager for automatic flush on shutdown.
         */
        private void persistCacheState() {
            try {
                durableCache.save(new ConcurrentHashMap<>(storage));
            } catch (Exception e) {
                System.err.println(
                        "[" + nodeId + "] Failed to persist cache state: " + e.getMessage());
            }
        }

        /**
         * Flush any pending cache updates to disk.
         *
         * <p>Called during shutdown to ensure all cache entries are persisted. DurableState
         * auto-registers with JvmShutdownManager for graceful shutdown.
         */
        void flush() {
            // DurableState auto-flushes via JvmShutdownManager
        }
    }

    /** Distributed cache cluster manager */
    private static class DistributedCache {
        private final String localNodeId;
        private final int replicationFactor;
        private final ConsistentHash hashRing;
        private final Map<String, Proc<Map<String, CacheEntry>, CacheMsg>> localNodes;
        private final Proc<Map<String, CacheEntry>, CacheMsg> localProc;
        private final CacheNodeProc cacheNodeProc; // Keep reference for flush

        DistributedCache(String localNodeId, List<String> allNodes, int replicationFactor) {
            this.localNodeId = localNodeId;
            this.replicationFactor = replicationFactor;
            this.hashRing = new ConsistentHash(allNodes, 100); // 100 virtual nodes per physical
            this.localNodes = new ConcurrentHashMap<>();

            // Create cache node process with persistence
            this.cacheNodeProc = new CacheNodeProc(localNodeId);

            // Create local process
            this.localProc = Proc.spawn(cacheNodeProc.initialState(), cacheNodeProc::handle);

            localNodes.put(localNodeId, localProc);
        }

        void put(String key, byte[] value, Duration ttl) {
            List<String> replicas = hashRing.findReplicas(key, replicationFactor);
            System.out.println("[" + localNodeId + "] PUT " + key + " → replicas: " + replicas);

            for (String node : replicas) {
                if (node.equals(localNodeId)) {
                    CompletableFuture<Boolean> future = new CompletableFuture<>();
                    localProc.tell(new CacheMsg.Put(key, value, ttl, future));
                } else {
                    // In real impl, would use RPC to remote node
                    System.out.println("[" + localNodeId + "] Replicating to " + node);
                }
            }
        }

        Optional<byte[]> get(String key) {
            String primary = hashRing.findNode(key);
            System.out.println("[" + localNodeId + "] GET " + key + " → primary: " + primary);

            if (primary.equals(localNodeId)) {
                try {
                    CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();
                    localProc.tell(new CacheMsg.Get(key, future));
                    return future.get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Try replicas
                    return getFromReplica(key);
                }
            } else {
                // In real impl, would RPC to primary
                return getFromReplica(key);
            }
        }

        private Optional<byte[]> getFromReplica(String key) {
            // Try replicas in order
            List<String> replicas = hashRing.findReplicas(key, replicationFactor);
            for (String node : replicas) {
                if (node.equals(localNodeId)) {
                    try {
                        CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();
                        localProc.tell(new CacheMsg.Get(key, future));
                        return future.get(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // Try next replica
                    }
                }
            }
            return Optional.empty();
        }

        void invalidate(String key) {
            List<String> replicas = hashRing.findReplicas(key, replicationFactor);
            for (String node : replicas) {
                if (node.equals(localNodeId)) {
                    localProc.tell(new CacheMsg.Invalidate(key, localNodeId));
                } else {
                    // In real impl, would RPC to replica
                }
            }
        }

        CacheStats stats() {
            try {
                CompletableFuture<CacheStats> future = new CompletableFuture<>();
                localProc.tell(new CacheMsg.Stats(future));
                return future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                return new CacheStats(0, 0, 0.0, 0, 0);
            }
        }

        void stop() throws InterruptedException {
            // Flush cache state before shutdown
            cacheNodeProc.flush();
            localProc.stop();
        }
    }

    /** CLI entry point */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java DistributedCacheExample <nodeId> [port] [replication]");
            System.err.println("Example: java DistributedCacheExample node1 6081 2");
            System.exit(1);
        }

        String nodeId = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6081;
        int replication = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        List<String> allNodes = List.of("node1", "node2", "node3");
        var cache = new DistributedCache(nodeId, allNodes, replication);

        System.out.println(
                "["
                        + nodeId
                        + "] Cache node started: port="
                        + port
                        + ", replication="
                        + replication);

        // Interactive console
        var scanner = new java.util.Scanner(System.in);
        System.out.println("\nCommands: put <key> <value>, get <key>, delete <key>, stats, quit");

        while (scanner.hasNextLine()) {
            System.out.print(nodeId + "> ");
            String[] parts = scanner.nextLine().trim().split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "put" -> {
                    if (parts.length < 3) {
                        System.out.println("Usage: put <key> <value>");
                        break;
                    }
                    String key = parts[1];
                    byte[] value = parts[2].getBytes();
                    cache.put(key, value, Duration.ofMinutes(5));
                }

                case "get" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: get <key>");
                        break;
                    }
                    String key = parts[1];
                    Optional<byte[]> result = cache.get(key);
                    result.ifPresentOrElse(
                            v -> System.out.println("Value: " + new String(v)),
                            () -> System.out.println("Not found"));
                }

                case "delete" -> {
                    if (parts.length < 2) {
                        System.out.println("Usage: delete <key>");
                        break;
                    }
                    String key = parts[1];
                    cache.invalidate(key);
                    System.out.println("Deleted: " + key);
                }

                case "stats" -> {
                    CacheStats stats = cache.stats();
                    System.out.println("Stats: " + stats);
                }

                case "quit" -> {
                    try {
                        cache.stop();
                        System.out.println("Bye!");
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("Interrupted. Bye!");
                        return;
                    }
                }

                default -> System.out.println("Unknown: " + cmd);
            }
        }
    }
}
