package io.github.seanchatmangpt.jotp.stress;

import java.util.ArrayList;
import java.util.List;

/**
 * ObservabilityCapacityPlanner — capacity planning for observability infrastructure.
 *
 * <p>Models the overhead of monitoring, tracing, and metrics collection on system performance.
 * Helps determine when observability becomes a bottleneck and how to scale monitoring
 * infrastructure.
 */
public final class ObservabilityCapacityPlanner {

    private final List<ObservabilityOverheadMeasurement> measurements = new ArrayList<>();
    private final String systemName;

    public ObservabilityCapacityPlanner(String systemName) {
        this.systemName = systemName;
    }

    /**
     * Record observability overhead at a given throughput.
     *
     * @param throughput operations per second
     * @param baselineLatencyMs latency without observability
     * @param observedLatencyMs latency with observability
     * @param cpuOverheadPercent additional CPU usage
     * @param memoryOverheadMb additional memory usage
     */
    public void recordMeasurement(
            double throughput,
            double baselineLatencyMs,
            double observedLatencyMs,
            double cpuOverheadPercent,
            double memoryOverheadMb) {
        double latencyOverheadPercent =
                ((observedLatencyMs - baselineLatencyMs) / baselineLatencyMs) * 100.0;
        measurements.add(
                new ObservabilityOverheadMeasurement(
                        throughput, latencyOverheadPercent, cpuOverheadPercent, memoryOverheadMb));
    }

    /**
     * Calculate the breaking point where observability overhead exceeds threshold.
     *
     * @param maxAcceptableOverheadPercent maximum acceptable overhead percentage
     * @return throughput at which overhead exceeds threshold
     */
    public double findBreakingPoint(double maxAcceptableOverheadPercent) {
        for (ObservabilityOverheadMeasurement m : measurements) {
            if (m.latencyOverheadPercent() > maxAcceptableOverheadPercent) {
                return m.throughput();
            }
        }
        return Double.MAX_VALUE; // No breaking point found
    }

    /**
     * Generate capacity planning recommendations.
     *
     * @return recommendations for scaling observability infrastructure
     */
    public String generateRecommendations() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Observability Capacity Planning for ").append(systemName).append(" ===\n");

        if (measurements.isEmpty()) {
            sb.append("No measurements available.\n");
            return sb.toString();
        }

        double avgOverhead =
                measurements.stream()
                        .mapToDouble(ObservabilityOverheadMeasurement::latencyOverheadPercent)
                        .average()
                        .orElse(0.0);
        double maxOverhead =
                measurements.stream()
                        .mapToDouble(ObservabilityOverheadMeasurement::latencyOverheadPercent)
                        .max()
                        .orElse(0.0);

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

    /** Measurement record for observability overhead. */
    private record ObservabilityOverheadMeasurement(
            double throughput,
            double latencyOverheadPercent,
            double cpuOverheadPercent,
            double memoryOverheadMb) {}
}
