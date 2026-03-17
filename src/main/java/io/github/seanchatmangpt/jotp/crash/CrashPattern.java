package io.github.seanchatmangpt.jotp.crash;

/**
 * Common crash patterns detected in crash dumps.
 *
 * <p>Each pattern represents a specific type of failure with characteristics that can be detected
 * from the crash dump metadata and system state.
 */
public enum CrashPattern {
    /**
     * Out of memory - heap exhausted.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Heap usage > 95% of max
     *   <li>Recent GC cycles ineffective
     *   <li>Memory allocation failures
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#FULL_RESTART}
     */
    OOM("Out of Memory", RecoveryType.FULL_RESTART),

    /**
     * Process killed by external signal.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Abrupt termination
     *   <li>No exception in logs
     *   <li>Process signal received
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#SELECTIVE_RESTART}
     */
    SIGKILL("Signal Kill", RecoveryType.SELECTIVE_RESTART),

    /**
     * Unhandled exception in process.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Exception stack trace present
     *   <li>Process terminated abnormally
     *   <li>No supervisor intervention
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#SELECTIVE_RESTART}
     */
    UNHANDLED_EXCEPTION("Unhandled Exception", RecoveryType.SELECTIVE_RESTART),

    /**
     * Supervisor detected failure and restarted.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Child process crashed
     *   <li>Supervisor restart triggered
     *   <li>Restart strategy applied
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#SELECTIVE_RESTART}
     */
    SUPERVISOR_RESTART("Supervisor Restart", RecoveryType.SELECTIVE_RESTART),

    /**
     * State inconsistency detected.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Sequence number mismatches
     *   <li>State/ACK inconsistencies
     *   <li>Orphaned processes
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#STATE_RESTORE}
     */
    STATE_INCONSISTENCY("State Inconsistency", RecoveryType.STATE_RESTORE),

    /**
     * Message log corruption.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Message log read failures
     *   <li>Checksum mismatches
     *   <li>Incomplete message sequences
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#MESSAGE_REPLAY}
     */
    MESSAGE_LOG_CORRUPTION("Message Log Corruption", RecoveryType.MESSAGE_REPLAY),

    /**
     * Database connection failure.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Database connection errors
     *   <li>Timeout exceptions
     *   <li>Connection pool exhaustion
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#SELECTIVE_RESTART}
     */
    DATABASE_FAILURE("Database Failure", RecoveryType.SELECTIVE_RESTART),

    /**
     * Network partition detected.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Network timeout errors
     *   <li>Unreachable nodes
     *   <li>Distributed coordinator failures
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#SELECTIVE_RESTART}
     */
    NETWORK_PARTITION("Network Partition", RecoveryType.SELECTIVE_RESTART),

    /**
     * Unknown or unrecognized pattern.
     *
     * <p><strong>Characteristics:</strong>
     *
     * <ul>
     *   <li>Cannot determine specific cause
     *   <li>Multiple conflicting indicators
     *   <li>Insufficient data
     * </ul>
     *
     * <p><strong>Default Recovery:</strong> {@link RecoveryType#FULL_RESTART}
     */
    UNKNOWN("Unknown Pattern", RecoveryType.FULL_RESTART);

    private final String displayName;
    private final RecoveryType defaultRecoveryType;

    CrashPattern(String displayName, RecoveryType defaultRecoveryType) {
        this.displayName = displayName;
        this.defaultRecoveryType = defaultRecoveryType;
    }

    /**
     * Get the display name for this pattern.
     *
     * @return the human-readable pattern name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Get the default recovery type for this pattern.
     *
     * @return the suggested recovery type
     */
    public RecoveryType defaultRecoveryType() {
        return defaultRecoveryType;
    }

    /**
     * Check if this pattern is a fatal error requiring full restart.
     *
     * @return true if this pattern typically requires full restart
     */
    public boolean requiresFullRestart() {
        return this == OOM || this == UNKNOWN;
    }

    /**
     * Check if this pattern allows selective recovery.
     *
     * @return true if selective restart is viable
     */
    public boolean allowsSelectiveRecovery() {
        return !requiresFullRestart();
    }
}
