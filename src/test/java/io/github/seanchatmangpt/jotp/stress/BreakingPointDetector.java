package io.github.seanchatmangpt.jotp.stress;

/**
 * BreakingPointDetector — identifies when system performance degrades under load.
 *
 * <p>A "breaking point" is detected when one of these conditions occurs:
 * <ul>
 *   <li>Throughput drops >10% from peak</li>
 *   <li>Latency p99 exceeds threshold (e.g., 10ms for 1M msg/sec nominal)</li>
 *   <li>Error rate exceeds threshold (e.g., >0.1%)</li>
 *   <li>Heap pressure reaches critical level (e.g., >90% heap used)</li>
 * </ul>
 */
public final class BreakingPointDetector {

  private final double throughputDropThreshold; // 0.1 = 10%
  private final double latencyThresholdMs;
  private final double errorRateThreshold; // 0.1 = 0.1%
  private final double heapPressureThreshold; // 0.9 = 90%

  private double peakThroughput = 0;
  private boolean breakingPointDetected = false;
  private String breakingPointReason = "";

  public BreakingPointDetector(
      double throughputDropThreshold,
      double latencyThresholdMs,
      double errorRateThreshold,
      double heapPressureThreshold) {
    this.throughputDropThreshold = throughputDropThreshold;
    this.latencyThresholdMs = latencyThresholdMs;
    this.errorRateThreshold = errorRateThreshold;
    this.heapPressureThreshold = heapPressureThreshold;
  }

  /**
   * Analyze metrics and detect breaking point.
   *
   * @param metrics current metrics snapshot
   * @return true if breaking point detected
   */
  public boolean detect(MetricsCollector metrics) {
    if (breakingPointDetected) return true; // Sticky detection

    double throughput = metrics.getThroughputPerSec();
    double latencyP99 = metrics.getLatencyPercentileMs(99);
    double errorRate = metrics.getErrorRate();
    double heapPressure = metrics.getPeakHeapMb() / getMaxHeap();

    // Update peak throughput
    if (throughput > peakThroughput) {
      peakThroughput = throughput;
    }

    // Check for throughput drop
    if (peakThroughput > 0) {
      double dropPercentage = (peakThroughput - throughput) / peakThroughput;
      if (dropPercentage >= throughputDropThreshold) {
        breakingPointDetected = true;
        breakingPointReason =
            String.format(
                "Throughput dropped %.1f%% (peak %.0f, current %.0f ops/sec)",
                dropPercentage * 100, peakThroughput, throughput);
        return true;
      }
    }

    // Check for latency threshold
    if (latencyP99 >= latencyThresholdMs) {
      breakingPointDetected = true;
      breakingPointReason =
          String.format("Latency p99 exceeded threshold: %.2f ms >= %.2f ms", latencyP99, latencyThresholdMs);
      return true;
    }

    // Check for error rate threshold
    if (errorRate >= errorRateThreshold) {
      breakingPointDetected = true;
      breakingPointReason = String.format("Error rate exceeded threshold: %.2f%% >= %.2f%%", errorRate, errorRateThreshold);
      return true;
    }

    // Check for heap pressure
    if (heapPressure >= heapPressureThreshold) {
      breakingPointDetected = true;
      breakingPointReason =
          String.format(
              "Heap pressure critical: %.1f%% >= %.1f%%",
              heapPressure * 100, heapPressureThreshold * 100);
      return true;
    }

    return false;
  }

  /**
   * Whether a breaking point has been detected.
   */
  public boolean isBreakingPointDetected() {
    return breakingPointDetected;
  }

  /**
   * Get the reason for breaking point detection.
   */
  public String getBreakingPointReason() {
    return breakingPointReason;
  }

  /**
   * Reset detection state (for testing multiple scenarios).
   */
  public void reset() {
    breakingPointDetected = false;
    breakingPointReason = "";
    peakThroughput = 0;
  }

  private double getMaxHeap() {
    return Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0);
  }

  /**
   * Factory for creating a default detector with reasonable thresholds.
   */
  public static BreakingPointDetector createDefault() {
    return new BreakingPointDetector(
        0.10, // 10% throughput drop
        10.0, // 10ms latency p99
        0.1, // 0.1% error rate
        0.90 // 90% heap pressure
        );
  }

  /**
   * Factory for strict breaking point detection (lower thresholds).
   */
  public static BreakingPointDetector createStrict() {
    return new BreakingPointDetector(
        0.05, // 5% throughput drop
        1.0, // 1ms latency p99
        0.01, // 0.01% error rate
        0.80 // 80% heap pressure
        );
  }
}
