package io.github.seanchatmangpt.jotp.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * ArchitecturalTradeoffAnalysis — comparative analysis framework for architecture decisions.
 *
 * <p>Enables systematic comparison of competing architectures (JOTP, Akka, Vert.x, etc.) on
 * multiple dimensions: throughput, latency, resource consumption, cost, and operational complexity.
 */
public class ArchitecturalTradeoffAnalysis {

    private final String scenarioName;
    private final List<Architecture> architectures = new ArrayList<>();

    public ArchitecturalTradeoffAnalysis(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    /**
     * Add an architecture to the analysis.
     *
     * @param architecture architecture to compare
     */
    public void addArchitecture(Architecture architecture) {
        architectures.add(architecture);
    }

    /**
     * Generate executive summary for architecture selection.
     *
     * @return formatted summary
     */
    public String executiveSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ARCHITECTURAL TRADEOFF ANALYSIS: ").append(scenarioName).append("\n");
        sb.append("=".repeat(80)).append("\n\n");

        for (Architecture arch : architectures) {
            sb.append(arch.name()).append("\n");
            sb.append("-".repeat(80)).append("\n");
            sb.append(String.format("  Throughput:     %.0f ops/sec%n", arch.throughput()));
            sb.append(String.format("  Latency p99:    %.2f ms%n", arch.latencyP99Ms()));
            sb.append(String.format("  CPU Usage:      %.1f%%%n", arch.cpuPercent()));
            sb.append(String.format("  Memory:         %.1f MB%n", arch.memoryMb()));
            sb.append(String.format("  Threads:        %,d%n", arch.threadCount()));
            sb.append(String.format("  GC Pause:       %.2f ms%n", arch.gcPauseMs()));
            sb.append(String.format("  Complexity:     %d/10%n", arch.complexity()));
            sb.append(String.format("  Cost per B ops: $%.2f%n", arch.costPerBillionOps()));
            sb.append(String.format("  Recovery:       %s%n", arch.recoveryMechanism()));
            sb.append(String.format("  Recovery Time:  %.0f ms%n", arch.recoveryTimeMs()));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Recommend architecture for specific requirements.
     *
     * @param maxLatencyP99Ms maximum acceptable p99 latency
     * @param minThroughputTps minimum throughput requirement
     * @param maxCostPerBillion maximum cost per billion operations
     * @param preferSimplicity prefer simpler solutions over performance
     * @return recommendation
     */
    public String recommendForRequirements(
            Double maxLatencyP99Ms,
            Long minThroughputTps,
            Double maxCostPerBillion,
            boolean preferSimplicity) {

        Architecture best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Architecture arch : architectures) {
            double score = 0;

            // Latency score (lower is better)
            if (maxLatencyP99Ms != null) {
                if (arch.latencyP99Ms() > maxLatencyP99Ms) {
                    continue; // Disqualified
                }
                score += (maxLatencyP99Ms - arch.latencyP99Ms()) * 10;
            }

            // Throughput score (higher is better)
            if (minThroughputTps != null) {
                if (arch.throughput() < minThroughputTps) {
                    continue; // Disqualified
                }
                score += (arch.throughput() - minThroughputTps) / 1000.0;
            }

            // Cost score (lower is better)
            if (maxCostPerBillion != null) {
                if (arch.costPerBillionOps() > maxCostPerBillion) {
                    continue; // Disqualified
                }
                score += (maxCostPerBillion - arch.costPerBillionOps()) * 5;
            }

            // Simpler preference (lower complexity is better)
            if (preferSimplicity) {
                score += (10 - arch.complexity()) * 20;
            }

            if (score > bestScore) {
                bestScore = score;
                best = arch;
            }
        }

        if (best == null) {
            return "No architecture meets the specified requirements.";
        }

        return String.format(
                "RECOMMENDED: %s%n"
                        + "  Score: %.1f%n"
                        + "  Throughput: %.0f ops/sec, Latency p99: %.2f ms%n"
                        + "  Cost: $%.2f per billion ops, Complexity: %d/10%n"
                        + "  Recovery: %s (%.0f ms)",
                best.name(),
                bestScore,
                best.throughput(),
                best.latencyP99Ms(),
                best.costPerBillionOps(),
                best.complexity(),
                best.recoveryMechanism(),
                best.recoveryTimeMs());
    }

    /** Architecture representation for comparison. */
    public record Architecture(
            String name,
            double throughput, // ops/sec
            double latencyP99Ms, // p99 latency in ms
            double cpuPercent, // CPU utilization
            double memoryMb, // memory in MB
            int threadCount, // number of threads
            double gcPauseMs, // average GC pause
            int complexity, // 1-10 (1=simple, 10=complex)
            double costPerBillionOps, // USD cost per billion operations
            String recoveryMechanism, // description of recovery strategy
            double recoveryTimeMs // time to recover from failure
            ) {}
}
