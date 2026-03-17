/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.persistence.Event;
import io.github.seanchatmangpt.jotp.persistence.PostgresBackend;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Machine learning-based performance degradation predictor for JOTP clusters.
 *
 * <p>This interface defines a predictive system that learns from historical performance metrics and
 * forecasts degradation before users are affected. Built on LSTM (Long Short-Term Memory) neural
 * networks trained on PostgreSQL event logs.
 *
 * <p><strong>Mapping to OTP Supervision:</strong>
 *
 * <p>In Erlang/OTP, failure is detected <em>after</em> it occurs — a process crashes, the
 * supervisor restarts it. JOTP's PerformancePredictor inverts this model: predict the crash
 * <em>before</em> it happens, triggering preventive scaling/maintenance via {@link ClusterOptimizer}
 * integration.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>{@code
 * [PostgreSQL Event Log] ──────┐
 *         ↓ (Event Stream)      │
 *   [Time-Series Aggregator]    │
 *         ↓ (Metrics Window)    │
 *   [LSTM Network Training]     │
 *         ↓ (Model)             ├─→ [PerformancePredictor]
 *   [Anomaly Feedback Loop]     │        ↓
 *         ↓                     │    [Predictions]
 *   [Pattern Recognition]       │        ↓
 *   [Seasonality Learning]      │   [ClusterOptimizer]
 *   [Failure Scenario DB]   ────┘        ↓
 *                                [Preventive Scaling]
 * }</pre>
 *
 * <p><strong>Forecast Horizons:</strong>
 *
 * <ul>
 *   <li><strong>5-minute forecast:</strong> Fine-grained latency oscillations; detect immediate
 *       load spikes
 *   <li><strong>30-minute forecast:</strong> Medium-term trend shifts; identify capacity
 *       exhaustion
 *   <li><strong>1-hour forecast:</strong> Long-term degradation patterns; predict maintenance
 *       windows
 * </ul>
 *
 * <p><strong>Metrics Predicted:</strong>
 *
 * <ul>
 *   <li><strong>Latency:</strong> P50, P95, P99 response times (milliseconds)
 *   <li><strong>Throughput:</strong> Requests per second; queue depth
 *   <li><strong>Failure Probability:</strong> Likelihood of service failure in next window
 * </ul>
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // Create predictor from PostgreSQL event log
 * var backend = new PostgresBackend("prod-db", 5432, "metrics");
 * var predictor = PerformancePredictor.create(backend);
 *
 * // Train on historical data (10 days of events)
 * predictor.train(Duration.ofDays(10));
 *
 * // Forecast next 30 minutes of latency
 * var predictions = predictor.forecast(
 *     MetricType.LATENCY,
 *     Duration.ofMinutes(30),
 *     Duration.ofMinutes(5)  // 5-min window resolution
 * );
 *
 * // Check if degradation is likely
 * if (predictions.degradationLikelihood() > 0.75) {
 *     // Trigger preventive scaling via ClusterOptimizer
 *     optimizer.scaleUp(predictions.estimatedPeakValue());
 * }
 *
 * // Learn from anomalies detected in production
 * predictor.recordAnomalyFeedback(anomaly, actualOutcome);
 * }</pre>
 *
 * <p><strong>ML Architecture:</strong>
 *
 * <ul>
 *   <li><strong>LSTM Encoder-Decoder:</strong> 2-layer LSTM with 128 hidden units; attention
 *       mechanism for sequence alignment
 *   <li><strong>Seasonality Component:</strong> Discrete Fourier Transform (DFT) for periodic
 *       patterns (daily, weekly)
 *   <li><strong>Anomaly Detector:</strong> Isolation Forest on residuals to identify failure
 *       scenarios
 *   <li><strong>Ensemble Voting:</strong> 3 models (LSTM, AutoRegressive, Gaussian Process)
 *       combined via weighted voting
 * </ul>
 *
 * <p><strong>Training Data Requirements:</strong>
 *
 * <ul>
 *   <li>Minimum 7 days of metrics for seasonality detection
 *   <li>Minimum 50 failure events for robust failure prediction
 *   <li>Time-series window: 5-minute aggregates (flexible)
 *   <li>Features: latency, throughput, error rate, garbage collection pauses, CPU usage
 * </ul>
 *
 * <p><strong>Accuracy Metrics:</strong>
 *
 * <ul>
 *   <li><strong>MSE (Mean Squared Error):</strong> Penalizes large forecast errors
 *   <li><strong>MAE (Mean Absolute Error):</strong> Robust to outliers
 *   <li><strong>MAPE (Mean Absolute Percentage Error):</strong> Normalized scale-independent error
 *   <li><strong>AUC-ROC:</strong> Failure classification threshold optimization
 * </ul>
 *
 * <p><strong>Integration with ClusterOptimizer:</strong>
 *
 * <p>Predictions feed directly into {@link ClusterOptimizer} for proactive decisions:
 *
 * <ul>
 *   <li>Scale up if predicted latency exceeds SLA threshold
 *   <li>Rebalance partitions if predicted throughput drops
 *   <li>Trigger maintenance if predicted failure probability > 0.9
 *   <li>Dynamically adjust timeout values based on latency forecast
 * </ul>
 *
 * <p><strong>Pattern Recognition Features:</strong>
 *
 * <ul>
 *   <li><strong>Repeating Failure Scenarios:</strong> Stores temporal patterns of past failures
 *       (e.g., "GC pause followed by queue backlog"); recognizes when scenario is reoccurring
 *   <li><strong>Cascade Detection:</strong> Identifies chains of failures (e.g., node A crashes →
 *       causes B to overload → causes C to fail)
 *   <li><strong>Threshold Crossing Prediction:</strong> Forecasts when metric will cross SLA
 *       boundary
 * </ul>
 *
 * <p><strong>Seasonality Handling:</strong>
 *
 * <ul>
 *   <li><strong>Daily Patterns:</strong> Peak hours (morning, evening) vs. off-peak
 *   <li><strong>Weekly Patterns:</strong> Weekend vs. weekday traffic
 *   <li><strong>Event-Based Seasonality:</strong> Deploy spikes, batch job windows, backup
 *       maintenance
 *   <li><strong>Adaptive Windows:</strong> Automatically detects seasonal boundaries and adjusts
 *       forecasts
 * </ul>
 *
 * <p><strong>Anomaly Feedback Loop:</strong>
 *
 * <p>When runtime anomaly detection triggers (see {@link AnomalyDetector}), record the outcome to
 * improve future predictions:
 *
 * <pre>{@code
 * // Anomaly was detected in production
 * var detected = detector.check(metrics);
 * if (detected.isPresent()) {
 *     // Wait for outcome (actual service impact)
 *     var actualImpact = waitForOutcome(detected.get());
 *
 *     // Feed back: was prediction correct? did optimizer prevent it?
 *     predictor.recordAnomalyFeedback(
 *         detected.get(),
 *         new Outcome(wasPredicted, wasActuallyHarmful, scaleUpHelped)
 *     );
 * }
 * }</pre>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Records:</strong> Time-series data points, predictions, metrics snapshots
 *   <li><strong>Sealed Interfaces:</strong> {@code Prediction}, {@code MetricType} (exhaustive
 *       pattern matching)
 *   <li><strong>Pattern Matching:</strong> Route metric types to appropriate LSTM models
 *   <li><strong>Virtual Threads:</strong> Training and forecasting execute on virtual threads;
 *       background async model updates never block request path
 * </ul>
 */
public interface PerformancePredictor {

  /**
   * Create a new performance predictor backed by PostgreSQL event log.
   *
   * @param backend PostgreSQL backend for event sourcing
   * @return new predictor instance
   */
  static PerformancePredictor create(PostgresBackend backend) {
    return new LSTMPerformancePredictorImpl(backend);
  }

  /**
   * Train the LSTM model on historical metrics.
   *
   * <p>This operation is CPU-intensive and should be run during off-peak hours or on a background
   * thread. Uses events from PostgreSQL within the specified lookback window.
   *
   * <p>Training includes:
   * <ul>
   *   <li>Aggregating events into 5-minute time-series buckets
   *   <li>Normalizing metrics (z-score normalization)
   *   <li>Extracting seasonal components via DFT
   *   <li>Training 3-model ensemble (LSTM + AutoRegressive + Gaussian Process)
   *   <li>Learning anomaly patterns from failure events
   *   <li>Validating on holdout test set
   * </ul>
   *
   * @param lookbackWindow how far back to read events (e.g., {@code Duration.ofDays(10)})
   * @return {@code Ok(metrics)} on success with accuracy snapshot, {@code Err(exception)} on
   *     failure
   * @throws IllegalArgumentException if lookbackWindow is less than 7 days
   */
  Result<TrainingMetrics, Exception> train(Duration lookbackWindow);

  /**
   * Forecast a metric over a future time window.
   *
   * <p>Runs the trained LSTM model to predict values at multiple forecast horizons.
   *
   * @param metric metric type to forecast (LATENCY, THROUGHPUT, FAILURE_PROBABILITY)
   * @param horizon how far ahead to forecast (5 min, 30 min, 1 hour)
   * @param resolution time-series bucket size (typically 5 minutes)
   * @return sequence of predictions with confidence intervals and degradation likelihood
   * @throws IllegalStateException if model has not been trained
   */
  List<Prediction> forecast(MetricType metric, Duration horizon, Duration resolution);

  /**
   * Predict the likelihood of service failure within a time window.
   *
   * <p>Uses ensemble classification: if predicted latency > SLA AND predicted throughput drops >
   * 20%, failure probability increases. Also considers recent anomaly rate.
   *
   * @param window time window to forecast
   * @return failure probability in range [0.0, 1.0]
   */
  double predictFailureProb(Duration window);

  /**
   * Get the current training status and model quality metrics.
   *
   * @return status snapshot (trained, accuracy metrics, last update time, etc.)
   */
  ModelStatus getStatus();

  /**
   * Record observed vs. predicted outcome for online learning.
   *
   * <p>Feedback improves future predictions. Call this after a prediction window has elapsed and
   * actual outcome is known.
   *
   * @param prediction the original prediction
   * @param actual observed metric values
   * @param degradationOccurred whether actual degradation materialized
   */
  void recordFeedback(Prediction prediction, MetricVector actual, boolean degradationOccurred);

  /**
   * Record an anomaly feedback event for the online learning loop.
   *
   * <p>When runtime anomaly detection triggers and we later observe the outcome (whether it was
   * actually harmful), feed it back to improve failure prediction.
   *
   * @param anomaly the detected anomaly
   * @param outcome whether it caused actual degradation, whether scaling helped, etc.
   */
  void recordAnomalyFeedback(DetectedAnomaly anomaly, AnomalyOutcome outcome);

  /**
   * Identify repeating failure scenarios in historical data.
   *
   * <p>Scans past failures to find common patterns: e.g., "GC pause → latency spike → queue
   * overflow → failure" patterns that recur. Returns top N by frequency.
   *
   * @param topN how many scenario patterns to return
   * @return ordered list of recurring failure patterns with frequency counts
   */
  List<FailurePattern> discoverFailureScenarios(int topN);

  /**
   * Check if a repeating failure scenario is currently occurring.
   *
   * <p>Matches recent metrics against known failure patterns; returns confidence score.
   *
   * @param pattern the failure scenario to check for
   * @return confidence in range [0.0, 1.0] that this pattern is active
   */
  double checkFailurePatternActive(FailurePattern pattern);

  /**
   * Get seasonality decomposition for a metric.
   *
   * <p>Returns periodic components (daily, weekly, etc.) that explain normal metric variation.
   * Helps distinguish normal variation from true degradation.
   *
   * @param metric metric type
   * @return seasonality components with frequencies and amplitudes
   */
  SeasonalityDecomposition getSeasonalityDecomposition(MetricType metric);

  /**
   * Identify metrics that best predict failures.
   *
   * <p>Runs feature importance analysis using SHAP values or permutation importance.
   *
   * @return metrics ranked by predictive power for failure events
   */
  List<FeatureImportance> getFailurePredictors();

  /**
   * Close predictor and release resources (DB connections, thread pools).
   */
  @Override
  void close();

  // ─────────────────────────── Data Types ───────────────────────────

  /**
   * Types of metrics the predictor can forecast.
   *
   * <ul>
   *   <li><strong>LATENCY:</strong> P50/P95/P99 response times in milliseconds
   *   <li><strong>THROUGHPUT:</strong> Requests per second
   *   <li><strong>ERROR_RATE:</strong> Failed requests per second
   *   <li><strong>GC_PAUSE_MS:</strong> Stop-the-world garbage collection duration
   *   <li><strong>QUEUE_DEPTH:</strong> Pending messages in process mailboxes
   *   <li><strong>CPU_USAGE_PERCENT:</strong> JVM heap usage percentage
   * </ul>
   */
  sealed interface MetricType {
    String name();
  }

  record LatencyMetric(String percentile) implements MetricType {}

  record ThroughputMetric(String window) implements MetricType {}

  record ErrorRateMetric(String type) implements MetricType {}

  record GCPauseMetric(String gen) implements MetricType {}

  record QueueDepthMetric(String queueName) implements MetricType {}

  record CPUUsageMetric(String host) implements MetricType {}

  /**
   * A single prediction point in time.
   *
   * <p>Includes both the forecasted value and confidence intervals.
   */
  record Prediction(
      Instant timestamp,
      double predictedValue,
      double confidenceInterval95,
      double degradationLikelihood,
      boolean isAnomalous,
      String rationale) {}

  /**
   * Vector of current metrics (used for feedback).
   */
  record MetricVector(
      Instant timestamp,
      double latencyP50,
      double latencyP95,
      double latencyP99,
      double throughputRps,
      double errorRateRps,
      double gcPauseMs,
      double queueDepth,
      double cpuUsagePercent) {}

  /**
   * Detected anomaly for feedback loop.
   */
  record DetectedAnomaly(
      Instant detectedAt,
      MetricVector metrics,
      double anomalyScore,
      String anomalyType,
      String rationale) {}

  /**
   * Outcome of an anomaly (was it predicted? did scaling help?).
   */
  record AnomalyOutcome(
      Instant resolvedAt,
      boolean wasPredicted,
      boolean causedActualDegradation,
      boolean scalingPrevented,
      double actualMetricsImpact) {}

  /**
   * Training metrics snapshot.
   */
  record TrainingMetrics(
      int samplesUsed,
      double mse,
      double mae,
      double mape,
      double aucRoc,
      int failureEventsFound,
      int seasonalPatternsFound,
      long trainingDurationMs) {}

  /**
   * Model status.
   */
  record ModelStatus(
      boolean isTrained,
      Instant lastTrainedAt,
      TrainingMetrics lastMetrics,
      int predictionCacheSize,
      Map<String, Double> featureImportance) {}

  /**
   * Repeating failure scenario pattern.
   */
  record FailurePattern(
      String patternName,
      List<String> eventSequence,
      int occurrenceCount,
      double averageRecoveryTimeMs,
      List<MetricThreshold> triggeringConditions) {}

  /**
   * Threshold condition that triggers a failure pattern.
   */
  record MetricThreshold(MetricType metric, double value, String operator) {}

  /**
   * Seasonality decomposition.
   */
  record SeasonalityDecomposition(
      List<SeasonalComponent> components, double explainedVariance) {}

  /**
   * Single seasonal component (daily, weekly, etc.).
   */
  record SeasonalComponent(
      String frequency, double amplitude, List<Double> harmonics) {}

  /**
   * Feature importance for failure prediction.
   */
  record FeatureImportance(MetricType metric, double importance, double shapValue) {}
}
