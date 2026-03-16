package io.github.seanchatmangpt.jotp.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Test helper class for simulating atomic state writes in crash recovery tests.
 *
 * <p><strong>TEST-ONLY CLASS:</strong> This is a simplified test helper that simulates atomic
 * writes using file system operations. It is NOT the production {@link
 * io.github.seanchatmangpt.jotp.persistence.AtomicStateWriter} class which uses RocksDB.
 *
 * <p>This class provides a simple API for testing crash recovery scenarios:
 *
 * <ul>
 *   <li>Atomic writes via temp file + atomic move
 *   <li>Automatic backup creation on overwrite
 *   <li>Backup recovery for corrupted state files
 * </ul>
 *
 * <p><strong>Usage in tests:</strong>
 *
 * <pre>{@code
 * var writer = new AtomicStateWriter(stateFile);
 * writer.writeState("{\"counter\":42}");
 * boolean recovered = writer.recoverFromBackup();
 * }</pre>
 *
 * @see io.github.seanchatmangpt.jotp.persistence.AtomicStateWriter
 */
public class TestAtomicStateWriter {

    private final Path stateFile;

    /**
     * Create a new atomic state writer for the given file.
     *
     * @param stateFile the target state file path
     */
    public TestAtomicStateWriter(Path stateFile) {
        this.stateFile = stateFile;
    }

    /**
     * Write state atomically to the state file.
     *
     * <p>Implementation uses temp file + atomic rename for crash safety:
     *
     * <ol>
     *   <li>Write content to {@code .tmp} file
     *   <li>If state file exists, move it to {@code .bak} (backup)
     *   <li>Atomic move temp file to final location
     *   <li>Clean up temp file on failure
     * </ol>
     *
     * <p>This ensures that after a crash, either the old state (in backup) or the new state (in
     * main file) is available, never a partially written file.
     *
     * @param state the state content to write (JSON string)
     * @throws IOException if write operation fails
     * @throws NullPointerException if state is null
     */
    public void writeState(String state) throws IOException {
        if (state == null) {
            throw new NullPointerException("state cannot be null");
        }

        Path tempFile = Path.of(stateFile + ".tmp");
        Path backupFile = getBackupPath(stateFile);

        try {
            // Create parent directories if needed
            if (stateFile.getParent() != null) {
                Files.createDirectories(stateFile.getParent());
            }

            // Write to temp file first
            Files.writeString(tempFile, state);

            // If state file exists, move it to backup
            if (Files.exists(stateFile)) {
                Files.move(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // Atomic move temp to final location
            Files.move(
                    tempFile,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best effort cleanup
            }
            throw e;
        }
    }

    /**
     * Recover state from backup file.
     *
     * <p>Restores the state file from backup if:
     *
     * <ul>
     *   <li>Backup file exists
     *   <li>State file is missing or corrupted
     * </ul>
     *
     * <p><strong>Use case:</strong> After detecting corruption in the main state file, call this
     * method to restore the last known good state from backup.
     *
     * @return {@code true} if backup was restored, {@code false} if no backup exists
     * @throws IOException if recovery operation fails
     */
    public boolean recoverFromBackup() throws IOException {
        Path backupFile = getBackupPath(stateFile);

        if (!Files.exists(backupFile)) {
            return false;
        }

        // Restore from backup to main file
        Files.copy(backupFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * Get the backup file path for a given state file.
     *
     * <p>Backup files use the {@code .bak} extension. For example:
     *
     * <ul>
     *   <li>{@code state.dat} → {@code state.dat.bak}
     *   <li>{@code counter.json} → {@code counter.json.bak}
     * </ul>
     *
     * @param stateFile the state file path
     * @return the corresponding backup file path with {@code .bak} extension
     */
    public static Path getBackupPath(Path stateFile) {
        return Path.of(stateFile + ".bak");
    }
}
