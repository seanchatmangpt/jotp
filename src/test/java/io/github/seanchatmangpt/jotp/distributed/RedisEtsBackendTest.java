package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import redis.clients.jedis.*;

/**
 * Unit tests for RedisEtsBackend.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>SET, BAG, and ORDERED_SET table types
 *   <li>CRUD operations
 *   <li>Pattern matching
 *   <li>PersistenceBackend contract
 *   <li>Error handling
 * </ul>
 *
 * <p>Requires Redis running on localhost:6379
 */
@DisplayName("RedisEtsBackend")
public class RedisEtsBackendTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private RedisEtsBackend backend;

    @BeforeEach
    void setUp() {
        backend = new RedisEtsBackend(REDIS_HOST, REDIS_PORT, "test-node", 3600);
        // Clear any previous test data
        try (Jedis jedis = new JedisPool(REDIS_HOST, REDIS_PORT).getResource()) {
            Set<String> keys = jedis.keys("jotp:ets:test:*");
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        } catch (Exception e) {
            // Ignore if Redis not running
        }
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @Nested
    @DisplayName("SET table operations")
    class SetTableTests {

        @Test
        @DisplayName("should put and get value")
        void testPutAndGet() {
            backend.createTable("test:set", EtsTable.TableType.SET);
            byte[] value = "test-value".getBytes(StandardCharsets.UTF_8);

            backend.put("test:set", "key-1", value);
            List<byte[]> results = backend.get("test:set", "key-1");

            assertThat(results).hasSize(1);
            assertThat(results.get(0)).isEqualTo(value);
        }

        @Test
        @DisplayName("should overwrite duplicate keys")
        void testSetOverwrite() {
            backend.createTable("test:set", EtsTable.TableType.SET);
            byte[] value1 = "first".getBytes();
            byte[] value2 = "second".getBytes();

            backend.put("test:set", "key-1", value1);
            backend.put("test:set", "key-1", value2);
            List<byte[]> results = backend.get("test:set", "key-1");

            assertThat(results).hasSize(1);
            assertThat(results.get(0)).isEqualTo(value2);
        }

        @Test
        @DisplayName("should delete key")
        void testDelete() {
            backend.createTable("test:set", EtsTable.TableType.SET);
            backend.put("test:set", "key-1", "value".getBytes());

            int deleted = backend.delete("test:set", "key-1");

            assertThat(deleted).isEqualTo(1);
            assertThat(backend.get("test:set", "key-1")).isEmpty();
        }

        @Test
        @DisplayName("should check containment")
        void testContains() {
            backend.createTable("test:set", EtsTable.TableType.SET);
            backend.put("test:set", "key-1", "value".getBytes());

            assertThat(backend.contains("test:set", "key-1")).isTrue();
            assertThat(backend.contains("test:set", "missing")).isFalse();
        }

        @Test
        @DisplayName("should list all keys")
        void testKeys() {
            backend.createTable("test:set", EtsTable.TableType.SET);
            backend.put("test:set", "key-1", "value".getBytes());
            backend.put("test:set", "key-2", "value".getBytes());

            List<String> keys = backend.keys("test:set");

            assertThat(keys).containsExactlyInAnyOrder("key-1", "key-2");
        }
    }

    @Nested
    @DisplayName("BAG table operations")
    class BagTableTests {

        @Test
        @DisplayName("should allow duplicate keys")
        void testBagDuplicates() {
            backend.createTable("test:bag", EtsTable.TableType.BAG);
            byte[] value1 = "first".getBytes();
            byte[] value2 = "second".getBytes();

            backend.put("test:bag", "event-1", value1);
            backend.put("test:bag", "event-1", value2);
            List<byte[]> results = backend.get("test:bag", "event-1");

            assertThat(results).hasSize(2);
            assertThat(results).contains(value1, value2);
        }

        @Test
        @DisplayName("should delete all values for key")
        void testBagDeleteAll() {
            backend.createTable("test:bag", EtsTable.TableType.BAG);
            backend.put("test:bag", "event-1", "value1".getBytes());
            backend.put("test:bag", "event-1", "value2".getBytes());
            backend.put("test:bag", "event-2", "value3".getBytes());

            int deleted = backend.delete("test:bag", "event-1");

            assertThat(deleted).isEqualTo(2);
            assertThat(backend.get("test:bag", "event-1")).isEmpty();
            assertThat(backend.get("test:bag", "event-2")).hasSize(1);
        }

        @Test
        @DisplayName("should list unique keys")
        void testBagKeys() {
            backend.createTable("test:bag", EtsTable.TableType.BAG);
            backend.put("test:bag", "key-1", "value1".getBytes());
            backend.put("test:bag", "key-1", "value2".getBytes());
            backend.put("test:bag", "key-2", "value3".getBytes());

            List<String> keys = backend.keys("test:bag");

            assertThat(keys).containsExactlyInAnyOrder("key-1", "key-2");
        }
    }

    @Nested
    @DisplayName("ORDERED_SET table operations")
    class OrderedSetTableTests {

        @Test
        @DisplayName("should maintain order by version")
        void testOrderedSetOrder() {
            backend.createTable("test:ordered", EtsTable.TableType.ORDERED_SET);
            byte[] value1 = "first".getBytes();
            byte[] value2 = "second".getBytes();

            backend.writeAtomicWithVersion("test:ordered", "ts-1", value1, 1L);
            backend.writeAtomicWithVersion("test:ordered", "ts-1", value2, 2L);

            List<byte[]> results = backend.get("test:ordered", "ts-1");

            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("should be idempotent by version")
        void testOrderedSetIdempotence() {
            backend.createTable("test:ordered", EtsTable.TableType.ORDERED_SET);
            byte[] value = "data".getBytes();

            backend.writeAtomicWithVersion("test:ordered", "ts-1", value, 5L);
            backend.writeAtomicWithVersion("test:ordered", "ts-1", "ignored".getBytes(), 3L);

            List<byte[]> results = backend.get("test:ordered", "ts-1");

            assertThat(results).hasSize(1);
            assertThat(results.get(0)).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Pattern matching")
    class PatternMatchingTests {

        @Test
        @DisplayName("should match prefix patterns")
        void testPrefixMatch() {
            backend.createTable("test:configs", EtsTable.TableType.SET);
            backend.put("test:configs", "app:db:host", "localhost".getBytes());
            backend.put("test:configs", "app:db:port", "5432".getBytes());
            backend.put("test:configs", "app:cache:ttl", "3600".getBytes());

            List<String> dbConfigs = backend.match("test:configs", "app:db:*");

            assertThat(dbConfigs).containsExactlyInAnyOrder("app:db:host", "app:db:port");
        }

        @Test
        @DisplayName("should match exact keys")
        void testExactMatch() {
            backend.createTable("test:configs", EtsTable.TableType.SET);
            backend.put("test:configs", "exact", "value".getBytes());

            List<String> results = backend.match("test:configs", "exact");

            assertThat(results).containsExactly("exact");
        }

        @Test
        @DisplayName("should return empty for non-matching pattern")
        void testNoMatch() {
            backend.createTable("test:configs", EtsTable.TableType.SET);
            backend.put("test:configs", "foo", "value".getBytes());

            List<String> results = backend.match("test:configs", "bar:*");

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("Selection")
    class SelectionTests {

        @Test
        @DisplayName("should select with predicate")
        void testSelectPredicate() {
            backend.createTable("test:data", EtsTable.TableType.SET);
            backend.put("test:data", "prod-1", "value".getBytes());
            backend.put("test:data", "dev-2", "value".getBytes());
            backend.put("test:data", "prod-3", "value".getBytes());

            List<String> prodKeys = backend.select("test:data", k -> k.startsWith("prod"));

            assertThat(prodKeys).containsExactlyInAnyOrder("prod-1", "prod-3");
        }
    }

    @Nested
    @DisplayName("Table management")
    class TableManagementTests {

        @Test
        @DisplayName("should get table statistics")
        void testStats() {
            backend.createTable("test:stats", EtsTable.TableType.SET);
            backend.put("test:stats", "key-1", "value".getBytes());
            backend.put("test:stats", "key-2", "value".getBytes());

            EtsTable.TableStats stats = backend.stats("test:stats");

            assertThat(stats.objectCount()).isEqualTo(2);
            assertThat(stats.type()).isEqualTo(EtsTable.TableType.SET);
            assertThat(stats.name()).isEqualTo("test:stats");
        }

        @Test
        @DisplayName("should clear table")
        void testClearTable() {
            backend.createTable("test:clear", EtsTable.TableType.SET);
            backend.put("test:clear", "key-1", "value".getBytes());
            backend.put("test:clear", "key-2", "value".getBytes());

            backend.clearTable("test:clear");

            assertThat(backend.keys("test:clear")).isEmpty();
        }

        @Test
        @DisplayName("should drop table")
        void testDropTable() {
            backend.createTable("test:drop", EtsTable.TableType.SET);
            backend.put("test:drop", "key-1", "value".getBytes());

            backend.dropTable("test:drop");

            assertThat(backend.listTables()).doesNotContain("test:drop");
        }

        @Test
        @DisplayName("should list tables")
        void testListTables() {
            backend.createTable("test:table1", EtsTable.TableType.SET);
            backend.createTable("test:table2", EtsTable.TableType.BAG);

            Set<String> tables = backend.listTables();

            assertThat(tables).contains("test:table1", "test:table2");
        }
    }

    @Nested
    @DisplayName("PersistenceBackend contract")
    class PersistenceBackendTests {

        @Test
        @DisplayName("should save and load state")
        void testSaveLoad() {
            byte[] state = "state-data".getBytes();
            backend.save("proc-1", state);

            Optional<byte[]> loaded = backend.load("proc-1");

            assertThat(loaded).isPresent().contains(state);
        }

        @Test
        @DisplayName("should check state existence")
        void testExists() {
            backend.save("proc-1", "data".getBytes());

            assertThat(backend.exists("proc-1")).isTrue();
            assertThat(backend.exists("proc-nonexistent")).isFalse();
        }

        @Test
        @DisplayName("should delete state")
        void testDeleteState() {
            backend.save("proc-1", "data".getBytes());
            backend.delete("proc-1");

            assertThat(backend.exists("proc-1")).isFalse();
        }

        @Test
        @DisplayName("should write atomic state + ACK")
        void testWriteAtomic() {
            byte[] state = "state".getBytes();
            byte[] ack = new byte[8];
            ack[7] = 5; // ack sequence 5

            backend.writeAtomic("proc-1", state, ack);

            Optional<byte[]> loaded = backend.load("proc-1");
            Optional<Long> ackSeq = backend.getAckSequence("proc-1");

            assertThat(loaded).isPresent().contains(state);
            assertThat(ackSeq).isPresent().contains(5L);
        }

        @Test
        @DisplayName("should list all persisted keys")
        void testListKeys() {
            backend.save("proc-1", "data1".getBytes());
            backend.save("proc-2", "data2".getBytes());

            List<String> keys = new ArrayList<>();
            backend.listKeys().forEach(keys::add);

            assertThat(keys).contains("proc-1", "proc-2");
        }

        @Test
        @DisplayName("should delete ACK marker")
        void testDeleteAck() {
            byte[] ack = new byte[8];
            ack[7] = 3;
            backend.writeAtomic("proc-1", "state".getBytes(), ack);

            backend.deleteAck("proc-1");

            assertThat(backend.getAckSequence("proc-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Subscriptions")
    class SubscriptionTests {

        @Test
        @DisplayName("should notify on put")
        void testSubscribeOnPut() throws InterruptedException {
            backend.createTable("test:notify", EtsTable.TableType.SET);

            BlockingQueue<RedisEtsBackend.ChangeEvent> events = new LinkedBlockingQueue<>();
            backend.subscribeTable("test:notify", events::offer);

            backend.put("test:notify", "key-1", "value".getBytes());

            RedisEtsBackend.ChangeEvent event = events.poll(1, TimeUnit.SECONDS);
            assertThat(event)
                    .isNotNull()
                    .extracting(e -> e.type())
                    .isEqualTo(RedisEtsBackend.ChangeEvent.ChangeType.PUT);
            assertThat(event.key()).isEqualTo("key-1");
        }

        @Test
        @DisplayName("should notify on delete")
        void testSubscribeOnDelete() throws InterruptedException {
            backend.createTable("test:notify", EtsTable.TableType.SET);
            backend.put("test:notify", "key-1", "value".getBytes());

            BlockingQueue<RedisEtsBackend.ChangeEvent> events = new LinkedBlockingQueue<>();
            backend.subscribeTable("test:notify", events::offer);

            backend.delete("test:notify", "key-1");

            RedisEtsBackend.ChangeEvent event = events.poll(1, TimeUnit.SECONDS);
            assertThat(event)
                    .isNotNull()
                    .extracting(e -> e.type())
                    .isEqualTo(RedisEtsBackend.ChangeEvent.ChangeType.DELETE);
        }

        @Test
        @DisplayName("should unsubscribe")
        void testUnsubscribe() throws InterruptedException {
            backend.createTable("test:notify", EtsTable.TableType.SET);

            BlockingQueue<RedisEtsBackend.ChangeEvent> events = new LinkedBlockingQueue<>();
            backend.subscribeTable("test:notify", events::offer);
            backend.unsubscribeTable("test:notify");

            backend.put("test:notify", "key-1", "value".getBytes());

            RedisEtsBackend.ChangeEvent event = events.poll(500, TimeUnit.MILLISECONDS);
            assertThat(event).isNull();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should throw on null table name")
        void testNullTableName() {
            assertThatThrownBy(() -> backend.createTable(null, EtsTable.TableType.SET))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on null key")
        void testNullKey() {
            backend.createTable("test:errors", EtsTable.TableType.SET);

            assertThatThrownBy(() -> backend.put("test:errors", null, "value".getBytes()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on null value")
        void testNullValue() {
            backend.createTable("test:errors", EtsTable.TableType.SET);

            assertThatThrownBy(() -> backend.put("test:errors", "key-1", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should throw on closed backend")
        void testClosedBackend() {
            backend.close();

            assertThatThrownBy(() -> backend.createTable("test:errors", EtsTable.TableType.SET))
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("closed");
        }
    }

    @Nested
    @DisplayName("Auto-creation behavior")
    class AutoCreationTests {

        @Test
        @DisplayName("should auto-create table as SET if not exists")
        void testAutoCreateAsSet() {
            // Don't explicitly create
            backend.put("test:auto", "key-1", "value".getBytes());

            EtsTable.TableStats stats = backend.stats("test:auto");

            assertThat(stats.type()).isEqualTo(EtsTable.TableType.SET);
        }
    }
}
