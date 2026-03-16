package io.github.seanchatmangpt.jotp.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * SimpleCapacityPlanner — basic capacity planning for system resources.
 *
 * <p>Provides straightforward capacity planning calculations based on performance metrics. Helps
 * estimate resource requirements for target throughput and latency SLAs.
 */
public final class SimpleCapacityPlanner {

    private final List<CapacityDataPoint> dataPoints = new ArrayList<>();
    private final String systemName;

    public SimpleCapacityPlanner(String systemName) {
        this.systemName = systemName;
    }

    /**
     * Record a performance measurement at a specific load level.
     *
     * @param throughput operations per second
     * @param latencyP99Ms 99th percentile latency in milliseconds
     * @param cpuUtilizationPercent CPU utilization
     * @param memoryUsedMb memory usage in MB
     */
    public void recordDataPoint(
            double throughput,
            double latencyP99Ms,
            double cpuUtilizationPercent,
            double memoryUsedMb) {
        dataPoints.add(
                new CapacityDataPoint(
                        throughput, latencyP99Ms, cpuUtilizationPercent, memoryUsedMb));
    }

    /**
     * Estimate the maximum throughput before violating latency SLA.
     *
     * @param maxLatencyP99Ms maximum acceptable 99th percentile latency
     * @return estimated maximum throughput
     */
    public double estimateMaxThroughput(double maxLatencyP99Ms) {
        double maxThroughput = 0;
        for (CapacityDataPoint dp : dataPoints) {
            if (dp.latencyP99Ms() <= maxLatencyP99Ms && dp.throughput() > maxThroughput) {
                maxThroughput = dp.throughput();
            }
        }
        return maxThroughput;
    }

    /**
     * Estimate resources needed for target throughput.
     *
     * @param targetThroughput desired throughput
     * @return estimated resource requirements
     */
    public ResourceRequirement estimateResourcesFor(double targetThroughput) {
        // Find the closest data point
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

        // Scale resources linearly
        double scalingFactor = targetThroughput / closest.throughput();
        double estimatedCpu = closest.cpuUtilizationPercent() * scalingFactor;
        double estimatedMemory = closest.memoryUsedMb() * scalingFactor;
        int estimatedInstances = (int) Math.ceil(scalingFactor);

        return new ResourceRequirement(estimatedCpu, estimatedMemory, estimatedInstances);
    }

    /**
     * Generate capacity planning report.
     *
     * @return formatted capacity planning recommendations
     */
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
                dataPoints.stream().mapToDouble(CapacityDataPoint::latencyP99Ms).max().orElse(0);

        sb.append(String.format("Maximum measured throughput: %.0f ops/sec\n", maxThroughput));
        sb.append(String.format("Maximum measured latency p99: %.2f ms\n", maxLatency));

        // Estimate for common SLAs
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

    /** Data point for capacity planning. */
    private record CapacityDataPoint(
            double throughput,
            double latencyP99Ms,
            double cpuUtilizationPercent,
            double memoryUsedMb) {}

    /** Resource requirement estimation. */
    public record ResourceRequirement(
            double cpuUtilizationPercent, double memoryMb, int instances) {}
}
