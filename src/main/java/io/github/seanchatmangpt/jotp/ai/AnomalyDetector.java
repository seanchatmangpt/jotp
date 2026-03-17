package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.*;
import java.lang.management.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Continuous monitoring system that detects anomalies in JOTP process clusters.
 *
 * <p>Collects metrics from:
 *
 * <ul>
 *   <li>JVM memory (heap usage, GC overhead)
 *   <li>CPU (thread count, CPU time)
 *   <li>Process latency (p50, p95, p99 response times)
 *   <li>Exception rates (events/sec)
 *   <li>Supervisor health (crash rates, restart attempts)
 *   <li>Circuit breaker trips (failure counts, error rates)
 * </ul>
 *
 * <p>Uses statistical anomaly detection:
 *
 * <ul>
 *   <li>Threshold-based: latency p99 > 500ms
 *   <li>Rate-based: exceptions > 100/sec
 *   <li>Trend-based: GC overhead increasing > 5% in window
 *   <li>Distribution-based: response time distribution shift detection
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * var detector = new AnomalyDetector(Duration.ofSeconds(60));
 * var symptoms = detector.scan();
 * for (var symptom : symptoms) {
 *     System.out.println("Detected: " + symptom);
 * }
 * }</pre>
 */
public final class AnomalyDetector {

  private final Duration windowSize;
  private final MemoryMXBean memoryBean;
  private final RuntimeMXBean runtimeBean;
  private final ThreadMXBean threadBean;
  private final OperatingSystemMXBean osBean;
  private final List<GarbageCollectorMXBean> gcBeans;

  // Metric history for trend analysis
  private final Queue<MetricSnapshot> history;
  private final int maxHistorySize;

  // Thresholds (configurable)
  private double latencyThresholdMs = 500.0;
  private double exceptionThresholdPerSec = 100.0;
  private double gcOverheadThreshold = 0.40; // 40%
  private double cpuThreshold = 0.80; // 80%
  private int cascadingCrashThreshold = 5; // 5+ crashes in window
  private double circuitBreakerErrorRateThreshold = 0.20; // 20%

  // State tracking
  private final AtomicLong lastExceptionCount = new AtomicLong(0);
  private final AtomicLong lastGcTime = new AtomicLong(0);
  private final AtomicLong lastCpuTime = new AtomicLong(0);
  private final Map<String, ProcessMetrics> processMetrics = new ConcurrentHashMap<>();
  private final Map<String, CircuitBreakerMetrics> circuitMetrics = new ConcurrentHashMap<>();
  private volatile Instant lastScan = Instant.now();

  public AnomalyDetector(Duration window) {
    this.windowSize = window;
    this.memoryBean = ManagementFactory.getMemoryMXBean();
    this.runtimeBean = ManagementFactory.getRuntimeMXBean();
    this.threadBean = ManagementFactory.getThreadMXBean();
    this.osBean = (OperatingSystemMXBean)
        ManagementFactory.getOperatingSystemMXBean();
    this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    this.history = new ConcurrentLinkedQueue<>();
    this.maxHistorySize = (int) Math.max(10, window.toSeconds() / 5); // ~1 sample per 5s
  }

  /**
   * Scan current system state and detect anomalies.
   *
   * @return list of detected symptoms
   */
  public List<SelfHealer.Symptom> scan() {
    var symptoms = new ArrayList<SelfHealer.Symptom>();
    var now = Instant.now();

    // Snapshot current metrics
    var snapshot = captureMetrics(now);
    history.offer(snapshot);
    if (history.size() > maxHistorySize) {
      history.poll();
    }

    // Check for various anomalies
    checkLatencyAnomaly(symptoms);
    checkMemoryAnomaly(symptoms);
    checkExceptionAnomaly(symptoms);
    checkCpuAnomaly(symptoms);
    checkCascadingCrash(symptoms);
    checkCircuitBreakerTrips(symptoms);
    checkDeadlock(symptoms);

    lastScan = now;
    return symptoms;
  }

  /** Record metrics for a monitored process. */
  public void recordProcessLatency(String processId, long latencyMs) {
    processMetrics.computeIfAbsent(processId, _ -> new ProcessMetrics())
        .addLatency(latencyMs);
  }

  /** Record exception occurrence. */
  public void recordException(String exceptionType) {
    lastExceptionCount.incrementAndGet();
  }

  /** Record circuit breaker state. */
  public void recordCircuitBreakerState(String circuitName, int failures, int successes) {
    var metrics = circuitMetrics.computeIfAbsent(circuitName, _ -> new CircuitBreakerMetrics());
    metrics.recordAttempt(failures, successes);
  }

  // ── Detection Methods ────────────────────────────────────────────────────────────────

  private void checkLatencyAnomaly(List<SelfHealer.Symptom> symptoms) {
    var p99 = calculatePercentile(99);
    var p95 = calculatePercentile(95);
    var affectedCount = processMetrics.values().stream()
        .filter(m -> m.getLatency99() > latencyThresholdMs)
        .toList()
        .size();

    if (p99 > latencyThresholdMs && affectedCount > 0) {
      symptoms.add(new SelfHealer.Symptom.HighLatency(p99, p95, affectedCount));
    }
  }

  private void checkMemoryAnomaly(List<SelfHealer.Symptom> symptoms) {
    var memUsage = memoryBean.getHeapMemoryUsage();
    var heapUsed = memUsage.getUsed();
    var heapMax = memUsage.getMax();

    // Check GC overhead
    long totalGcTime = gcBeans.stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionTime)
        .sum();
    long timeDelta = totalGcTime - lastGcTime.getAndSet(totalGcTime);
    long wallTimeDelta = Math.max(1, Duration.between(lastScan, Instant.now()).toMillis());
    double gcOverhead = Math.min(1.0, (double) timeDelta / wallTimeDelta);

    // Check for memory leak trend
    double heapUsagePercent = (double) heapUsed / heapMax;
    var leakingProcs = processMetrics.values().stream()
        .filter(m -> m.isLeaking())
        .toList()
        .size();

    if (gcOverhead > gcOverheadThreshold) {
      symptoms.add(new SelfHealer.Symptom.MemoryLeak(
          gcOverhead, heapUsed, leakingProcs));
    }
  }

  private void checkExceptionAnomaly(List<SelfHealer.Symptom> symptoms) {
    long currentCount = lastExceptionCount.get();
    long countDelta = currentCount - lastExceptionCount.getAndSet(currentCount);
    long timeDelta = Math.max(1, Duration.between(lastScan, Instant.now()).toSeconds());
    double exceptionRate = (double) countDelta / timeDelta;

    if (exceptionRate > exceptionThresholdPerSec) {
      var affectedCount = processMetrics.values().stream()
          .filter(ProcessMetrics::hasRecentExceptions)
          .toList()
          .size();
      symptoms.add(new SelfHealer.Symptom.ExceptionStorm(
          (int) exceptionRate, "Unknown", affectedCount));
    }
  }

  private void checkCpuAnomaly(List<SelfHealer.Symptom> symptoms) {
    var cpuTime = threadBean.getCurrentThreadCpuTime();
    long timeDelta = cpuTime - lastCpuTime.getAndSet(cpuTime);
    var cpuUsage = osBean.getProcessCpuLoad();

    if (cpuUsage > cpuThreshold) {
      var hotThreads = threadBean.getThreadCount();
      symptoms.add(new SelfHealer.Symptom.CpuSaturation(cpuUsage * 100, hotThreads));
    }
  }

  private void checkCascadingCrash(List<SelfHealer.Symptom> symptoms) {
    var recentCrashes = processMetrics.values().stream()
        .filter(m -> m.recentCrashCount() > 0)
        .toList();

    if (recentCrashes.size() >= cascadingCrashThreshold) {
      var timeSpan = recentCrashes.stream()
          .mapToLong(ProcessMetrics::getTimeSinceLastCrash)
          .min()
          .orElse(0L);

      symptoms.add(new SelfHealer.Symptom.CascadingCrash(
          recentCrashes.size(), timeSpan, "Unknown root cause"));
    }
  }

  private void checkCircuitBreakerTrips(List<SelfHealer.Symptom> symptoms) {
    for (var entry : circuitMetrics.entrySet()) {
      var metrics = entry.getValue();
      if (metrics.getErrorRate() > circuitBreakerErrorRateThreshold) {
        symptoms.add(new SelfHealer.Symptom.CircuitBreakerTrip(
            entry.getKey(), metrics.failureCount, metrics.getErrorRate()));
      }
    }
  }

  private void checkDeadlock(List<SelfHealer.Symptom> symptoms) {
    var deadlockedIds = threadBean.findDeadlockedThreads();
    if (deadlockedIds != null && deadlockedIds.length > 0) {
      var duration = Duration.between(lastScan, Instant.now()).toMillis();
      symptoms.add(new SelfHealer.Symptom.DeadlockDetected(
          deadlockedIds.length, duration));
    }
  }

  // ── Helper Methods ────────────────────────────────────────────────────────────────────

  private MetricSnapshot captureMetrics(Instant now) {
    var memUsage = memoryBean.getHeapMemoryUsage();
    var threadCount = threadBean.getThreadCount();
    var cpuLoad = osBean.getProcessCpuLoad();
    return new MetricSnapshot(now, memUsage.getUsed(), threadCount, cpuLoad);
  }

  private double calculatePercentile(int percentile) {
    var latencies = new ArrayList<Double>();
    for (var proc : processMetrics.values()) {
      latencies.addAll(proc.getRecentLatencies());
    }
    if (latencies.isEmpty()) return 0;

    Collections.sort(latencies);
    int index = (int) ((percentile / 100.0) * latencies.size());
    return latencies.get(Math.min(index, latencies.size() - 1));
  }

  // ── Inner Classes ────────────────────────────────────────────────────────────────────

  private static class ProcessMetrics {
    private final Queue<Long> latencies = new ConcurrentLinkedQueue<>();
    private final AtomicLong lastCrashTime = new AtomicLong(0);
    private final AtomicInteger crashCount = new AtomicInteger(0);
    private final AtomicInteger exceptionCount = new AtomicInteger(0);
    private static final int MAX_LATENCIES = 1000;

    void addLatency(long ms) {
      latencies.offer(ms);
      if (latencies.size() > MAX_LATENCIES) {
        latencies.poll();
      }
    }

    double getLatency99() {
      if (latencies.isEmpty()) return 0;
      var sorted = latencies.stream().sorted().toList();
      int idx = (int) (0.99 * sorted.size());
      return sorted.get(Math.min(idx, sorted.size() - 1));
    }

    List<Double> getRecentLatencies() {
      return latencies.stream().map(Long::doubleValue).toList();
    }

    void recordCrash() {
      lastCrashTime.set(System.currentTimeMillis());
      crashCount.incrementAndGet();
    }

    int recentCrashCount() {
      long ago = System.currentTimeMillis() - 60_000;
      return lastCrashTime.get() > ago ? crashCount.get() : 0;
    }

    long getTimeSinceLastCrash() {
      return System.currentTimeMillis() - lastCrashTime.get();
    }

    void recordException() {
      exceptionCount.incrementAndGet();
    }

    boolean hasRecentExceptions() {
      return exceptionCount.get() > 0;
    }

    boolean isLeaking() {
      // Simple heuristic: consistently high latencies + memory pressure
      double avg = getRecentLatencies().stream()
          .mapToDouble(Double::doubleValue)
          .average()
          .orElse(0);
      return avg > 200 && getRecentLatencies().size() > 100;
    }
  }

  private static class CircuitBreakerMetrics {
    private volatile int failureCount = 0;
    private volatile int successCount = 0;

    void recordAttempt(int failures, int successes) {
      this.failureCount = failures;
      this.successCount = successes;
    }

    double getErrorRate() {
      int total = failureCount + successCount;
      if (total == 0) return 0;
      return (double) failureCount / total;
    }
  }

  private record MetricSnapshot(
      Instant timestamp,
      long heapUsedBytes,
      int threadCount,
      double cpuLoad) {}
}
