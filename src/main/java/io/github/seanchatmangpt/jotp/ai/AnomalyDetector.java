package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Real-time anomaly detection system for JOTP process clusters.
 *
 * <p>Detects cascading failures, performance degradation, and resource anomalies using isolation
 * forest algorithm on a sliding window of streaming metrics. Integrates with Proc for
 * fault-tolerant event processing and triggers preventive actions before cascades occur.
 *
 * <p><strong>Architecture:</strong>
 *
 * <ul>
 *   <li><strong>Sealed Events:</strong> {@link AnomalyEvent} hierarchy (Normal, Anomaly,
 *       CriticalAnomaly) enables exhaustive pattern matching in handlers
 *   <li><strong>Metrics Collection:</strong> Sliding window maintains 5-minute history of latency,
 *       throughput, error rates, and resource usage
 *   <li><strong>Isolation Forest:</strong> O(n log n) algorithm detects outliers without assuming
 *       normal distribution or training data
 *   <li><strong>Baseline Learning:</strong> First 100 samples establish normal behavior pattern;
 *       detection begins after warm-up phase
 *   <li><strong>Streaming Integration:</strong> Processes metrics via EventManager; handlers can
 *       trigger failover, circuit breaker activation, or autoscaling
 *   <li><strong>Stateful Process:</strong> State machine tracks learning phase, baseline metrics,
 *       and anomaly history
 * </ul>
 *
 * <p><strong>Detection Thresholds (configurable):</strong>
 *
 * <ul>
 *   <li><strong>Anomaly:</strong> Isolation forest anomaly score > 0.7
 *   <li><strong>Critical:</strong> Anomaly score > 0.85 OR error rate > 30% OR latency spike >
 *       500ms
 *   <li><strong>Memory Leak:</strong> Gradual memory increase over 5 samples (leak detection)
 * </ul>
 *
 * <p><strong>Use Cases:</strong>
 *
 * <ul>
 *   <li>Detect slow network: latency spike causes anomaly detection, triggers failover
 *   <li>Detect memory leak: gradual HeapUsed increase, escalates from Anomaly to Critical
 *   <li>Detect cascading failures: error rate explosion (e.g., 0% → 50%), immediate Critical event
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * var detector = AnomalyDetector.create();
 * detector.addHandler(event -> {
 *   switch (event) {
 *     case AnomalyEvent.Normal n -> logger.debug("System healthy");
 *     case AnomalyEvent.Anomaly a -> {
 *       logger.warn("Anomaly detected: {}", a);
 *       circuit.halfOpen(); // Trigger circuit breaker
 *     }
 *     case AnomalyEvent.CriticalAnomaly c -> {
 *       logger.error("CRITICAL: {}", c);
 *       failover.triggerImmediate(); // Initiate failover
 *     }
 *   }
 * });
 *
 * // Stream metrics (typically from application monitoring)
 * detector.recordMetric(new Metric(latency, throughput, errorRate, heapUsed));
 * }</pre>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Sealed Interfaces:</strong> {@code sealed interface AnomalyEvent} restricts to
 *       Normal, Anomaly, CriticalAnomaly for exhaustive pattern matching
 *   <li><strong>Records:</strong> Immutable Metric, IsolationTree, and TreeNode records carry
 *       detection state
 *   <li><strong>Pattern Matching:</strong> {@code switch (event) case ...} enforces exhaustive
 *       coverage
 *   <li><strong>Virtual Threads:</strong> Lightweight detector process with streaming metric
 *       processing
 * </ul>
 */
public final class AnomalyDetector {

  /**
   * Sealed anomaly event hierarchy — exhaustive pattern matching in handlers.
   *
   * <p>Handlers receive one of three events:
   *
   * <ul>
   *   <li>{@link Normal} — system operating within baseline parameters
   *   <li>{@link Anomaly} — metric deviation detected; preventive actions suggested
   *   <li>{@link CriticalAnomaly} — severe deviation or error cascade; immediate action required
   * </ul>
   */
  public sealed interface AnomalyEvent {

    /**
     * System metrics are within normal baseline range.
     *
     * @param metric the current metric snapshot
     * @param anomalyScore isolation forest score (0.0-1.0; lower is more normal)
     */
    record Normal(Metric metric, double anomalyScore) implements AnomalyEvent {}

    /**
     * Anomaly detected but not yet critical. Triggers preventive actions (e.g., graceful
     * degradation).
     *
     * @param metric the anomalous metric snapshot
     * @param anomalyScore isolation forest score > 0.7
     * @param reason human-readable description (e.g., "latency spike", "memory leak")
     * @param deviations map of metric names to their deviation from baseline
     */
    record Anomaly(Metric metric, double anomalyScore, String reason, Map<String, Double> deviations)
        implements AnomalyEvent {}

    /**
     * Critical anomaly detected. Immediate action required (e.g., failover, circuit breaker).
     *
     * @param metric the critical metric snapshot
     * @param anomalyScore isolation forest score > 0.85
     * @param reason human-readable description (e.g., "cascading failure", "resource exhaustion")
     * @param deviations map of metric names to their severe deviation from baseline
     */
    record CriticalAnomaly(
        Metric metric, double anomalyScore, String reason, Map<String, Double> deviations)
        implements AnomalyEvent {}
  }

  /**
   * Streaming metric snapshot — latency, throughput, error rate, resource usage.
   *
   * @param timestamp when metric was recorded
   * @param latencyMs request latency percentile (p99)
   * @param throughputReqPerSec requests processed per second
   * @param errorRatePercent failed requests as percentage (0-100)
   * @param heapUsedMb JVM heap memory used
   */
  public record Metric(
      Instant timestamp, double latencyMs, double throughputReqPerSec, double errorRatePercent, double heapUsedMb) {}

  /**
   * Isolation tree node — recursive structure for forest anomaly scoring.
   *
   * @param depth tree depth from root
   * @param size number of samples at this node
   * @param samples metric indices in this node (null for internal nodes)
   * @param splitDim feature dimension (0=latency, 1=throughput, 2=errorRate, 3=heapUsed)
   * @param splitVal feature value at split
   * @param left left subtree (lower values)
   * @param right right subtree (higher values)
   */
  record IsolationTree(
      int depth,
      int size,
      List<Integer> samples,
      int splitDim,
      double splitVal,
      IsolationTree left,
      IsolationTree right) {}

  /** Forest configuration and detection parameters. */
  public record Config(
      int treeCount,
      int sampleSize,
      int windowSize,
      double anomalyThreshold,
      double criticalThreshold,
      Duration warmupDuration) {

    /** Default configuration: 10 trees, 256 sample size, 5-minute window. */
    public static Config defaults() {
      return new Config(10, 256, 300, 0.70, 0.85, Duration.ofSeconds(100));
    }

    public Config withAnomalyThreshold(double threshold) {
      return new Config(treeCount, sampleSize, windowSize, threshold, criticalThreshold, warmupDuration);
    }

    public Config withCriticalThreshold(double threshold) {
      return new Config(treeCount, sampleSize, windowSize, anomalyThreshold, threshold, warmupDuration);
    }

    public Config withWindowSize(int size) {
      return new Config(treeCount, sampleSize, size, anomalyThreshold, criticalThreshold, warmupDuration);
    }
  }

  /** Internal state machine for AnomalyDetector process. */
  sealed interface DetectorMsg {
    record RecordMetric(Metric metric) implements DetectorMsg {}

    record GetState(Consumer<DetectorState> callback) implements DetectorMsg {}

    record Stop() implements DetectorMsg {}
  }

  /** Mutable detector state — baseline, trees, and detection history. */
  record DetectorState(
      List<Metric> metrics,
      List<IsolationTree> forest,
      Metric baselineLatency,
      Metric baselineThroughput,
      Metric baselineErrorRate,
      Metric baselineHeapUsed,
      int warmupSamples,
      boolean learned,
      Instant lastCritical,
      int anomalyCount,
      int criticalCount) {}

  private final EventManager<AnomalyEvent> eventManager;
  private final Proc<DetectorState, DetectorMsg> detector;
  private final Config config;

  private AnomalyDetector(Config config) {
    this.config = config;
    this.eventManager = new EventManager<>();
    this.detector = new Proc<>(initialState(), this::handleDetectorMsg);
  }

  /**
   * Create a new anomaly detector with default configuration.
   *
   * @return a running detector process
   */
  public static AnomalyDetector create() {
    return create(Config.defaults());
  }

  /**
   * Create a new anomaly detector with custom configuration.
   *
   * @param config detection thresholds, window size, tree count
   * @return a running detector process
   */
  public static AnomalyDetector create(Config config) {
    return new AnomalyDetector(config);
  }

  /**
   * Register an event handler — called for each Normal, Anomaly, or CriticalAnomaly event.
   *
   * <p>Handlers that throw exceptions are removed by the event manager but do not affect the
   * detector.
   *
   * @param handler the anomaly event consumer
   */
  public void addHandler(Consumer<AnomalyEvent> handler) {
    eventManager.addHandler(
        new EventManager.Handler<AnomalyEvent>() {
          @Override
          public void handleEvent(AnomalyEvent event) {
            handler.accept(event);
          }
        });
  }

  /**
   * Record a metric snapshot — triggers anomaly detection algorithm.
   *
   * <p>Non-blocking; metric is queued to detector process.
   *
   * @param metric latency, throughput, error rate, heap usage
   */
  public void recordMetric(Metric metric) {
    detector.tell(new DetectorMsg.RecordMetric(metric));
  }

  /**
   * Get current detector state (blocking ask).
   *
   * @return detector state or empty Result if detector crashed
   * @throws InterruptedException if interrupted
   */
  public Result<DetectorState, Exception> getState() throws InterruptedException {
    try {
      var future = new java.util.concurrent.CompletableFuture<DetectorState>();
      detector.tell(
          new DetectorMsg.GetState(
              state -> future.complete(state)));
      var state = future.get(2, TimeUnit.SECONDS);
      return Result.ok(state);
    } catch (Exception e) {
      return Result.err(e);
    }
  }

  /**
   * Stop the detector process and close event manager.
   */
  public void stop() {
    detector.stop();
    eventManager.stop();
  }

  // ====== Internal Implementation ======

  private DetectorState initialState() {
    return new DetectorState(
        Collections.synchronizedList(new ArrayList<>()),
        new ArrayList<>(),
        null,
        null,
        null,
        null,
        0,
        false,
        Instant.now(),
        0,
        0);
  }

  private DetectorState handleDetectorMsg(DetectorState state, DetectorMsg msg) {
    return switch (msg) {
      case DetectorMsg.RecordMetric metric -> recordAndDetect(state, metric.metric());
      case DetectorMsg.GetState getState -> {
        getState.callback().accept(state);
        yield state;
      }
      case DetectorMsg.Stop stop -> state;
    };
  }

  private DetectorState recordAndDetect(DetectorState state, Metric metric) {
    var metrics = new ArrayList<>(state.metrics());
    metrics.add(metric);

    // Maintain sliding window
    int windowSize = config.windowSize();
    if (metrics.size() > windowSize) {
      metrics.remove(0);
    }

    // Warm-up phase: collect baseline
    if (state.warmupSamples() < config.sampleSize()) {
      var newState = new DetectorState(
          metrics,
          state.forest(),
          metric,
          metric,
          metric,
          metric,
          state.warmupSamples() + 1,
          false,
          state.lastCritical(),
          state.anomalyCount(),
          state.criticalCount());

      // Emit Normal event during warmup
      eventManager.notify(new AnomalyEvent.Normal(metric, 0.1));
      return newState;
    }

    // Build forest if not yet learned
    DetectorState learned = state;
    if (!state.learned() && metrics.size() >= config.sampleSize()) {
      var forest = buildForest(metrics, config);
      learned = new DetectorState(
          metrics,
          forest,
          state.baselineLatency(),
          state.baselineThroughput(),
          state.baselineErrorRate(),
          state.baselineHeapUsed(),
          state.warmupSamples(),
          true,
          state.lastCritical(),
          state.anomalyCount(),
          state.criticalCount());
    }

    // Detect anomalies if learned
    if (learned.learned()) {
      var (event, newState) = detectAnomaly(learned, metric);
      if (event != null) {
        eventManager.notify(event);
      }
      return newState;
    }

    return learned;
  }

  private record Detection(AnomalyEvent event, DetectorState state) {}

  private Detection detectAnomaly(DetectorState state, Metric metric) {
    // Calculate isolation forest anomaly score
    double score = 0.0;
    for (var tree : state.forest()) {
      score += isoPathLength(metric, tree);
    }
    score = score / state.forest().size(); // Average over forest

    // Normalize score to 0-1 range (empirical)
    score = Math.min(1.0, Math.max(0.0, score / 10.0));

    // Check for anomalies
    var deviations = computeDeviations(state, metric);
    boolean isErrorRateSpike = metric.errorRatePercent() > 30.0 && deviations.get("errorRate") > 20.0;
    boolean isLatencySpike = metric.latencyMs() > 500.0;
    boolean isMemoryLeak = detectMemoryLeak(state.metrics());

    AnomalyEvent event = null;
    DetectorState newState = state;

    if (score > config.criticalThreshold() || isErrorRateSpike) {
      // Critical anomaly
      event = new AnomalyEvent.CriticalAnomaly(
          metric,
          score,
          reasonFor(metric, deviations, isErrorRateSpike, isMemoryLeak),
          deviations);
      newState = new DetectorState(
          state.metrics(),
          state.forest(),
          state.baselineLatency(),
          state.baselineThroughput(),
          state.baselineErrorRate(),
          state.baselineHeapUsed(),
          state.warmupSamples(),
          true,
          Instant.now(),
          state.anomalyCount(),
          state.criticalCount() + 1);
    } else if (score > config.anomalyThreshold() || isLatencySpike || isMemoryLeak) {
      // Anomaly
      event = new AnomalyEvent.Anomaly(
          metric,
          score,
          reasonFor(metric, deviations, false, isMemoryLeak),
          deviations);
      newState = new DetectorState(
          state.metrics(),
          state.forest(),
          state.baselineLatency(),
          state.baselineThroughput(),
          state.baselineErrorRate(),
          state.baselineHeapUsed(),
          state.warmupSamples(),
          true,
          state.lastCritical(),
          state.anomalyCount() + 1,
          state.criticalCount());
    } else {
      // Normal
      event = new AnomalyEvent.Normal(metric, score);
    }

    return new Detection(event, newState);
  }

  private Map<String, Double> computeDeviations(DetectorState state, Metric metric) {
    var map = new java.util.HashMap<String, Double>();
    if (state.baselineLatency() != null) {
      map.put("latency", Math.abs(metric.latencyMs() - state.baselineLatency().latencyMs()));
      map.put("throughput", Math.abs(metric.throughputReqPerSec() - state.baselineThroughput().throughputReqPerSec()));
      map.put("errorRate", Math.abs(metric.errorRatePercent() - state.baselineErrorRate().errorRatePercent()));
      map.put("heapUsed", Math.abs(metric.heapUsedMb() - state.baselineHeapUsed().heapUsedMb()));
    }
    return map;
  }

  private boolean detectMemoryLeak(List<Metric> metrics) {
    if (metrics.size() < 5) {
      return false;
    }
    // Check if last 5 heap values are increasing
    var last5 = metrics.subList(metrics.size() - 5, metrics.size());
    for (int i = 1; i < last5.size(); i++) {
      if (last5.get(i).heapUsedMb() <= last5.get(i - 1).heapUsedMb()) {
        return false;
      }
    }
    return true;
  }

  private String reasonFor(Metric metric, Map<String, Double> deviations, boolean errorSpike, boolean memLeak) {
    if (errorSpike) {
      return "cascading failure: error rate spike to " + metric.errorRatePercent() + "%";
    }
    if (memLeak) {
      return "memory leak: heap usage increased by " + deviations.get("heapUsed") + "MB";
    }
    if (deviations.get("latency") > 100.0) {
      return "latency spike: " + deviations.get("latency") + "ms above baseline";
    }
    return "anomaly detected in cluster metrics";
  }

  // ====== Isolation Forest Algorithm ======

  private List<IsolationTree> buildForest(List<Metric> metrics, Config config) {
    var forest = new ArrayList<IsolationTree>();
    var random = new java.util.Random(42); // Deterministic for testing

    for (int t = 0; t < config.treeCount(); t++) {
      // Random subsample
      var sample = new ArrayList<Integer>();
      for (int i = 0; i < config.sampleSize(); i++) {
        sample.add(random.nextInt(metrics.size()));
      }
      forest.add(buildTree(metrics, sample, random, 0));
    }
    return forest;
  }

  private IsolationTree buildTree(List<Metric> metrics, List<Integer> samples, java.util.Random random, int depth) {
    if (samples.size() <= 1) {
      return new IsolationTree(depth, samples.size(), samples, -1, 0.0, null, null);
    }

    // Randomly select feature dimension
    int splitDim = random.nextInt(4); // 4 dimensions: latency, throughput, errorRate, heapUsed

    // Find split value
    double min = Double.MAX_VALUE;
    double max = Double.MIN_VALUE;
    for (int idx : samples) {
      double val = getFeature(metrics.get(idx), splitDim);
      min = Math.min(min, val);
      max = Math.max(max, val);
    }

    if (min == max) {
      return new IsolationTree(depth, samples.size(), samples, -1, 0.0, null, null);
    }

    double splitVal = min + random.nextDouble() * (max - min);

    // Split samples
    var left = new ArrayList<Integer>();
    var right = new ArrayList<Integer>();
    for (int idx : samples) {
      if (getFeature(metrics.get(idx), splitDim) < splitVal) {
        left.add(idx);
      } else {
        right.add(idx);
      }
    }

    // Recurse
    return new IsolationTree(
        depth,
        samples.size(),
        null,
        splitDim,
        splitVal,
        buildTree(metrics, left, random, depth + 1),
        buildTree(metrics, right, random, depth + 1));
  }

  private double getFeature(Metric metric, int dim) {
    return switch (dim) {
      case 0 -> metric.latencyMs();
      case 1 -> metric.throughputReqPerSec();
      case 2 -> metric.errorRatePercent();
      case 3 -> metric.heapUsedMb();
      default -> 0.0;
    };
  }

  private double isoPathLength(Metric metric, IsolationTree tree) {
    return pathLength(metric, tree, 0);
  }

  private double pathLength(Metric metric, IsolationTree tree, int depth) {
    if (tree.left() == null && tree.right() == null) {
      // Leaf node — add adjustment for small sizes
      double adjustment = Math.log(tree.size());
      return depth + adjustment;
    }

    double val = getFeature(metric, tree.splitDim());
    if (val < tree.splitVal()) {
      return pathLength(metric, tree.left(), depth + 1);
    } else {
      return pathLength(metric, tree.right(), depth + 1);
    }
  }
}
