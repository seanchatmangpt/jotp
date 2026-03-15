package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import io.github.seanchatmangpt.jotp.observability.FrameworkMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Simple capacity planning tests for observability infrastructure.
 *
 * <p>Validates performance SLAs across instance profiles:
 *
 * <ul>
 *   <li>Small: 1K msg/sec, <1% CPU, P99 <1ms
 *   <li>Medium: 10K msg/sec, <3% CPU, P99 <2ms
 *   <li>Large: 100K msg/sec, <5% CPU, P99 <5ms
 *   <li>Enterprise: 1M msg/sec, <10% CPU, P99 <10ms
 * </ul>
 *
 * <p>Generates JSON reports for production sizing decisions.
 */
@Tag("stress")
@Tag("observability")
@DisplayName("Observability Capacity Planning Tests")
class SimpleCapacityPlanner {

    /** Target CPU overhead percentages by instance type */
    private static final double SMALL_CPU_TARGET = 1.0;

    private static final double MEDIUM_CPU_TARGET = 3.0;
    private static final double LARGE_CPU_TARGET = 5.0;
    private static final double ENTERPRISE_CPU_TARGET = 10.0;

    /** Target P99 latency in milliseconds by instance type */
    private static final double SMALL_P99_TARGET_MS = 1.0;

    private static final double MEDIUM_P99_TARGET_MS = 2.0;
    private static final double LARGE_P99_TARGET_MS = 5.0;
    private static final double ENTERPRISE_P99_TARGET_MS = 10.0;

    /** Memory overhead target: <10MB per 1000 events */
    private static final long MEMORY_PER_1K_EVENTS = 10 * 1024 * 1024; // 10MB

    /**
     * Test: Small Instance Profile
     *
     * <p>Capacity: 1K messages/sec, 10 processes <br>
     * SLA: <1% CPU overhead, P99 latency <1ms
     */
    @Test
    @DisplayName("Small instance: 1K msg/sec, <1% CPU, P99 <1ms")
    void smallInstance() throws Exception {
        int targetThroughput = 1_000; // messages per second
        int processCount = 10;
        double cpuTarget = SMALL_CPU_TARGET;
        double p99Target = SMALL_P99_TARGET_MS;

        CapacityReport report =
                runCapacityTest("small", targetThroughput, processCount, cpuTarget, p99Target);

        System.out.println("\n=== SMALL INSTANCE CAPACITY REPORT ===");
        System.out.println(report.toJson());
        System.out.println("\n");

        assertTrue(report.slaCompliant(), "Small instance SLA not met: " + report.violations());
    }

    /**
     * Test: Medium Instance Profile
     *
     * <p>Capacity: 10K messages/sec, 100 processes <br>
     * SLA: <3% CPU overhead, P99 latency <2ms
     */
    @Test
    @DisplayName("Medium instance: 10K msg/sec, <3% CPU, P99 <2ms")
    void mediumInstance() throws Exception {
        int targetThroughput = 10_000;
        int processCount = 100;
        double cpuTarget = MEDIUM_CPU_TARGET;
        double p99Target = MEDIUM_P99_TARGET_MS;

        CapacityReport report =
                runCapacityTest("medium", targetThroughput, processCount, cpuTarget, p99Target);

        System.out.println("\n=== MEDIUM INSTANCE CAPACITY REPORT ===");
        System.out.println(report.toJson());
        System.out.println("\n");

        assertTrue(report.slaCompliant(), "Medium instance SLA not met: " + report.violations());
    }

    /**
     * Test: Large Instance Profile
     *
     * <p>Capacity: 100K messages/sec, 1K processes <br>
     * SLA: <5% CPU overhead, P99 latency <5ms
     */
    @Test
    @DisplayName("Large instance: 100K msg/sec, <5% CPU, P99 <5ms")
    void largeInstance() throws Exception {
        int targetThroughput = 100_000;
        int processCount = 1_000;
        double cpuTarget = LARGE_CPU_TARGET;
        double p99Target = LARGE_P99_TARGET_MS;

        CapacityReport report =
                runCapacityTest("large", targetThroughput, processCount, cpuTarget, p99Target);

        System.out.println("\n=== LARGE INSTANCE CAPACITY REPORT ===");
        System.out.println(report.toJson());
        System.out.println("\n");

        assertTrue(report.slaCompliant(), "Large instance SLA not met: " + report.violations());
    }

    /**
     * Test: Enterprise Instance Profile
     *
     * <p>Capacity: 1M messages/sec, 10K processes <br>
     * SLA: <10% CPU overhead, P99 latency <10ms
     */
    @Test
    @DisplayName("Enterprise instance: 1M msg/sec, <10% CPU, P99 <10ms")
    void enterpriseInstance() throws Exception {
        int targetThroughput = 1_000_000;
        int processCount = 10_000;
        double cpuTarget = ENTERPRISE_CPU_TARGET;
        double p99Target = ENTERPRISE_P99_TARGET_MS;

        CapacityReport report =
                runCapacityTest("enterprise", targetThroughput, processCount, cpuTarget, p99Target);

        System.out.println("\n=== ENTERPRISE INSTANCE CAPACITY REPORT ===");
        System.out.println(report.toJson());
        System.out.println("\n");

        assertTrue(
                report.slaCompliant(), "Enterprise instance SLA not met: " + report.violations());
    }

    /** Run a capacity planning test for the given instance profile. */
    private CapacityReport runCapacityTest(
            String instanceType,
            int targetThroughput,
            int processCount,
            double cpuTargetPercent,
            double p99TargetMs)
            throws Exception {

        FrameworkEventBus eventBus = FrameworkEventBus.create();
        FrameworkMetrics metrics = FrameworkMetrics.create();

        List<LatencySample> samples = new ArrayList<>(targetThroughput);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(processCount);
        AtomicInteger publishedCount = new AtomicInteger(0);
        Semaphore concurrencyLimit = new Semaphore(Math.min(processCount, 1000));

        // Baseline CPU time
        var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
        long baselineCpuTime = threadBean.getCurrentThreadCpuTime();

        // Spawn producer processes
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < processCount; i++) {
                final int processId = i;
                executor.submit(
                        () -> {
                            try {
                                startLatch.await();
                                while (publishedCount.get() < targetThroughput) {
                                    concurrencyLimit.acquire();
                                    try {
                                        if (publishedCount.get() >= targetThroughput) break;

                                        int currentCount = publishedCount.incrementAndGet();
                                        if (currentCount > targetThroughput) break;

                                        Instant start = Instant.now();

                                        // Publish event with full metrics collection
                                        eventBus.publish(
                                                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                                                        Instant.now(),
                                                        "proc_" + processId + "_" + currentCount,
                                                        "capacity_test"));

                                        Instant end = Instant.now();
                                        long latencyNs = Duration.between(start, end).toNanos();
                                        synchronized (samples) {
                                            samples.add(new LatencySample(currentCount, latencyNs));
                                        }
                                    } finally {
                                        concurrencyLimit.release();
                                    }
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                completeLatch.countDown();
                            }
                        });
            }

            // Start all producers simultaneously
            Instant testStart = Instant.now();
            startLatch.countDown();
            completeLatch.await();
            Instant testEnd = Instant.now();

            long testDurationMs = Duration.between(testStart, testEnd).toMillis();
            long actualCpuTime = threadBean.getCurrentThreadCpuTime() - baselineCpuTime;

            // Calculate percentiles
            List<Long> latencies = samples.stream().map(LatencySample::latencyNs).sorted().toList();

            long p50 = percentile(latencies, 50);
            long p95 = percentile(latencies, 95);
            long p99 = percentile(latencies, 99);

            double actualThroughput = (targetThroughput * 1000.0) / testDurationMs;
            double cpuOverhead = (actualCpuTime / 1_000_000.0) / testDurationMs * 100.0;

            // Memory metrics
            long memoryBefore =
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            System.gc();
            Thread.sleep(50);
            long memoryAfter =
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryUsed = memoryAfter - memoryBefore;

            return new CapacityReport(
                    instanceType,
                    targetThroughput,
                    actualThroughput,
                    processCount,
                    p50 / 1_000_000.0, // convert to ms
                    p95 / 1_000_000.0,
                    p99 / 1_000_000.0,
                    cpuOverhead,
                    memoryUsed,
                    (memoryUsed / (targetThroughput / 1000)),
                    cpuTargetPercent,
                    p99TargetMs);
        }
    }

    /** Calculate percentile from sorted list. */
    private long percentile(List<Long> sortedData, int percentile) {
        if (sortedData.isEmpty()) return 0;
        int index = (int) Math.ceil((percentile / 100.0) * sortedData.size()) - 1;
        return sortedData.get(Math.max(0, Math.min(index, sortedData.size() - 1)));
    }

    /** Latency sample record. */
    private record LatencySample(int sequence, long latencyNs) {}

    /** Capacity planning report. */
    static class CapacityReport {
        private final String instanceType;
        private final int targetThroughput;
        private final double actualThroughput;
        private final int processCount;
        private final double p50LatencyMs;
        private final double p95LatencyMs;
        private final double p99LatencyMs;
        private final double cpuOverheadPercent;
        private final long totalMemoryBytes;
        private final long memoryPer1KEvents;
        private final double cpuTargetPercent;
        private final double p99TargetMs;

        CapacityReport(
                String instanceType,
                int targetThroughput,
                double actualThroughput,
                int processCount,
                double p50LatencyMs,
                double p95LatencyMs,
                double p99LatencyMs,
                double cpuOverheadPercent,
                long totalMemoryBytes,
                long memoryPer1KEvents,
                double cpuTargetPercent,
                double p99TargetMs) {
            this.instanceType = instanceType;
            this.targetThroughput = targetThroughput;
            this.actualThroughput = actualThroughput;
            this.processCount = processCount;
            this.p50LatencyMs = p50LatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.cpuOverheadPercent = cpuOverheadPercent;
            this.totalMemoryBytes = totalMemoryBytes;
            this.memoryPer1KEvents = memoryPer1KEvents;
            this.cpuTargetPercent = cpuTargetPercent;
            this.p99TargetMs = p99TargetMs;
        }

        boolean slaCompliant() {
            return p99LatencyMs <= p99TargetMs
                    && cpuOverheadPercent <= cpuTargetPercent
                    && memoryPer1KEvents < MEMORY_PER_1K_EVENTS;
        }

        List<String> violations() {
            List<String> violations = new ArrayList<>();
            if (p99LatencyMs > p99TargetMs) {
                violations.add(
                        String.format(
                                "P99 latency %.2fms exceeds target %.2fms",
                                p99LatencyMs, p99TargetMs));
            }
            if (cpuOverheadPercent > cpuTargetPercent) {
                violations.add(
                        String.format(
                                "CPU overhead %.2f%% exceeds target %.2f%%",
                                cpuOverheadPercent, cpuTargetPercent));
            }
            if (memoryPer1KEvents >= MEMORY_PER_1K_EVENTS) {
                violations.add(
                        String.format(
                                "Memory overhead %,d bytes/1K events exceeds target %,d bytes",
                                memoryPer1KEvents, MEMORY_PER_1K_EVENTS));
            }
            return violations;
        }

        String toJson() {
            return String.format(
                    """
                {
                  "instance_type": "%s",
                  "target_throughput": %d,
                  "actual_throughput": "%.2f",
                  "process_count": %d,
                  "latency": {
                    "p50_ms": "%.3f",
                    "p95_ms": "%.3f",
                    "p99_ms": "%.3f"
                  },
                  "cpu_overhead_percent": "%.2f",
                  "cpu_target_percent": "%.2f",
                  "memory": {
                    "total_bytes": %d,
                    "per_1k_events_bytes": %d,
                    "target_bytes": %d
                  },
                  "sla_compliance": "%s",
                  "violations": [%s]
                }
                """
                            .trim(),
                    instanceType,
                    targetThroughput,
                    actualThroughput,
                    processCount,
                    p50LatencyMs,
                    p95LatencyMs,
                    p99LatencyMs,
                    cpuOverheadPercent,
                    cpuTargetPercent,
                    totalMemoryBytes,
                    memoryPer1KEvents,
                    MEMORY_PER_1K_EVENTS,
                    slaCompliant() ? "PASS" : "FAIL",
                    violations().isEmpty()
                            ? ""
                            : violations().stream()
                                    .map(v -> "\"" + v.replace("\"", "'") + "\"")
                                    .collect(Collectors.joining(", ")));
        }
    }
}
