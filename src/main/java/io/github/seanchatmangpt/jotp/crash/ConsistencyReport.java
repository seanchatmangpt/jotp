package io.github.seanchatmangpt.jotp.crash;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Report on state consistency across the crash dump.
 *
 * <p>Provides comprehensive analysis of state consistency issues found during crash dump
 * verification, including severity assessment and recommendations.
 *
 * @param isConsistent whether the overall dump is consistent
 * @param issues list of consistency issues found
 * @param consistentProcesses set of process names with consistent state
 * @param inconsistentProcesses set of process names with inconsistent state
 * @param processVerifications detailed verification results per process
 */
public record ConsistencyReport(
        boolean isConsistent,
        List<ConsistencyIssue> issues,
        Set<String> consistentProcesses,
        Set<String> inconsistentProcesses,
        Map<String, StateVerification> processVerifications) {

    /**
     * Get the highest severity level of all issues.
     *
     * @return the highest severity, or NONE if no issues
     */
    public ConsistencyIssue.Severity getHighestSeverity() {
        return issues.stream()
                .map(ConsistencyIssue::severity)
                .max(Enum::compareTo)
                .orElse(ConsistencyIssue.Severity.NONE);
    }

    /**
     * Check if recovery is safe without data loss.
     *
     * <p>Recovery is considered safe if:
     *
     * <ul>
     *   <li>The dump is consistent
     *   <li>No critical or error issues exist
     *   <li>All issues are warnings or informational
     * </ul>
     *
     * @return true if safe recovery is possible
     */
    public boolean isSafeRecovery() {
        return isConsistent
                && getHighestSeverity().ordinal() <= ConsistencyIssue.Severity.WARNING.ordinal();
    }

    /**
     * Check if there are any critical issues.
     *
     * @return true if any issues are critical severity
     */
    public boolean hasCriticalIssues() {
        return issues.stream().anyMatch(i -> i.severity() == ConsistencyIssue.Severity.CRITICAL);
    }

    /**
     * Get only the critical issues.
     *
     * @return list of critical issues
     */
    public List<ConsistencyIssue> getCriticalIssues() {
        return issues.stream()
                .filter(i -> i.severity() == ConsistencyIssue.Severity.CRITICAL)
                .toList();
    }

    /**
     * Get only the error issues.
     *
     * @return list of error issues
     */
    public List<ConsistencyIssue> getErrorIssues() {
        return issues.stream()
                .filter(i -> i.severity() == ConsistencyIssue.Severity.ERROR)
                .toList();
    }

    /**
     * Get only the warning issues.
     *
     * @return list of warning issues
     */
    public List<ConsistencyIssue> getWarningIssues() {
        return issues.stream()
                .filter(i -> i.severity() == ConsistencyIssue.Severity.WARNING)
                .toList();
    }

    /**
     * Get the percentage of consistent processes.
     *
     * @return percentage (0-100) of processes that are consistent
     */
    public double consistencyPercentage() {
        int totalProcesses = consistentProcesses.size() + inconsistentProcesses.size();
        if (totalProcesses == 0) {
            return 100.0; // No processes means fully consistent
        }
        return (consistentProcesses.size() * 100.0) / totalProcesses;
    }

    /**
     * Get a summary of the consistency report.
     *
     * @return human-readable summary
     */
    public String summary() {
        if (isConsistent && issues.isEmpty()) {
            return String.format("All %d processes are consistent", consistentProcesses.size());
        }

        return String.format(
                "Found %d issues: %d critical, %d errors, %d warnings. %d/%d processes consistent (%.1f%%)",
                issues.size(),
                getCriticalIssues().size(),
                getErrorIssues().size(),
                getWarningIssues().size(),
                consistentProcesses.size(),
                consistentProcesses.size() + inconsistentProcesses.size(),
                consistencyPercentage());
    }

    /**
     * Create an empty (fully consistent) report.
     *
     * @return a consistency report with no issues
     */
    public static ConsistencyReport empty() {
        return new ConsistencyReport(true, List.of(), Set.of(), Set.of(), Map.of());
    }

    /**
     * Create a report with a single issue.
     *
     * @param issue the consistency issue
     * @return a consistency report with the issue
     */
    public static ConsistencyReport withIssue(ConsistencyIssue issue) {
        return new ConsistencyReport(
                false, List.of(issue), Set.of(), Set.of(issue.processName()), Map.of());
    }
}
