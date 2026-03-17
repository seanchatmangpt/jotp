package io.github.seanchatmangpt.jotp.crash;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.CrashDump;
import io.github.seanchatmangpt.jotp.CrashDumpCollector;
import io.github.seanchatmangpt.jotp.persistence.InMemoryBackend;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Integration tests for CrashDumpAnalyzer using DTR narrative documentation.
 *
 * <p>Tests end-to-end crash analysis scenarios including:
 *
 * <ul>
 *   <li>Crash dump collection and analysis
 *   <li>Recovery strategy selection
 *   <li>Consistency verification
 *   <li>Recommendation generation
 * </ul>
 *
 * @see CrashDumpAnalyzer
 * @see RecoveryStrategy
 */
@DtrTest
class CrashDumpAnalyzerIT {

    private InMemoryBackend backend;
    private CrashDumpAnalyzer analyzer;
    private CrashDumpCollector collector;

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        backend = new InMemoryBackend();
        analyzer = new CrashDumpAnalyzer(backend);
        collector = CrashDumpCollector.getInstance();
        collector.reset();
    }

    @AfterEach
    void tearDown() {
        ApplicationController.reset();
        collector.reset();
    }

    @org.junit.jupiter.api.Test
    void shouldAnalyzeCrashDumpAndSelectStrategy(DtrContext ctx) {
        ctx.say(
                """
            The CrashDumpAnalyzer is responsible for analyzing crash dumps and
            determining the optimal recovery strategy. It must correctly identify
            crash patterns, verify state consistency, and recommend appropriate
            recovery actions.

            This test demonstrates the complete analysis flow from crash dump
            to recovery strategy selection.
            """);

        ctx.say(
                """
            Phase 1: Create a realistic crash dump

            We simulate an out-of-memory crash with high heap usage and multiple
            processes in various states.
            """);

        ctx.sayCode(
                "java",
                """
            // Create a dump simulating OOM crash
            var metrics = new CrashDump.SystemMetrics(
                950_000_000L,  // 950MB used (95%)
                1_000_000_000L, // 1GB max
                100, 50, 8, 2.5
            );

            var dump = new CrashDump(
                "node-1",
                Instant.now(),
                5000,
                processes,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                CrashDump.SupervisorTreeDump.EMPTY,
                metrics
            );
            """);

        var processes =
                Collections.singletonMap(
                        "worker-1",
                        new CrashDump.ProcessDump(
                                "worker-1",
                                "WorkerState",
                                new byte[0],
                                100L,
                                5,
                                Collections.emptyList(),
                                false,
                                false,
                                100,
                                95));

        var metrics = new CrashDump.SystemMetrics(950_000_000L, 1_000_000_000L, 100, 50, 8, 2.5);

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        processes,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        metrics);

        ctx.say(
                """
            Phase 2: Analyze the crash dump

            The analyzer examines the dump to detect the crash pattern and
            determine the appropriate recovery strategy.
            """);

        ctx.sayCode(
                "java",
                """
            RecoveryStrategy strategy = analyzer.analyze(dump);

            // Verify OOM was detected
            assertThat(strategy.type()).isEqualTo(RecoveryType.FULL_RESTART);
            assertThat(strategy.requiresFullRestart()).isTrue();
            """);

        RecoveryStrategy strategy = analyzer.analyze(dump);

        assertThat(strategy.type()).isEqualTo(RecoveryType.FULL_RESTART);
        assertThat(strategy.requiresFullRestart()).isTrue();

        ctx.say(
                """
            Phase 3: Verify consistency and get recommendations

            The analyzer provides detailed consistency reports and prioritized
            recommendations to guide recovery decisions.
            """);

        ctx.sayCode(
                "java",
                """
            ConsistencyReport report = analyzer.verifyConsistency(dump);
            List<Recommendation> recommendations = analyzer.getRecommendations(dump);

            // Recommendations should be sorted by priority
            assertThat(recommendations).isNotEmpty();
            assertThat(recommendations.get(0).isHighPriority()).isTrue();
            """);

        ConsistencyReport report = analyzer.verifyConsistency(dump);
        List<Recommendation> recommendations = analyzer.getRecommendations(dump);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.get(0).isHighPriority()).isTrue();

        ctx.say(
                """
            Analysis complete!

            The analyzer correctly identified the OOM pattern and recommended
            a full system restart. The consistency report shows the state of
            all processes, and recommendations are prioritized to guide recovery.
            """);

        ctx.sayCode(
                "output",
                String.format(
                        """
                Recovery Strategy: %s
                Complexity: %s (%d/100)
                Estimated Time: %s

                Consistency: %s
                Summary: %s

                Top Recommendation: %s
                Priority: %s
                Success Probability: %.0f%%
                """,
                        strategy.type(),
                        strategy.complexityLevel(),
                        strategy.complexityScore(),
                        strategy.estimatedTime(),
                        report.isConsistent() ? "CONSISTENT" : "INCONSISTENT",
                        report.summary(),
                        recommendations.get(0).title(),
                        recommendations.get(0).priorityLevel(),
                        recommendations.get(0).successProbability() * 100));
    }

    @org.junit.jupiter.api.Test
    void shouldDetectSelectiveRestartScenario(DtrContext ctx) {
        ctx.say(
                """
            Not all crashes require full system restart. The analyzer must
            identify scenarios where selective restart is appropriate, such as
            isolated process failures or signal-based termination.
            """);

        ctx.say(
                """
            Create a crash dump with short JVM uptime (SIGKILL pattern)
            """);

        var metrics = new CrashDump.SystemMetrics(500_000_000L, 1_000_000_000L, 100, 50, 8, 2.5);

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        500, // Very short uptime = SIGKILL
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        metrics);

        ctx.sayCode(
                "java",
                """
            RecoveryStrategy strategy = analyzer.analyze(dump);

            // SIGKILL should trigger selective restart
            assertThat(strategy.type()).isEqualTo(RecoveryType.SELECTIVE_RESTART);
            assertThat(strategy.requiresFullRestart()).isFalse();
            """);

        RecoveryStrategy strategy = analyzer.analyze(dump);

        assertThat(strategy.type()).isEqualTo(RecoveryType.SELECTIVE_RESTART);
        assertThat(strategy.requiresFullRestart()).isFalse();

        ctx.say("Selective restart is faster and less disruptive than full restart.");
    }

    @org.junit.jupiter.api.Test
    void shouldVerifyConsistencyAcrossProcesses(DtrContext ctx) {
        ctx.say(
                """
            State consistency is critical for safe recovery. The analyzer
            must detect inconsistencies between crash dump state and persisted
            state, sequence number mismatches, and registry issues.
            """);

        var processes = new java.util.HashMap<String, CrashDump.ProcessDump>();

        // Consistent process
        processes.put(
                "consistent-proc",
                new CrashDump.ProcessDump(
                        "consistent-proc",
                        "State",
                        new byte[0],
                        100L,
                        0,
                        Collections.emptyList(),
                        false,
                        false,
                        0, // No messages processed
                        0));

        // Inconsistent process (processed messages but no persisted state)
        processes.put(
                "inconsistent-proc",
                new CrashDump.ProcessDump(
                        "inconsistent-proc",
                        "State",
                        new byte[0],
                        100L,
                        5,
                        Collections.emptyList(),
                        false,
                        false,
                        100, // Processed messages
                        95));

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        processes,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        CrashDump.SystemMetrics.EMPTY);

        ctx.sayCode(
                "java",
                """
            ConsistencyReport report = analyzer.verifyConsistency(dump);

            // Should detect inconsistency
            assertThat(report.isConsistent()).isFalse();
            assertThat(report.inconsistentProcesses()).contains("inconsistent-proc");
            assertThat(report.consistentProcesses()).contains("consistent-proc");
            """);

        ConsistencyReport report = analyzer.verifyConsistency(dump);

        assertThat(report.isConsistent()).isFalse();
        assertThat(report.inconsistentProcesses()).contains("inconsistent-proc");
        assertThat(report.consistentProcesses()).contains("consistent-proc");

        ctx.say("Consistency issues are reported with severity and recommendations.");
    }

    @org.junit.jupiter.api.Test
    void shouldPrioritizeRecommendations(DtrContext ctx) {
        ctx.say(
                """
            Recovery recommendations must be prioritized by urgency and success
            probability. High-priority recommendations should appear first in
            the list to guide recovery decisions.
            """);

        var metrics = new CrashDump.SystemMetrics(950_000_000L, 1_000_000_000L, 100, 50, 8, 2.5);

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        metrics);

        ctx.sayCode(
                "java",
                """
            List<Recommendation> recommendations = analyzer.getRecommendations(dump);

            // Verify priority sorting
            for (int i = 1; i < recommendations.size(); i++) {
                assertThat(recommendations.get(i).priority())
                    .isLessThanOrEqualTo(recommendations.get(i - 1).priority());
            }

            // First recommendation should be highest priority
            assertThat(recommendations.get(0).priority()).isGreaterThanOrEqualTo(7);
            """);

        List<Recommendation> recommendations = analyzer.getRecommendations(dump);

        // Verify priority sorting
        for (int i = 1; i < recommendations.size(); i++) {
            assertThat(recommendations.get(i).priority())
                    .isLessThanOrEqualTo(recommendations.get(i - 1).priority());
        }

        // First recommendation should be highest priority
        assertThat(recommendations.get(0).priority()).isGreaterThanOrEqualTo(7);

        ctx.say("Prioritized recommendations help operators make quick recovery decisions.");
    }

    @org.junit.jupiter.api.Test
    void shouldIdentifyPendingMessagesForReplay(DtrContext ctx) {
        ctx.say(
                """
            Messages that were pending at crash time must be identified for
            replay during recovery. The analyzer extracts these messages from
            the crash dump and includes them in the recovery strategy.
            """);

        var messages =
                List.of(
                        new CrashDump.MessageDump("ProcessTask", "task1".getBytes(), Instant.now()),
                        new CrashDump.MessageDump("ProcessTask", "task2".getBytes(), Instant.now()),
                        new CrashDump.MessageDump(
                                "ProcessTask", "task3".getBytes(), Instant.now()));

        var processes =
                Collections.singletonMap(
                        "task-worker",
                        new CrashDump.ProcessDump(
                                "task-worker",
                                "WorkerState",
                                new byte[0],
                                100L,
                                3, // 3 pending messages
                                messages,
                                false,
                                false,
                                100,
                                95));

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        processes,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        CrashDump.SystemMetrics.EMPTY);

        ctx.sayCode(
                "java",
                """
            RecoveryStrategy strategy = analyzer.analyze(dump);

            // Should identify pending messages
            assertThat(strategy.involvesMessageReplay()).isTrue();
            assertThat(strategy.messagesToReplay()).hasSize(3);
            """);

        RecoveryStrategy strategy = analyzer.analyze(dump);

        assertThat(strategy.involvesMessageReplay()).isTrue();
        assertThat(strategy.messagesToReplay()).hasSize(3);

        ctx.say("Pending messages are extracted for replay during recovery.");
    }

    @org.junit.jupiter.api.Test
    void shouldCalculateRecoveryComplexity(DtrContext ctx) {
        ctx.say(
                """
            Recovery complexity is calculated based on the number of processes,
            pending messages, supervisor tree depth, and consistency issues.
            This score (1-100) helps operators understand the difficulty of
            recovery.
            """);

        var processes = new java.util.HashMap<String, CrashDump.ProcessDump>();

        // Add multiple processes to increase complexity
        for (int i = 1; i <= 5; i++) {
            processes.put(
                    "proc-" + i,
                    new CrashDump.ProcessDump(
                            "proc-" + i,
                            "State",
                            new byte[0],
                            100L,
                            i * 2,
                            Collections.emptyList(),
                            false,
                            false,
                            100,
                            95));
        }

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        processes,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        CrashDump.SystemMetrics.EMPTY);

        ctx.sayCode(
                "java",
                """
            RecoveryStrategy strategy = analyzer.analyze(dump);

            // Complexity should reflect the number of processes and messages
            assertThat(strategy.complexityScore()).isGreaterThan(0);
            assertThat(strategy.complexityScore()).isLessThanOrEqualTo(100);
            assertThat(strategy.summary()).contains("complexity");
            """);

        RecoveryStrategy strategy = analyzer.analyze(dump);

        assertThat(strategy.complexityScore()).isGreaterThan(0);
        assertThat(strategy.complexityScore()).isLessThanOrEqualTo(100);
        assertThat(strategy.summary()).contains("complexity");

        ctx.say(
                String.format(
                        "Complexity score: %d/100 (%s)",
                        strategy.complexityScore(), strategy.complexityLevel()));
    }

    @org.junit.jupiter.api.Test
    void shouldEstimateRecoveryTime(DtrContext ctx) {
        ctx.say(
                """
            Recovery time estimation helps operators plan their response.
            The analyzer estimates time based on recovery type, process count,
            and message count.
            """);

        var processes =
                Collections.singletonMap(
                        "proc-1",
                        new CrashDump.ProcessDump(
                                "proc-1",
                                "State",
                                new byte[0],
                                100L,
                                5,
                                Collections.emptyList(),
                                false,
                                false,
                                100,
                                95));

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        processes,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        CrashDump.SystemMetrics.EMPTY);

        ctx.sayCode(
                "java",
                """
            RecoveryStrategy strategy = analyzer.analyze(dump);

            // Should estimate recovery time
            assertThat(strategy.estimatedTime()).isNotNull();
            assertThat(strategy.estimatedTime()).isGreaterThan(Duration.ZERO);
            assertThat(strategy.estimatedTime()).isLessThan(Duration.ofHours(1));
            """);

        RecoveryStrategy strategy = analyzer.analyze(dump);

        assertThat(strategy.estimatedTime()).isNotNull();
        assertThat(strategy.estimatedTime()).isGreaterThan(java.time.Duration.ZERO);
        assertThat(strategy.estimatedTime()).isLessThan(java.time.Duration.ofHours(1));

        ctx.say(String.format("Estimated recovery time: %s", strategy.estimatedTime()));
    }
}
