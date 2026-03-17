package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EtsTableTest {

    private EtsTable setTable;
    private EtsTable bagTable;
    private EtsTable orderedSetTable;

    @BeforeEach
    void setup() {
        setTable = new EtsTable.Set("test-set");
        bagTable = new EtsTable.Bag("test-bag");
        orderedSetTable = new EtsTable.OrderedSet("test-ordered");
    }

    // ===== SET TABLE TESTS =====

    @Test
    void testSetPutAndGet() {
        setTable.put("key1", "value1".getBytes());
        List<byte[]> result = setTable.get("key1");

        assertThat(result).hasSize(1);
        assertThat(new String(result.get(0))).isEqualTo("value1");
    }

    @Test
    void testSetOverwrite() {
        setTable.put("key", "value1".getBytes());
        setTable.put("key", "value2".getBytes());

        List<byte[]> result = setTable.get("key");
        assertThat(result).hasSize(1);
        assertThat(new String(result.get(0))).isEqualTo("value2");
    }

    @Test
    void testSetDelete() {
        setTable.put("key", "value".getBytes());
        int deleted = setTable.delete("key");

        assertThat(deleted).isEqualTo(1);
        assertThat(setTable.get("key")).isEmpty();
    }

    @Test
    void testSetContains() {
        setTable.put("key", "value".getBytes());

        assertThat(setTable.contains("key")).isTrue();
        assertThat(setTable.contains("nonexistent")).isFalse();
    }

    @Test
    void testSetKeys() {
        setTable.put("key1", "v1".getBytes());
        setTable.put("key2", "v2".getBytes());
        setTable.put("key3", "v3".getBytes());

        List<String> keys = setTable.keys();
        assertThat(keys).containsExactlyInAnyOrder("key1", "key2", "key3");
    }

    @Test
    void testSetValues() {
        setTable.put("key1", "v1".getBytes());
        setTable.put("key2", "v2".getBytes());

        List<byte[]> values = setTable.values();
        assertThat(values).hasSize(2);
    }

    @Test
    void testSetClear() {
        setTable.put("key1", "v1".getBytes());
        setTable.put("key2", "v2".getBytes());

        setTable.clear();

        assertThat(setTable.keys()).isEmpty();
    }

    @Test
    void testSetStats() {
        setTable.put("key1", "v1".getBytes());
        setTable.put("key2", "v2".getBytes());

        EtsTable.TableStats stats = setTable.stats();
        assertThat(stats.name()).isEqualTo("test-set");
        assertThat(stats.type()).isEqualTo(EtsTable.TableType.SET);
        assertThat(stats.objectCount()).isEqualTo(2);
    }

    // ===== BAG TABLE TESTS =====

    @Test
    void testBagAllowsDuplicateKeys() {
        bagTable.put("key", "value1".getBytes());
        bagTable.put("key", "value2".getBytes());
        bagTable.put("key", "value3".getBytes());

        List<byte[]> result = bagTable.get("key");
        assertThat(result).hasSize(3);
    }

    @Test
    void testBagMultipleKeys() {
        bagTable.put("key1", "v1".getBytes());
        bagTable.put("key1", "v1b".getBytes());
        bagTable.put("key2", "v2".getBytes());

        assertThat(bagTable.get("key1")).hasSize(2);
        assertThat(bagTable.get("key2")).hasSize(1);
    }

    @Test
    void testBagDelete() {
        bagTable.put("key", "v1".getBytes());
        bagTable.put("key", "v2".getBytes());

        int deleted = bagTable.delete("key");
        assertThat(deleted).isEqualTo(2);
        assertThat(bagTable.get("key")).isEmpty();
    }

    @Test
    void testBagStats() {
        bagTable.put("key1", "v1".getBytes());
        bagTable.put("key1", "v1b".getBytes());
        bagTable.put("key2", "v2".getBytes());

        EtsTable.TableStats stats = bagTable.stats();
        assertThat(stats.type()).isEqualTo(EtsTable.TableType.BAG);
        assertThat(stats.objectCount()).isEqualTo(3);
    }

    @Test
    void testBagClear() {
        bagTable.put("key1", "v1".getBytes());
        bagTable.put("key1", "v1b".getBytes());

        bagTable.clear();

        assertThat(bagTable.keys()).isEmpty();
        assertThat(bagTable.values()).isEmpty();
    }

    // ===== ORDERED SET TABLE TESTS =====

    @Test
    void testOrderedSetMaintainsSortOrder() {
        orderedSetTable.put("charlie", "c".getBytes());
        orderedSetTable.put("alpha", "a".getBytes());
        orderedSetTable.put("bravo", "b".getBytes());

        List<String> keys = orderedSetTable.keys();
        assertThat(keys).containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    void testOrderedSetOverwrite() {
        orderedSetTable.put("key", "v1".getBytes());
        orderedSetTable.put("key", "v2".getBytes());

        List<byte[]> result = orderedSetTable.get("key");
        assertThat(result).hasSize(1);
    }

    @Test
    void testOrderedSetDelete() {
        orderedSetTable.put("key1", "v1".getBytes());
        orderedSetTable.put("key2", "v2".getBytes());

        orderedSetTable.delete("key1");

        List<String> keys = orderedSetTable.keys();
        assertThat(keys).containsExactly("key2");
    }

    @Test
    void testOrderedSetValues() {
        orderedSetTable.put("c", "c-value".getBytes());
        orderedSetTable.put("a", "a-value".getBytes());
        orderedSetTable.put("b", "b-value".getBytes());

        List<byte[]> values = orderedSetTable.values();
        assertThat(values).hasSize(3);
    }

    // ===== PATTERN MATCHING TESTS =====

    @Test
    void testMatchPrefixPattern() {
        setTable.put("user:001", "alice".getBytes());
        setTable.put("user:002", "bob".getBytes());
        setTable.put("admin:001", "charlie".getBytes());

        List<String> matches = setTable.match("user:*");
        assertThat(matches).containsExactlyInAnyOrder("user:001", "user:002");
    }

    @Test
    void testMatchSuffixPattern() {
        setTable.put("prefix:alice:user", "data".getBytes());
        setTable.put("prefix:bob:user", "data".getBytes());
        setTable.put("prefix:alice:admin", "data".getBytes());

        List<String> matches = setTable.match("prefix:*:user");
        assertThat(matches)
                .containsExactlyInAnyOrder("prefix:alice:user", "prefix:bob:user");
    }

    @Test
    void testMatchExactKey() {
        setTable.put("exact-key", "value".getBytes());
        setTable.put("other-key", "value".getBytes());

        List<String> matches = setTable.match("exact-key");
        assertThat(matches).containsExactly("exact-key");
    }

    @Test
    void testMatchNoMatches() {
        setTable.put("key1", "value".getBytes());
        setTable.put("key2", "value".getBytes());

        List<String> matches = setTable.match("missing:*");
        assertThat(matches).isEmpty();
    }

    @Test
    void testMatchComplexPattern() {
        setTable.put("data:2024-01-01:processed", "v".getBytes());
        setTable.put("data:2024-01-02:processed", "v".getBytes());
        setTable.put("data:2024-01-03:failed", "v".getBytes());

        List<String> matches = setTable.match("data:*:processed");
        assertThat(matches)
                .containsExactlyInAnyOrder(
                        "data:2024-01-01:processed",
                        "data:2024-01-02:processed");
    }

    // ===== SELECT TESTS =====

    @Test
    void testSelectWithSimplePredicate() {
        setTable.put("key1", "v".getBytes());
        setTable.put("key2", "v".getBytes());
        setTable.put("other", "v".getBytes());

        List<String> selected = setTable.select(k -> k.startsWith("key"));
        assertThat(selected).containsExactlyInAnyOrder("key1", "key2");
    }

    @Test
    void testSelectWithComplexPredicate() {
        setTable.put("user:001", "v".getBytes());
        setTable.put("user:002", "v".getBytes());
        setTable.put("user:003", "v".getBytes());
        setTable.put("admin:001", "v".getBytes());

        List<String> selected = setTable.select(k -> k.matches("user:00[13]"));
        assertThat(selected).containsExactlyInAnyOrder("user:001", "user:003");
    }

    @Test
    void testSelectEmpty() {
        setTable.put("key1", "v".getBytes());

        List<String> selected = setTable.select(k -> k.startsWith("missing"));
        assertThat(selected).isEmpty();
    }

    // ===== TABLE TYPE TESTS =====

    @Test
    void testSetType() {
        assertThat(setTable.type()).isEqualTo(EtsTable.TableType.SET);
    }

    @Test
    void testBagType() {
        assertThat(bagTable.type()).isEqualTo(EtsTable.TableType.BAG);
    }

    @Test
    void testOrderedSetType() {
        assertThat(orderedSetTable.type()).isEqualTo(EtsTable.TableType.ORDERED_SET);
    }

    // ===== CONCURRENCY TESTS =====

    @Test
    void testConcurrentPuts() throws Exception {
        int threadCount = 10;
        int putsPerThread = 50;
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < putsPerThread; j++) {
                    setTable.put("key-" + threadId + "-" + j, ("v" + j).getBytes());
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertThat(setTable.keys()).hasSize(threadCount * putsPerThread);
    }

    @Test
    void testConcurrentBagInserts() throws Exception {
        int threadCount = 5;
        int insertsPerThread = 40;
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < insertsPerThread; j++) {
                    bagTable.put("bag-key", ("value-" + j).getBytes());
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        List<byte[]> values = bagTable.get("bag-key");
        assertThat(values).hasSize(threadCount * insertsPerThread);
    }

    @Test
    void testConcurrentReadWrite() throws Exception {
        // Pre-populate
        for (int i = 0; i < 50; i++) {
            setTable.put("pre-" + i, "v".getBytes());
        }

        var readThreads = new Thread[5];
        var writeThreads = new Thread[5];

        for (int i = 0; i < 5; i++) {
            readThreads[i] = new Thread(() -> {
                for (int j = 0; j < 30; j++) {
                    setTable.get("pre-" + (j % 50));
                }
            });

            final int writerId = i;
            writeThreads[i] = new Thread(() -> {
                for (int j = 0; j < 30; j++) {
                    setTable.put("write-" + writerId + "-" + j, "v".getBytes());
                }
            });
        }

        for (Thread t : readThreads) t.start();
        for (Thread t : writeThreads) t.start();
        for (Thread t : readThreads) t.join();
        for (Thread t : writeThreads) t.join();

        assertThat(setTable.keys()).hasSizeGreaterThanOrEqualTo(50);
    }

    @Test
    void testConcurrentMatching() throws Exception {
        // Pre-populate
        for (int i = 0; i < 100; i++) {
            setTable.put("prefix:key-" + i, "v".getBytes());
        }

        var threads = new Thread[10];
        var matchCounts = new int[10];

        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                List<String> matches = setTable.match("prefix:*");
                matchCounts[threadId] = matches.size();
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        for (int count : matchCounts) {
            assertThat(count).isEqualTo(100);
        }
    }
}
