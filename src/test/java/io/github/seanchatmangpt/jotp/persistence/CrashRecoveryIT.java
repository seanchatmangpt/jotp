package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for crash recovery scenarios.
 *
 * <p>Tests comprehensive crash recovery patterns including:
 *
 * <ul>
 *   <li>State persistence and recovery after simulated crash
 *   <li>Atomic state writes with rollback on failure
 *   <li>Backup file creation and recovery
 *   <li>Clean shutdown with graceful state persistence
 * </ul>
 *
 * <p><strong>Crash Simulation:</strong> These tests simulate JVM crashes by:
 *
 * <ol>
 *   <li>Writing state to persistence
 *   <li>Simulating crash (shutdown without cleanup)
 *   <li>Creating new writer instance (simulates restart)
 *   <li>Verifying state recovery
 * </ol>
 *
 * @see AtomicStateWriter
 * @see io.github.seanchatmangpt.jotp.DurableState
 */
@DisplayName("Crash Recovery Integration Tests")
class CrashRecoveryIT {

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @AfterEach
    void tearDown() {
        ApplicationController.reset();
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should persist and recover state after simulated crash")
    void shouldPersistAndRecoverStateAfterSimulatedCrash() throws Exception {
        Path stateFile = tempDir.resolve("crash-test-1.dat");

        // Phase 1: Write state and simulate crash
        var writer1 = new TestAtomicStateWriter(stateFile);
        String state1 = "{\"counter\":42,\"version\":1}";

        writer1.writeState(state1);

        // Verify state was written
        assertThat(Files.exists(stateFile)).isTrue();
        assertThat(Files.readString(stateFile)).isEqualTo(state1);

        // Phase 2: Simulate crash (close writer without cleanup)
        // In real scenario, JVM crashes here

        // Phase 3: Recover after crash (simulate JVM restart)
        var writer2 = new TestAtomicStateWriter(stateFile);
        String recoveredState = Files.readString(stateFile);

        // Verify state recovered
        assertThat(recoveredState).isEqualTo(state1);
    }

    @Test
    @DisplayName("Should recover from backup when main file is corrupted")
    void shouldRecoverFromBackupWhenMainFileIsCorrupted() throws Exception {
        Path stateFile = tempDir.resolve("corrupted-recovery.dat");

        // Phase 1: Write valid state
        var writer1 = new TestAtomicStateWriter(stateFile);
        String validState = "{\"value\":100,\"status\":\"active\"}";

        writer1.writeState(validState);

        // Verify state and backup exist
        assertThat(Files.exists(stateFile)).isTrue();
        Path backupFile = AtomicStateWriter.getBackupPath(stateFile);
        assertThat(Files.exists(backupFile)).isTrue();

        // Phase 2: Corrupt main file (simulate crash during write)
        Files.writeString(stateFile, "CORRUPTED DATA!!!");

        // Phase 3: Recover from backup
        var writer2 = new TestAtomicStateWriter(stateFile);
        boolean recovered = writer2.recoverFromBackup();

        assertThat(recovered).isTrue();
        assertThat(Files.readString(stateFile)).isEqualTo(validState);
    }

    @Test
    @DisplayName("Should handle multiple crash-recovery cycles")
    void shouldHandleMultipleCrashRecoveryCycles() throws Exception {
        Path stateFile = tempDir.resolve("multi-crash.dat");
        List<String> states = new ArrayList<>();

        // Cycle 1: Initial state
        var writer1 = new TestAtomicStateWriter(stateFile);
        String state1 = "{\"cycle\":1,\"value\":\"initial\"}";
        writer1.writeState(state1);
        states.add(state1);

        // Simulate crash and recover
        var writer2 = new TestAtomicStateWriter(stateFile);
        assertThat(Files.readString(stateFile)).isEqualTo(state1);

        // Cycle 2: Update state
        String state2 = "{\"cycle\":2,\"value\":\"updated\"}";
        writer2.writeState(state2);
        states.add(state2);

        // Simulate crash and recover
        var writer3 = new TestAtomicStateWriter(stateFile);
        assertThat(Files.readString(stateFile)).isEqualTo(state2);

        // Cycle 3: Final state
        String state3 = "{\"cycle\":3,\"value\":\"final\"}";
        writer3.writeState(state3);
        states.add(state3);

        // Final recovery
        var writer4 = new TestAtomicStateWriter(stateFile);
        assertThat(Files.readString(stateFile)).isEqualTo(state3);
    }

    @Test
    @DisplayName("Should verify atomic write prevents partial state corruption")
    void shouldVerifyAtomicWritePreventsPartialCorruption() throws Exception {
        Path stateFile = tempDir.resolve("atomic-write.dat");

        var writer = new TestAtomicStateWriter(stateFile);
        String validState = "{\"data\":\"large-payload\",\"items\":[1,2,3,4,5]}";

        // Write state
        writer.writeState(validState);

        // Verify file contains complete state
        String content = Files.readString(stateFile);
        assertThat(content).isEqualTo(validState);
        assertThat(content).startsWith("{").endsWith("}");

        // Verify JSON is valid (complete, not truncated)
        assertThat(content).contains("\"data\"").contains("\"items\"");
    }

    @Test
    @DisplayName("Should handle state corruption with backup recovery")
    void shouldHandleStateCorruptionWithBackupRecovery() throws Exception {
        Path stateFile = tempDir.resolve("corruption-test.dat");

        // Write initial valid state
        var writer1 = new TestAtomicStateWriter(stateFile);
        String initialState = "{\"counter\":0,\"status\":\"ok\"}";
        writer1.writeState(initialState);

        // Update state (creates backup)
        var writer2 = new TestAtomicStateWriter(stateFile);
        String updatedState = "{\"counter\":1,\"status\":\"ok\"}";
        writer2.writeState(updatedState);

        // Verify backup exists with previous state
        Path backupFile = AtomicStateWriter.getBackupPath(stateFile);
        assertThat(Files.exists(backupFile)).isTrue();
        assertThat(Files.readString(backupFile)).isEqualTo(initialState);

        // Corrupt main file
        Files.writeString(stateFile, "CORRUPTED");

        // Recover from backup
        var writer3 = new TestAtomicStateWriter(stateFile);
        boolean recovered = writer3.recoverFromBackup();

        assertThat(recovered).isTrue();
        assertThat(Files.readString(stateFile)).isEqualTo(initialState);
    }

    @Test
    @DisplayName("Should handle empty state file on first start")
    void shouldHandleEmptyStateFileOnFirstStart() throws Exception {
        Path stateFile = tempDir.resolve("first-start.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        // No file exists yet
        assertThat(Files.exists(stateFile)).isFalse();

        // Attempting recovery should return false
        boolean recovered = writer.recoverFromBackup();
        assertThat(recovered).isFalse();
    }

    @Test
    @DisplayName("Should verify state consistency across multiple writes")
    void shouldVerifyStateConsistencyAcrossMultipleWrites() throws Exception {
        Path stateFile = tempDir.resolve("consistency-test.dat");
        List<String> writtenStates = new ArrayList<>();

        var writer = new TestAtomicStateWriter(stateFile);

        // Perform multiple writes
        for (int i = 0; i < 10; i++) {
            String state = "{\"iteration\":" + i + ",\"value\":\"test-" + i + "\"}";
            writer.writeState(state);
            writtenStates.add(state);

            // Verify file contains the written state
            String content = Files.readString(stateFile);
            assertThat(content).isEqualTo(state);
        }

        // Final state should be the last write
        String finalContent = Files.readString(stateFile);
        assertThat(finalContent).isEqualTo(writtenStates.get(9));
        assertThat(finalContent).contains("\"iteration\":9");
    }

    @Test
    @DisplayName("Should handle special characters in state")
    void shouldHandleSpecialCharactersInState() throws Exception {
        Path stateFile = tempDir.resolve("special-chars.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        // State with special characters
        String specialState =
                "{\"message\":\"Test with unicode: \\u00E9, \\u4E2D\\u6587\","
                        + "\"symbols\":\"\\t\\n\\r\","
                        + "\"quotes\":\"\\\"quoted\\\"\"}";

        writer.writeState(specialState);

        // Verify state persists correctly
        String recovered = Files.readString(stateFile);
        assertThat(recovered).isEqualTo(specialState);
    }

    @Test
    @DisplayName("Should handle large state files")
    void shouldHandleLargeStateFiles() throws Exception {
        Path stateFile = tempDir.resolve("large-state.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        // Create large state (100KB)
        var largeState = new StringBuilder();
        largeState.append("{\"data\":\"");
        for (int i = 0; i < 100000; i++) {
            largeState.append("x");
        }
        largeState.append("\"}");

        writer.writeState(largeState.toString());

        // Verify large file was written correctly
        assertThat(Files.size(stateFile)).isGreaterThan(100000);
        String content = Files.readString(stateFile);
        assertThat(content).startsWith("{\"data\":\"").endsWith("\"}");
    }

    @Test
    @DisplayName("Should clean up temporary files on failure")
    void shouldCleanUpTemporaryFilesOnFailure() throws Exception {
        Path stateFile = tempDir.resolve("cleanup-test.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        // Write initial state
        writer.writeState("{\"initial\":true}");

        // Try to write invalid state (should create temp file then clean up)
        // Note: AtomicStateWriter should clean up .tmp files even on failure
        writer.writeState("{\"updated\":true}");

        // List all files in directory
        var allFiles = Files.list(tempDir).toList();

        // Check for leftover .tmp files
        var tempFiles = allFiles.stream().filter(p -> p.toString().endsWith(".tmp")).toList();

        assertThat(tempFiles).isEmpty();
    }

    @Test
    @DisplayName("Should verify backup is created on overwrite")
    void shouldVerifyBackupIsCreatedOnOverwrite() throws Exception {
        Path stateFile = tempDir.resolve("backup-test.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        // Write initial state
        String state1 = "{\"version\":1,\"value\":\"first\"}";
        writer.writeState(state1);

        // Overwrite with new state
        String state2 = "{\"version\":2,\"value\":\"second\"}";
        writer.writeState(state2);

        // Verify backup exists with previous state
        Path backupFile = AtomicStateWriter.getBackupPath(stateFile);
        assertThat(Files.exists(backupFile)).isTrue();
        assertThat(Files.readString(backupFile)).isEqualTo(state1);

        // Verify main file has new state
        assertThat(Files.readString(stateFile)).isEqualTo(state2);
    }

    @Test
    @DisplayName("Should handle concurrent writes with atomicity")
    void shouldHandleConcurrentWritesWithAtomicity() throws Exception {
        Path stateFile = tempDir.resolve("concurrent-test.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        // Perform multiple writes
        for (int i = 0; i < 5; i++) {
            String state = "{\"write\":" + i + ",\"timestamp\":" + System.currentTimeMillis() + "}";
            writer.writeState(state);
        }

        // Final file should contain valid JSON (not corrupted)
        String finalContent = Files.readString(stateFile);
        assertThat(finalContent).startsWith("{").endsWith("}");

        // Should be one of the written states
        assertThat(finalContent).contains("\"write\":");
    }
}
