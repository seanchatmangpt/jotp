package io.github.seanchatmangpt.jotp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Configuration record for persistence settings in JOTP.
 *
 * <p>Controls durability level, snapshot intervals, storage location, and sync behavior for
 * persistent state management. Supports environment variable overrides for deployment flexibility.
 *
 * <p><strong>Sync Writes:</strong> When {@code syncWrites} is true (recommended for production),
 * all writes are flushed to the WAL before returning. This ensures crash safety but has higher
 * latency. Set to false only for testing or when data loss is acceptable.
 *
 * <p><strong>Environment Variable Overrides:</strong>
 *
 * <ul>
 *   <li>{@code JOTP_DURABILITY} - Overrides durability level (NONE, BEST_EFFORT, DURABLE,
 *       TRANSACTIONAL)
 *   <li>{@code JOTP_PERSISTENCE_DIR} - Overrides persistence directory path
 *   <li>{@code JOTP_SNAPSHOT_INTERVAL} - Overrides snapshot interval (in seconds)
 *   <li>{@code JOTP_EVENTS_PER_SNAPSHOT} - Overrides events per snapshot
 *   <li>{@code JOTP_SYNC_WRITES} - Overrides sync writes setting (true/false)
 * </ul>
 *
 * <p><strong>Usage example:</strong>
 *
 * <pre>{@code
 * // Using defaults with builder
 * PersistenceConfig config = PersistenceConfig.builder()
 *     .durabilityLevel(PersistenceConfig.DurabilityLevel.DURABLE)
 *     .persistenceDirectory(Path.of("/var/lib/jotp/state"))
 *     .syncWrites(true)  // CRITICAL for crash safety
 *     .build();
 *
 * // Production-ready durable config
 * PersistenceConfig durable = PersistenceConfig.durable();
 *
 * // Using environment variable overrides
 * // JOTP_DURABILITY=TRANSACTIONAL JOTP_PERSISTENCE_DIR=/data/jotp java -jar app.jar
 * PersistenceConfig fromEnv = PersistenceConfig.fromEnvironment();
 * }</pre>
 *
 * @param durabilityLevel the persistence durability guarantee
 * @param snapshotInterval minimum time between snapshots (in seconds)
 * @param eventsPerSnapshot number of events to accumulate before snapshot
 * @param persistenceDirectory directory for persistent storage
 * @param syncWrites if true, writes are synced to WAL before returning (crash-safe)
 * @see DurableState
 * @see JvmShutdownManager
 */
public record PersistenceConfig(
        DurabilityLevel durabilityLevel,
        long snapshotInterval,
        int eventsPerSnapshot,
        Path persistenceDirectory,
        boolean syncWrites) {

    /**
     * Durability level for state persistence.
     *
     * <p>Controls the trade-off between performance and data safety.
     */
    public enum DurabilityLevel {
        /**
         * No persistence - state is ephemeral and lost on restart.
         *
         * <p>Use for transient state, caches, or when persistence is not required.
         */
        NONE,

        /**
         * Best-effort persistence - state may be lost on crash.
         *
         * <p>Uses asynchronous writes without fsync. Fast but may lose recent changes on power
         * failure or crash.
         */
        BEST_EFFORT,

        /**
         * Durable persistence - state survives crashes with fsync.
         *
         * <p>Uses write-ahead logging with fsync after each write. Guarantees that committed state
         * survives crashes, but with higher latency.
         */
        DURABLE,

        /**
         * Transactional persistence - ACID guarantees.
         *
         * <p>Full transactional semantics with atomic commits, consistency checks, isolation, and
         * durability. Highest safety, highest latency.
         */
        TRANSACTIONAL
    }

    /** Default persistence configuration. */
    public static final PersistenceConfig DEFAULT =
            new PersistenceConfig(
                    DurabilityLevel.DURABLE,
                    60L, // 60 seconds between snapshots
                    1000, // 1000 events per snapshot
                    Path.of(System.getProperty("java.io.tmpdir"), "jotp-persistence"),
                    true); // syncWrites=true for crash safety

    /**
     * Create a production-ready durable configuration.
     *
     * <p>Includes:
     *
     * <ul>
     *   <li>DURABLE durability level
     *   <li>syncWrites=true for crash safety
     *   <li>Default snapshot interval and events-per-snapshot
     * </ul>
     *
     * @return a new PersistenceConfig optimized for production durability
     */
    public static PersistenceConfig durable() {
        return DEFAULT;
    }

    /**
     * Create a configuration from environment variables.
     *
     * <p>Environment variables override defaults:
     *
     * <ul>
     *   <li>{@code JOTP_DURABILITY} - Durability level name
     *   <li>{@code JOTP_PERSISTENCE_DIR} - Directory path
     *   <li>{@code JOTP_SNAPSHOT_INTERVAL} - Interval in seconds
     *   <li>{@code JOTP_EVENTS_PER_SNAPSHOT} - Events count
     * </ul>
     *
     * @return configuration with environment overrides applied
     */
    public static PersistenceConfig fromEnvironment() {
        return fromEnvironment(DEFAULT);
    }

    /**
     * Create a configuration from environment variables with custom defaults.
     *
     * @param defaults the base configuration to override
     * @return configuration with environment overrides applied
     */
    public static PersistenceConfig fromEnvironment(PersistenceConfig defaults) {
        DurabilityLevel level =
                parseDurabilityLevel(System.getenv("JOTP_DURABILITY"))
                        .orElse(defaults.durabilityLevel());

        Path directory =
                Optional.ofNullable(System.getenv("JOTP_PERSISTENCE_DIR"))
                        .map(Path::of)
                        .orElse(defaults.persistenceDirectory());

        long interval =
                Optional.ofNullable(System.getenv("JOTP_SNAPSHOT_INTERVAL"))
                        .map(Long::parseLong)
                        .orElse(defaults.snapshotInterval());

        int eventsPerSnap =
                Optional.ofNullable(System.getenv("JOTP_EVENTS_PER_SNAPSHOT"))
                        .map(Integer::parseInt)
                        .orElse(defaults.eventsPerSnapshot());

        boolean sync =
                Optional.ofNullable(System.getenv("JOTP_SYNC_WRITES"))
                        .map(Boolean::parseBoolean)
                        .orElse(defaults.syncWrites());

        return new PersistenceConfig(level, interval, eventsPerSnap, directory, sync);
    }

    private static Optional<DurabilityLevel> parseDurabilityLevel(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DurabilityLevel.valueOf(value.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Create a new builder for constructing PersistenceConfig.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for PersistenceConfig. */
    public static final class Builder {
        private DurabilityLevel durabilityLevel = DEFAULT.durabilityLevel;
        private long snapshotInterval = DEFAULT.snapshotInterval;
        private int eventsPerSnapshot = DEFAULT.eventsPerSnapshot;
        private Path persistenceDirectory = DEFAULT.persistenceDirectory;
        private boolean syncWrites = DEFAULT.syncWrites;

        private Builder() {}

        /**
         * Set the durability level.
         *
         * @param level the durability level
         * @return this builder
         */
        public Builder durabilityLevel(DurabilityLevel level) {
            this.durabilityLevel = level;
            return this;
        }

        /**
         * Set the snapshot interval in seconds.
         *
         * @param seconds minimum time between snapshots
         * @return this builder
         */
        public Builder snapshotInterval(long seconds) {
            this.snapshotInterval = seconds;
            return this;
        }

        /**
         * Set the number of events per snapshot.
         *
         * @param count number of events to accumulate before snapshot
         * @return this builder
         */
        public Builder eventsPerSnapshot(int count) {
            this.eventsPerSnapshot = count;
            return this;
        }

        /**
         * Set the persistence directory.
         *
         * @param directory directory for persistent storage
         * @return this builder
         */
        public Builder persistenceDirectory(Path directory) {
            this.persistenceDirectory = directory;
            return this;
        }

        /**
         * Set whether writes should be synced to WAL before returning.
         *
         * <p>CRITICAL: Set to true for crash safety in production. False should only be used for
         * testing or when data loss is acceptable.
         *
         * @param sync if true, writes are synced before returning
         * @return this builder
         */
        public Builder syncWrites(boolean sync) {
            this.syncWrites = sync;
            return this;
        }

        /**
         * Build the PersistenceConfig instance.
         *
         * @return a new PersistenceConfig
         */
        public PersistenceConfig build() {
            return new PersistenceConfig(
                    durabilityLevel,
                    snapshotInterval,
                    eventsPerSnapshot,
                    persistenceDirectory,
                    syncWrites);
        }
    }
}
