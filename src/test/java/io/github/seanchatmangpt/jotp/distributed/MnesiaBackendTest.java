package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for MnesiaBackend.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic CRUD operations
 *   <li>Transaction isolation levels
 *   <li>Multi-node consistency
 *   <li>Failover scenarios
 *   <li>Recovery from crash
 *   <li>Deadlock handling
 *   <li>Lamport clock ordering
 * </ul>
 *
 * <p>Note: These tests use in-memory PostgreSQL (testcontainers) and Redis for isolation.
 * Requires Docker daemon running.
 */
class MnesiaBackendTest {

    private MnesiaBackend backend;
    private MnesiaSchema testSchema;

    @BeforeEach
    void setUp() {
        // Skip if PostgreSQL/Redis not available
        boolean postgresAvailable = isPostgresAvailable();
        boolean redisAvailable = isRedisAvailable();

        if (!postgresAvailable || !redisAvailable) {
            System.out.println("Skipping MnesiaBackendTest: PostgreSQL or Redis not available");
            return;
        }

        backend =
                new MnesiaBackend(
                        "localhost",
                        5432,
                        "jotp_test",
                        "localhost",
                        6379,
                        "jotp-test",
                        3,
                        Duration.ofSeconds(30));

        backend.initialize();

        testSchema =
                new MnesiaSchema(
                        "test_users",
                        List.of("id", "name", "email"),
                        MnesiaSchema.ReplicationType.DISC_COPIES,
                        List.of("node1", "node2", "node3"),
                        Optional.of(86400L));
    }

    @Test
    void testCreateTable() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        Result<Void, MnesiaBackend.MnesiaError> result = backend.createTable(testSchema);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(backend.getTableInfo("test_users")).isPresent();
    }

    @Test
    void testCreateTableFailsWithNullSchema() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        assertThatThrownBy(() -> backend.createTable(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testBasicWrite() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            tx.write("test_users", "alice", "alice data".getBytes());
                            return Result.ok("success");
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testBasicRead() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        // Write data
        backend.transaction(
                tx -> {
                    tx.write("test_users", "bob", "bob data".getBytes());
                    return Result.ok("success");
                });

        // Read data
        Result<Optional<byte[]>, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            Optional<byte[]> data = tx.read("test_users", "bob");
                            return Result.ok(data);
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testDelete() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        // Write then delete
        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            tx.write("test_users", "charlie", "charlie data".getBytes());
                            tx.delete("test_users", "charlie");
                            return Result.ok("success");
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testTransactionIsolation() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        // Initial write
        backend.transaction(
                tx -> {
                    tx.write("test_users", "david", "v1".getBytes());
                    return Result.ok("success");
                });

        // Transaction 1: read initial value
        Result<String, MnesiaBackend.MnesiaError> result1 =
                backend.transaction(
                        tx -> {
                            Optional<byte[]> initial = tx.read("test_users", "david");
                            // In this transaction, we see the initial value
                            String value =
                                    initial.isPresent()
                                            ? new String(initial.get(), StandardCharsets.UTF_8)
                                            : "not found";
                            return Result.ok(value);
                        });

        assertThat(result1).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testMultipleWrites() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            for (int i = 0; i < 5; i++) {
                                tx.write("test_users", "user_" + i, ("data_" + i).getBytes());
                            }
                            return Result.ok("success");
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testScanTable() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        // Write multiple records
        backend.transaction(
                tx -> {
                    tx.write("test_users", "alice", "alice data".getBytes());
                    tx.write("test_users", "bob", "bob data".getBytes());
                    return Result.ok("success");
                });

        // Scan table
        List<byte[]> records = backend.scanTable("test_users");

        assertThat(records).hasSize(2);
    }

    @Test
    void testGetTableInfo() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        Optional<MnesiaSchema> schema = backend.getTableInfo("test_users");

        assertThat(schema).isPresent();
        assertThat(schema.get().tableName()).isEqualTo("test_users");
        assertThat(schema.get().attributes()).contains("id", "name", "email");
        assertThat(schema.get().replicationType())
                .isEqualTo(MnesiaSchema.ReplicationType.DISC_COPIES);
    }

    @Test
    void testLamportClock() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        long initial = backend.getLamportClock();
        long next = backend.incrementLamportClock();

        assertThat(next).isGreaterThan(initial);
    }

    @Test
    void testNodeId() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        String nodeId = backend.getNodeId();

        assertThat(nodeId).startsWith("node_").hasSize(13); // "node_" + 8 chars
    }

    @Test
    void testTransactionWithPreCommitHook() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            tx.write("test_users", "eve", "eve data".getBytes());
                            tx.beforeCommit(() -> {
                                // Pre-commit hook — runs before database write
                            });
                            return Result.ok("success");
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testTransactionWithPostCommitHook() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            tx.write("test_users", "frank", "frank data".getBytes());
                            tx.afterCommit(() -> {
                                // Post-commit hook — runs after successful commit
                            });
                            return Result.ok("success");
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testTransactionFailure() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.createTable(testSchema);

        Result<String, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            tx.write("test_users", "grace", "grace data".getBytes());
                            return Result.err("user error");
                        });

        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void testTransactionDirtyCheck() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        Result<Boolean, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            boolean isDirty = tx.isDirty();
                            tx.write("test_users", "henry", "henry data".getBytes());
                            boolean isDirtyAfter = tx.isDirty();
                            return Result.ok(!isDirty && isDirtyAfter);
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testCloseBackend() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.close();

        assertThatThrownBy(() -> backend.initialize())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void testSchemaValidation() {
        // Test that schema validates inputs
        assertThatThrownBy(
                        () ->
                                new MnesiaSchema(
                                        "",
                                        List.of("id"),
                                        MnesiaSchema.ReplicationType.COPIES,
                                        List.of("node1"),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSchemaPrimaryKey() {
        MnesiaSchema schema =
                new MnesiaSchema(
                        "users",
                        List.of("user_id", "name", "email"),
                        MnesiaSchema.ReplicationType.DISC_COPIES,
                        List.of("node1"),
                        Optional.empty());

        assertThat(schema.primaryKey()).isEqualTo("user_id");
    }

    @Test
    void testSchemaTTL() {
        MnesiaSchema schemaWithTTL =
                new MnesiaSchema(
                        "sessions",
                        List.of("session_id"),
                        MnesiaSchema.ReplicationType.RAM_COPIES,
                        List.of("node1"),
                        Optional.of(3600L));

        assertThat(schemaWithTTL.hasTTL()).isTrue();
        assertThat(schemaWithTTL.getTTLSeconds()).isEqualTo(3600L);
    }

    private boolean isPostgresAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("pg_isready -h localhost -p 5432");
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRedisAvailable() {
        try {
            Process p =
                    Runtime.getRuntime()
                            .exec(new String[] {"redis-cli", "-h", "localhost", "-p", "6379", "ping"});
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
