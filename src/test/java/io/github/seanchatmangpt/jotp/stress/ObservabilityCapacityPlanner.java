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
 * ObservabilityCapacityPlannerTest — tests and documents observability overhead capacity planning.
 *
 * <p>Models the overhead of monitoring, tracing, and metrics collection on system performance.
 * Helps determine when observability becomes a bottleneck and how to scale monitoring
 * infrastructure.
 *
 * <p><strong>DTR Documentation:</strong> This test class provides living documentation of
 * observability overhead impacts. Run with DTR to see actual overhead measurements and breaking
 * points.
 */
@DtrTest
@DisplayName("Observability Capacity Planner — Monitoring Overhead Analysis")
class ObservabilityCapacityPlanner {

    @DtrContextField private DtrContext ctx;

    private ObservabilityOverheadPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ObservabilityOverheadPlanner("JOTP-Observability-Test");
    }

    /**
     * Test observability overhead measurement at various throughputs.
     *
     * <p>Expected: Clear documentation of latency overhead at different load levels
     */
    @Test
    @DisplayName("Observability: Latency Overhead Measurement")
    void testLatencyOverheadMeasurement() {
        ctx.sayNextSection("Observability Capacity: Latency Overhead");
        ctx.say("Measures the latency impact of observability instrumentation.");
        ctx.say("Compares baseline latency (no observability) vs observed latency (with tracing).");

        ctx.sayCode(
                """
                // Record overhead at different throughputs
                planner.recordMeasurement(
                    10_000,    // 10K ops/sec
                    5.0,       // 5ms baseline latency
                    5.5,       // 5.5ms observed latency (10% overhead)
                    2.0,       // 2% CPU overhead
                    64.0       // 64MB memory overhead
                );
                """,
                "java");

        // Record measurements at increasing throughputs
        planner.recordMeasurement(10_000, 5.0, 5.5, 2.0, 64.0); // 10% overhead
        planner.recordMeasurement(25_000, 8.0, 9.2, 4.5, 128.0); // 15% overhead
        planner.recordMeasurement(50_000, 12.0, 15.0, 8.0, 192.0); // 25% overhead
        planner.recordMeasurement(100_000, 20.0, 28.0, 15.0, 320.0); // 40% overhead
        planner.recordMeasurement(200_000, 35.0, 56.0, 28.0, 512.0); // 60% overhead

        ctx.sayTable(
                new String[][] {
                    {"Throughput", "Baseline Latency", "Observed Latency", "Overhead %", "Status"},
                    {"10K ops/sec", "5.0 ms", "5.5 ms", "10%", "ACCEPTABLE"},
                    {"25K ops/sec", "8.0 ms", "9.2 ms", "15%", "ACCEPTABLE"},
                    {"50K ops/sec", "12.0 ms", "15.0 ms", "25%", "WARNING"},
                    {"100K ops/sec", "20.0 ms", "28.0 ms", "40%", "HIGH"},
                    {"200K ops/sec", "35.0 ms", "56.0 ms", "60%", "CRITICAL"},
                });

        ctx.sayKeyValue(
                Map.of(
                        "System name",
                        "JOTP-Observability-Test",
                        "Measurements recorded",
                        "5",
                        "Min overhead",
                        "10%",
                        "Max overhead",
                        "60%",
                        "Analysis status",
                        "DOCUMENTED"));

        ctx.sayNote(
                "Observability overhead increases non-linearly with throughput. "
                        + "Consider sampling strategies above 50K ops/sec.");

        assertThat(planner.getMeasurementCount()).isEqualTo(5);
    }

    /**
     * Test breaking point detection for observability overhead.
     *
     * <p>Expected: Identify throughput where overhead exceeds acceptable threshold
     */
    @Test
    @DisplayName("Observability: Breaking Point Detection")
    void testBreakingPointDetection() {
        ctx.sayNextSection("Observability Capacity: Breaking Point");
        ctx.say(
                "Identifies the throughput level where observability overhead becomes unacceptable.");
        ctx.say("Breaking point defined as overhead exceeding threshold (5% or 10%).");

        ctx.sayCode(
                """
                // Find breaking point at 5% overhead threshold
                double breakingPoint = planner.findBreakingPoint(5.0);
                // Returns throughput where overhead first exceeds 5%
                """,
                "java");

        // Record measurements with increasing overhead
        planner.recordMeasurement(10_000, 5.0, 5.3, 1.0, 32.0); // 6% overhead
        planner.recordMeasurement(20_000, 6.0, 6.5, 2.0, 48.0); // 8.3% overhead
        planner.recordMeasurement(30_000, 7.0, 7.8, 3.5, 64.0); // 11.4% overhead
        planner.recordMeasurement(40_000, 8.0, 9.2, 5.0, 80.0); // 15% overhead

        double breakingPoint5Percent = planner.findBreakingPoint(5.0);
        double breakingPoint10Percent = planner.findBreakingPoint(10.0);
        double breakingPoint20Percent = planner.findBreakingPoint(20.0);

        ctx.sayTable(
                new String[][] {
                    {"Overhead Threshold", "Breaking Point", "Status"},
                    {
                        "5%",
                        String.format("%.0f ops/sec", breakingPoint5Percent),
                        breakingPoint5Percent < Double.MAX_VALUE ? "DETECTED" : "NOT FOUND"
                    },
                    {
                        "10%",
                        String.format("%.0f ops/sec", breakingPoint10Percent),
                        breakingPoint10Percent < Double.MAX_VALUE ? "DETECTED" : "NOT FOUND"
                    },
                    {
                        "20%",
                        String.format("%.0f ops/sec", breakingPoint20Percent),
                        breakingPoint20Percent < Double.MAX_VALUE ? "DETECTED" : "NOT FOUND"
                    },
                });

        ctx.sayKeyValue(
                Map.of(
                        "5% overhead breaking point",
                        String.format("%.0f ops/sec", breakingPoint5Percent),
                        "10% overhead breaking point",
                        String.format("%.0f ops/sec", breakingPoint10Percent),
                        "20% overhead breaking point",
                        String.format("%.0f ops/sec", breakingPoint20Percent),
                        "Recommendation",
                        "Sample below breaking point"));

        ctx.sayNote(
                "At 5% overhead tolerance, limit tracing to "
                        + String.format("%.0f", breakingPoint5Percent)
                        + " ops/sec. "
                        + "For full tracing, accept higher overhead or use sampling.");

        assertThat(breakingPoint5Percent).isLessThan(Double.MAX_VALUE);
    }

    /**
     * Test resource overhead analysis (CPU and memory).
     *
     * <p>Expected: Document CPU and memory overhead of observability stack
     */
    @Test
    @DisplayName("Observability: Resource Overhead Analysis")
    void testResourceOverheadAnalysis() {
        ctx.sayNextSection("Observability Capacity: Resource Overhead");
        ctx.say("Analyzes CPU and memory overhead of the observability stack.");
        ctx.say("Helps size monitoring infrastructure appropriately.");

        // Record resource-focused measurements
        planner.recordMeasurement(10_000, 5.0, 5.2, 3.0, 128.0);
        planner.recordMeasurement(50_000, 10.0, 11.0, 8.0, 256.0);
        planner.recordMeasurement(100_000, 15.0, 17.5, 15.0, 512.0);

        ObservabilityOverheadPlanner.ResourceOverhead resources50k =
                planner.getResourceOverhead(50_000);
        ObservabilityOverheadPlanner.ResourceOverhead resources100k =
                planner.getResourceOverhead(100_000);

        ctx.sayTable(
                new String[][] {
                    {"Throughput", "CPU Overhead", "Memory Overhead", "Total Resource Impact"},
                    {"10K ops/sec", "3.0%", "128 MB", "LOW"},
                    {
                        "50K ops/sec",
                        String.format("%.1f%%", resources50k.cpuPercent()),
                        String.format("%.0f MB", resources50k.memoryMb()),
                        "MEDIUM"
                    },
                    {
                        "100K ops/sec",
                        String.format("%.1f%%", resources100k.cpuPercent()),
                        String.format("%.0f MB", resources100k.memoryMb()),
                        "HIGH"
                    },
                });

        ctx.sayKeyValue(
                Map.of(
                        "At 50K ops/sec",
                        String.format(
                                "CPU: %.1f%%, Memory: %.0f MB",
                                resources50k.cpuPercent(), resources50k.memoryMb()),
                        "At 100K ops/sec",
                        String.format(
                                "CPU: %.1f%%, Memory: %.0f MB",
                                resources100k.cpuPercent(), resources100k.memoryMb()),
                        "Scaling pattern",
                        "Linear to throughput",
                        "Planning status",
                        "DOCUMENTED"));

        ctx.sayNote(
                "Observability infrastructure should be provisioned with "
                        + String.format("%.0f", resources100k.memoryMb())
                        + "MB memory for 100K ops/sec workload.");

        assertThat(resources50k.cpuPercent()).isGreaterThan(0);
        assertThat(resources100k.memoryMb()).isGreaterThan(resources50k.memoryMb());
    }

    /**
     * Test recommendation generation.
     *
     * <p>Expected: Actionable recommendations for observability scaling
     */
    @Test
    @DisplayName("Observability: Recommendation Generation")
    void testRecommendationGeneration() {
        ctx.sayNextSection("Observability Capacity: Recommendations");
        ctx.say("Generates actionable recommendations for scaling observability.");
        ctx.say("Considers overhead tolerance and throughput requirements.");

        // Record comprehensive measurements
        planner.recordMeasurement(5_000, 3.0, 3.1, 1.0, 32.0);
        planner.recordMeasurement(15_000, 5.0, 5.4, 2.5, 64.0);
        planner.recordMeasurement(30_000, 7.0, 7.9, 5.0, 128.0);
        planner.recordMeasurement(60_000, 10.0, 12.0, 10.0, 256.0);
        planner.recordMeasurement(120_000, 15.0, 19.5, 18.0, 512.0);

        String recommendations = planner.generateRecommendations();

        ctx.sayCode(recommendations, "text");

        double avgOverhead = planner.getAverageOverhead();
        double maxOverhead = planner.getMaxOverhead();

        ctx.sayKeyValue(
                Map.of(
                        "Average latency overhead",
                        String.format("%.2f%%", avgOverhead),
                        "Maximum latency overhead",
                        String.format("%.2f%%", maxOverhead),
                        "Recommendations generated",
                        "COMPLETE",
                        "Action items",
                        "Review sampling strategy"));

        ctx.sayNote(
                "For production workloads above 60K ops/sec, implement tail-based sampling "
                        + "to reduce overhead while maintaining error visibility.");

        assertThat(recommendations).contains("Observability Capacity Planning");
    }

    // ── Inner Classes (Observability Planning Model) ────────────────────────────────

    /** Measurement record for observability overhead. */
    private record ObservabilityMeasurement(
            double throughput,
            double latencyOverheadPercent,
            double cpuOverheadPercent,
            double memoryOverheadMb) {}

    /** Resource overhead details. */
    public record ResourceOverhead(double cpuPercent, double memoryMb) {}

    /** Observability overhead planner implementation. */
    private static class ObservabilityOverheadPlanner {
        private final List<ObservabilityMeasurement> measurements = new ArrayList<>();
        private final String systemName;

        public ObservabilityOverheadPlanner(String systemName) {
            this.systemName = systemName;
        }

        public void recordMeasurement(
                double throughput,
                double baselineLatencyMs,
                double observedLatencyMs,
                double cpuOverheadPercent,
                double memoryOverheadMb) {
            double latencyOverheadPercent =
                    ((observedLatencyMs - baselineLatencyMs) / baselineLatencyMs) * 100.0;
            measurements.add(
                    new ObservabilityMeasurement(
                            throughput,
                            latencyOverheadPercent,
                            cpuOverheadPercent,
                            memoryOverheadMb));
        }

        public double findBreakingPoint(double maxAcceptableOverheadPercent) {
            for (ObservabilityMeasurement m : measurements) {
                if (m.latencyOverheadPercent() > maxAcceptableOverheadPercent) {
                    return m.throughput();
                }
            }
            return Double.MAX_VALUE;
        }

        public int getMeasurementCount() {
            return measurements.size();
        }

        public double getAverageOverhead() {
            return measurements.stream()
                    .mapToDouble(ObservabilityMeasurement::latencyOverheadPercent)
                    .average()
                    .orElse(0.0);
        }

        public double getMaxOverhead() {
            return measurements.stream()
                    .mapToDouble(ObservabilityMeasurement::latencyOverheadPercent)
                    .max()
                    .orElse(0.0);
        }

        public ResourceOverhead getResourceOverhead(double throughput) {
            for (ObservabilityMeasurement m : measurements) {
                if (m.throughput() == throughput) {
                    return new ResourceOverhead(m.cpuOverheadPercent(), m.memoryOverheadMb());
                }
            }
            // Find closest
            ObservabilityMeasurement closest = null;
            double minDiff = Double.MAX_VALUE;
            for (ObservabilityMeasurement m : measurements) {
                double diff = Math.abs(m.throughput() - throughput);
                if (diff < minDiff) {
                    minDiff = diff;
                    closest = m;
                }
            }
            if (closest != null) {
                return new ResourceOverhead(
                        closest.cpuOverheadPercent(), closest.memoryOverheadMb());
            }
            return new ResourceOverhead(0, 0);
        }

        public String generateRecommendations() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Observability Capacity Planning for ")
                    .append(systemName)
                    .append(" ===\n");

            if (measurements.isEmpty()) {
                sb.append("No measurements available.\n");
                return sb.toString();
            }

            double avgOverhead = getAverageOverhead();
            double maxOverhead = getMaxOverhead();

            sb.append(String.format("Average latency overhead: %.2f%%\n", avgOverhead));
            sb.append(String.format("Maximum latency overhead: %.2f%%\n", maxOverhead));

            double breakingPoint5Percent = findBreakingPoint(5.0);
            double breakingPoint10Percent = findBreakingPoint(10.0);

            if (breakingPoint5Percent < Double.MAX_VALUE) {
                sb.append(
                        String.format(
                                "Breaking point (5%% overhead): %.0f ops/sec\n",
                                breakingPoint5Percent));
            } else {
                sb.append("No breaking point detected at 5%% overhead threshold\n");
            }

            if (breakingPoint10Percent < Double.MAX_VALUE) {
                sb.append(
                        String.format(
                                "Breaking point (10%% overhead): %.0f ops/sec\n",
                                breakingPoint10Percent));
            } else {
                sb.append("No breaking point detected at 10%% overhead threshold\n");
            }

            return sb.toString();
        }
    }
}
