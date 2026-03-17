package io.github.seanchatmangpt.jotp.crash;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Duration;
import java.util.List;

/**
 * Strategy for recovering from a crash based on dump analysis.
 *
 * <p>Encapsulates the complete recovery plan including which processes to restart, which messages
 * to replay, estimated time, and complexity assessment.
 *
 * @param type the recovery type to apply
 * @param processesToRestart list of process references to restart
 * @param messagesToReplay list of pending messages to replay
 * @param estimatedTime estimated time to complete recovery
 * @param complexityScore complexity score (1-100, higher = more complex)
 */
public record RecoveryStrategy(
        RecoveryType type,
        List<ProcRef<?, ?>> processesToRestart,
        List<PendingMessage> messagesToReplay,
        Duration estimatedTime,
        int complexityScore) {

    /**
     * Check if this strategy requires full system restart.
     *
     * @return true if recovery type is FULL_RESTART
     */
    public boolean requiresFullRestart() {
        return type == RecoveryType.FULL_RESTART;
    }

    /**
     * Check if this strategy can recover without message replay.
     *
     * @return true if recovery type is STATE_RESTORE or FULL_RESTART
     */
    public boolean isStateRestoreOnly() {
        return type == RecoveryType.STATE_RESTORE || type == RecoveryType.FULL_RESTART;
    }

    /**
     * Check if this strategy involves message replay.
     *
     * @return true if messagesToReplay is non-empty
     */
    public boolean involvesMessageReplay() {
        return !messagesToReplay.isEmpty();
    }

    /**
     * Get the number of processes to restart.
     *
     * @return count of processes to restart
     */
    public int processCount() {
        return processesToRestart.size();
    }

    /**
     * Get the number of messages to replay.
     *
     * @return count of messages to replay
     */
    public int messageCount() {
        return messagesToReplay.size();
    }

    /**
     * Check if this is a simple recovery (low complexity).
     *
     * @return true if complexity score is below 30
     */
    public boolean isSimple() {
        return complexityScore < 30;
    }

    /**
     * Check if this is a complex recovery (high complexity).
     *
     * @return true if complexity score is above 70
     */
    public boolean isComplex() {
        return complexityScore > 70;
    }

    /**
     * Get a human-readable complexity level.
     *
     * @return complexity level as a string
     */
    public String complexityLevel() {
        if (complexityScore < 20) return "TRIVIAL";
        if (complexityScore < 40) return "SIMPLE";
        if (complexityScore < 60) return "MODERATE";
        if (complexityScore < 80) return "COMPLEX";
        return "VERY_COMPLEX";
    }

    /**
     * Create a summary of this recovery strategy.
     *
     * @return human-readable summary
     */
    public String summary() {
        return String.format(
                "RecoveryStrategy[type=%s, processes=%d, messages=%d, time=%s, complexity=%s (%d)]",
                type,
                processCount(),
                messageCount(),
                estimatedTime,
                complexityLevel(),
                complexityScore);
    }

    /**
     * Create a full restart strategy.
     *
     * @param estimatedTime estimated time for full restart
     * @return a full restart recovery strategy
     */
    public static RecoveryStrategy fullRestart(Duration estimatedTime) {
        return new RecoveryStrategy(
                RecoveryType.FULL_RESTART, List.of(), List.of(), estimatedTime, 50);
    }

    /**
     * Create a state restore strategy.
     *
     * @param processes processes to restart
     * @param estimatedTime estimated time for restore
     * @return a state restore recovery strategy
     */
    public static RecoveryStrategy stateRestore(
            List<ProcRef<?, ?>> processes, Duration estimatedTime) {
        return new RecoveryStrategy(
                RecoveryType.STATE_RESTORE, processes, List.of(), estimatedTime, 30);
    }

    /**
     * Create a selective restart strategy.
     *
     * @param processes processes to restart
     * @param messages messages to replay
     * @param estimatedTime estimated time for recovery
     * @param complexity complexity score
     * @return a selective restart recovery strategy
     */
    public static RecoveryStrategy selectiveRestart(
            List<ProcRef<?, ?>> processes,
            List<PendingMessage> messages,
            Duration estimatedTime,
            int complexity) {
        return new RecoveryStrategy(
                RecoveryType.SELECTIVE_RESTART, processes, messages, estimatedTime, complexity);
    }

    /**
     * Create a message replay strategy.
     *
     * @param messages messages to replay
     * @param estimatedTime estimated time for replay
     * @return a message replay recovery strategy
     */
    public static RecoveryStrategy messageReplay(
            List<PendingMessage> messages, Duration estimatedTime) {
        return new RecoveryStrategy(
                RecoveryType.MESSAGE_REPLAY, List.of(), messages, estimatedTime, 20);
    }
}
