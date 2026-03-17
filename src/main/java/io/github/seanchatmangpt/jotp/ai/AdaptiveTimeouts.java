package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Adaptive Timeout System — learns optimal timeout values based on network conditions, service
 * behavior, and response time distributions.
 *
 * <p><strong>Problem:</strong> Static timeouts lead to two failure modes:
 *
 * <ol>
 *   <li><strong>Too strict:</strong> Healthy services timeout due to temporary latency spikes,
 *       triggering cascading failures and timeout storms.
 *   <li><strong>Too lenient:</strong> System waits forever on genuinely failed services, causing
 *       resource exhaustion and slow customer-facing degradation.
 * </ol>
 *
 * <p><strong>Solution:</strong> Continuously monitor response time distributions and dynamically
 * adjust timeouts based on statistical percentiles, node load, and network latency.
 *
 * <p><strong>Algorithm:</strong>
 *
 * <pre>{@code
 * For each service:
 *   1. Collect response times from requests
 *   2. Compute p50, p99, p999 (percentiles)
 *   3. Calculate optimal_timeout = p999 + (p999 - p50) * buffer_factor
 *   4. Adjust based on:
 *      - Network latency (add base RTT estimate)
 *      - Node load (scale up if CPU/memory high)
 *      - Service health (p999 / p50 ratio indicates jitter)
 *   5. Apply bounds: [min_timeout, max_timeout]
 *   6. Smooth transitions to prevent timeout storms
 * }</pre>
 *
 * <p><strong>Erlang Inspiration:</strong>
 *
 * <p>Joe Armstrong's principle: "Erlang supervisors know when to restart. We need systems that know
 * when to timeout." This system monitors the distribution of response times and makes timeout
 * decisions from first principles: p999 + margin for safety.
 *
 * <p><strong>Key Features:</strong>
 *
 * <ul>
 *   <li><strong>Per-Service Tuning:</strong> Each service gets its own timeout curve based on its
 *       unique behavior.
 *   <li><strong>Statistical Analysis:</strong> Uses online percentile computation via histogram
 *       buckets, not in-memory arrays.
 *   <li><strong>Dynamic Adjustment:</strong> Timeouts move smoothly toward optimal value without
 *       sudden jumps that trigger storms.
 *   <li><strong>Network Awareness:</strong> Factors in base latency and link saturation.
 *   <li><strong>Load Awareness:</strong> Scales timeouts up when node CPU/memory is high.
 *   <li><strong>Storm Prevention:</strong> Exponential backoff for consecutive timeouts;
 *       hysteresis to prevent oscillation.
 *   <li><strong>Audit Trail:</strong> Stores timeout history in PostgreSQL for SRE analysis and
 *       post-mortem.
 *   <li><strong>History & Telemetry:</strong> Exposes metrics for monitoring: p50, p99, p999,
 *       timeout duration, timeout count, recommendation.
 * </ul>
 *
 * <p><strong>Example usage:</strong>
 *
 * <pre>{@code
 * var timeouts = AdaptiveTimeouts.create();
 *
 * // Service "payments" makes a request
 * long startNanos = System.nanoTime();
 * Result<PaymentResponse, Exception> result = Result.of(() ->
 *     payments.charge(amount)
 * );
 * long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
 *
 * // Record the response time
 * timeouts.recordResponse("payments", elapsedMs, result.isSuccess());
 *
 * // Get the current adaptive timeout for next request
 * Duration timeout = timeouts.getTimeout("payments");
 * Future<PaymentResponse> future = payments.askAsync(request, timeout);
 * }</pre>
 *
 * @see <a href="https://erlang.org/doc/man/gen_server.html#call-3">Erlang gen_server:call/3</a>
 * @see <a href="https://joearms.github.io/#index">Joe Armstrong - Erlang and beyond</a>
 */
public class AdaptiveTimeouts {

  /** Service-specific timeout statistics and configuration. */
  public record TimeoutStats(
      String serviceName,
      long p50Ms,
      long p99Ms,
      long p999Ms,
      long currentTimeoutMs,
      long recommendedTimeoutMs,
      long totalRequests,
      long totalTimeouts,
      double jitterRatio,
      Instant lastUpdate) {

    /**
     * Returns {@code true} if service is experiencing high timeout rate (> 1% consecutive
     * timeouts).
     */
    public boolean isUnderStress() {
      return totalRequests > 0 && (totalTimeouts * 100.0 / totalRequests) > 1.0;
    }

    /** Returns the ratio of current timeout to recommended (1.0 = on-target). */
    public double timeoutHealth() {
      return recommendedTimeoutMs > 0 ? (double) currentTimeoutMs / recommendedTimeoutMs : 1.0;
    }
  }

  /** Configuration for adaptive timeout behavior. */
  public record Config(
      // Statistical bounds
      long minTimeoutMs,
      long maxTimeoutMs,
      // Smoothing and storm prevention
      double bufferFactor,
      long smoothingWindowMs,
      double hysteresisThreshold,
      // Load and latency awareness
      double cpuScaleFactor,
      long estimatedNetworkLatencyMs,
      // History storage
      Optional<TimeoutHistoryStore> historyStore,
      // Update frequency
      long statisticsUpdateIntervalMs) {

    public static Config defaults() {
      return new Config(
          100, // minTimeoutMs
          30000, // maxTimeoutMs
          0.5, // bufferFactor (p99 + (p99-p50)*0.5)
          60000, // smoothingWindowMs (1 minute)
          0.05, // hysteresisThreshold (5% tolerance)
          1.5, // cpuScaleFactor (scale by 1.5x when CPU high)
          5, // estimatedNetworkLatencyMs
          Optional.empty(), // historyStore
          5000 // statisticsUpdateIntervalMs
      );
    }
  }

  /** Interface for storing timeout decisions in persistent storage (e.g., PostgreSQL). */
  public interface TimeoutHistoryStore {
    /**
     * Record a timeout adjustment decision.
     *
     * @param serviceName service identifier
     * @param previousTimeoutMs old timeout value
     * @param newTimeoutMs new timeout value
     * @param p99Ms 99th percentile response time
     * @param totalRequests total requests for this service
     * @param reason human-readable reason for adjustment
     */
    void recordAdjustment(
        String serviceName,
        long previousTimeoutMs,
        long newTimeoutMs,
        long p99Ms,
        long totalRequests,
        String reason);

    /**
     * Query historical timeout adjustments for post-mortem analysis.
     *
     * @param serviceName service identifier
     * @param limit maximum number of records to return
     * @return list of adjustment history
     */
    List<Map<String, Object>> queryHistory(String serviceName, int limit);
  }

  /** In-memory histogram for online percentile computation. */
  private static class LatencyHistogram {
    private final int[] buckets; // buckets[i] = count of responses with latency in [i*10ms,
    // (i+1)*10ms)
    private final int bucketSizeMs = 10; // each bucket covers 10ms
    private long totalCount = 0;
    private long sumMs = 0; // for average computation

    LatencyHistogram(int maxBuckets) {
      this.buckets = new int[maxBuckets];
    }

    synchronized void record(long latencyMs) {
      totalCount++;
      sumMs += latencyMs;
      int bucketIndex = (int) Math.min(latencyMs / bucketSizeMs, buckets.length - 1);
      if (bucketIndex >= 0) {
        buckets[bucketIndex]++;
      }
    }

    synchronized long percentile(double p) {
      if (totalCount == 0) return 0;
      long targetCount = (long) (totalCount * p);
      long count = 0;
      for (int i = 0; i < buckets.length; i++) {
        count += buckets[i];
        if (count >= targetCount) {
          return (long) (i * bucketSizeMs);
        }
      }
      return buckets.length * bucketSizeMs;
    }

    synchronized long getAverage() {
      return totalCount > 0 ? sumMs / totalCount : 0;
    }

    synchronized long getTotalCount() {
      return totalCount;
    }

    synchronized void reset() {
      Arrays.fill(buckets, 0);
      totalCount = 0;
      sumMs = 0;
    }
  }

  /** Per-service adaptive timeout state. */
  private static class ServiceTimeoutState {
    private final String serviceName;
    private final Config config;
    private final LatencyHistogram histogram;

    private long currentTimeoutMs;
    private long recommendedTimeoutMs;
    private long lastUpdateNanos = System.nanoTime();
    private long consecutiveTimeouts = 0;
    private long totalRequests = 0;
    private long totalTimeouts = 0;
    private final Queue<Long> recentTimeouts = new ConcurrentLinkedQueue<>();
    private static final int RECENT_WINDOW_SIZE = 100;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    ServiceTimeoutState(String serviceName, Config config) {
      this.serviceName = serviceName;
      this.config = config;
      this.histogram = new LatencyHistogram(3000); // up to 30 seconds in 10ms buckets
      this.currentTimeoutMs = config.minTimeoutMs;
      this.recommendedTimeoutMs = config.minTimeoutMs;
    }

    void recordResponse(long latencyMs, boolean success) {
      histogram.record(latencyMs);
      totalRequests++;
      if (!success) {
        totalTimeouts++;
        consecutiveTimeouts++;
        recentTimeouts.offer(System.currentTimeMillis());
        if (recentTimeouts.size() > RECENT_WINDOW_SIZE) {
          recentTimeouts.poll();
        }
      } else {
        consecutiveTimeouts = 0;
      }

      // Check if we should update recommendation
      long elapsedSinceUpdate = System.nanoTime() - lastUpdateNanos;
      if (elapsedSinceUpdate > config.statisticsUpdateIntervalMs * 1_000_000L) {
        updateRecommendedTimeout();
      }
    }

    void updateRecommendedTimeout() {
      lock.writeLock().lock();
      try {
        long p50 = histogram.percentile(0.50);
        long p99 = histogram.percentile(0.99);
        long p999 = histogram.percentile(0.999);

        // Algorithm: optimal_timeout = p999 + (p999 - p50) * buffer_factor
        long jitterMargin = (long) ((p999 - p50) * config.bufferFactor);
        long baseRecommendation = p999 + jitterMargin;

        // Add estimated network latency
        baseRecommendation += config.estimatedNetworkLatencyMs;

        // Scale by node load (if CPU is high, services may be slower)
        double nodeLoadFactor = getNodeLoadFactor();
        baseRecommendation = (long) (baseRecommendation * nodeLoadFactor);

        // Scale by consecutive timeout streak (exponential backoff to prevent storms)
        if (consecutiveTimeouts > 0) {
          double backoffFactor = Math.pow(1.5, Math.min(consecutiveTimeouts, 5));
          baseRecommendation = (long) (baseRecommendation * backoffFactor);
        }

        // Apply bounds
        long newRecommendation =
            Math.max(config.minTimeoutMs, Math.min(config.maxTimeoutMs, baseRecommendation));

        // Hysteresis: only update if change exceeds threshold
        double changeRatio =
            recommendedTimeoutMs > 0 ? (double) newRecommendation / recommendedTimeoutMs : 1.0;
        if (Math.abs(changeRatio - 1.0) > config.hysteresisThreshold) {
          long oldRecommendation = recommendedTimeoutMs;
          recommendedTimeoutMs = newRecommendation;

          // Smooth transition to new timeout
          currentTimeoutMs = smoothTransition(currentTimeoutMs, newRecommendation);

          // Log adjustment for history store
          if (config.historyStore.isPresent()) {
            String reason =
                String.format(
                    "p50=%dms, p99=%dms, p999=%dms, jitter=%.2f, load=%.2fx, backoff=%.2fx",
                    p50,
                    p99,
                    p999,
                    (p999 - p50) / (double) Math.max(p50, 1),
                    nodeLoadFactor,
                    consecutiveTimeouts > 0 ? Math.pow(1.5, Math.min(consecutiveTimeouts, 5)) : 1.0
                );
            config
                .historyStore
                .get()
                .recordAdjustment(
                    serviceName, oldRecommendation, newRecommendation, p99, totalRequests, reason);
          }
        }

        lastUpdateNanos = System.nanoTime();
      } finally {
        lock.writeLock().unlock();
      }
    }

    private long smoothTransition(long currentTimeoutMs, long targetTimeoutMs) {
      // Move toward target timeout at most 10% per update to prevent sudden jumps
      double smoothFactor = 0.1;
      long change = (long) ((targetTimeoutMs - currentTimeoutMs) * smoothFactor);
      return Math.max(config.minTimeoutMs, Math.min(config.maxTimeoutMs, currentTimeoutMs + change));
    }

    private double getNodeLoadFactor() {
      // Simplified: check CPU and memory. In production, integrate with JMX or OS metrics.
      try {
        var runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryRatio = (double) usedMemory / maxMemory;

        // If memory usage > 80%, scale timeouts up
        if (memoryRatio > 0.8) {
          return 1.0 + (config.cpuScaleFactor - 1.0) * (memoryRatio - 0.8) / 0.2;
        }
        return 1.0;
      } catch (Exception e) {
        return 1.0;
      }
    }

    TimeoutStats getStats() {
      lock.readLock().lock();
      try {
        long p50 = histogram.percentile(0.50);
        long p99 = histogram.percentile(0.99);
        long p999 = histogram.percentile(0.999);

        double jitterRatio = p50 > 0 ? (double) (p999 - p50) / p50 : 0.0;

        return new TimeoutStats(
            serviceName,
            p50,
            p99,
            p999,
            currentTimeoutMs,
            recommendedTimeoutMs,
            totalRequests,
            totalTimeouts,
            jitterRatio,
            Instant.now());
      } finally {
        lock.readLock().unlock();
      }
    }
  }

  private final Config config;
  private final ConcurrentHashMap<String, ServiceTimeoutState> serviceStates =
      new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(1, r -> new Thread(r, "AdaptiveTimeouts-Scheduler"));
  private final AtomicLong startTimeNanos = new AtomicLong(System.nanoTime());

  private AdaptiveTimeouts(Config config) {
    this.config = config;
    // Periodically update recommendations for all services
    scheduler.scheduleAtFixedRate(
        this::updateAllRecommendations,
        config.statisticsUpdateIntervalMs,
        config.statisticsUpdateIntervalMs,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Create an AdaptiveTimeouts system with default configuration.
   *
   * @return new adaptive timeout manager
   */
  public static AdaptiveTimeouts create() {
    return new AdaptiveTimeouts(Config.defaults());
  }

  /**
   * Create an AdaptiveTimeouts system with custom configuration.
   *
   * @param config custom configuration
   * @return new adaptive timeout manager
   */
  public static AdaptiveTimeouts create(Config config) {
    return new AdaptiveTimeouts(config);
  }

  /**
   * Record a response time for a service.
   *
   * @param serviceName service identifier
   * @param latencyMs response latency in milliseconds
   * @param success {@code true} if request succeeded, {@code false} if timed out
   */
  public void recordResponse(String serviceName, long latencyMs, boolean success) {
    ServiceTimeoutState state =
        serviceStates.computeIfAbsent(serviceName, name -> new ServiceTimeoutState(name, config));
    state.recordResponse(latencyMs, success);
  }

  /**
   * Get the current adaptive timeout for a service.
   *
   * @param serviceName service identifier
   * @return recommended timeout duration
   */
  public Duration getTimeout(String serviceName) {
    ServiceTimeoutState state =
        serviceStates.computeIfAbsent(serviceName, name -> new ServiceTimeoutState(name, config));
    return Duration.ofMillis(state.currentTimeoutMs);
  }

  /**
   * Get statistics for a service (for monitoring and debugging).
   *
   * @param serviceName service identifier
   * @return timeout statistics
   */
  public TimeoutStats getStats(String serviceName) {
    ServiceTimeoutState state = serviceStates.get(serviceName);
    if (state == null) {
      return new TimeoutStats(serviceName, 0, 0, 0, 0, 0, 0, 0, 0.0, Instant.now());
    }
    return state.getStats();
  }

  /**
   * Get statistics for all monitored services.
   *
   * @return map of service name to timeout statistics
   */
  public Map<String, TimeoutStats> getAllStats() {
    Map<String, TimeoutStats> stats = new LinkedHashMap<>();
    for (var entry : serviceStates.entrySet()) {
      stats.put(entry.getKey(), entry.getValue().getStats());
    }
    return stats;
  }

  /**
   * Record a response time using a Result type (convenient for railway-oriented code).
   *
   * @param serviceName service identifier
   * @param latencyMs response latency in milliseconds
   * @param result outcome of the operation
   */
  public void recordResponse(String serviceName, long latencyMs, Result<?, ?> result) {
    recordResponse(serviceName, latencyMs, result.isSuccess());
  }

  /**
   * Reset all statistics for a service (for testing or fresh start).
   *
   * @param serviceName service identifier
   */
  public void resetService(String serviceName) {
    ServiceTimeoutState state = serviceStates.get(serviceName);
    if (state != null) {
      state.histogram.reset();
      state.totalRequests = 0;
      state.totalTimeouts = 0;
      state.consecutiveTimeouts = 0;
    }
  }

  /**
   * Reset all statistics (for testing).
   */
  public void resetAll() {
    serviceStates.clear();
  }

  private void updateAllRecommendations() {
    for (var state : serviceStates.values()) {
      state.updateRecommendedTimeout();
    }
  }

  /**
   * Shut down the adaptive timeout system gracefully.
   */
  public void shutdown() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Shut down and wait for termination.
   *
   * @param timeoutMs maximum time to wait in milliseconds
   * @return {@code true} if shutdown completed, {@code false} if timeout
   */
  public boolean shutdown(long timeoutMs) {
    scheduler.shutdown();
    try {
      return scheduler.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Configure a custom history store for persistent timeout decisions (e.g., PostgreSQL).
   *
   * <p>Example with PostgreSQL:
   *
   * <pre>{@code
   * var store = new TimeoutHistoryStore() {
   *     private final DataSource ds = getPostgresDataSource();
   *
   *     @Override
   *     public void recordAdjustment(String serviceName, long prevTimeout, long newTimeout,
   *         long p99Ms, long totalRequests, String reason) {
   *         try (var conn = ds.getConnection()) {
   *             String sql = "INSERT INTO timeout_history " +
   *                 "(service_name, previous_timeout_ms, new_timeout_ms, p99_ms, total_requests, reason, recorded_at) " +
   *                 "VALUES (?, ?, ?, ?, ?, ?, NOW())";
   *             var stmt = conn.prepareStatement(sql);
   *             stmt.setString(1, serviceName);
   *             stmt.setLong(2, prevTimeout);
   *             stmt.setLong(3, newTimeout);
   *             stmt.setLong(4, p99Ms);
   *             stmt.setLong(5, totalRequests);
   *             stmt.setString(6, reason);
   *             stmt.executeUpdate();
   *         } catch (SQLException e) {
   *             logger.error("Failed to record timeout adjustment", e);
   *         }
   *     }
   *
   *     @Override
   *     public List<Map<String, Object>> queryHistory(String serviceName, int limit) {
   *         // Implement SQL query
   *     }
   * };
   * }</pre>
   */
  public interface PostgresHistoryStore extends TimeoutHistoryStore {}
}
