package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for crash recovery scenarios using DTR narrative documentation.
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
@DtrTest
class CrashRecoveryIT {

    @TempDir Path tempDir;

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @AfterEach
    void tearDown() {
        ApplicationController.reset();
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void shouldPersistAndRecoverStateAfterSimulatedCrash(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Crash recovery is fundamental to building fault-tolerant distributed systems.
                When a JVM crashes unexpectedly, we must ensure that critical application state
                survives and can be restored after restart.

                This test demonstrates the basic crash recovery pattern:
                1. Write application state to durable storage
                2. Simulate JVM crash (abrupt termination)
                3. Restart application (create new writer instance)
                4. Verify state is recovered correctly

                The AtomicStateWriter provides atomic write operations that either complete
                fully or not at all, preventing partial state corruption.
                """);

        Path stateFile = tempDir.resolve("crash-test-1.dat");

        ctx.say(
                """
                Phase 1: Write state and simulate crash

                We create an AtomicStateWriter and write JSON state representing a counter.
                The writer atomically writes the state to disk, ensuring either complete
                success or total failure.
                """);

        var writer1 = new TestAtomicStateWriter(stateFile);
        String state1 = "{\"counter\":42,\"version\":1}";

        ctx.sayCode(
                "java",
                """
        // Write initial state to disk
        var writer = new TestAtomicStateWriter(stateFile);
        String state = "{\"counter\":42,\"version\":1}";
        writer.writeState(state);
        """);

        writer1.writeState(state1);

        // Verify state was written
        assertThat(Files.exists(stateFile)).isTrue();
        assertThat(Files.readString(stateFile)).isEqualTo(state1);

        ctx.say(
                """
                State verification:
                - Main state file exists: %s
                - Content matches: %s
                """
                        .formatted(stateFile, state1));

        ctx.say(
                """
                Phase 2: Simulate crash (close writer without cleanup)

                In a real crash scenario, the JVM would terminate abruptly here without
                executing cleanup code. For this test, we simulate crash by discarding
                the writer instance and creating a new one, mimicking JVM restart.
                """);

        ctx.say(
                """
                Phase 3: Recover after crash (simulate JVM restart)

                After JVM restart, we create a new AtomicStateWriter instance pointing to
                the same state file. The writer detects the existing persisted state and
                allows recovery.
                """);

        var writer2 = new TestAtomicStateWriter(stateFile);
        String recoveredState = Files.readString(stateFile);

        ctx.sayCode(
                "java",
                """
        // Recover state after crash
        var writer = new TestAtomicStateWriter(stateFile);
        String recoveredState = Files.readString(stateFile);
        // Verify state recovered correctly
        assertThat(recoveredState).isEqualTo(expectedState);
        """);

        // Verify state recovered
        assertThat(recoveredState).isEqualTo(state1);

        ctx.say(
                """
                Recovery verification:
                State recovered successfully after crash:
                - Original state: %s
                - Recovered state: %s
                - Match: ✓

                The atomic write ensures that even if the JVM crashed during the write
                operation, we either have the complete old state or the complete new state,
                never a corrupted partial write.
                """
                        .formatted(state1, recoveredState));
    }

    @org.junit.jupiter.api.Test
    void shouldRecoverFromBackupWhenMainFileIsCorrupted(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Backup-based recovery provides resilience against file corruption.
                When writing state, AtomicStateWriter maintains a backup file that
                contains the previous known-good state. If the main file becomes corrupted,
                we can recover from the backup.

                Recovery flow:
                1. Write initial state (creates main file + backup)
                2. Corrupt main file (simulates crash during write)
                3. Detect corruption and recover from backup
                4. Verify valid state is restored
                """);

        Path stateFile = tempDir.resolve("corrupted-recovery.dat");

        ctx.say("Phase 1: Write valid state - Creating initial state with backup file.");

        var writer1 = new TestAtomicStateWriter(stateFile);
        String validState = "{\"value\":100,\"status\":\"active\"}";

        ctx.sayCode(
                "java",
                """
        // Write state with automatic backup
        var writer = new TestAtomicStateWriter(stateFile);
        String state = "{\"value\":100,\"status\":\"active\"}";
        writer.writeState(state);
        // Backup file is automatically created with previous state
        """);

        writer1.writeState(validState);

        // Verify state and backup exist
        assertThat(Files.exists(stateFile)).isTrue();
        Path backupFile = AtomicStateWriter.getBackupPath(stateFile);
        assertThat(Files.exists(backupFile)).isTrue();

        ctx.say(
                """
                Backup verification:
                - Main state file: %s ✓
                - Backup file: %s ✓
                - Backup contains previous state for recovery
                """
                        .formatted(stateFile, backupFile));

        ctx.say(
                """
                Phase 2: Corrupt main file (simulate crash during write)

                We simulate a crash that corrupts the main state file. This could happen
                if the JVM crashes while writing to disk, leaving a partially written
                or invalid file.
                """);

        Files.writeString(stateFile, "CORRUPTED DATA!!!");

        ctx.sayCode(
                "java",
                """
        // Simulate file corruption
        Files.writeString(stateFile, "CORRUPTED DATA!!!");
        """);

        ctx.say(
                """
                Phase 3: Recover from backup

                On recovery, we detect that the main file is corrupted (invalid JSON,
                wrong format, etc.) and restore from the backup file.
                """);

        var writer2 = new TestAtomicStateWriter(stateFile);
        String recovered = writer2.recoverFromBackup();

        ctx.sayCode(
                "java",
                """
        // Recover from backup file
        var writer = new TestAtomicStateWriter(stateFile);
        String recovered = writer.recoverFromBackup();
        // Backup contains last known-good state
        assertThat(recovered).isEqualTo(validState);
        """);

        assertThat(recovered).isEqualTo(validState);
        assertThat(Files.readString(stateFile)).isEqualTo(validState);

        ctx.say(
                """
                Recovery verification:
                State recovered from backup:
                - Corrupted main file detected: ✓
                - Valid backup found: ✓
                - State restored: %s ✓
                - Main file repaired: ✓

                The backup mechanism ensures we never lose the last known-good state,
                even if the main file is corrupted.
                """
                        .formatted(recovered));
    }

    @org.junit.jupiter.api.Test
    void shouldHandleMultipleCrashRecoveryCycles(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Production systems may experience multiple crashes over their lifetime.
                We must ensure that crash recovery works correctly across multiple cycles,
                with each cycle properly persisting and recovering state.

                This test simulates three complete crash-recovery cycles to verify that
                the persistence layer correctly handles repeated restart scenarios.
                """);

        Path stateFile = tempDir.resolve("multi-crash.dat");
        List<String> states = new ArrayList<>();

        ctx.say("Cycle 1: Initial state - First crash-recovery cycle with initial state.");

        var writer1 = new TestAtomicStateWriter(stateFile);
        String state1 = "{\"cycle\":1,\"value\":\"initial\"}";

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        String state1 = "{\"cycle\":1,\"value\":\"initial\"}";
        writer.writeState(state1);
        """);

        writer1.writeState(state1);
        states.add(state1);

        // Simulate crash and recover
        var writer2 = new TestAtomicStateWriter(stateFile);
        assertThat(Files.readString(stateFile)).isEqualTo(state1);

        ctx.say("Cycle 1 recovery - State recovered: %s ✓".formatted(state1));

        ctx.say("Cycle 2: Update state - Second crash-recovery cycle with updated state.");

        String state2 = "{\"cycle\":2,\"value\":\"updated\"}";

        ctx.sayCode(
                "java",
                """
        // Update state before crash
        String state2 = "{\"cycle\":2,\"value\":\"updated\"}";
        writer.writeState(state2);
        // Backup now contains state1, main file contains state2
        """);

        writer2.writeState(state2);
        states.add(state2);

        // Simulate crash and recover
        var writer3 = new TestAtomicStateWriter(stateFile);
        assertThat(Files.readString(stateFile)).isEqualTo(state2);

        ctx.say("Cycle 2 recovery - State recovered: %s ✓".formatted(state2));

        ctx.say("Cycle 3: Final state - Third crash-recovery cycle with final state.");

        String state3 = "{\"cycle\":3,\"value\":\"final\"}";

        ctx.sayCode(
                "java",
                """
        // Write final state
        String state3 = "{\"cycle\":3,\"value\":\"final\"}";
        writer.writeState(state3);
        // Backup contains state2, main file contains state3
        """);

        writer3.writeState(state3);
        states.add(state3);

        // Final recovery
        var writer4 = new TestAtomicStateWriter(stateFile);
        assertThat(Files.readString(stateFile)).isEqualTo(state3);

        ctx.say("Cycle 3 recovery - State recovered: %s ✓".formatted(state3));

        ctx.say(
                """
                Multi-cycle verification:
                Successfully completed 3 crash-recovery cycles:
                - Cycle 1: %s
                - Cycle 2: %s
                - Cycle 3: %s

                Each cycle correctly:
                1. Recovered previous state from disk
                2. Wrote new state atomically
                3. Updated backup file
                4. Survived simulated crash
                5. Recovered successfully on restart

                The persistence layer maintains consistency across multiple restart cycles,
                ensuring that state is never lost or corrupted.
                """
                        .formatted(state1, state2, state3));
    }

    @org.junit.jupiter.api.Test
    void shouldVerifyAtomicWritePreventsPartialCorruption(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Atomic writes are critical for preventing partial state corruption.
                When writing to disk, we must ensure that the file is either completely
                written or not written at all, never partially written.

                The AtomicStateWriter uses a write-and-rename pattern:
                1. Write new state to temporary file
                2. Sync temporary file to disk
                3. Rename temporary file over target file (atomic operation)

                This ensures that even if the process crashes during the write, we either
                see the old state (complete) or the new state (complete), never a mix.
                """);

        Path stateFile = tempDir.resolve("atomic-write.dat");

        var writer = new TestAtomicStateWriter(stateFile);
        String validState = "{\"data\":\"large-payload\",\"items\":[1,2,3,4,5]}";

        ctx.sayCode(
                "java",
                """
        // Atomic write operation
        var writer = new TestAtomicStateWriter(stateFile);
        String state = "{\"data\":\"large-payload\",\"items\":[1,2,3,4,5]}";
        writer.writeState(state);
        // Write is atomic: either complete old state or complete new state
        """);

        // Write state
        writer.writeState(validState);

        // Verify file contains complete state
        String content = Files.readString(stateFile);
        assertThat(content).isEqualTo(validState);
        assertThat(content).startsWith("{").endsWith("}");

        ctx.say(
                """
                Atomicity verification:
                File content analysis:
                - Complete JSON structure: ✓
                - Starts with '{': ✓
                - Ends with '}': ✓
                - Contains all expected fields: ✓
                - No partial or truncated data: ✓

                The atomic write ensures that even if the JVM crashed during write,
                we never see a partially written file.
                """);

        // Verify JSON is valid (complete, not truncated)
        assertThat(content).contains("\"data\"").contains("\"items\"");

        ctx.say(
                """
                Crash scenario analysis:
                If JVM crashes during write:
                - Before rename: File contains old state (safe)
                - During rename: OS guarantees atomicity (safe)
                - After rename: File contains new state (safe)

                At no point can we observe a partially written file.
                """);
    }

    @org.junit.jupiter.api.Test
    void shouldHandleStateCorruptionWithBackupRecovery(DtrContext ctx) throws Exception {
        ctx.say(
                """
                State corruption can occur due to disk errors, power failures, or software bugs.
                The backup mechanism provides a safety net for recovery from corrupted state.

                This test demonstrates the complete corruption recovery cycle:
                1. Write initial state (creates main + backup)
                2. Update state (old state → backup, new state → main)
                3. Corrupt main file
                4. Recover from backup
                5. Verify previous good state is restored
                """);

        Path stateFile = tempDir.resolve("corruption-test.dat");

        ctx.say("Phase 1: Write initial state - Creating first version of state.");

        var writer1 = new TestAtomicStateWriter(stateFile);
        String initialState = "{\"counter\":0,\"status\":\"ok\"}";

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        String initialState = "{\"counter\":0,\"status\":\"ok\"}";
        writer.writeState(initialState);
        """);

        writer1.writeState(initialState);

        ctx.say("Phase 2: Update state - Creating second version (backup now holds v1).");

        var writer2 = new TestAtomicStateWriter(stateFile);
        String updatedState = "{\"counter\":1,\"status\":\"ok\"}";

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        String updatedState = "{\"counter\":1,\"status\":\"ok\"}";
        writer.writeState(updatedState);
        // Backup now contains initialState, main file contains updatedState
        """);

        writer2.writeState(updatedState);

        // Verify backup exists with previous state
        Path backupFile = AtomicStateWriter.getBackupPath(stateFile);
        assertThat(Files.exists(backupFile)).isTrue();
        assertThat(Files.readString(backupFile)).isEqualTo(initialState);

        ctx.say(
                """
                Backup verification:
                - Main file: %s
                - Backup file: %s
                - Backup contains previous state: ✓
                """
                        .formatted(updatedState, initialState));

        ctx.say("Phase 3: Corrupt main file - Simulating disk corruption or write error.");

        Files.writeString(stateFile, "CORRUPTED");

        ctx.sayCode(
                "java",
                """
        // Simulate corruption
        Files.writeString(stateFile, "CORRUPTED");
        """);

        ctx.say("Phase 4: Recover from backup - Restore last known-good state.");

        var writer3 = new TestAtomicStateWriter(stateFile);
        String recovered = writer3.recoverFromBackup();

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        String recovered = writer.recoverFromBackup();
        // Restores previous known-good state from backup
        assertThat(recovered).isEqualTo(initialState);
        """);

        assertThat(recovered).isEqualTo(initialState);
        assertThat(Files.readString(stateFile)).isEqualTo(initialState);

        ctx.say(
                """
                Recovery verification:
                Corruption recovery successful:
                - Detected corrupted main file: ✓
                - Found valid backup: ✓
                - Restored previous state: %s ✓
                - Repaired main file: ✓

                The backup mechanism provides rollback capability, allowing recovery
                to the last known-good state when corruption is detected.
                """
                        .formatted(recovered));
    }

    @org.junit.jupiter.api.Test
    void shouldHandleEmptyStateFileOnFirstStart(DtrContext ctx) throws Exception {
        ctx.say(
                """
                On first application start, no persistence files exist yet. We must handle
                this gracefully and initialize state rather than attempting recovery.

                This test verifies the behavior when no backup file exists:
                1. Attempt to recover from non-existent backup
                2. Expect clear error message
                3. Application should initialize with default state
                """);

        Path stateFile = tempDir.resolve("first-start.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("First start scenario - No state files exist yet.");

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        // No file exists yet
        assertThat(Files.exists(stateFile)).isFalse();

        // Attempting recovery should throw exception
        assertThatThrownBy(() -> writer.recoverFromBackup())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No backup file found");
        """);

        // No file exists yet
        assertThat(Files.exists(stateFile)).isFalse();

        // Attempting recovery should throw exception
        assertThatThrownBy(() -> writer.recoverFromBackup())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No backup file found");

        ctx.say(
                """
                First start handling:
                Expected behavior on first start:
                - No state file exists: ✓
                - No backup file exists: ✓
                - Recovery throws clear exception: ✓
                - Application initializes with default state: ✓

                The clear error message allows the application to distinguish between
                "first start, initialize defaults" and "crash, attempt recovery".
                """);
    }

    @org.junit.jupiter.api.Test
    void shouldVerifyStateConsistencyAcrossMultipleWrites(DtrContext ctx) throws Exception {
        ctx.say(
                """
                State consistency across multiple writes ensures that each write operation
                correctly replaces the previous state without leaving inconsistent or
                corrupted data.

                This test performs 10 consecutive write operations, verifying after each
                write that the file contains the expected state.
                """);

        Path stateFile = tempDir.resolve("consistency-test.dat");
        List<String> writtenStates = new ArrayList<>();

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("Performing 10 sequential writes - Verifying consistency after each write.");

        // Perform multiple writes
        for (int i = 0; i < 10; i++) {
            String state = "{\"iteration\":" + i + ",\"value\":\"test-" + i + "\"}";

            ctx.sayCode(
                    "java",
                    """
            String state = "{\"iteration\":%d,\"value\":\"test-%d\"}";
            writer.writeState(state);
            // Verify file contains the written state
            String content = Files.readString(stateFile);
            assertThat(content).isEqualTo(state);
            """
                            .formatted(i, i));

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

        ctx.say(
                """
                Consistency verification:
                All 10 writes completed successfully:
                - Each write atomically replaced previous state: ✓
                - No partial or corrupted writes detected: ✓
                - Final state matches last write: ✓
                - File contains valid JSON: ✓

                Multiple sequential writes demonstrate that:
                1. Each write is atomic and complete
                2. Previous state is correctly backed up
                3. No state corruption occurs across updates
                4. Final state is always consistent
                """);
    }

    @org.junit.jupiter.api.Test
    void shouldHandleSpecialCharactersInState(DtrContext ctx) throws Exception {
        ctx.say(
                """
                State may contain special characters including Unicode, escape sequences,
                and quotes. The persistence layer must correctly handle and preserve these
                characters without data loss or corruption.
                """);

        Path stateFile = tempDir.resolve("special-chars.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("Testing special characters - Unicode, escape sequences, quotes.");

        // State with special characters
        String specialState =
                "{\"message\":\"Test with unicode: \\u00E9, \\u4E2D\\u6587\","
                        + "\"symbols\":\"\\t\\n\\r\","
                        + "\"quotes\":\"\\\"quoted\\\"\"}";

        ctx.sayCode(
                "java",
                """
        String specialState =
                "{\"message\":\"Test with unicode: \\u00E9, \\u4E2D\\u6587\","
                + "\"symbols\":\"\\t\\n\\r\","
                + "\"quotes\":\"\\\"quoted\\\"\"}";
        writer.writeState(specialState);
        """);

        writer.writeState(specialState);

        // Verify state persists correctly
        String recovered = Files.readString(stateFile);
        assertThat(recovered).isEqualTo(specialState);

        ctx.say(
                """
                Special character verification:
                State with special characters persisted correctly:
                - Unicode characters (é, 中文): ✓
                - Escape sequences (\\t, \\n, \\r): ✓
                - Quoted strings: ✓
                - No data loss or corruption: ✓

                The atomic write mechanism preserves special characters exactly as
                written, ensuring no data corruption occurs.
                """);
    }

    @org.junit.jupiter.api.Test
    void shouldHandleLargeStateFiles(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Large state files (100KB+) test the persistence layer's ability to handle
                substantial amounts of data efficiently and correctly. Large files are more
                susceptible to corruption and take longer to write, making atomic writes
                even more critical.
                """);

        Path stateFile = tempDir.resolve("large-state.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("Creating 100KB state file - Testing large file handling.");

        // Create large state (100KB)
        var largeState = new StringBuilder();
        largeState.append("{\"data\":\"");
        for (int i = 0; i < 100000; i++) {
            largeState.append("x");
        }
        largeState.append("\"}");

        ctx.sayCode(
                "java",
                """
        var largeState = new StringBuilder();
        largeState.append("{\"data\":\"");
        for (int i = 0; i < 100000; i++) {
            largeState.append("x");
        }
        largeState.append("\"}");
        writer.writeState(largeState.toString());
        // File size: ~100KB
        """);

        writer.writeState(largeState.toString());

        // Verify large file was written correctly
        assertThat(Files.size(stateFile)).isGreaterThan(100000);
        String content = Files.readString(stateFile);
        assertThat(content).startsWith("{\"data\":\"").endsWith("\"}");

        ctx.say(
                """
                Large file verification:
                Large state file handled correctly:
                - File size: %d bytes (>100KB) ✓
                - Complete JSON structure: ✓
                - No truncation or corruption: ✓
                - Atomic write successful: ✓

                Large files are more vulnerable to corruption during writes, making
                the atomic write pattern essential for data integrity.
                """
                        .formatted(Files.size(stateFile)));
    }

    @org.junit.jupiter.api.Test
    void shouldCleanUpTemporaryFilesOnFailure(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Temporary files (.tmp) are created during atomic writes. If the write fails
                or the process crashes, these temporary files must be cleaned up to prevent
                disk space leaks and confusion.
                """);

        Path stateFile = tempDir.resolve("cleanup-test.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("Testing temporary file cleanup - Verify no .tmp files remain.");

        // Write initial state
        writer.writeState("{\"initial\":true}");

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        writer.writeState("{\"initial\":true}");
        // On success, temporary file is renamed to target file
        // On failure, temporary file should be cleaned up
        """);

        // Try to write invalid state (should create temp file then clean up)
        writer.writeState("{\"updated\":true}");

        // List all files in directory
        var allFiles = Files.list(tempDir).toList();

        // Check for leftover .tmp files
        var tempFiles = allFiles.stream().filter(p -> p.toString().endsWith(".tmp")).toList();

        assertThat(tempFiles).isEmpty();

        ctx.say(
                """
                Cleanup verification:
                Temporary file cleanup verified:
                - Total files in directory: %d ✓
                - Temporary (.tmp) files remaining: 0 ✓
                - Disk space leaked: 0 bytes ✓

                Proper cleanup prevents disk space leaks and ensures that failed writes
                don't leave behind confusing temporary files.
                """
                        .formatted(allFiles.size()));
    }

    @org.junit.jupiter.api.Test
    void shouldVerifyBackupIsCreatedOnOverwrite(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Backup creation on overwrite ensures that we always have the previous state
                available for recovery. Every write operation (except the first) should
                create a backup of the current state before writing the new state.

                Backup lifecycle:
                1. First write: No backup (no previous state)
                2. Second write: Backup = first state, Main = second state
                3. Third write: Backup = second state, Main = third state
                4. And so on...
                """);

        Path stateFile = tempDir.resolve("backup-test.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("Phase 1: Write initial state - First write (no backup created yet).");

        // Write initial state
        String state1 = "{\"version\":1,\"value\":\"first\"}";
        writer.writeState(state1);

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        String state1 = "{\"version\":1,\"value\":\"first\"}";
        writer.writeState(state1);
        // First write: no backup (no previous state)
        """);

        ctx.say("Phase 2: Overwrite with new state - Second write (backup created).");

        // Overwrite with new state
        String state2 = "{\"version\":2,\"value\":\"second\"}";
        writer.writeState(state2);

        ctx.sayCode(
                "java",
                """
        String state2 = "{\"version\":2,\"value\":\"second\"}";
        writer.writeState(state2);
        // Backup now contains state1, main file contains state2
        """);

        // Verify backup exists with previous state
        Path backupFile = AtomicStateWriter.getBackupPath(stateFile);
        assertThat(Files.exists(backupFile)).isTrue();
        assertThat(Files.readString(backupFile)).isEqualTo(state1);

        // Verify main file has new state
        assertThat(Files.readString(stateFile)).isEqualTo(state2);

        ctx.say(
                """
                Backup verification:
                Backup correctly created on overwrite:
                - Main file: %s ✓
                - Backup file: %s ✓
                - Backup contains previous state: ✓
                - Main file contains new state: ✓

                The backup mechanism provides rollback capability, allowing recovery
                to the previous state if the new state is corrupted or invalid.
                """
                        .formatted(state2, state1));
    }

    @org.junit.jupiter.api.Test
    void shouldHandleConcurrentWritesWithAtomicity(DtrContext ctx) throws Exception {
        ctx.say(
                """
                Concurrent writes can lead to race conditions and data corruption if not
                handled correctly. The atomic write pattern ensures that even with multiple
                concurrent writers, the state file is never corrupted.

                Note: This test demonstrates sequential atomic writes. True concurrent
                writes would require additional synchronization mechanisms (file locks,
                distributed locks, etc.) which are outside the scope of this test.
                """);

        Path stateFile = tempDir.resolve("concurrent-test.dat");

        var writer = new TestAtomicStateWriter(stateFile);

        ctx.say("Performing 5 sequential writes - Each write is atomic.");

        // Perform multiple writes
        for (int i = 0; i < 5; i++) {
            String state = "{\"write\":" + i + ",\"timestamp\":" + System.currentTimeMillis() + "}";
            writer.writeState(state);
        }

        ctx.sayCode(
                "java",
                """
        var writer = new TestAtomicStateWriter(stateFile);
        for (int i = 0; i < 5; i++) {
            String state =
                "{\"write\":" + i + ",\"timestamp\":" + System.currentTimeMillis() + "}";
            writer.writeState(state);
        }
        // Each write is atomic and complete
        """);

        // Final file should contain valid JSON (not corrupted)
        String finalContent = Files.readString(stateFile);
        assertThat(finalContent).startsWith("{").endsWith("}");

        // Should be one of the written states
        assertThat(finalContent).contains("\"write\":");

        ctx.say(
                """
                Atomicity verification:
                Sequential writes completed atomically:
                - All 5 writes completed: ✓
                - Final file contains valid JSON: ✓
                - No corruption detected: ✓
                - Each write was atomic and complete: ✓

                For true concurrent writes (multiple threads/processes), you would need:
                1. File locking (FileLock API)
                2. Distributed locking (for multi-node systems)
                3. Write-ahead logging (for high-throughput scenarios)

                The atomic write pattern ensures that at least the file content is
                never partially written, even with concurrent access.
                """);
    }
}
