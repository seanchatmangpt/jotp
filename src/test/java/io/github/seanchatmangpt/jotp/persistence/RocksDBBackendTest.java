package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RocksDBBackend}.
 *
 * <p>Verifies RocksDB persistence backend with atomic writes, ACK operations, and crash recovery.
 */
@DisplayName("RocksDBBackend Tests")
class RocksDBBackendTest {

    @TempDir Path tempDir;

    private RocksDBBackend backend;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("rocksdb-test");
        Files.createDirectories(dataDir);
        backend = new RocksDBBackend(dataDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    @DisplayName("Should save and load snapshots")
    void save_andLoad_returnsSnapshot() {
        String key = "test-key";
        byte[] data = "test-data".getBytes();

        backend.save(key, data);
        Optional<byte[]> loaded = backend.load(key);

        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(data);
    }

    @Test
    @DisplayName("Should overwrite existing snapshots")
    void save_overwritesExistingSnapshot() {
        String key = "test-key";
        byte[] data1 = "version-1".getBytes();
        byte[] data2 = "version-2".getBytes();

        backend.save(key, data1);
        backend.save(key, data2);

        Optional<byte[]> loaded = backend.load(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(data2);
    }

    @Test
    @DisplayName("Should return empty for non-existent keys")
    void load_returnsEmptyForNonExistent() {
        Optional<byte[]> loaded = backend.load("non-existent");

        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Should delete snapshots")
    void delete_removesSnapshot() {
        String key = "test-key";
        byte[] data = "test-data".getBytes();

        backend.save(key, data);
        assertThat(backend.exists(key)).isTrue();

        backend.delete(key);
        assertThat(backend.exists(key)).isFalse();
        assertThat(backend.load(key)).isEmpty();
    }

    @Test
    @DisplayName("Should handle delete of non-existent key gracefully")
    void delete_handlesNonExistentKey() {
        assertThatCode(() -> backend.delete("non-existent")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should check snapshot existence")
    void exists_returnsCorrectStatus() {
        String key = "test-key";
        byte[] data = "test-data".getBytes();

        assertThat(backend.exists(key)).isFalse();

        backend.save(key, data);
        assertThat(backend.exists(key)).isTrue();
    }

    @Test
    @DisplayName("Should list all keys")
    void listKeys_returnsAllKeys() {
        backend.save("key1", "data1".getBytes());
        backend.save("key2", "data2".getBytes());
        backend.save("key3", "data3".getBytes());

        Iterable<String> keys = backend.listKeys();

        assertThat(keys).containsExactlyInAnyOrder("key1", "key2", "key3");
    }

    @Test
    @DisplayName("Should return empty iterable when no keys exist")
    void listKeys_returnsEmptyWhenNoKeys() {
        Iterable<String> keys = backend.listKeys();

        assertThat(keys).isEmpty();
    }

    @Test
    @DisplayName("Should write atomic state and ACK batches")
    void writeAtomic_writesStateAndAck() {
        String key = "atomic-key";
        byte[] stateBytes = "state-data".getBytes();
        byte[] ackBytes = "42".getBytes();

        backend.writeAtomic(key, stateBytes, ackBytes);

        // Verify state was written
        Optional<byte[]> loadedState = backend.load(key);
        assertThat(loadedState).isPresent();
        assertThat(loadedState.get()).isEqualTo(stateBytes);

        // Verify ACK was written
        Optional<Long> ackSeq = backend.getAckSequence(key);
        assertThat(ackSeq).isPresent();
        assertThat(ackSeq.get()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Should overwrite atomic writes")
    void writeAtomic_overwritesExisting() {
        String key = "atomic-key";
        byte[] state1 = "state-v1".getBytes();
        byte[] ack1 = "1".getBytes();
        byte[] state2 = "state-v2".getBytes();
        byte[] ack2 = "2".getBytes();

        backend.writeAtomic(key, state1, ack1);
        backend.writeAtomic(key, state2, ack2);

        Optional<byte[]> loadedState = backend.load(key);
        assertThat(loadedState).isPresent();
        assertThat(loadedState.get()).isEqualTo(state2);

        Optional<Long> ackSeq = backend.getAckSequence(key);
        assertThat(ackSeq).isPresent();
        assertThat(ackSeq.get()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should get ACK sequence")
    void getAckSequence_returnsSequence() {
        String key = "ack-key";
        byte[] stateBytes = "state".getBytes();
        byte[] ackBytes = "123".getBytes();

        backend.writeAtomic(key, stateBytes, ackBytes);

        Optional<Long> ackSeq = backend.getAckSequence(key);
        assertThat(ackSeq).isPresent();
        assertThat(ackSeq.get()).isEqualTo(123L);
    }

    @Test
    @DisplayName("Should return empty for non-existent ACK")
    void getAckSequence_returnsEmptyForNonExistent() {
        Optional<Long> ackSeq = backend.getAckSequence("non-existent");

        assertThat(ackSeq).isEmpty();
    }

    @Test
    @DisplayName("Should delete ACK markers")
    void deleteAck_removesAckMarker() {
        String key = "ack-key";
        byte[] stateBytes = "state".getBytes();
        byte[] ackBytes = "1".getBytes();

        backend.writeAtomic(key, stateBytes, ackBytes);
        assertThat(backend.getAckSequence(key)).isPresent();

        backend.deleteAck(key);
        assertThat(backend.getAckSequence(key)).isEmpty();
    }

    @Test
    @DisplayName("Should check for duplicate messages")
    void isDuplicate_detectsDuplicates() {
        String key = "dup-key";
        byte[] stateBytes = "state".getBytes();
        byte[] ackBytes = "10".getBytes();

        backend.writeAtomic(key, stateBytes, ackBytes);

        assertThat(backend.isDuplicate(key, 5)).isTrue(); // Less than ACK
        assertThat(backend.isDuplicate(key, 10)).isTrue(); // Equal to ACK
        assertThat(backend.isDuplicate(key, 15)).isFalse(); // Greater than ACK
    }

    @Test
    @DisplayName("Should return false for duplicates when no ACK exists")
    void isDuplicate_returnsFalseWhenNoAck() {
        assertThat(backend.isDuplicate("no-ack-key", 1)).isFalse();
    }

    @Test
    @DisplayName("Should handle null key gracefully")
    void save_handlesNullKey() {
        assertThatThrownBy(() -> backend.save(null, "data".getBytes()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("key");
    }

    @Test
    @DisplayName("Should handle null snapshot gracefully")
    void save_handlesNullSnapshot() {
        assertThatThrownBy(() -> backend.save("key", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("snapshot");
    }

    @Test
    @DisplayName("Should throw after close")
    void save_throwsAfterClose() throws Exception {
        backend.close();

        assertThatThrownBy(() -> backend.save("key", "data".getBytes()))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Should handle large data sets")
    void save_handlesLargeData() {
        String key = "large-key";
        byte[] largeData = new byte[10 * 1024 * 1024]; // 10 MB
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        backend.save(key, largeData);

        Optional<byte[]> loaded = backend.load(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).hasSize(10 * 1024 * 1024);
        assertThat(loaded.get()).isEqualTo(largeData);
    }

    @Test
    @DisplayName("Should support concurrent operations")
    void operations_supportConcurrentAccess() throws InterruptedException {
        var threads = new java.util.ArrayList<Thread>();
        var latch = new java.util.concurrent.CountDownLatch(10);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Exception>();

        for (int i = 0; i < 10; i++) {
            final int index = i;
            var thread =
                    new Thread(
                            () -> {
                                try {
                                    String key = "concurrent-" + index;
                                    byte[] data = ("data-" + index).getBytes();

                                    backend.save(key, data);
                                    backend.load(key);
                                    backend.exists(key);

                                    if (index % 2 == 0) {
                                        backend.delete(key);
                                    }
                                } catch (Exception e) {
                                    errors.add(e);
                                } finally {
                                    latch.countDown();
                                }
                            });
            threads.add(thread);
            thread.start();
        }

        assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Should handle multiple column families")
    void constructor_supportsMultipleColumnFamilies() throws Exception {
        var cfNames = java.util.List.of("default", "cf1", "cf2");

        RocksDBBackend multiCFBackend = new RocksDBBackend(dataDir, cfNames);

        assertThat(multiCFBackend).isNotNull();
        assertThat(multiCFBackend.getDb()).isNotNull();

        multiCFBackend.close();
    }

    @Test
    @DisplayName("Should provide data directory path")
    void getDataDir_returnsPath() {
        Path returnedDir = backend.getDataDir();

        assertThat(returnedDir).isEqualTo(dataDir);
    }

    @Test
    @DisplayName("Should provide RocksDB instance")
    void getDb_returnsInstance() {
        var db = backend.getDb();

        assertThat(db).isNotNull();
    }

    @Test
    @DisplayName("Should provide sync write options")
    void getSyncWriteOptions_returnsOptions() {
        var options = backend.getSyncWriteOptions();

        assertThat(options).isNotNull();
        assertThat(options.sync()).isTrue();
    }

    @Test
    @DisplayName("Should handle special characters in keys")
    void save_handlesSpecialCharactersInKeys() {
        String specialKey = "key:with-special/chars\\and\tspaces";

        backend.save(specialKey, "data".getBytes());

        Optional<byte[]> loaded = backend.load(specialKey);
        assertThat(loaded).isPresent();
    }

    @Test
    @DisplayName("Should persist data across backend instances")
    void save_persistsAcrossInstances() throws Exception {
        String key = "persistent-key";
        byte[] data = "persistent-data".getBytes();

        backend.save(key, data);

        // Close and recreate backend
        backend.close();
        backend = new RocksDBBackend(dataDir);

        Optional<byte[]> loaded = backend.load(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(data);
    }
}
