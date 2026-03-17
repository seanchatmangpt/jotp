package io.github.seanchatmangpt.jotp.distributed;

/**
 * Example usage of ETS backend for JOTP distributed systems.
 *
 * <p>Demonstrates:
 *
 * <ul>
 *   <li>Creating and using different table types (Set, Bag, OrderedSet)
 *   <li>Pattern matching queries (ETS equivalent)
 *   <li>TTL-based automatic cleanup
 *   <li>Multi-node cluster replication via Redis
 *   <li>Vector clock consistency tracking
 *   <li>PersistenceBackend interface for process state snapshots
 * </ul>
 *
 * <p><strong>Prerequisites:</strong>
 *
 * <ul>
 *   <li>Redis running on localhost:6379
 *   <li>Java 26+ with preview features enabled
 * </ul>
 */
public class EtsBackendExample {

    /**
     * Example 1: Basic single-node ETS usage.
     */
    public static void example1_BasicUsage() {
        System.out.println("=== Example 1: Basic ETS Backend Usage ===\n");

        // Create ETS backend (single node)
        EtsBackend backend = new EtsBackend("node-1", 3600, "localhost", 6379);

        // Create tables with different semantics
        backend.createTable("users", EtsTable.TableType.SET);
        backend.createTable("events", EtsTable.TableType.BAG);
        backend.createTable("timeseries", EtsTable.TableType.ORDERED_SET);

        // Put data into SET table (unique keys)
        backend.put("users", "user:001", "Alice".getBytes());
        backend.put("users", "user:002", "Bob".getBytes());
        backend.put("users", "user:003", "Charlie".getBytes());

        // Get data
        var users = backend.get("users", "user:001");
        System.out.println("User 001: " + (users.isEmpty() ? "not found" : new String(users.get(0))));

        // Put data into BAG table (duplicates allowed)
        backend.put("events", "event-type:login", "user-1 logged in".getBytes());
        backend.put("events", "event-type:login", "user-2 logged in".getBytes());
        backend.put("events", "event-type:logout", "user-1 logged out".getBytes());

        var loginEvents = backend.get("events", "event-type:login");
        System.out.println("Login events count: " + loginEvents.size());

        // Put data into ORDERED_SET (maintains sort order)
        backend.put("timeseries", "2024-01-03", "data3".getBytes());
        backend.put("timeseries", "2024-01-01", "data1".getBytes());
        backend.put("timeseries", "2024-01-02", "data2".getBytes());

        var sortedKeys = backend.keys("timeseries");
        System.out.println("Timeseries keys (sorted): " + sortedKeys);

        backend.close();
        System.out.println();
    }

    /**
     * Example 2: Pattern matching queries (ETS equivalent).
     */
    public static void example2_PatternMatching() {
        System.out.println("=== Example 2: Pattern Matching Queries ===\n");

        EtsBackend backend = new EtsBackend("node-1", 3600, "localhost", 6379);
        backend.createTable("data", EtsTable.TableType.SET);

        // Insert test data
        backend.put("data", "user:001:profile", "{}".getBytes());
        backend.put("data", "user:001:settings", "{}".getBytes());
        backend.put("data", "user:002:profile", "{}".getBytes());
        backend.put("data", "admin:001:dashboard", "{}".getBytes());
        backend.put("data", "log:2024:01", "{}".getBytes());
        backend.put("data", "log:2024:02", "{}".getBytes());

        // Prefix match: ets:match(Table, "user:*")
        var userKeys = backend.match("data", "user:*");
        System.out.println("All user keys: " + userKeys);

        // Prefix match: ets:match(Table, "user:001:*")
        var user1Keys = backend.match("data", "user:001:*");
        System.out.println("User 001 keys: " + user1Keys);

        // Sandwich match: ets:match(Table, "log:2024:*")
        var logKeys = backend.match("data", "log:2024:*");
        System.out.println("2024 logs: " + logKeys);

        // Select with predicate
        var selectedKeys = backend.select("data", k -> k.contains("profile"));
        System.out.println("Profile keys: " + selectedKeys);

        backend.close();
        System.out.println();
    }

    /**
     * Example 3: Using ETS as PersistenceBackend for process state.
     */
    public static void example3_ProcessPersistence() {
        System.out.println("=== Example 3: Process State Persistence ===\n");

        EtsBackend backend = new EtsBackend("node-1", 3600, "localhost", 6379);

        // Save process state (implements PersistenceBackend interface)
        byte[] processState1 = "process-state-data-001".getBytes();
        byte[] processState2 = "process-state-data-002".getBytes();

        backend.save("payment-processor-001", processState1);
        backend.save("email-sender-002", processState2);

        System.out.println("Saved 2 process states");

        // Load process state
        var loaded = backend.load("payment-processor-001");
        System.out.println("Loaded state: "
                + (loaded.isPresent() ? new String(loaded.get()) : "not found"));

        // Check existence
        System.out.println("Exists payment-processor-001: " + backend.exists("payment-processor-001"));
        System.out.println("Exists unknown-process: " + backend.exists("unknown-process"));

        // List all process states
        var allStates = backend.listKeys();
        System.out.println("All saved states: " + allStates);

        // Write atomic state + ACK
        byte[] ackBytes = new byte[]{0, 0, 0, 0, 0, 0, 0, 1};
        backend.writeAtomic("payment-processor-003", "state-data".getBytes(), ackBytes);

        var ackSeq = backend.getAckSequence("payment-processor-003");
        System.out.println("ACK sequence: " + (ackSeq.isPresent() ? ackSeq.get() : "none"));

        backend.close();
        System.out.println();
    }

    /**
     * Example 4: Multi-node cluster replication.
     */
    public static void example4_ClusterReplication() throws Exception {
        System.out.println("=== Example 4: Multi-Node Cluster Replication ===\n");

        // Create three cluster nodes
        EtsBackend node1 = new EtsBackend("node-1", 3600, "localhost", 6379);
        EtsBackend node2 = new EtsBackend("node-2", 3600, "localhost", 6379);
        EtsBackend node3 = new EtsBackend("node-3", 3600, "localhost", 6379);

        // Create shared table on all nodes
        node1.createTable("shared", EtsTable.TableType.SET);
        node2.createTable("shared", EtsTable.TableType.SET);
        node3.createTable("shared", EtsTable.TableType.SET);

        // Subscribe to remote writes on node2 and node3
        java.util.concurrent.CountDownLatch replicated = new java.util.concurrent.CountDownLatch(2);

        node2.subscribeTable("shared", event -> {
            System.out.println("Node2 received: " + event.key() + " from " + event.originNode());
            replicated.countDown();
        });

        node3.subscribeTable("shared", event -> {
            System.out.println("Node3 received: " + event.key() + " from " + event.originNode());
            replicated.countDown();
        });

        // Give subscribers time to establish connections
        Thread.sleep(100);

        // Write from node1 (owner)
        System.out.println("Node1 writing data...");
        node1.put("shared", "key-1", "value-1".getBytes());

        // Wait for replication
        boolean success = replicated.await(2, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("Replication success: " + success);

        // Verify all nodes have the data
        System.out.println("Node1 has key-1: " + node1.get("shared", "key-1"));
        System.out.println("Node2 has key-1: " + node2.get("shared", "key-1"));
        System.out.println("Node3 has key-1: " + node3.get("shared", "key-1"));

        // Check vector clocks
        var vc1 = node1.getReplication().getVectorClock("shared");
        var vc2 = node2.getReplication().getVectorClock("shared");
        System.out.println("Node1 vector clock: " + vc1);
        System.out.println("Node2 vector clock: " + vc2);

        node1.close();
        node2.close();
        node3.close();
        System.out.println();
    }

    /**
     * Example 5: Vector clock causality detection.
     */
    public static void example5_VectorClocks() {
        System.out.println("=== Example 5: Vector Clock Causality ===\n");

        var vc1 = new EtsClusterReplication.VectorClock();
        vc1.increment("node-1").increment("node-1").increment("node-2");

        var vc2 = new EtsClusterReplication.VectorClock();
        vc2.increment("node-1").increment("node-1").increment("node-1");

        var vc3 = new EtsClusterReplication.VectorClock();
        vc3.increment("node-1").increment("node-3");

        System.out.println("VC1: " + vc1);
        System.out.println("VC2: " + vc2);
        System.out.println("VC3: " + vc3);

        System.out.println("\nVC1 happens before VC2: " + vc1.happensBefore(vc2));
        System.out.println("VC2 happens before VC1: " + vc2.happensBefore(vc1));
        System.out.println("VC1 concurrent with VC3: " + vc1.concurrent(vc3));

        System.out.println();
    }

    /**
     * Example 6: Table management and statistics.
     */
    public static void example6_TableManagement() {
        System.out.println("=== Example 6: Table Management ===\n");

        EtsBackend backend = new EtsBackend("node-1", 3600, "localhost", 6379);

        // Create multiple tables
        backend.createTable("users", EtsTable.TableType.SET);
        backend.createTable("events", EtsTable.TableType.BAG);
        backend.createTable("metrics", EtsTable.TableType.ORDERED_SET);

        // Add data
        backend.put("users", "u1", "alice".getBytes());
        backend.put("users", "u2", "bob".getBytes());
        backend.put("events", "evt", "login".getBytes());
        backend.put("events", "evt", "logout".getBytes());

        // List tables
        System.out.println("Tables: " + backend.listTables());

        // Get stats for each table
        for (String tableName : backend.listTables()) {
            EtsTable.TableStats stats = backend.stats(tableName);
            System.out.println("Table " + tableName + ": type=" + stats.type() + ", objects="
                    + stats.objectCount() + ", ageMs=" + stats.ageMillis());
        }

        // Clear a table
        backend.clearTable("events");
        System.out.println("After clearing events: " + backend.keys("events"));

        // Drop a table
        backend.dropTable("metrics");
        System.out.println("After dropping metrics: " + backend.listTables());

        backend.close();
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        example1_BasicUsage();
        example2_PatternMatching();
        example3_ProcessPersistence();
        example4_ClusterReplication();
        example5_VectorClocks();
        example6_TableManagement();

        System.out.println("\nAll examples completed successfully!");
    }
}
