package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.distributed.MnesiaBackend;
import io.github.seanchatmangpt.jotp.distributed.MnesiaSchema;
import io.github.seanchatmangpt.jotp.distributed.MnesiaTransaction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Example: Using Mnesia-style distributed database backend in JOTP.
 *
 * <p>This example demonstrates how to use the MnesiaBackend to build a distributed,
 * fault-tolerant application following Erlang OTP patterns.
 *
 * <p><strong>Features demonstrated:</strong>
 *
 * <ul>
 *   <li>Creating tables with schema definition
 *   <li>Running transactions with MVCC isolation
 *   <li>Reading and writing data
 *   <li>Transaction hooks for distributed coordination
 *   <li>Multi-node replication
 *   <li>Automatic recovery
 * </ul>
 *
 * <p><strong>Running this example:</strong>
 *
 * <pre>{@code
 * # Requires PostgreSQL and Redis running
 * docker run -d --name postgres -e POSTGRES_DB=jotp_example postgres:15
 * docker run -d --name redis redis:7
 *
 * # Run the example
 * java -cp target/classes io.github.seanchatmangpt.jotp.examples.MnesiaBackendExample
 *
 * # Cleanup
 * docker stop postgres redis && docker rm postgres redis
 * }</pre>
 *
 * @see MnesiaBackend
 * @see MnesiaSchema
 * @see MnesiaTransaction
 */
public class MnesiaBackendExample {

    public static void main(String[] args) throws Exception {
        System.out.println("JOTP Mnesia Backend Example");
        System.out.println("============================\n");

        // Initialize backend
        MnesiaBackend backend =
                new MnesiaBackend(
                        "localhost", 5432, "jotp_example",
                        "localhost", 6379, "jotp-example",
                        3, // replication factor
                        Duration.ofSeconds(30)); // lock timeout

        try {
            backend.initialize();
            System.out.println("✓ Backend initialized");
            System.out.println("  Node ID: " + backend.getNodeId());
            System.out.println("  Initial Lamport Clock: " + backend.getLamportClock() + "\n");

            // Example 1: Create a users table
            example1CreateTable(backend);

            // Example 2: Basic CRUD operations
            example2BasicCRUD(backend);

            // Example 3: Transaction with multiple operations
            example3MultiOperation(backend);

            // Example 4: Transaction hooks for distributed coordination
            example4TransactionHooks(backend);

            // Example 5: Query and scan operations
            example5Scanning(backend);

            // Example 6: Error handling
            example6ErrorHandling(backend);

            System.out.println("\n✓ All examples completed successfully!");
        } finally {
            backend.close();
            System.out.println("✓ Backend closed");
        }
    }

    /**
     * Example 1: Create a table with schema.
     */
    private static void example1CreateTable(MnesiaBackend backend) {
        System.out.println("Example 1: Create Table");
        System.out.println("----------------------");

        // Define schema (like Erlang's mnesia:create_table/2)
        MnesiaSchema usersSchema =
                new MnesiaSchema(
                        "users",
                        List.of("user_id", "name", "email", "created_at"),
                        MnesiaSchema.ReplicationType.DISC_COPIES,
                        List.of("node1", "node2", "node3"),
                        Optional.of(86400L)); // 24-hour TTL

        Result<Void, MnesiaBackend.MnesiaError> result = backend.createTable(usersSchema);

        if (result instanceof Result.Ok) {
            System.out.println("✓ Created 'users' table");
            System.out.println("  Attributes: " + usersSchema.attributes());
            System.out.println("  Replication: " + usersSchema.replicationType());
            System.out.println("  Replicas: " + usersSchema.replicaNodes());
            System.out.println("  TTL: " + usersSchema.getTTLSeconds() + " seconds\n");
        } else {
            System.out.println("✗ Failed to create table: " + result);
        }
    }

    /**
     * Example 2: Basic CRUD operations inside a transaction.
     */
    private static void example2BasicCRUD(MnesiaBackend backend) {
        System.out.println("Example 2: Basic CRUD Operations");
        System.out.println("--------------------------------");

        // Create
        Result<String, MnesiaBackend.MnesiaError> createResult =
                backend.transaction(
                        tx -> {
                            String userId = "user_001";
                            String userData = "name=Alice|email=alice@example.com";
                            tx.write("users", userId, userData.getBytes(StandardCharsets.UTF_8));
                            return Result.ok("Created: " + userId);
                        });

        if (createResult instanceof Result.Ok<?, ?> okResult) {
            System.out.println("✓ CREATE: " + okResult.value());
        }

        // Read
        Result<Optional<String>, MnesiaBackend.MnesiaError> readResult =
                backend.transaction(
                        tx -> {
                            Optional<byte[]> data = tx.read("users", "user_001");
                            Optional<String> userData =
                                    data.map(d -> new String(d, StandardCharsets.UTF_8));
                            return Result.ok(userData);
                        });

        if (readResult instanceof Result.Ok<?, ?> okResult) {
            @SuppressWarnings("unchecked")
            Optional<String> userData = (Optional<String>) okResult.value();
            if (userData.isPresent()) {
                System.out.println("✓ READ: " + userData.get());
            }
        }

        // Update
        Result<String, MnesiaBackend.MnesiaError> updateResult =
                backend.transaction(
                        tx -> {
                            String updatedData = "name=Alice Cooper|email=alice.cooper@example.com";
                            tx.write("users", "user_001", updatedData.getBytes(StandardCharsets.UTF_8));
                            return Result.ok("Updated: user_001");
                        });

        if (updateResult instanceof Result.Ok<?, ?> okResult) {
            System.out.println("✓ UPDATE: " + okResult.value());
        }

        // Delete
        Result<String, MnesiaBackend.MnesiaError> deleteResult =
                backend.transaction(
                        tx -> {
                            tx.delete("users", "user_001");
                            return Result.ok("Deleted: user_001");
                        });

        if (deleteResult instanceof Result.Ok<?, ?> okResult) {
            System.out.println("✓ DELETE: " + okResult.value() + "\n");
        }
    }

    /**
     * Example 3: Transaction with multiple operations.
     */
    private static void example3MultiOperation(MnesiaBackend backend) {
        System.out.println("Example 3: Multi-Operation Transaction");
        System.out.println("-------------------------------------");

        Result<Integer, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            // Write multiple records
                            tx.write("users", "user_101", "Alice".getBytes());
                            tx.write("users", "user_102", "Bob".getBytes());
                            tx.write("users", "user_103", "Charlie".getBytes());

                            // Conditional delete
                            Optional<byte[]> userData = tx.read("users", "user_102");
                            if (userData.isPresent()) {
                                String name = new String(userData.get(), StandardCharsets.UTF_8);
                                if (name.equals("Bob")) {
                                    tx.delete("users", "user_102");
                                }
                            }

                            // Update
                            tx.write("users", "user_101", "Alice Updated".getBytes());

                            return Result.ok(2); // 2 net writes
                        });

        if (result instanceof Result.Ok<?, ?> okResult) {
            @SuppressWarnings("unchecked")
            Integer netWrites = (Integer) okResult.value();
            System.out.println("✓ Transaction committed with " + netWrites + " net writes\n");
        }
    }

    /**
     * Example 4: Transaction hooks for distributed coordination.
     */
    private static void example4TransactionHooks(MnesiaBackend backend) {
        System.out.println("Example 4: Transaction Hooks");
        System.out.println("----------------------------");

        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            tx.write("users", "user_200", "David".getBytes());

                            // Pre-commit hook: runs before database write
                            tx.beforeCommit(
                                    () -> {
                                        System.out.println("  → Pre-commit: Validating user data");
                                        // Perform validation, acquire resources, etc.
                                    });

                            // Post-commit hook: runs after successful commit
                            tx.afterCommit(
                                    () -> {
                                        System.out.println("  → Post-commit: Notifying subscribers");
                                        // Publish events, update caches, etc.
                                    });

                            return Result.ok("committed");
                        });

        if (result instanceof Result.Ok) {
            System.out.println("✓ Transaction with hooks completed\n");
        }
    }

    /**
     * Example 5: Scanning and querying tables.
     */
    private static void example5Scanning(MnesiaBackend backend) {
        System.out.println("Example 5: Scanning and Querying");
        System.out.println("--------------------------------");

        // Write some test data
        backend.transaction(
                tx -> {
                    for (int i = 0; i < 5; i++) {
                        tx.write(
                                "users",
                                "scan_user_" + i,
                                ("User " + i).getBytes(StandardCharsets.UTF_8));
                    }
                    return Result.ok("ok");
                });

        // Scan all records
        List<byte[]> allRecords = backend.scanTable("users");
        System.out.println("✓ Scanned table: " + allRecords.size() + " records");

        // Get table schema info
        Optional<MnesiaSchema> tableInfo = backend.getTableInfo("users");
        if (tableInfo.isPresent()) {
            MnesiaSchema schema = tableInfo.get();
            System.out.println("  Primary Key: " + schema.primaryKey());
            System.out.println("  Replication Type: " + schema.replicationType());
            System.out.println("  Has TTL: " + schema.hasTTL() + "\n");
        }
    }

    /**
     * Example 6: Error handling.
     */
    private static void example6ErrorHandling(MnesiaBackend backend) {
        System.out.println("Example 6: Error Handling");
        System.out.println("------------------------");

        // Transaction that fails
        Result<String, MnesiaBackend.MnesiaError> failedResult =
                backend.transaction(
                        tx -> {
                            tx.write("users", "user_300", "Eve".getBytes());
                            // Return error
                            return Result.err("user validation failed");
                        });

        if (failedResult instanceof Result.Err<?, ?> errResult) {
            System.out.println("✓ Error handled: " + errResult.error() + "\n");
        }
    }
}
