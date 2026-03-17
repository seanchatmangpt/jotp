package io.github.seanchatmangpt.jotp.distributed;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Example demonstrating Redis-based ETS backend with distributed table storage.
 *
 * <p>Shows how to:
 * <ul>
 *   <li>Create tables (SET, BAG, ORDERED_SET) stored in Redis
 *   <li>Perform CRUD operations on distributed tables
 *   <li>Use pattern matching for queries
 *   <li>Subscribe to local table changes
 *   <li>Use as a PersistenceBackend for process snapshots
 *   <li>Handle multi-node cluster scenarios
 * </ul>
 */
public class RedisEtsBackendExample {

    public static void main(String[] args) throws Exception {
        example_setTypeTable();
        example_bagTypeTable();
        example_orderedSetTypeTable();
        example_patternMatching();
        example_subscriptions();
        example_persistenceBackend();
        example_multiNodeCluster();
    }

    /**
     * Example 1: SET table (unique keys, last write wins).
     */
    private static void example_setTypeTable() {
        System.out.println("\n=== Example 1: SET Type Table ===");

        try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-1")) {
            // Create SET table
            backend.createTable("users", EtsTable.TableType.SET);

            // Put unique users
            backend.put("users", "user-42", "Alice".getBytes(StandardCharsets.UTF_8));
            backend.put("users", "user-99", "Bob".getBytes(StandardCharsets.UTF_8));

            // Get values
            List<byte[]> values = backend.get("users", "user-42");
            System.out.println("User 42: " + (values.isEmpty() ? "not found"
                    : new String(values.get(0), StandardCharsets.UTF_8)));

            // Check containment
            boolean exists = backend.contains("users", "user-42");
            System.out.println("User 42 exists: " + exists);

            // Get statistics
            EtsTable.TableStats stats = backend.stats("users");
            System.out.println("Users table stats: " + stats.objectCount() + " objects");

            // List keys
            List<String> allKeys = backend.keys("users");
            System.out.println("All user keys: " + allKeys);

            // Delete
            int deleted = backend.delete("users", "user-42");
            System.out.println("Deleted " + deleted + " user(s)");

            backend.clearTable("users");
        }
    }

    /**
     * Example 2: BAG table (duplicate keys allowed, all values stored).
     */
    private static void example_bagTypeTable() {
        System.out.println("\n=== Example 2: BAG Type Table ===");

        try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-2")) {
            backend.createTable("events", EtsTable.TableType.BAG);

            // Bag allows duplicates: same key, multiple values
            backend.put("events", "event-001", "login".getBytes(StandardCharsets.UTF_8));
            backend.put("events", "event-001", "logout".getBytes(StandardCharsets.UTF_8));
            backend.put("events", "event-002", "purchase".getBytes(StandardCharsets.UTF_8));

            // Get returns all values for the key
            List<byte[]> eventValues = backend.get("events", "event-001");
            System.out.println("Events for event-001: " + eventValues.size() + " values");

            eventValues.forEach(v -> System.out.println("  - " + new String(v, StandardCharsets.UTF_8)));

            // Delete removes ALL values for the key
            int deleted = backend.delete("events", "event-001");
            System.out.println("Deleted " + deleted + " event(s)");

            backend.clearTable("events");
        }
    }

    /**
     * Example 3: ORDERED_SET table (unique keys with sorted iteration).
     */
    private static void example_orderedSetTypeTable() {
        System.out.println("\n=== Example 3: ORDERED_SET Type Table ===");

        try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-3")) {
            backend.createTable("timeseries", EtsTable.TableType.ORDERED_SET);

            // OrderedSet maintains order by score (version/timestamp)
            byte[] data1 = "metric=42".getBytes(StandardCharsets.UTF_8);
            byte[] data2 = "metric=99".getBytes(StandardCharsets.UTF_8);

            backend.writeAtomicWithVersion("timeseries", "ts-001", data1, 1L);
            backend.writeAtomicWithVersion("timeseries", "ts-001", data2, 2L);

            // Get ordered entries
            List<byte[]> values = backend.get("timeseries", "ts-001");
            System.out.println("Timeseries values: " + values.size());

            values.forEach(v -> System.out.println("  - " + new String(v, StandardCharsets.UTF_8)));

            backend.clearTable("timeseries");
        }
    }

    /**
     * Example 4: Pattern matching queries.
     */
    private static void example_patternMatching() {
        System.out.println("\n=== Example 4: Pattern Matching ===");

        try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-4")) {
            backend.createTable("configs", EtsTable.TableType.SET);

            // Insert with hierarchical keys
            backend.put("configs", "app:database:host", "localhost".getBytes());
            backend.put("configs", "app:database:port", "5432".getBytes());
            backend.put("configs", "app:cache:host", "redis".getBytes());
            backend.put("configs", "app:logging:level", "DEBUG".getBytes());

            // Pattern: prefix match
            List<String> dbConfigs = backend.match("configs", "app:database:*");
            System.out.println("Database configs: " + dbConfigs);

            // Pattern: exact match
            List<String> hostOnly = backend.match("configs", "app:database:host");
            System.out.println("Exact match: " + hostOnly);

            // All keys
            List<String> allConfigs = backend.keys("configs");
            System.out.println("All config keys: " + allConfigs);

            backend.clearTable("configs");
        }
    }

    /**
     * Example 5: Subscribe to local table changes.
     */
    private static void example_subscriptions() throws InterruptedException {
        System.out.println("\n=== Example 5: Subscriptions ===");

        try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-5")) {
            backend.createTable("notifications", EtsTable.TableType.SET);

            // Subscribe to changes
            backend.subscribeTable("notifications", event -> {
                System.out.println("Notification: " + event.type() + " on " + event.key()
                        + " (from " + event.originNode() + ")");
            });

            // Make changes (will trigger notifications)
            backend.put("notifications", "notif-1", "alert".getBytes());
            Thread.sleep(100); // Allow notification to be processed

            backend.delete("notifications", "notif-1");
            Thread.sleep(100);

            backend.clearTable("notifications");
            Thread.sleep(100);

            backend.unsubscribeTable("notifications");
        }
    }

    /**
     * Example 6: Use as PersistenceBackend for process snapshots.
     */
    private static void example_persistenceBackend() {
        System.out.println("\n=== Example 6: PersistenceBackend ===");

        try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-6")) {
            // Save process state snapshot
            byte[] snapshot = "state=running, counter=42".getBytes();
            backend.save("proc-001", snapshot);

            // Load snapshot
            Optional<byte[]> loaded = backend.load("proc-001");
            System.out.println("Loaded state: " + (loaded.isPresent()
                    ? new String(loaded.get(), StandardCharsets.UTF_8)
                    : "not found"));

            // Check existence
            boolean exists = backend.exists("proc-001");
            System.out.println("State exists: " + exists);

            // Write atomic state + ACK
            byte[] state = "state=suspended".getBytes();
            byte[] ack = new byte[8];
            ack[7] = 1; // ack sequence 1
            backend.writeAtomic("proc-002", state, ack);

            // Get ACK sequence
            Optional<Long> ackSeq = backend.getAckSequence("proc-002");
            System.out.println("ACK sequence: " + (ackSeq.isPresent() ? ackSeq.get() : "none"));

            // List all persisted states
            Iterable<String> keys = backend.listKeys();
            System.out.println("Persisted keys: " + String.join(", ", keys));

            // Delete state
            backend.delete("proc-001");
            backend.delete("proc-002");
        }
    }

    /**
     * Example 7: Multi-node cluster scenario.
     */
    private static void example_multiNodeCluster() throws InterruptedException {
        System.out.println("\n=== Example 7: Multi-Node Cluster ===");
        System.out.println(
                "Note: In a real cluster, each node would be on a different machine/JVM");

        try (RedisEtsBackend node1 = new RedisEtsBackend("localhost", 6379, "node-A");
             RedisEtsBackend node2 = new RedisEtsBackend("localhost", 6379, "node-B")) {

            // Both nodes create same table
            node1.createTable("sharedData", EtsTable.TableType.SET);
            node2.createTable("sharedData", EtsTable.TableType.SET);

            // Node 1 writes
            node1.put("sharedData", "key-1", "data-from-node-1".getBytes());

            Thread.sleep(100); // Simulate network delay

            // Node 2 reads (from same Redis)
            List<byte[]> values = node2.get("sharedData", "key-1");
            System.out.println("Node 2 reads from Node 1: "
                    + (values.isEmpty() ? "not found"
                    : new String(values.get(0), StandardCharsets.UTF_8)));

            // Node 2 writes
            node2.put("sharedData", "key-2", "data-from-node-2".getBytes());

            Thread.sleep(100);

            // Node 1 reads (from same Redis)
            List<byte[]> node1Values = node1.get("sharedData", "key-2");
            System.out.println("Node 1 reads from Node 2: "
                    + (node1Values.isEmpty() ? "not found"
                    : new String(node1Values.get(0), StandardCharsets.UTF_8)));

            // Both nodes see consistent view
            List<String> node1Keys = node1.keys("sharedData");
            List<String> node2Keys = node2.keys("sharedData");
            System.out.println("Node 1 keys: " + node1Keys);
            System.out.println("Node 2 keys: " + node2Keys);
            System.out.println("Consistent view: " + node1Keys.equals(node2Keys));

            node1.clearTable("sharedData");
        }
    }
}
