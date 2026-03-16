package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InMemoryBackend}.
 *
 * <p>Verifies in-memory persistence backend with proper lifecycle management and thread safety.
 */
@DisplayName("InMemoryBackend Tests")
class InMemoryBackendTest {

    private InMemoryBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryBackend();
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
    @DisplayName("Should throw on load after close")
    void load_throwsAfterClose() throws Exception {
        backend.close();

        assertThatThrownBy(() -> backend.load("key"))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Should throw on exists after close")
    void exists_throwsAfterClose() throws Exception {
        backend.close();

        assertThatThrownBy(() -> backend.exists("key"))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Should throw on delete after close")
    void delete_throwsAfterClose() throws Exception {
        backend.close();

        assertThatThrownBy(() -> backend.delete("key"))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Should throw on listKeys after close")
    void listKeys_throwsAfterClose() throws Exception {
        backend.close();

        assertThatThrownBy(() -> backend.listKeys())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Should clear all data on close")
    void close_clearsAllData() throws Exception {
        backend.save("key1", "data1".getBytes());
        backend.save("key2", "data2".getBytes());

        backend.close();

        // Create new backend - should be empty
        InMemoryBackend newBackend = new InMemoryBackend();
        assertThat(newBackend.listKeys()).isEmpty();
        newBackend.close();
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
    @DisplayName("Should support concurrent operations safely")
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
    @DisplayName("Should handle special characters in keys")
    void save_handlesSpecialCharactersInKeys() {
        String specialKey = "key:with-special/chars\\and\tspaces";

        backend.save(specialKey, "data".getBytes());

        Optional<byte[]> loaded = backend.load(specialKey);
        assertThat(loaded).isPresent();
    }

    @Test
    @DisplayName("Should handle empty byte arrays")
    void save_handlesEmptyByteArray() {
        String key = "empty-key";
        byte[] emptyData = new byte[0];

        backend.save(key, emptyData);

        Optional<byte[]> loaded = backend.load(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEmpty();
    }

    @Test
    @DisplayName("Should be idempotent on close")
    void close_isIdempotent() throws Exception {
        backend.close();
        assertThatCode(() -> backend.close()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle multiple save/load cycles")
    void operations_handleMultipleCycles() {
        for (int i = 0; i < 100; i++) {
            String key = "cycle-" + i;
            byte[] data = ("data-" + i).getBytes();

            backend.save(key, data);

            Optional<byte[]> loaded = backend.load(key);
            assertThat(loaded).isPresent();
            assertThat(loaded.get()).isEqualTo(data);
        }

        assertThat(backend.listKeys()).hasSize(100);
    }

    @Test
    @DisplayName("Should not persist data across instances")
    void save_doesNotPersistAcrossInstances() throws Exception {
        String key = "volatile-key";
        byte[] data = "volatile-data".getBytes();

        backend.save(key, data);

        // Close and create new backend
        backend.close();
        backend = new InMemoryBackend();

        Optional<byte[]> loaded = backend.load(key);
        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Should handle binary data correctly")
    void save_handlesBinaryData() {
        String key = "binary-key";
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }

        backend.save(key, binaryData);

        Optional<byte[]> loaded = backend.load(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(binaryData);
    }

    @Test
    @DisplayName("Should list keys reflects current state")
    void listKeys_reflectsCurrentState() {
        assertThat(backend.listKeys()).isEmpty();

        backend.save("key1", "data1".getBytes());
        assertThat(backend.listKeys()).containsExactly("key1");

        backend.save("key2", "data2".getBytes());
        assertThat(backend.listKeys()).containsExactlyInAnyOrder("key1", "key2");

        backend.delete("key1");
        assertThat(backend.listKeys()).containsExactly("key2");
    }
}
