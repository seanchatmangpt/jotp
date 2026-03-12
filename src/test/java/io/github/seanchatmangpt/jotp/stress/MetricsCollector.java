package io.github.seanchatmangpt.jotp.stress;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MetricsCollector — thread-safe accumulation of performance metrics during stress tests.
 *
 * <p>Captures throughput, latency percentiles, memory usage, and GC events without synchronization
 * bottlenecks (using lock-free atomic operations and thread-safe collections).
 */
public final class MetricsCollector {

  private final String testName;
  private final long startTimeMs;
  private final List<LatencyEvent> latencyEvents = new CopyOnWriteArrayList<>();
  private final AtomicLong operationCount = new AtomicLong();
  private final AtomicLong errorCount = new AtomicLong();
  private long startHeapBytes = 0;
  private long peakHeapBytes = 0;
  private int gcEventCount = 0;
  private long totalGcPauseMs = 0;

  public MetricsCollector(String testName) {
    this.testName = testName;
    this.startTimeMs = System.currentTimeMillis();
    this.startHeapBytes = getHeapUsage();
    this.peakHeapBytes = startHeapBytes;
  }

  /**
   * Record a single operation with latency.
   *
   * @param latencyMs operation latency in milliseconds
   */
  public void recordOperation(long latencyMs) {
    operationCount.incrementAndGet();
    latencyEvents.add(new LatencyEvent(System.currentTimeMillis(), latencyMs));
    updatePeakHeap();
  }

  /**
   * Record a failed operation.
   */
  public void recordError() {
    errorCount.incrementAndGet();
  }

  /**
   * Get elapsed time since test start.
   *
   * @return elapsed milliseconds
   */
  public long getElapsedMs() {
    return System.currentTimeMillis() - startTimeMs;
  }

  /**
   * Get test name.
   */
  public String getTestName() {
    return testName;
  }

  /**
   * Get total operations completed.
   */
  public long getOperationCount() {
    return operationCount.get();
  }

  /**
   * Get total errors encountered.
   */
  public long getErrorCount() {
    return errorCount.get();
  }

  /**
   * Get throughput (operations per second).
   */
  public double getThroughputPerSec() {
    long elapsedMs = getElapsedMs();
    if (elapsedMs == 0) return 0;
    return (double) operationCount.get() * 1000 / elapsedMs;
  }

  /**
   * Get latency percentile in milliseconds.
   *
   * @param percentile 0-100
   * @return latency at percentile in ms
   */
  public double getLatencyPercentileMs(int percentile) {
    if (latencyEvents.isEmpty()) return 0;
    List<Long> sorted = latencyEvents.stream().map(e -> e.latencyMs).sorted().toList();
    int index = (int) ((percentile / 100.0) * sorted.size());
    index = Math.min(index, sorted.size() - 1);
    return sorted.get(index);
  }

  /**
   * Get peak latency in milliseconds.
   */
  public long getMaxLatencyMs() {
    return latencyEvents.stream().mapToLong(e -> e.latencyMs).max().orElse(0);
  }

  /**
   * Get average latency in milliseconds.
   */
  public double getAvgLatencyMs() {
    if (latencyEvents.isEmpty()) return 0;
    return latencyEvents.stream().mapToLong(e -> e.latencyMs).average().orElse(0);
  }

  /**
   * Get peak heap usage in megabytes.
   */
  public double getPeakHeapMb() {
    return peakHeapBytes / (1024.0 * 1024.0);
  }

  /**
   * Get heap growth in megabytes.
   */
  public double getHeapGrowthMb() {
    return (peakHeapBytes - startHeapBytes) / (1024.0 * 1024.0);
  }

  /**
   * Get error rate (0-100).
   */
  public double getErrorRate() {
    long total = operationCount.get() + errorCount.get();
    if (total == 0) return 0;
    return (100.0 * errorCount.get()) / total;
  }

  /**
   * Capture current GC stats (call before and after to measure impact).
   *
   * @return GC snapshot
   */
  public GcSnapshot captureGcSnapshot() {
    long gcCount = 0;
    long gcTime = 0;
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      gcCount += bean.getCollectionCount();
      gcTime += bean.getCollectionTime();
    }
    return new GcSnapshot(gcCount, gcTime);
  }

  /**
   * Print human-readable summary.
   */
  public String getSummary() {
    return String.format(
        """
        === %s ===
        Duration: %.1f sec
        Throughput: %.0f ops/sec
        Latency p50: %.2f ms, p99: %.2f ms, p999: %.2f ms, max: %d ms
        Avg latency: %.2f ms
        Operations: %d, Errors: %d (%.2f%% error rate)
        Heap: peak %.1f MB, growth %.1f MB
        """,
        testName,
        getElapsedMs() / 1000.0,
        getThroughputPerSec(),
        getLatencyPercentileMs(50),
        getLatencyPercentileMs(99),
        getLatencyPercentileMs(999),
        getMaxLatencyMs(),
        getAvgLatencyMs(),
        operationCount.get(),
        errorCount.get(),
        getErrorRate(),
        getPeakHeapMb(),
        getHeapGrowthMb());
  }

  private void updatePeakHeap() {
    long current = getHeapUsage();
    while (current > peakHeapBytes) {
      peakHeapBytes = Math.max(peakHeapBytes, current);
      current = getHeapUsage();
    }
  }

  private long getHeapUsage() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    return memoryBean.getHeapMemoryUsage().getUsed();
  }

  /** Record of a single latency event. */
  private record LatencyEvent(long timestampMs, long latencyMs) {}

  /** GC snapshot for before/after comparison. */
  public record GcSnapshot(long collectionCount, long collectionTimeMs) {
    /**
     * Compute change between two snapshots.
     *
     * @param after later snapshot
     * @return delta
     */
    public GcDelta delta(GcSnapshot after) {
      return new GcDelta(
          after.collectionCount - collectionCount,
          after.collectionTimeMs - collectionTimeMs);
    }
  }

  /** Change in GC metrics. */
  public record GcDelta(long gcCount, long gcTimeMs) {}
}
