package io.github.seanchatmangpt.jotp.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Simple file-based state writer for testing.
 *
 * <p>This is a test utility that provides atomic-ish writes with backup support. Not suitable for
 * production use - use {@link AtomicStateWriter} with RocksDB backend instead.
 */
public class TestAtomicStateWriter {

    private final Path stateFile;

    /**
     * Create a new test writer.
     *
     * @param stateFile the file to write state to
     */
    public TestAtomicStateWriter(Path stateFile) {
        this.stateFile = stateFile;
    }

    /**
     * Write state to file with backup.
     *
     * <p>This creates a backup of the existing file (if any) before writing the new state.
     *
     * @param state the state to write (as JSON string)
     * @throws IOException if write fails
     */
    public void writeState(String state) throws IOException {
        // Create backup if file exists
        if (Files.exists(stateFile)) {
            Path backupFile = getBackupPath(stateFile);
            Files.copy(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Write new state
        Files.writeString(
                stateFile,
                state,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /**
     * Recover state from backup file.
     *
     * <p>If the main file is corrupted or missing, this method attempts to recover from the backup.
     *
     * @return the recovered state from backup
     * @throws IOException if no backup exists or recovery fails
     */
    public String recoverFromBackup() throws IOException {
        Path backupFile = getBackupPath(stateFile);

        if (!Files.exists(backupFile)) {
            throw new IOException("No backup file found at " + backupFile);
        }

        // Restore from backup
        String backupContent = Files.readString(backupFile);
        Files.writeString(
                stateFile,
                backupContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        return backupContent;
    }

    /**
     * Get the backup file path for a given state file.
     *
     * @param stateFile the state file path
     * @return the backup file path
     */
    public static Path getBackupPath(Path stateFile) {
        return Path.of(stateFile + ".bak");
    }
}
