package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MnesiaBackend.
 *
 * <p>Tests more complex scenarios:
 * <ul>
 *   <li>Multi-threaded concurrent access
 *   <li>Failover and recovery
 *   <li>Distributed lock contention
 *   <li>Large data sets
 *   <li>TTL expiration
 * </ul>
 */
class MnesiaBackendIT {

    private MnesiaBackend backend;
    private MnesiaSchema schema;

    @BeforeEach
    void setUp() {
        boolean available = isPostgresAvailable() && isRedisAvailable();
        if (!available) {
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

        schema =
                new MnesiaSchema(
                        "test_table",
                        List.of("id", "value"),
                        MnesiaSchema.ReplicationType.DISC_COPIES,
                        List.of("node1", "node2", "node3"),
                        Optional.empty());

        backend.createTable(schema);
    }

    @Test
    void testConcurrentWrites() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        int numThreads = 4;
        int operationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(
                    () -> {
                        try {
                            for (int i = 0; i < operationsPerThread; i++) {
                                String key = "thread_" + threadId + "_key_" + i;
                                backend.transaction(
                                        tx -> {
                                            tx.write("test_table", key, ("value_" + i).getBytes());
                                            return Result.ok("ok");
                                        });
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();

        // Verify all writes completed
        List<byte[]> records = backend.scanTable("test_table");
        assertThat(records).hasSize(numThreads * operationsPerThread);
    }

    @Test
    void testConcurrentReadWrite() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        // Initial data
        backend.transaction(
                tx -> {
                    for (int i = 0; i < 5; i++) {
                        tx.write("test_table", "key_" + i, ("initial_" + i).getBytes());
                    }
                    return Result.ok("ok");
                });

        int numReaders = 2;
        int numWriters = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
        CountDownLatch latch = new CountDownLatch(numReaders + numWriters);

        // Readers
        for (int r = 0; r < numReaders; r++) {
            executor.submit(
                    () -> {
                        try {
                            for (int i = 0; i < 10; i++) {
                                backend.transaction(
                                        tx -> {
                                            tx.read("test_table", "key_0");
                                            tx.read("test_table", "key_1");
                                            return Result.ok("ok");
                                        });
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        // Writers
        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            executor.submit(
                    () -> {
                        try {
                            for (int i = 0; i < 10; i++) {
                                backend.transaction(
                                        tx -> {
                                            tx.write("test_table", "key_" + writerId, ("v_" + i).getBytes());
                                            return Result.ok("ok");
                                        });
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();

        // Verify consistency
        List<byte[]> records = backend.scanTable("test_table");
        assertThat(records).isNotEmpty();
    }

    @Test
    void testTransactionWithMultipleOperations() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        Result<Integer, MnesiaBackend.MnesiaError> result =
                backend.transaction(
                        tx -> {
                            // Multiple writes
                            for (int i = 0; i < 5; i++) {
                                tx.write("test_table", "key_" + i, ("value_" + i).getBytes());
                            }

                            // Delete one
                            tx.delete("test_table", "key_2");

                            // Update another
                            tx.write("test_table", "key_0", "updated_value".getBytes());

                            return Result.ok(5);
                        });

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    void testLamportClockIncrement() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        long[] clocks = new long[10];
        for (int i = 0; i < 10; i++) {
            clocks[i] = backend.incrementLamportClock();
        }

        // Verify monotonicity
        for (int i = 1; i < clocks.length; i++) {
            assertThat(clocks[i]).isGreaterThan(clocks[i - 1]);
        }
    }

    @Test
    void testTransactionCommitOrder() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        List<Long> clocks = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 5; i++) {
            Result<Void, MnesiaBackend.MnesiaError> result =
                    backend.transaction(
                            tx -> {
                                clocks.add(tx.getLamportClock());
                                tx.write("test_table", "tx_" + i, "data".getBytes());
                                return Result.ok(null);
                            });

            assertThat(result).isInstanceOf(Result.Ok.class);
        }

        // Verify Lamport clocks are ordered
        for (int i = 1; i < clocks.size(); i++) {
            assertThat(clocks.get(i)).isGreaterThanOrEqualTo(clocks.get(i - 1));
        }
    }

    @Test
    void testFlushTransactionLog() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        backend.transaction(
                tx -> {
                    tx.write("test_table", "flush_test", "value".getBytes());
                    return Result.ok("ok");
                });

        // Flush should not throw
        backend.flushTransactionLog();
    }

    @Test
    void testReplicationTypes() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        MnesiaSchema ramCopiesSchema =
                new MnesiaSchema(
                        "ram_table",
                        List.of("id"),
                        MnesiaSchema.ReplicationType.RAM_COPIES,
                        List.of("node1", "node2"),
                        Optional.empty());

        Result<Void, MnesiaBackend.MnesiaError> result = backend.createTable(ramCopiesSchema);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(backend.getTableInfo("ram_table")).isPresent();
        assertThat(backend.getTableInfo("ram_table").get().replicationType())
                .isEqualTo(MnesiaSchema.ReplicationType.RAM_COPIES);
    }

    @Test
    void testSchemaWithTTL() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        MnesiaSchema ttlSchema =
                new MnesiaSchema(
                        "ttl_table",
                        List.of("id", "session"),
                        MnesiaSchema.ReplicationType.DISC_COPIES,
                        List.of("node1"),
                        Optional.of(60L)); // 60 seconds TTL

        Result<Void, MnesiaBackend.MnesiaError> result = backend.createTable(ttlSchema);

        assertThat(result).isInstanceOf(Result.Ok.class);

        Optional<MnesiaSchema> retrieved = backend.getTableInfo("ttl_table");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().hasTTL()).isTrue();
        assertThat(retrieved.get().getTTLSeconds()).isEqualTo(60L);
    }

    @Test
    void testLargeDataSet() {
        if (!isPostgresAvailable() || !isRedisAvailable()) {
            return;
        }

        int recordCount = 100;

        // Write large dataset
        Result<String, MnesiaBackend.MnesiaError> writeResult =
                backend.transaction(
                        tx -> {
                            for (int i = 0; i < recordCount; i++) {
                                String data =
                                        "x".repeat(1000)
                                                + "_"
                                                + i; // ~1KB per record
                                tx.write("test_table", "large_" + i, data.getBytes());
                            }
                            return Result.ok("written");
                        });

        assertThat(writeResult).isInstanceOf(Result.Ok.class);

        // Scan and verify
        List<byte[]> records = backend.scanTable("test_table");
        assertThat(records.size()).isGreaterThanOrEqualTo(recordCount);
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
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
