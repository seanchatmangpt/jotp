package io.github.seanchatmangpt.jotp.crash;

/**
 * A consistency issue found during crash dump analysis.
 *
 * <p>Represents a specific problem detected in the crash dump that may affect recovery. Each issue
 * has a severity level and a recommended action.
 *
 * @param processName the process name where the issue was found
 * @param severity the severity level of the issue
 * @param description human-readable description of the issue
 * @param recommendation recommended action to address the issue
 */
public record ConsistencyIssue(
        String processName, Severity severity, String description, String recommendation) {

    /** Severity level of a consistency issue. */
    public enum Severity {
        /** No issue detected */
        NONE,

        /** Informational - not a problem but worth noting */
        INFO,

        /** Potentially problematic - may affect recovery */
        WARNING,

        /** Definitely problematic - will affect recovery */
        ERROR,

        /** Critical - system cannot recover safely */
        CRITICAL
    }

    /**
     * Check if this issue blocks recovery.
     *
     * <p>Critical and Error severity issues typically block recovery.
     *
     * @return true if this issue blocks recovery
     */
    public boolean blocksRecovery() {
        return severity == Severity.CRITICAL || severity == Severity.ERROR;
    }

    /**
     * Check if this issue is just informational.
     *
     * @return true if severity is INFO or NONE
     */
    public boolean isInformational() {
        return severity == Severity.INFO || severity == Severity.NONE;
    }

    /**
     * Create a critical consistency issue.
     *
     * @param processName the process name
     * @param description the issue description
     * @param recommendation the recommended action
     * @return a critical consistency issue
     */
    public static ConsistencyIssue critical(
            String processName, String description, String recommendation) {
        return new ConsistencyIssue(processName, Severity.CRITICAL, description, recommendation);
    }

    /**
     * Create a warning consistency issue.
     *
     * @param processName the process name
     * @param description the issue description
     * @param recommendation the recommended action
     * @return a warning consistency issue
     */
    public static ConsistencyIssue warning(
            String processName, String description, String recommendation) {
        return new ConsistencyIssue(processName, Severity.WARNING, description, recommendation);
    }

    /**
     * Create an error consistency issue.
     *
     * @param processName the process name
     * @param description the issue description
     * @param recommendation the recommended action
     * @return an error consistency issue
     */
    public static ConsistencyIssue error(
            String processName, String description, String recommendation) {
        return new ConsistencyIssue(processName, Severity.ERROR, description, recommendation);
    }
}
