package io.github.seanchatmangpt.jotp.stress;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SimpleCapacityPlannerTest — tests and documents basic capacity planning for system resources.
 *
 * <p>Provides straightforward capacity planning calculations based on performance metrics. Helps
 * estimate resource requirements for target throughput and latency SLAs.
 *
 * <p><strong>DTR Documentation:</strong> This test class provides living documentation of capacity
 * planning methodology. Run with DTR to see executable examples with actual capacity estimates.
 */
@DtrTest
@DisplayName("Simple Capacity Planner — Basic Resource Planning")
class SimpleCapacityPlanner {

    @DtrContextField private DtrContext ctx;

    private CapacityPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new CapacityPlanner("JOTP-Test-System");
    }

    /**
     * Test capacity planning with sample data points.
     *
     * <p>Expected: Accurate estimation of max throughput at various latency SLAs
     */
    @Test
    @DisplayName("Capacity: Estimate Max Throughput at SLA Targets")
    void testEstimateMaxThroughput() {
        ctx.sayNextSection("Capacity Planning: Max Throughput Estimation");
        ctx.say("Determines maximum throughput achievable at different latency SLA targets.");
        ctx.say("Uses measured data points to extrapolate system capacity.");

        ctx.sayCode(
                """
                // Record capacity data points
                planner.recordDataPoint(10_000, 5.0, 25.0, 512);   // 10K ops, 5ms p99
                planner.recordDataPoint(25_000, 12.0, 45.0, 768);  // 25K ops, 12ms p99
                planner.recordDataPoint(50_000, 35.0, 72.0, 1024); // 50K ops, 35ms p99
                planner.recordDataPoint(100_000, 95.0, 95.0, 1536);// 100K ops, 95ms p99
                """,
                "java");

        // Record sample data points simulating real measurements
        planner.recordDataPoint(10_000, 5.0, 25.0, 512);
        planner.recordDataPoint(25_000, 12.0, 45.0, 768);
        planner.recordDataPoint(50_000, 35.0, 72.0, 1024);
        planner.recordDataPoint(100_000, 95.0, 95.0, 1536);

        double maxAt10ms = planner.estimateMaxThroughput(10.0);
        double maxAt50ms = planner.estimateMaxThroughput(50.0);
        double maxAt100ms = planner.estimateMaxThroughput(100.0);

        ctx.sayTable(
                new String[][] {
                    {"SLA Target (p99)", "Max Throughput", "Estimated CPU", "Status"},
                    {"< 10 ms", String.format("%.0f ops/sec", maxAt10ms), "~45%", "TIGHT"},
                    {"< 50 ms", String.format("%.0f ops/sec", maxAt50ms), "~72%", "COMFORTABLE"},
                    {"< 100 ms", String.format("%.0f ops/sec", maxAt100ms), "~95%", "LIMIT"},
                });

        ctx.sayKeyValue(
                Map.of(
                        "System name",
                        "JOTP-Test-System",
                        "Max throughput at p99<10ms",
                        String.format("%.0f ops/sec", maxAt10ms),
                        "Max throughput at p99<50ms",
                        String.format("%.0f ops/sec", maxAt50ms),
                        "Max throughput at p99<100ms",
                        String.format("%.0f ops/sec", maxAt100ms),
                        "Planning status",
                        "DOCUMENTED"));

        ctx.sayNote(
                "For strict SLA (p99<10ms), limit to "
                        + String.format("%.0f", maxAt10ms)
                        + " ops/sec. "
                        + "Relaxed SLA (p99<100ms) allows up to "
                        + String.format("%.0f", maxAt100ms)
                        + " ops/sec.");

        assertThat(maxAt10ms).isGreaterThan(0);
        assertThat(maxAt50ms).isGreaterThan(maxAt10ms);
        assertThat(maxAt100ms).isGreaterThan(maxAt50ms);
    }

    /**
     * Test resource estimation for target throughput.
     *
     * <p>Expected: Accurate resource requirement estimates for scaling decisions
     */
    @Test
    @DisplayName("Capacity: Resource Estimation for Target Throughput")
    void testEstimateResourcesForTargetThroughput() {
        ctx.sayNextSection("Capacity Planning: Resource Estimation");
        ctx.say("Estimates CPU, memory, and instance requirements for target throughput.");
        ctx.say("Enables horizontal and vertical scaling decisions.");

        ctx.sayCode(
                """
                // Estimate resources for 75K ops/sec target
                ResourceRequirement req = planner.estimateResourcesFor(75_000);
                // Returns: CPU%, Memory MB, Instance count
                """,
                "java");

        // Record baseline data points
        planner.recordDataPoint(25_000, 10.0, 30.0, 512);
        planner.recordDataPoint(50_000, 25.0, 55.0, 1024);
        planner.recordDataPoint(100_000, 80.0, 90.0, 2048);

        // Estimate for various targets
        ResourceRequirement req25k = planner.estimateResourcesFor(25_000);
        ResourceRequirement req50k = planner.estimateResourcesFor(50_000);
        ResourceRequirement req75k = planner.estimateResourcesFor(75_000);
        ResourceRequirement req100k = planner.estimateResourcesFor(100_000);

        ctx.sayTable(
                new String[][] {
                    {"Target Throughput", "CPU Required", "Memory Required", "Instances"},
                    {
                        "25K ops/sec",
                        String.format("%.0f%%", req25k.cpuPercent()),
                        String.format("%.0f MB", req25k.memoryMb()),
                        String.valueOf(req25k.instances())
                    },
                    {
                        "50K ops/sec",
                        String.format("%.0f%%", req50k.cpuPercent()),
                        String.format("%.0f MB", req50k.memoryMb()),
                        String.valueOf(req50k.instances())
                    },
                    {
                        "75K ops/sec",
                        String.format("%.0f%%", req75k.cpuPercent()),
                        String.format("%.0f MB", req75k.memoryMb()),
                        String.valueOf(req75k.instances())
                    },
                    {
                        "100K ops/sec",
                        String.format("%.0f%%", req100k.cpuPercent()),
                        String.format("%.0f MB", req100k.memoryMb()),
                        String.valueOf(req100k.instances())
                    },
                });

        ctx.sayKeyValue(
                Map.of(
                        "Target throughput",
                        "75,000 ops/sec",
                        "Estimated CPU",
                        String.format("%.0f%%", req75k.cpuPercent()),
                        "Estimated memory",
                        String.format("%.0f MB", req75k.memoryMb()),
                        "Recommended instances",
                        String.valueOf(req75k.instances()),
                        "Scaling model",
                        "Linear"));

        ctx.sayNote(
                "For 75K ops/sec, provision "
                        + String.format("%.0f", req75k.cpuPercent())
                        + "% CPU and "
                        + String.format("%.0f", req75k.memoryMb())
                        + "MB memory per instance.");

        assertThat(req75k.cpuPercent()).isGreaterThan(0);
        assertThat(req75k.memoryMb()).isGreaterThan(0);
    }

    /**
     * Test capacity report generation.
     *
     * <p>Expected: Comprehensive report with all capacity metrics
     */
    @Test
    @DisplayName("Capacity: Report Generation")
    void testGenerateReport() {
        ctx.sayNextSection("Capacity Planning: Report Generation");
        ctx.say("Generates comprehensive capacity planning report.");
        ctx.say("Documents all measured data points and extrapolated limits.");

        // Record comprehensive data points
        planner.recordDataPoint(5_000, 3.0, 15.0, 256);
        planner.recordDataPoint(10_000, 5.0, 25.0, 384);
        planner.recordDataPoint(20_000, 8.0, 38.0, 512);
        planner.recordDataPoint(40_000, 18.0, 55.0, 768);
        planner.recordDataPoint(80_000, 45.0, 78.0, 1280);
        planner.recordDataPoint(100_000, 85.0, 92.0, 1600);

        String report = planner.generateReport();

        ctx.sayCode(report, "text");

        ctx.sayKeyValue(
                Map.of(
                        "Data points recorded",
                        "6",
                        "Max measured throughput",
                        "100,000 ops/sec",
                        "Max measured latency p99",
                        "85.00 ms",
                        "Report status",
                        "GENERATED"));

        ctx.sayNote(
                "Capacity report provides actionable data for infrastructure planning and SLA negotiation.");

        assertThat(report).contains("Capacity Planning");
        assertThat(report).contains("100,000");
    }

    // ── Inner Classes (Capacity Planning Model) ─────────────────────────────────────

    /** Data point for capacity planning. */
    private record CapacityDataPoint(
            double throughput, double latencyP99Ms, double cpuPercent, double memoryMb) {}

    /** Resource requirement estimation. */
    public record ResourceRequirement(double cpuPercent, double memoryMb, int instances) {}

    /** Capacity planner implementation. */
    private static class CapacityPlanner {
        private final List<CapacityDataPoint> dataPoints = new ArrayList<>();
        private final String systemName;

        public CapacityPlanner(String systemName) {
            this.systemName = systemName;
        }

        public void recordDataPoint(
                double throughput, double latencyP99Ms, double cpuPercent, double memoryMb) {
            dataPoints.add(new CapacityDataPoint(throughput, latencyP99Ms, cpuPercent, memoryMb));
        }

        public double estimateMaxThroughput(double maxLatencyP99Ms) {
            double maxThroughput = 0;
            for (CapacityDataPoint dp : dataPoints) {
                if (dp.latencyP99Ms() <= maxLatencyP99Ms && dp.throughput() > maxThroughput) {
                    maxThroughput = dp.throughput();
                }
            }
            return maxThroughput;
        }

        public ResourceRequirement estimateResourcesFor(double targetThroughput) {
            CapacityDataPoint closest = null;
            double minDiff = Double.MAX_VALUE;

            for (CapacityDataPoint dp : dataPoints) {
                double diff = Math.abs(dp.throughput() - targetThroughput);
                if (diff < minDiff) {
                    minDiff = diff;
                    closest = dp;
                }
            }

            if (closest == null) {
                return new ResourceRequirement(0, 0, 0);
            }

            double scalingFactor = targetThroughput / closest.throughput();
            double estimatedCpu = closest.cpuPercent() * scalingFactor;
            double estimatedMemory = closest.memoryMb() * scalingFactor;
            int estimatedInstances = (int) Math.ceil(scalingFactor);

            return new ResourceRequirement(estimatedCpu, estimatedMemory, estimatedInstances);
        }

        public String generateReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Capacity Planning for ").append(systemName).append(" ===\n");

            if (dataPoints.isEmpty()) {
                sb.append("No data points available.\n");
                return sb.toString();
            }

            double maxThroughput =
                    dataPoints.stream().mapToDouble(CapacityDataPoint::throughput).max().orElse(0);
            double maxLatency =
                    dataPoints.stream()
                            .mapToDouble(CapacityDataPoint::latencyP99Ms)
                            .max()
                            .orElse(0);

            sb.append(String.format("Maximum measured throughput: %.0f ops/sec\n", maxThroughput));
            sb.append(String.format("Maximum measured latency p99: %.2f ms\n", maxLatency));

            double maxThroughputAt10ms = estimateMaxThroughput(10.0);
            double maxThroughputAt50ms = estimateMaxThroughput(50.0);
            double maxThroughputAt100ms = estimateMaxThroughput(100.0);

            sb.append(
                    String.format(
                            "Estimated max throughput at p99<10ms: %.0f ops/sec\n",
                            maxThroughputAt10ms));
            sb.append(
                    String.format(
                            "Estimated max throughput at p99<50ms: %.0f ops/sec\n",
                            maxThroughputAt50ms));
            sb.append(
                    String.format(
                            "Estimated max throughput at p99<100ms: %.0f ops/sec\n",
                            maxThroughputAt100ms));

            return sb.toString();
        }
    }
}
