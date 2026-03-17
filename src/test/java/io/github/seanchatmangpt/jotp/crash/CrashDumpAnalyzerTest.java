package io.github.seanchatmangpt.jotp.crash;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.CrashDump;
import io.github.seanchatmangpt.jotp.persistence.InMemoryBackend;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for CrashDumpAnalyzer. */
class CrashDumpAnalyzerTest {

    private InMemoryBackend backend;
    private CrashDumpAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        backend = new InMemoryBackend();
        analyzer = new CrashDumpAnalyzer(backend);
    }

    @Test
    void shouldDetectOOMPattern() {
        // Create dump with high heap usage (>95%)
        var metrics =
                new CrashDump.SystemMetrics(
                        950_000_000L, // 950MB used
                        1_000_000_000L, // 1GB max
                        100,
                        50,
                        8,
                        2.5);

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

        var strategy = analyzer.analyze(dump);

        assertThat(strategy.type()).isEqualTo(RecoveryType.FULL_RESTART);
        assertThat(strategy.requiresFullRestart()).isTrue();
        assertThat(strategy.complexityScore()).isGreaterThan(0);
    }

    @Test
    void shouldDetectSIGKILLPattern() {
        // Create dump with very short uptime (abrupt termination)
        var metrics = new CrashDump.SystemMetrics(500_000_000L, 1_000_000_000L, 100, 50, 8, 2.5);

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        500, // Less than 1000ms = abrupt termination
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        metrics);

        var strategy = analyzer.analyze(dump);

        assertThat(strategy.type()).isEqualTo(RecoveryType.SELECTIVE_RESTART);
        assertThat(strategy.requiresFullRestart()).isFalse();
    }

    @Test
    void shouldVerifyEmptyDumpAsConsistent() {
        var dump = CrashDump.EMPTY;

        var report = analyzer.verifyConsistency(dump);

        assertThat(report.isConsistent()).isTrue();
        assertThat(report.issues()).isEmpty();
        assertThat(report.consistentProcesses()).isEmpty();
        assertThat(report.inconsistentProcesses()).isEmpty();
        assertThat(report.summary()).contains("All 0 processes are consistent");
    }

    @Test
    void shouldVerifyDumpWithProcesses() {
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

        var report = analyzer.verifyConsistency(dump);

        // Process has no persisted state, but has processed messages - should be inconsistent
        assertThat(report.inconsistentProcesses()).contains("proc-1");
        assertThat(report.issues()).hasSize(1);
    }

    @Test
    void shouldProvideRecommendationsForOOM() {
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

        var recommendations = analyzer.getRecommendations(dump);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.get(0).title()).contains("Restart");
        assertThat(recommendations.get(0).suggestedType()).isEqualTo(RecoveryType.FULL_RESTART);
        assertThat(recommendations.get(0).priority()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void shouldProvideRecommendationsForStateInconsistency() {
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
                                0 // No messages processed
                                ));

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

        var recommendations = analyzer.getRecommendations(dump);

        assertThat(recommendations).isNotEmpty();
        // Should have recommendations for state inconsistency
        assertThat(recommendations.stream().anyMatch(r -> r.title().contains("State"))).isTrue();
    }

    @Test
    void shouldCalculateComplexityScore() {
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

        var strategy = analyzer.analyze(dump);

        assertThat(strategy.complexityScore()).isGreaterThan(0);
        assertThat(strategy.complexityScore()).isLessThanOrEqualTo(100);
        assertThat(strategy.summary()).isNotNull();
    }

    @Test
    void shouldIdentifyPendingMessagesForReplay() {
        var messages =
                List.of(
                        new CrashDump.MessageDump("Msg1", "msg1".getBytes(), Instant.now()),
                        new CrashDump.MessageDump("Msg2", "msg2".getBytes(), Instant.now()));

        var processes =
                Collections.singletonMap(
                        "proc-1",
                        new CrashDump.ProcessDump(
                                "proc-1",
                                "State",
                                new byte[0],
                                100L,
                                2, // 2 pending messages
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

        var strategy = analyzer.analyze(dump);

        assertThat(strategy.messagesToReplay()).hasSize(2);
        assertThat(strategy.involvesMessageReplay()).isTrue();
    }

    @Test
    void shouldDetectSupervisorRestartPattern() {
        var tree =
                new CrashDump.SupervisorTreeDump(
                        Collections.emptyList(),
                        10, // 10 total children
                        8 // 8 alive (2 restarted)
                        );

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        tree,
                        CrashDump.SystemMetrics.EMPTY);

        var strategy = analyzer.analyze(dump);

        // Should detect supervisor restart pattern
        assertThat(strategy.type()).isEqualTo(RecoveryType.SELECTIVE_RESTART);
    }

    @Test
    void shouldCheckRegistryConsistency() {
        var registry =
                Collections.singletonMap(
                        "registered-proc",
                        new CrashDump.RegistryEntryDump("registered-proc", true, Instant.now()));

        var dump =
                new CrashDump(
                        "node-1",
                        Instant.now(),
                        5000,
                        Collections.emptyMap(), // No processes
                        registry,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        CrashDump.SupervisorTreeDump.EMPTY,
                        CrashDump.SystemMetrics.EMPTY);

        var report = analyzer.verifyConsistency(dump);

        // Should detect registry inconsistency (process marked alive but not in dump)
        assertThat(report.issues()).isNotEmpty();
        assertThat(
                        report.issues().stream()
                                .anyMatch(i -> i.processName().equals("registered-proc")))
                .isTrue();
    }

    @Test
    void shouldProvideHighPriorityRecommendations() {
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

        var recommendations = analyzer.getRecommendations(dump);

        // Recommendations should be sorted by priority
        assertThat(recommendations).isNotEmpty();
        int firstPriority = recommendations.get(0).priority();
        for (var rec : recommendations) {
            assertThat(rec.priority()).isLessThanOrEqualTo(firstPriority);
        }

        // First recommendation should be high priority (OOM gets priority 9)
        assertThat(recommendations.get(0).priority()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void shouldEstimateRecoveryTime() {
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

        var strategy = analyzer.analyze(dump);

        assertThat(strategy.estimatedTime()).isNotNull();
        assertThat(strategy.estimatedTime().isNegative()).isFalse();
        assertThat(strategy.estimatedTime()).isLessThan(Duration.ofHours(1));
    }

    @Test
    void shouldHandleMessageLogCorruptionPattern() {
        var messages =
                List.of(
                        new CrashDump.MessageDump("Msg1", "msg1".getBytes(), Instant.now()),
                        new CrashDump.MessageDump("Msg2", "msg2".getBytes(), Instant.now()),
                        new CrashDump.MessageDump("Msg3", "msg3".getBytes(), Instant.now()));

        var processes =
                Collections.singletonMap(
                        "proc-1",
                        new CrashDump.ProcessDump(
                                "proc-1",
                                "State",
                                new byte[0],
                                100L,
                                3, // 3 pending messages
                                messages,
                                false,
                                false,
                                10, // Only 10 processed
                                10));

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

        var strategy = analyzer.analyze(dump);

        // High pending message ratio should trigger message replay detection
        assertThat(strategy.messagesToReplay()).hasSize(3);
    }

    @Test
    void shouldSortRecommendationsByPriority() {
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
                        CrashDump.SystemMetrics.EMPTY);

        var recommendations = analyzer.getRecommendations(dump);

        // Verify sorting
        for (int i = 1; i < recommendations.size(); i++) {
            assertThat(recommendations.get(i).priority())
                    .isLessThanOrEqualTo(recommendations.get(i - 1).priority());
        }
    }
}
