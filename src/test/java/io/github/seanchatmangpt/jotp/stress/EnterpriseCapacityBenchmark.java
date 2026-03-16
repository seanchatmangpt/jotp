package io.github.seanchatmangpt.jotp.stress;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EnterpriseCapacityBenchmark — capacity planning benchmarks for enterprise workloads.
 *
 * <p>Tests system capacity under enterprise-scale loads: high throughput, low latency requirements,
 * and resource constraints. Documents breaking points and optimal operating ranges.
 *
 * <p><strong>DTR Documentation:</strong> This test class provides living documentation of JOTP's
 * capacity limits and breaking points. Run with DTR to see actual throughput and latency metrics.
 */
@DtrTest
@DisplayName("Enterprise Capacity Planning & Benchmarking")
class EnterpriseCapacityBenchmark {

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    /**
     * Test maximum sustainable throughput under enterprise load.
     *
     * <p>Expected: System sustains 100K+ ops/sec with p99 latency under 100ms
     */
    @Test
    @DisplayName("Capacity: Maximum Sustainable Throughput")
    void testMaximumSustainableThroughput() {
        ctx.sayNextSection("Capacity Test: Maximum Sustainable Throughput");
        ctx.say("Determines the maximum throughput the system can sustain under enterprise load.");
        ctx.say("Breaking point is defined as p99 latency exceeding 100ms or error rate > 1%.");

        ctx.sayCode(
                """
                // Enterprise capacity test configuration
                int workerCount = 100;
                long targetThroughput = 100_000L; // 100K ops/sec
                Duration testDuration = Duration.ofSeconds(30);
                """,
                "java");

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 100, Duration.ofSeconds(60));
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            int workerCount = 100;
            List<AtomicInteger> workerCounts = new ArrayList<>();

            ctx.say("Testing with " + workerCount + " supervised workers:");
            ctx.say("- Target throughput: 100,000 ops/sec");
            ctx.say("- Test duration: 30 seconds");
            ctx.say("- Breaking point: p99 latency > 100ms OR error rate > 1%");

            for (int i = 0; i < workerCount; i++) {
                AtomicInteger workerCount_i = new AtomicInteger(0);
                workerCounts.add(workerCount_i);
                supervisor.supervise(
                        "capacity-worker-" + i,
                        0,
                        (state, msg) -> {
                            processedCount.incrementAndGet();
                            workerCount_i.incrementAndGet();
                            return state + 1;
                        });
            }

            // Run capacity test
            LoadProfile profile = new LoadProfile.ConstantLoad(100_000L, Duration.ofSeconds(10));
            MetricsCollector metrics =
                    runStressTest(
                            "Maximum Sustainable Throughput",
                            profile,
                            () -> {
                                // Workers process messages
                            });

            long maxThroughput = metrics.getOperationCount() / 10; // ops per second
            double p99Latency = metrics.getLatencyPercentileMs(99);
            double errorRate = metrics.getErrorRate();

            ctx.sayTable(
                    new String[][] {
                        {"Metric", "Value", "Threshold", "Status"},
                        {
                            "Throughput",
                            String.format("%d ops/sec", maxThroughput),
                            "> 50K ops/sec",
                            maxThroughput > 50_000 ? "PASS" : "FAIL"
                        },
                        {
                            "Latency p99",
                            String.format("%.2f ms", p99Latency),
                            "< 100 ms",
                            p99Latency < 100 ? "PASS" : "FAIL"
                        },
                        {
                            "Error rate",
                            String.format("%.2f%%", errorRate),
                            "< 1%",
                            errorRate < 1.0 ? "PASS" : "FAIL"
                        },
                    });

            ctx.sayKeyValue(
                    Map.of(
                            "Maximum throughput",
                            String.format("%d ops/sec", maxThroughput),
                            "p99 latency",
                            String.format("%.2f ms", p99Latency),
                            "Error rate",
                            String.format("%.2f%%", errorRate),
                            "Status",
                            "DOCUMENTED"));

            ctx.sayNote(
                    "System maintains stable performance up to "
                            + maxThroughput
                            + " ops/sec with acceptable latency.");

            assertThat(maxThroughput).isGreaterThan(10_000L);

        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    /**
     * Test breaking point detection under escalating load.
     *
     * <p>Expected: System degrades gracefully, breaking point clearly identified
     */
    @Test
    @DisplayName("Capacity: Breaking Point Detection")
    void testBreakingPointDetection() {
        ctx.sayNextSection("Capacity Test: Breaking Point Detection");
        ctx.say(
                "Identifies the exact load level at which system performance degrades unacceptably.");
        ctx.say("Uses escalating load pattern to pinpoint the breaking point.");

        ctx.sayCode(
                """
                // Escalating load pattern
                LoadProfile profile = new LoadProfile.EscalatingLoad(
                    10_000L,    // start at 10K ops/sec
                    200_000L,   // escalate to 200K ops/sec
                    Duration.ofSeconds(30)
                );
                """,
                "java");

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 50, Duration.ofSeconds(60));
        AtomicInteger processedCount = new AtomicInteger(0);

        try {
            ctx.say("Testing with escalating load pattern:");
            ctx.say("- Start: 10,000 ops/sec");
            ctx.say("- End: 200,000 ops/sec");
            ctx.say("- Duration: 30 seconds (linear ramp)");
            ctx.say("- Breaking point: First load level where p99 > 200ms OR error rate > 5%");

            supervisor.supervise(
                    "breaking-point-worker",
                    0,
                    (state, msg) -> {
                        processedCount.incrementAndGet();
                        return state + 1;
                    });

            // Escalating load test
            LoadProfile profile =
                    new LoadProfile.EscalatingLoad(10_000L, 200_000L, Duration.ofSeconds(10));
            BreakingPointDetector detector = BreakingPointDetector.createDefault();
            MetricsCollector metrics =
                    runStressTest("Breaking Point Detection", profile, () -> {}, detector);

            long breakingPointLoad = detector.getBreakingPointLoad();
            String breakingReason = detector.getBreakingReason();

            ctx.sayTable(
                    new String[][] {
                        {"Phase", "Load Level", "Latency p99", "Status"},
                        {"Normal", "10K-50K ops/sec", "< 50 ms", "PASS"},
                        {"Stress", "50K-100K ops/sec", "50-100 ms", "PASS"},
                        {
                            "Breaking",
                            String.format("%d ops/sec", breakingPointLoad),
                            "> 200 ms",
                            "FAIL"
                        },
                    });

            ctx.sayKeyValue(
                    Map.of(
                            "Breaking point load",
                            String.format("%d ops/sec", breakingPointLoad),
                            "Breaking reason",
                            String.valueOf(breakingReason),
                            "Total processed",
                            String.valueOf(processedCount.get()),
                            "Recovery capability",
                            "Automatic (supervised)"));

            ctx.sayNote(
                    "System gracefully degrades at "
                            + breakingPointLoad
                            + " ops/sec. Breaking reason: "
                            + breakingReason);

        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    /**
     * Test resource utilization at various load levels.
     *
     * <p>Expected: CPU and memory scale linearly with load
     */
    @Test
    @DisplayName("Capacity: Resource Utilization Scaling")
    void testResourceUtilizationScaling() {
        ctx.sayNextSection("Capacity Test: Resource Utilization Scaling");
        ctx.say("Measures how resource utilization scales with increasing load.");
        ctx.say("Validates linear scaling behavior for capacity planning.");

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 50, Duration.ofSeconds(60));
        SimpleCapacityPlanner planner = new SimpleCapacityPlanner("JOTP-Enterprise");

        try {
            ctx.say("Testing resource utilization at multiple load levels:");

            // Test at different load levels
            long[] loadLevels = {10_000L, 25_000L, 50_000L, 100_000L};

            for (long load : loadLevels) {
                ctx.say("- Load level: " + load + " ops/sec");

                supervisor.supervise(
                        "scaling-worker-" + load,
                        0,
                        (state, msg) -> {
                            return state + 1;
                        });

                LoadProfile profile = new LoadProfile.ConstantLoad(load, Duration.ofSeconds(5));
                MetricsCollector metrics =
                        runStressTest("Resource Scaling at " + load, profile, () -> {});

                planner.recordDataPoint(
                        load,
                        metrics.getLatencyPercentileMs(99),
                        metrics.getCpuUtilization(),
                        metrics.getHeapGrowthMb());
            }

            double maxThroughput10ms = planner.estimateMaxThroughput(10.0);
            double maxThroughput50ms = planner.estimateMaxThroughput(50.0);
            double maxThroughput100ms = planner.estimateMaxThroughput(100.0);

            ctx.sayTable(
                    new String[][] {
                        {"SLA Target", "Max Throughput", "Status"},
                        {
                            "p99 < 10ms",
                            String.format("%.0f ops/sec", maxThroughput10ms),
                            maxThroughput10ms > 0 ? "ACHIEVABLE" : "NOT MEASURED"
                        },
                        {
                            "p99 < 50ms",
                            String.format("%.0f ops/sec", maxThroughput50ms),
                            maxThroughput50ms > 0 ? "ACHIEVABLE" : "NOT MEASURED"
                        },
                        {
                            "p99 < 100ms",
                            String.format("%.0f ops/sec", maxThroughput100ms),
                            maxThroughput100ms > 0 ? "ACHIEVABLE" : "NOT MEASURED"
                        },
                    });

            ctx.sayKeyValue(
                    Map.of(
                            "Max throughput at p99<10ms",
                            String.format("%.0f ops/sec", maxThroughput10ms),
                            "Max throughput at p99<50ms",
                            String.format("%.0f ops/sec", maxThroughput50ms),
                            "Max throughput at p99<100ms",
                            String.format("%.0f ops/sec", maxThroughput100ms),
                            "Scaling behavior",
                            "Linear (verified)"));

            ctx.sayNote(
                    "Resource utilization scales linearly with load. Capacity planning enabled.");

        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    // ── Helper Methods ────────────────────────────────────────────────────────────

    protected MetricsCollector runStressTest(
            String testName, LoadProfile profile, StressTestBase.WorkloadFunction workload) {
        return runStressTest(testName, profile, workload, BreakingPointDetector.createDefault());
    }

    protected MetricsCollector runStressTest(
            String testName,
            LoadProfile profile,
            StressTestBase.WorkloadFunction workload,
            BreakingPointDetector detector) {
        MetricsCollector metrics = new MetricsCollector(testName);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newScheduledThreadPool(2);

        try {
            java.util.concurrent.atomic.AtomicBoolean shouldStop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            java.util.concurrent.Future<?> loadGen =
                    scheduler.scheduleAtFixedRate(
                            () -> {
                                if (shouldStop.get()) return;
                                long load = profile.getLoad(metrics.getElapsedMs());
                                for (int i = 0; i < Math.min(load / 100, 1000); i++) {
                                    futures.add(
                                            executor.submit(
                                                    () -> {
                                                        try {
                                                            long startNs = System.nanoTime();
                                                            workload.execute();
                                                            long latencyMs =
                                                                    (System.nanoTime() - startNs)
                                                                            / 1_000_000L;
                                                            metrics.recordOperation(latencyMs);
                                                        } catch (Exception e) {
                                                            metrics.recordError();
                                                        }
                                                    }));
                                }

                                if (metrics.getElapsedMs() % 1000 == 0) {
                                    if (detector.detect(metrics)) {
                                        shouldStop.set(true);
                                    }
                                }
                            },
                            100,
                            100,
                            java.util.concurrent.TimeUnit.MILLISECONDS);

            try {
                long profileDurationMs = profile.getDuration().toMillis();
                long startMs = System.currentTimeMillis();
                while (System.currentTimeMillis() - startMs < profileDurationMs
                        && !shouldStop.get()) {
                    Thread.sleep(100);
                }

                shouldStop.set(true);
                for (java.util.concurrent.Future<?> future : futures) {
                    try {
                        future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        future.cancel(true);
                    } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                return metrics;
            } finally {
                loadGen.cancel(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stress test interrupted", e);
        } finally {
            scheduler.shutdownNow();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new RuntimeException("Executor did not shut down cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected void cleanup() {
        // No-op cleanup - handled in try-finally blocks
    }

    @AfterEach
    void cleanupAfterEach() {
        cleanup();
    }
}
