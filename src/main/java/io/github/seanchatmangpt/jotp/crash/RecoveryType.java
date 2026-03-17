package io.github.seanchatmangpt.jotp.crash;

/**
 * Type of recovery strategy to apply after a crash.
 *
 * <p>Each type represents a different approach to recovering system state:
 *
 * <ul>
 *   <li>{@link #FULL_RESTART} - Restart all processes from scratch
 *   <li>{@link #SELECTIVE_RESTART} - Restart only affected processes
 *   <li>{@link #MESSAGE_REPLAY} - Replay pending messages to existing processes
 *   <li>{@link #STATE_RESTORE} - Restore state without message replay
 * </ul>
 *
 * @see RecoveryStrategy
 */
public enum RecoveryType {
    /**
     * Restart all processes from scratch.
     *
     * <p>Used when:
     *
     * <ul>
     *   <li>No recoverable state exists
     *   <li>Widespread corruption detected
     *   <li>Out of memory errors
     *   <li>Unknown or unrecognizable crash patterns
     * </ul>
     *
     * <p><strong>Recovery Time:</strong> Longest (full startup) <br>
     * <strong>Success Rate:</strong> Highest (clean slate)
     */
    FULL_RESTART,

    /**
     * Restart only affected processes.
     *
     * <p>Used when:
     *
     * <ul>
     *   <li>Isolated failures detected
     *   <li>Clean failure boundaries exist
     *   <li>Most processes are healthy
     *   <li>Signal-based process termination
     * </ul>
     *
     * <p><strong>Recovery Time:</strong> Medium (targeted restart) <br>
     * <strong>Success Rate:</strong> High (minimal disruption)
     */
    SELECTIVE_RESTART,

    /**
     * Replay pending messages to existing processes.
     *
     * <p>Used when:
     *
     * <ul>
     *   <li>Processes are alive but messages were lost
     *   <li>Message log corruption detected
     *   <li>Network partition caused message loss
     *   <li>Idempotent message handlers available
     * </ul>
     *
     * <p><strong>Recovery Time:</strong> Short (message replay only) <br>
     * <strong>Success Rate:</strong> Medium (depends on idempotence)
     */
    MESSAGE_REPLAY,

    /**
     * Restore state without message replay.
     *
     * <p>Used when:
     *
     * <ul>
     *   <li>State is consistent and verified
     *   <li>No pending messages require replay
     *   <li>Processes can be recreated from snapshots
     *   <li>State persistence is intact
     * </ul>
     *
     * <p><strong>Recovery Time:</strong> Shortest (state loading) <br>
     * <strong>Success Rate:</strong> High (verified state)
     */
    STATE_RESTORE
}
