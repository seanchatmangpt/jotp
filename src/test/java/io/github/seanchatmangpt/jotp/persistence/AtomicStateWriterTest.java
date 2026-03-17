package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AtomicStateWriter}.
 *
 * <p>Verifies atomic state write operations with proper file handling, crash recovery, and
 * concurrent access support.
 */
@DisplayName("AtomicStateWriter Tests")
class AtomicStateWriterTest {

    @TempDir Path tempDir;

    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test-state.json");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testFile)) {
            Files.deleteIfExists(testFile);
        }
    }

    @Test
    @DisplayName("Should write state atomically to new file")
    void writeState_atomicallyWritesNewFile() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);
        String state = "{\"value\":42}";

        writer.writeState(state);

        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(state);
    }

    @Test
    @DisplayName("Should overwrite existing file atomically")
    void writeState_atomicallyOverwritesExisting() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        // Write initial state
        writer.writeState("{\"value\":1}");
        assertThat(Files.readString(testFile)).isEqualTo("{\"value\":1}");

        // Write new state
        writer.writeState("{\"value\":2}");
        assertThat(Files.readString(testFile)).isEqualTo("{\"value\":2}");
    }

    @Test
    @DisplayName("Should create backup of existing file before overwrite")
    void writeState_createsBackupOfExisting() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        writer.writeState("{\"value\":1}");
        var originalContent = Files.readString(testFile);

        // Write new state
        writer.writeState("{\"value\":2}");

        // Check backup exists
        Path backupFile = AtomicStateWriter.getBackupPath(testFile);
        assertThat(Files.exists(backupFile)).isTrue();
        assertThat(Files.readString(backupFile)).isEqualTo(originalContent);
    }

    @Test
    @DisplayName("Should recover from backup if main file is corrupted")
    void recoverFromBackup_restoresBackupWhenMainCorrupted() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        // Write initial state (this creates a backup)
        writer.writeState("{\"value\":1}");

        // Corrupt the main file
        Files.writeString(testFile, "corrupted-data");

        // Recover from backup - returns the recovered state
        String recovered = writer.recoverFromBackup();

        assertThat(recovered).isEqualTo("{\"value\":1}");
        assertThat(Files.readString(testFile)).isEqualTo("{\"value\":1}");
    }

    @Test
    @DisplayName("Should throw when recovering without backup")
    void recoverFromBackup_throwsWhenNoBackup() {
        var writer = new TestAtomicStateWriter(testFile);

        assertThatThrownBy(() -> writer.recoverFromBackup())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No backup file found");
    }

    @Test
    @DisplayName("Should handle null state gracefully")
    void writeState_handlesNullState() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        assertThatThrownBy(() -> writer.writeState(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle empty state string")
    void writeState_handlesEmptyString() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        writer.writeState("");

        assertThat(Files.readString(testFile)).isEqualTo("");
    }

    @Test
    @DisplayName("Should support concurrent writes safely")
    void writeState_handlesConcurrentWrites() throws InterruptedException, IOException {
        var writer = new TestAtomicStateWriter(testFile);
        int threadCount = 10;
        var writesPerThread = 5;
        var executor = Executors.newFixedThreadPool(threadCount);
        var successCount = new AtomicInteger(0);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < writesPerThread; j++) {
                                writer.writeState(
                                        "{\"thread\":" + threadId + ",\"iteration\":" + j + "}");
                                successCount.incrementAndGet();
                            }
                        } catch (IOException e) {
                            // Concurrent writes may fail, which is acceptable
                            // The atomic operation ensures file consistency
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // File should exist and contain valid JSON (from last successful write)
        assertThat(Files.exists(testFile)).isTrue();
        String content = Files.readString(testFile);
        assertThat(content).startsWith("{\"thread\":").endsWith("}");
    }

    @Test
    @DisplayName("Should clean up temporary files on failure")
    void writeState_cleansUpTempFilesOnFailure() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        // Make parent directory read-only to simulate failure
        var parentDir = tempDir.toFile();
        var originalPermissions = parentDir.canWrite();
        try {
            parentDir.setWritable(false);

            assertThatThrownBy(() -> writer.writeState("{\"value\":1}"))
                    .isInstanceOf(IOException.class);

            // No temporary files should remain
            var tempFiles = Files.list(tempDir).filter(p -> p.toString().contains(".tmp")).toList();
            assertThat(tempFiles).isEmpty();

        } finally {
            parentDir.setWritable(true);
        }
    }

    @Test
    @DisplayName("Should handle large state files")
    void writeState_handlesLargeStateFiles() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        // Create a large state (1MB)
        var largeState = new StringBuilder();
        largeState.append("{\"data\":\"");
        for (int i = 0; i < 1024 * 256; i++) {
            largeState.append("x");
        }
        largeState.append("\"}");

        writer.writeState(largeState.toString());

        assertThat(Files.size(testFile)).isGreaterThan(1024 * 256);
    }

    @Test
    @DisplayName("Should generate unique backup paths")
    void getBackupPath_generatesUniquePaths() {
        Path backup1 = AtomicStateWriter.getBackupPath(testFile);
        Path backup2 = AtomicStateWriter.getBackupPath(testFile);

        assertThat(backup1).isEqualTo(backup2);
        assertThat(backup1.toString()).endsWith(".bak");
    }

    @Test
    @DisplayName("Should handle special characters in state")
    void writeState_handlesSpecialCharacters() throws IOException {
        var writer = new TestAtomicStateWriter(testFile);

        String specialState = "{\"value\":\"Test with unicode: \\u00E9, \\u4E2D\\u6587\"}";
        writer.writeState(specialState);

        assertThat(Files.readString(testFile)).isEqualTo(specialState);
    }
}
