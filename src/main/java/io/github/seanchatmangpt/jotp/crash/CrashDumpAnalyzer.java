package io.github.seanchatmangpt.jotp.crash;

import io.github.seanchatmangpt.jotp.CrashDump;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.persistence.PersistenceBackend;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Analyzes crash dumps to guide recovery strategy selection.
 *
 * <p>This class implements pattern recognition, consistency verification, and recovery
 * recommendations for JVM crashes. It integrates with the persistence layer to verify state
 * integrity and determine optimal recovery paths.
 *
 * <p><strong>Analysis Flow:</strong>
 *
 * <ol>
 *   <li>Detect crash pattern from dump metadata
 *   <li>Verify state consistency across all processes
 *   <li>Check sequence number integrity
 *   <li>Identify recoverable vs lost state
 *   <li>Generate recovery strategy with recommendations
 * </ol>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Load crash dump
 * CrashDump dump = CrashDumpCollector.getInstance().collectDump();
 *
 * // Analyze and get strategy
 * CrashDumpAnalyzer analyzer = new CrashDumpAnalyzer(persistenceBackend);
 * RecoveryStrategy strategy = analyzer.analyze(dump);
 *
 * // Get detailed report
 * ConsistencyReport report = analyzer.verifyConsistency(dump);
 * List<Recommendation> recommendations = analyzer.getRecommendations(dump);
 *
 * // Apply recovery
 * if (strategy.requiresFullRestart()) {
 *     restartSystem();
 * } else if (strategy.type() == RecoveryType.SELECTIVE_RESTART) {
 *     restartProcesses(strategy.processesToRestart());
 * }
 * }</pre>
 *
 * @see CrashDump
 * @see RecoveryStrategy
 * @see ConsistencyReport
 */
public final class CrashDumpAnalyzer {

    private final PersistenceBackend backend;

    // Thresholds for analysis decisions
    private static final Duration STALE_MESSAGE_THRESHOLD = Duration.ofMinutes(5);
    private static final double MIN_SUCCESS_PROBABILITY = 0.7;
    private static final int MAX_COMPLEXITY_SCORE = 100;
    private static final double OOM_HEAP_USAGE_RATIO = 0.95;
    private static final long ABRUPT_TERMINATION_THRESHOLD_MS = 1000;

    /**
     * Create a new analyzer with the given persistence backend.
     *
     * @param backend the persistence backend for state verification
     * @throws IllegalArgumentException if backend is null
     */
    public CrashDumpAnalyzer(PersistenceBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
    }

    /**
     * Analyze a crash dump and determine the optimal recovery strategy.
     *
     * <p>This method performs comprehensive analysis including:
     *
     * <ul>
     *   <li>Crash pattern detection
     *   <li>State consistency verification
     *   <li>Message replay requirements
     *   <li>Recovery time estimation
     * </ul>
     *
     * @param dump the crash dump to analyze
     * @return the recommended recovery strategy
     * @throws IllegalArgumentException if dump is null
     */
    public RecoveryStrategy analyze(CrashDump dump) {
        Objects.requireNonNull(dump, "dump must not be null");

        // Step 1: Detect crash pattern
        CrashPattern pattern = detectCrashPattern(dump);

        // Step 2: Verify consistency
        ConsistencyReport consistency = verifyConsistency(dump);

        // Step 3: Build strategy based on pattern and consistency
        return buildRecoveryStrategy(dump, pattern, consistency);
    }

    /**
     * Verify state consistency across all processes in the dump.
     *
     * <p>Checks for:
     *
     * <ul>
     *   <li>Sequence number gaps
     *   <li>State/ACK mismatches
     *   <li>Orphaned processes
     *   <li>Registry inconsistencies
     * </ul>
     *
     * @param dump the crash dump to verify
     * @return detailed consistency report
     * @throws IllegalArgumentException if dump is null
     */
    public ConsistencyReport verifyConsistency(CrashDump dump) {
        Objects.requireNonNull(dump, "dump must not be null");

        List<ConsistencyIssue> issues = new ArrayList<>();
        Set<String> consistentProcesses = new HashSet<>();
        Set<String> inconsistentProcesses = new HashSet<>();
        Map<String, StateVerification> verifications = new HashMap<>();

        // Verify each process
        for (Map.Entry<String, CrashDump.ProcessDump> entry : dump.processes().entrySet()) {
            String name = entry.getKey();
            CrashDump.ProcessDump procDump = entry.getValue();

            StateVerification verification = verifyProcessState(name, procDump);
            verifications.put(name, verification);

            if (verification.isValid()) {
                consistentProcesses.add(name);
            } else {
                inconsistentProcesses.add(name);
                issues.add(
                        ConsistencyIssue.error(
                                name,
                                verification.errorMessage().orElse("State verification failed"),
                                "Restore from persistent storage or restart process"));
            }
        }

        // Check registry consistency
        issues.addAll(checkRegistryConsistency(dump));

        // Check supervisor tree consistency
        issues.addAll(checkSupervisorTreeConsistency(dump));

        boolean isConsistent =
                issues.isEmpty()
                        || issues.stream()
                                .allMatch(
                                        i ->
                                                i.severity().ordinal()
                                                        <= ConsistencyIssue.Severity.WARNING
                                                                .ordinal());

        return new ConsistencyReport(
                isConsistent, issues, consistentProcesses, inconsistentProcesses, verifications);
    }

    /**
     * Get recovery recommendations based on dump analysis.
     *
     * <p>Returns prioritized list of recommendations with estimated success rates.
     *
     * @param dump the crash dump to analyze
     * @return list of recovery recommendations, sorted by priority
     * @throws IllegalArgumentException if dump is null
     */
    public List<Recommendation> getRecommendations(CrashDump dump) {
        Objects.requireNonNull(dump, "dump must not be null");

        List<Recommendation> recommendations = new ArrayList<>();

        // Analyze the dump
        CrashPattern pattern = detectCrashPattern(dump);
        ConsistencyReport consistency = verifyConsistency(dump);

        // Add recommendations based on pattern
        switch (pattern) {
            case OOM ->
                    recommendations.add(
                            Recommendation.highPriority(
                                    "Full System Restart",
                                    "Out of memory detected. Full restart required to clear heap.",
                                    RecoveryType.FULL_RESTART,
                                    Duration.ofSeconds(30)));

            case SIGKILL ->
                    recommendations.add(
                            new Recommendation(
                                    "Selective Process Restart",
                                    "Process killed by signal. Restart affected processes.",
                                    RecoveryType.SELECTIVE_RESTART,
                                    7,
                                    Duration.ofSeconds(10),
                                    0.9));

            case STATE_INCONSISTENCY -> {
                if (consistency.isSafeRecovery()) {
                    recommendations.add(
                            new Recommendation(
                                    "State Restoration",
                                    "State is consistent. Restore without message replay.",
                                    RecoveryType.STATE_RESTORE,
                                    8,
                                    Duration.ofSeconds(5),
                                    0.95));
                } else {
                    recommendations.add(
                            new Recommendation(
                                    "Selective Restart with State Recovery",
                                    "Inconsistent state detected. Restart affected processes with recovery.",
                                    RecoveryType.SELECTIVE_RESTART,
                                    9,
                                    Duration.ofSeconds(15),
                                    0.85));
                }
            }

            case MESSAGE_LOG_CORRUPTION ->
                    recommendations.add(
                            new Recommendation(
                                    "Message Replay from Checkpoint",
                                    "Message log corrupted. Replay from last consistent checkpoint.",
                                    RecoveryType.MESSAGE_REPLAY,
                                    8,
                                    Duration.ofSeconds(20),
                                    0.8));

            case UNHANDLED_EXCEPTION ->
                    recommendations.add(
                            new Recommendation(
                                    "Restart Failed Process",
                                    "Unhandled exception in process. Restart with supervision.",
                                    RecoveryType.SELECTIVE_RESTART,
                                    8,
                                    Duration.ofSeconds(5),
                                    0.9));

            case SUPERVISOR_RESTART ->
                    recommendations.add(
                            new Recommendation(
                                    "Supervisor Recovery",
                                    "Supervisor has restarted child. Verify stability.",
                                    RecoveryType.SELECTIVE_RESTART,
                                    6,
                                    Duration.ofSeconds(10),
                                    0.85));

            case DATABASE_FAILURE ->
                    recommendations.add(
                            new Recommendation(
                                    "Database Reconnection",
                                    "Database connection failed. Reconnect and resume.",
                                    RecoveryType.SELECTIVE_RESTART,
                                    7,
                                    Duration.ofSeconds(15),
                                    0.8));

            case NETWORK_PARTITION ->
                    recommendations.add(
                            new Recommendation(
                                    "Network Partition Recovery",
                                    "Network partition detected. Reconnect and sync state.",
                                    RecoveryType.SELECTIVE_RESTART,
                                    7,
                                    Duration.ofSeconds(20),
                                    0.75));

            default ->
                    recommendations.add(
                            new Recommendation(
                                    "Full System Restart",
                                    "Unknown crash pattern. Safest approach is full restart.",
                                    RecoveryType.FULL_RESTART,
                                    5,
                                    Duration.ofSeconds(30),
                                    0.7));
        }

        // Add warning recommendations if issues exist
        if (!consistency.issues().isEmpty()) {
            recommendations.add(
                    new Recommendation(
                            "Address Consistency Issues",
                            String.format(
                                    "Found %d consistency issues that may affect recovery.",
                                    consistency.issues().size()),
                            RecoveryType.SELECTIVE_RESTART,
                            6,
                            Duration.ofSeconds(10),
                            0.75));
        }

        // Sort by priority (descending)
        return recommendations.stream()
                .sorted(Comparator.comparingInt(Recommendation::priority).reversed())
                .toList();
    }

    // ── Private Analysis Methods ─────────────────────────────────────────────

    /** Detect the crash pattern from dump metadata. */
    private CrashPattern detectCrashPattern(CrashDump dump) {
        // Check system metrics for OOM
        if (isOutOfMemory(dump)) {
            return CrashPattern.OOM;
        }

        // Check for SIGKILL patterns (abrupt termination)
        if (isAbruptTermination(dump)) {
            return CrashPattern.SIGKILL;
        }

        // Check supervisor restart patterns
        if (hasSupervisorRestarts(dump)) {
            return CrashPattern.SUPERVISOR_RESTART;
        }

        // Check for state inconsistencies
        if (hasStateInconsistencies(dump)) {
            return CrashPattern.STATE_INCONSISTENCY;
        }

        // Check for message log issues (pending messages but no processing)
        if (hasMessageLogIssues(dump)) {
            return CrashPattern.MESSAGE_LOG_CORRUPTION;
        }

        // Default to unknown
        return CrashPattern.UNKNOWN;
    }

    /** Build recovery strategy from analysis results. */
    private RecoveryStrategy buildRecoveryStrategy(
            CrashDump dump, CrashPattern pattern, ConsistencyReport consistency) {
        RecoveryType type = determineRecoveryType(pattern, consistency);
        List<ProcRef<?, ?>> processesToRestart = identifyProcessesToRestart(dump, consistency);
        List<PendingMessage> messagesToReplay = identifyMessagesToReplay(dump);
        Duration estimatedTime =
                estimateRecoveryTime(type, processesToRestart.size(), messagesToReplay.size());
        int complexityScore = calculateComplexityScore(dump, consistency);

        return new RecoveryStrategy(
                type, processesToRestart, messagesToReplay, estimatedTime, complexityScore);
    }

    /** Determine the recovery type based on pattern and consistency. */
    private RecoveryType determineRecoveryType(
            CrashPattern pattern, ConsistencyReport consistency) {
        // OOM always requires full restart, regardless of consistency
        if (pattern == CrashPattern.OOM) {
            return RecoveryType.FULL_RESTART;
        }

        // For unknown patterns with no processes, default to pattern's suggestion
        if (pattern == CrashPattern.UNKNOWN
                && consistency.consistentProcesses().isEmpty()
                && consistency.inconsistentProcesses().isEmpty()) {
            return RecoveryType.FULL_RESTART;
        }

        // If state is consistent and has some processes but no inconsistencies, state restore
        if (consistency.isConsistent()
                && consistency.inconsistentProcesses().isEmpty()
                && !consistency.consistentProcesses().isEmpty()) {
            return RecoveryType.STATE_RESTORE;
        }

        // If only a few processes are inconsistent, selective restart
        if (!consistency.inconsistentProcesses().isEmpty()
                && consistency.inconsistentProcesses().size() <= 5) {
            return RecoveryType.SELECTIVE_RESTART;
        }

        // Default to pattern's suggested type
        return pattern.defaultRecoveryType();
    }

    /**
     * Identify which processes need to be restarted.
     *
     * <p>NOTE: This returns an empty list currently. In a full implementation, this would integrate
     * with ProcRegistry to create actual ProcRef instances for the inconsistent processes.
     */
    private List<ProcRef<?, ?>> identifyProcessesToRestart(
            CrashDump dump, ConsistencyReport consistency) {
        throw new UnsupportedOperationException(
                "not implemented: ProcRegistry integration required to create ProcRef instances from dump");
    }

    /** Identify messages that need to be replayed. */
    private List<PendingMessage> identifyMessagesToReplay(CrashDump dump) {
        List<PendingMessage> messages = new ArrayList<>();

        for (Map.Entry<String, CrashDump.ProcessDump> entry : dump.processes().entrySet()) {
            String processName = entry.getKey();
            CrashDump.ProcessDump procDump = entry.getValue();

            for (CrashDump.MessageDump msgDump : procDump.pendingMessages()) {
                messages.add(
                        new PendingMessage(
                                processName,
                                msgDump.messageClass(),
                                msgDump.serializedMessage(),
                                msgDump.enqueuedAt(),
                                procDump.lastProcessedSeq() + 1, // Next expected sequence
                                true // Assume idempotent for now
                                ));
            }
        }

        return messages;
    }

    /** Estimate recovery time based on strategy. */
    private Duration estimateRecoveryTime(RecoveryType type, int processCount, int messageCount) {
        Duration baseTime =
                switch (type) {
                    case FULL_RESTART -> Duration.ofSeconds(30);
                    case SELECTIVE_RESTART -> Duration.ofSeconds(5).multipliedBy(processCount);
                    case MESSAGE_REPLAY -> Duration.ofMillis(10).multipliedBy(messageCount);
                    case STATE_RESTORE -> Duration.ofSeconds(2);
                };

        // Add 20% buffer
        return baseTime.multipliedBy(120).dividedBy(100);
    }

    /** Calculate complexity score (1-100). */
    private int calculateComplexityScore(CrashDump dump, ConsistencyReport consistency) {
        int score = 0;

        // Base score from number of processes (at least 5 if OOM or other critical issues)
        score += Math.min(30, Math.max(5, dump.processes().size()));

        // Add for inconsistent processes
        score += consistency.inconsistentProcesses().size() * 10;

        // Add for pending messages
        int totalPending =
                dump.processes().values().stream()
                        .mapToInt(CrashDump.ProcessDump::pendingMessageCount)
                        .sum();
        score += Math.min(20, totalPending / 10);

        // Add for supervisor tree depth
        score += Math.min(20, dump.supervisorTree().rootSupervisors().size() * 5);

        // Ensure minimum score of 1 for any crash
        return Math.max(1, Math.min(MAX_COMPLEXITY_SCORE, score));
    }

    // ── Pattern Detection Methods ────────────────────────────────────────────

    private boolean isOutOfMemory(CrashDump dump) {
        var metrics = dump.systemMetrics();
        double heapUsageRatio = (double) metrics.heapUsedBytes() / metrics.heapMaxBytes();
        return heapUsageRatio > OOM_HEAP_USAGE_RATIO;
    }

    private boolean isAbruptTermination(CrashDump dump) {
        // Check if JVM uptime is very short (likely SIGKILL during startup)
        return dump.jvmUptimeMillis() < ABRUPT_TERMINATION_THRESHOLD_MS;
    }

    private boolean hasSupervisorRestarts(CrashDump dump) {
        return dump.supervisorTree().totalChildren() > dump.supervisorTree().aliveChildren();
    }

    private boolean hasStateInconsistencies(CrashDump dump) {
        // Check for processes with pending messages but no recent processing
        return dump.processes().values().stream()
                .anyMatch(p -> p.pendingMessageCount() > 0 && p.messagesOut() == 0);
    }

    private boolean hasMessageLogIssues(CrashDump dump) {
        // Check for high pending message count across processes
        long totalPending =
                dump.processes().values().stream()
                        .mapToLong(CrashDump.ProcessDump::pendingMessageCount)
                        .sum();

        long totalProcessed =
                dump.processes().values().stream()
                        .mapToLong(CrashDump.ProcessDump::messagesOut)
                        .sum();

        // If pending messages exceed 10% of processed, flag as potential issue
        return totalProcessed > 0 && (totalPending * 10 > totalProcessed);
    }

    // ── Consistency Checking Methods ─────────────────────────────────────────

    /** Verify a single process's state. */
    private StateVerification verifyProcessState(String name, CrashDump.ProcessDump procDump) {
        try {
            // Check if we have state in persistence
            var persistedState = backend.load(name);

            if (persistedState.isEmpty()) {
                // No persisted state - check if process has any messages processed
                if (procDump.messagesOut() > 0) {
                    return StateVerification.invalid(
                            name,
                            "No persisted state but process has processed messages",
                            procDump.lastProcessedSeq(),
                            0);
                }
                // New process with no history - valid
                return StateVerification.valid(name, procDump.lastProcessedSeq());
            }

            // State exists - verify sequence numbers match
            long persistedSeq = extractSequenceFromState(persistedState.get());
            long dumpSeq = procDump.lastProcessedSeq();

            if (persistedSeq != dumpSeq) {
                return StateVerification.invalid(
                        name,
                        String.format(
                                "Sequence mismatch: persisted=%d, dump=%d", persistedSeq, dumpSeq),
                        dumpSeq,
                        persistedSeq);
            }

            return StateVerification.valid(name, dumpSeq);

        } catch (Exception e) {
            return StateVerification.invalid(
                    name, "Verification error: " + e.getMessage(), procDump.lastProcessedSeq(), -1);
        }
    }

    /**
     * Extract sequence number from persisted state bytes.
     *
     * <p>NOTE: This is a simplified implementation. In a full implementation, this would
     * deserialize the state and extract the lastProcessedSeq field from a SequencedState
     * implementation.
     */
    private long extractSequenceFromState(byte[] stateBytes) {
        // For now, return 0 - real implementation would deserialize and get lastProcessedSeq
        // This would use the SnapshotCodec to deserialize and then call lastProcessedSeq()
        return 0;
    }

    /** Check registry consistency. */
    private List<ConsistencyIssue> checkRegistryConsistency(CrashDump dump) {
        List<ConsistencyIssue> issues = new ArrayList<>();

        // Check local registry
        for (Map.Entry<String, CrashDump.RegistryEntryDump> entry :
                dump.registryEntries().entrySet()) {
            String name = entry.getKey();
            var entryDump = entry.getValue();

            // Check if process exists in dump
            if (!dump.processes().containsKey(name)) {
                if (entryDump.processAlive()) {
                    issues.add(
                            ConsistencyIssue.warning(
                                    name,
                                    "Registry entry claims process is alive but not in dump",
                                    "Process may have crashed after registry snapshot"));
                }
            }
        }

        return issues;
    }

    /** Check supervisor tree consistency. */
    private List<ConsistencyIssue> checkSupervisorTreeConsistency(CrashDump dump) {
        List<ConsistencyIssue> issues = new ArrayList<>();

        var tree = dump.supervisorTree();

        // Check if alive children count matches
        if (tree.aliveChildren() > dump.processes().size()) {
            issues.add(
                    ConsistencyIssue.warning(
                            "supervisor-tree",
                            String.format(
                                    "Supervisor tree reports %d alive children but only %d processes in dump",
                                    tree.aliveChildren(), dump.processes().size()),
                            "Verify supervisor tree integrity"));
        }

        return issues;
    }
}
