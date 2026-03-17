package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EtsBackendTest {

    private EtsBackend backend;

    @BeforeEach
    void setup() {
        // Use high TTL for tests to avoid expiration during test runs
        backend = new EtsBackend("test-node", 3600, "localhost", 6379);
        backend.createTable("test", EtsTable.TableType.SET);
        backend.createTable("events", EtsTable.TableType.BAG);
    }

    @AfterEach
    void teardown() throws Exception {
        backend.close();
    }

    // ===== Basic PUT/GET/DELETE =====

    @Test
    void testPutAndGet() {
        byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
        backend.put("test", "key1", value);

        List<byte[]> retrieved = backend.get("test", "key1");
        assertThat(retrieved).hasSize(1).contains(value);
    }

    @Test
    void testGetNonExistentKey() {
        List<byte[]> retrieved = backend.get("test", "nonexistent");
        assertThat(retrieved).isEmpty();
    }

    @Test
    void testDelete() {
        byte[] value = "world".getBytes(StandardCharsets.UTF_8);
        backend.put("test", "key2", value);

        int deleted = backend.delete("test", "key2");
        assertThat(deleted).isEqualTo(1);

        List<byte[]> retrieved = backend.get("test", "key2");
        assertThat(retrieved).isEmpty();
    }

    @Test
    void testDeleteNonExistent() {
        int deleted = backend.delete("test", "nonexistent");
        assertThat(deleted).isZero();
    }

    @Test
    void testSetTableOverwrite() {
        backend.put("test", "key", "value1".getBytes());
        backend.put("test", "key", "value2".getBytes());

        List<byte[]> values = backend.get("test", "key");
        assertThat(values).hasSize(1);
        assertThat(new String(values.get(0))).isEqualTo("value2");
    }

    @Test
    void testBagTableDuplicates() {
        backend.put("events", "event-1", "data1".getBytes());
        backend.put("events", "event-1", "data2".getBytes());
        backend.put("events", "event-1", "data3".getBytes());

        List<byte[]> values = backend.get("events", "event-1");
        assertThat(values).hasSize(3);
    }

    // ===== Pattern Matching =====

    @Test
    void testMatchPrefixPattern() {
        backend.put("test", "user:1", "alice".getBytes());
        backend.put("test", "user:2", "bob".getBytes());
        backend.put("test", "admin:1", "charlie".getBytes());

        List<String> matches = backend.match("test", "user:*");
        assertThat(matches).containsExactlyInAnyOrder("user:1", "user:2");
    }

    @Test
    void testMatchSandwichPattern() {
        backend.put("test", "prefix:middle:suffix", "value".getBytes());
        backend.put("test", "prefix:other:suffix", "value".getBytes());
        backend.put("test", "prefix:middle:wrong", "value".getBytes());

        List<String> matches = backend.match("test", "prefix:*:suffix");
        assertThat(matches)
                .containsExactlyInAnyOrder(
                        "prefix:middle:suffix",
                        "prefix:other:suffix");
    }

    @Test
    void testMatchExactKey() {
        backend.put("test", "exact-key", "value".getBytes());
        backend.put("test", "other-key", "value".getBytes());

        List<String> matches = backend.match("test", "exact-key");
        assertThat(matches).containsExactly("exact-key");
    }

    @Test
    void testMatchNoResults() {
        backend.put("test", "key1", "value".getBytes());
        backend.put("test", "key2", "value".getBytes());

        List<String> matches = backend.match("test", "missing:*");
        assertThat(matches).isEmpty();
    }

    // ===== Select =====

    @Test
    void testSelectWithPredicate() {
        backend.put("test", "user:1", "alice".getBytes());
        backend.put("test", "user:2", "bob".getBytes());
        backend.put("test", "admin:1", "charlie".getBytes());

        List<String> selected = backend.select("test", k -> k.startsWith("user:"));
        assertThat(selected).containsExactlyInAnyOrder("user:1", "user:2");
    }

    @Test
    void testSelectWithNumberFilter() {
        backend.put("test", "id:001", "value".getBytes());
        backend.put("test", "id:002", "value".getBytes());
        backend.put("test", "id:003", "value".getBytes());

        List<String> selected =
                backend.select("test", k -> k.matches("id:00[13]"));
        assertThat(selected).containsExactlyInAnyOrder("id:001", "id:003");
    }

    // ===== Keys and Stats =====

    @Test
    void testKeys() {
        backend.put("test", "key1", "v1".getBytes());
        backend.put("test", "key2", "v2".getBytes());
        backend.put("test", "key3", "v3".getBytes());

        List<String> keys = backend.keys("test");
        assertThat(keys).containsExactlyInAnyOrder("key1", "key2", "key3");
    }

    @Test
    void testTableStats() {
        backend.put("test", "key1", "value".getBytes());
        backend.put("test", "key2", "value".getBytes());

        EtsTable.TableStats stats = backend.stats("test");
        assertThat(stats.name()).isEqualTo("test");
        assertThat(stats.type()).isEqualTo(EtsTable.TableType.SET);
        assertThat(stats.objectCount()).isEqualTo(2);
        assertThat(stats.ageMillis()).isLessThan(1000);
    }

    @Test
    void testListTables() {
        backend.createTable("table1", EtsTable.TableType.SET);
        backend.createTable("table2", EtsTable.TableType.BAG);

        assertThat(backend.listTables())
                .contains("test", "events", "table1", "table2");
    }

    // ===== PersistenceBackend Interface =====

    @Test
    void testSaveAndLoad() {
        byte[] snapshot = "process-state".getBytes();
        backend.save("proc-001", snapshot);

        Optional<byte[]> loaded = backend.load("proc-001");
        assertThat(loaded).isPresent().contains(snapshot);
    }

    @Test
    void testLoadNonExistent() {
        Optional<byte[]> loaded = backend.load("nonexistent");
        assertThat(loaded).isEmpty();
    }

    @Test
    void testDelete_PersistenceBackend() {
        backend.save("proc-001", "state".getBytes());
        backend.delete("proc-001");

        Optional<byte[]> loaded = backend.load("proc-001");
        assertThat(loaded).isEmpty();
    }

    @Test
    void testExists() {
        backend.save("proc-001", "state".getBytes());
        assertThat(backend.exists("proc-001")).isTrue();
        assertThat(backend.exists("nonexistent")).isFalse();
    }

    @Test
    void testListKeys() {
        backend.save("proc-001", "state1".getBytes());
        backend.save("proc-002", "state2".getBytes());
        backend.save("proc-003", "state3".getBytes());

        Iterable<String> keys = backend.listKeys();
        assertThat(keys).containsExactlyInAnyOrder("proc-001", "proc-002", "proc-003");
    }

    @Test
    void testWriteAtomic() {
        byte[] stateBytes = "state-data".getBytes();
        byte[] ackBytes = "0000000000000001".getBytes();

        backend.writeAtomic("proc-001", stateBytes, ackBytes);

        Optional<byte[]> state = backend.load("proc-001");
        assertThat(state).isPresent().contains(stateBytes);

        Optional<Long> ackSeq = backend.getAckSequence("proc-001");
        assertThat(ackSeq).isPresent();
    }

    @Test
    void testGetAckSequence() {
        byte[] stateBytes = "state".getBytes();
        long expectedSeq = 42L;
        byte[] ackBytes = longToBytes(expectedSeq);

        backend.writeAtomic("proc-001", stateBytes, ackBytes);

        Optional<Long> ackSeq = backend.getAckSequence("proc-001");
        assertThat(ackSeq).isPresent().contains(expectedSeq);
    }

    @Test
    void testDeleteAck() {
        byte[] stateBytes = "state".getBytes();
        byte[] ackBytes = longToBytes(42L);

        backend.writeAtomic("proc-001", stateBytes, ackBytes);
        backend.deleteAck("proc-001");

        Optional<Long> ackSeq = backend.getAckSequence("proc-001");
        assertThat(ackSeq).isEmpty();
    }

    // ===== Table Management =====

    @Test
    void testCreateTable_Auto() {
        // Accessing non-existent table should auto-create as SET
        backend.put("auto-table", "key", "value".getBytes());

        List<byte[]> values = backend.get("auto-table", "key");
        assertThat(values).hasSize(1);
    }

    @Test
    void testClearTable() {
        backend.put("test", "key1", "v1".getBytes());
        backend.put("test", "key2", "v2".getBytes());

        backend.clearTable("test");

        assertThat(backend.keys("test")).isEmpty();
    }

    @Test
    void testDropTable() {
        backend.put("test", "key1", "v1".getBytes());

        backend.dropTable("test");

        assertThat(backend.listTables()).doesNotContain("test");
    }

    // ===== Error Handling =====

    @Test
    void testSaveWithNullKey() {
        assertThatThrownBy(() -> backend.save(null, "data".getBytes()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSaveWithNullSnapshot() {
        assertThatThrownBy(() -> backend.save("key", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testLoadWithNullKey() {
        assertThatThrownBy(() -> backend.load(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testOperationAfterClose() {
        backend.close();

        assertThatThrownBy(() -> backend.put("test", "key", "value".getBytes()))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void testMultipleClose() {
        // Should not throw
        backend.close();
        backend.close();
    }

    // ===== Concurrency =====

    @Test
    void testConcurrentWrites() throws Exception {
        int threadCount = 10;
        int writesPerThread = 100;
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < writesPerThread; j++) {
                    String key = "key:" + threadId + ":" + j;
                    backend.put("test", key, ("value-" + j).getBytes());
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        List<String> keys = backend.keys("test");
        assertThat(keys).hasSize(threadCount * writesPerThread);
    }

    @Test
    void testConcurrentReadWrite() throws Exception {
        // Pre-populate
        for (int i = 0; i < 100; i++) {
            backend.put("test", "key:" + i, "value".getBytes());
        }

        var readers = new Thread[5];
        var writers = new Thread[5];

        for (int i = 0; i < 5; i++) {
            readers[i] = new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    backend.get("test", "key:" + (j % 100));
                }
            });

            final int writerId = i;
            writers[i] = new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    backend.put("test", "new-key:" + writerId + ":" + j, "data".getBytes());
                }
            });
        }

        for (Thread t : readers) t.start();
        for (Thread t : writers) t.start();
        for (Thread t : readers) t.join();
        for (Thread t : writers) t.join();

        assertThat(backend.keys("test")).hasSizeGreaterThanOrEqualTo(100);
    }

    @Test
    void testBagConcurrentInserts() throws Exception {
        int threadCount = 10;
        int insertsPerThread = 50;
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < insertsPerThread; j++) {
                    backend.put("events", "evt", ("value-" + threadId + "-" + j).getBytes());
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        List<byte[]> values = backend.get("events", "evt");
        assertThat(values).hasSize(threadCount * insertsPerThread);
    }

    // ===== OrderedSet =====

    @Test
    void testOrderedSetMaintainsOrder() {
        backend.createTable("ordered", EtsTable.TableType.ORDERED_SET);

        backend.put("ordered", "c", "v".getBytes());
        backend.put("ordered", "a", "v".getBytes());
        backend.put("ordered", "b", "v".getBytes());

        List<String> keys = backend.keys("ordered");
        assertThat(keys).containsExactly("a", "b", "c");
    }

    // ===== Helper =====

    private byte[] longToBytes(long value) {
        return new byte[]{
            (byte) (value >> 56),
            (byte) (value >> 48),
            (byte) (value >> 40),
            (byte) (value >> 32),
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }
}
