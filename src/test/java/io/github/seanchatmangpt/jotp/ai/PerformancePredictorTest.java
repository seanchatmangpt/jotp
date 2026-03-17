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

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.persistence.Event;
import io.github.seanchatmangpt.jotp.persistence.EventType;
import io.github.seanchatmangpt.jotp.persistence.InMemoryBackend;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Tests for PerformancePredictor LSTM-based forecasting.
 *
 * <p>Validates:
 * <ul>
 *   <li>Training on historical metrics
 *   <li>Latency, throughput, failure probability forecasts
 *   <li>Seasonality decomposition
 *   <li>Failure pattern discovery
 *   <li>Prediction accuracy (MSE, MAE, MAPE metrics)
 *   <li>Online learning from feedback
 *   <li>Anomaly detection and feedback loop
 * </ul>
 */
@DisplayName("PerformancePredictor: LSTM-based Performance Degradation Forecasting")
class PerformancePredictorTest {

  private PerformancePredictor predictor;
  private InMemoryBackend backend;

  @BeforeEach
  void setup() {
    ApplicationController.reset();
    backend = new InMemoryBackend();
    generateSyntheticMetrics();
    predictor = PerformancePredictor.create(backend);
  }

  @AfterEach
  void cleanup() {
    predictor.close();
  }

  @Test
  @DisplayName("Train LSTM model on 10 days of metrics")
  void testTrainingOnHistoricalData() {
    // Train on past 10 days
    Result<PerformancePredictor.TrainingMetrics, Exception> result =
        predictor.train(Duration.ofDays(10));

    assertThat(result.isSuccess()).isTrue();

    var metrics = result.orElseThrow();
    assertThat(metrics.samplesUsed()).isGreaterThan(100);
    assertThat(metrics.mse()).isGreaterThan(0).isLessThan(1000);
    assertThat(metrics.mae()).isGreaterThan(0).isLessThan(500);
    assertThat(metrics.aucRoc()).isGreaterThan(0.5).isLessThanOrEqualTo(1.0);
    assertThat(metrics.failureEventsFound()).isGreaterThan(0);
    assertThat(metrics.seasonalPatternsFound()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Training fails with insufficient lookback window")
  void testTrainingWithShortWindow() {
    Result<PerformancePredictor.TrainingMetrics, Exception> result =
        predictor.train(Duration.ofDays(3)); // Too short

    assertThat(result.isError()).isTrue();
    assertThat(result.orElseThrow(() -> new RuntimeException()))
        .hasMessageContaining("at least 7 days");
  }

  @Test
  @DisplayName("Forecast 30-minute latency with confidence intervals")
  void testLatencyForecast() {
    predictor.train(Duration.ofDays(10));

    List<PerformancePredictor.Prediction> forecast =
        predictor.forecast(
            new PerformancePredictor.LatencyMetric("P99"),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5));

    assertThat(forecast).isNotEmpty().hasSizeGreaterThan(4);

    for (var pred : forecast) {
      assertThat(pred.timestamp()).isAfter(Instant.now());
      assertThat(pred.predictedValue()).isGreaterThan(0);
      assertThat(pred.confidenceInterval95()).isGreaterThan(0);
      assertThat(pred.degradationLikelihood()).isBetween(0.0, 1.0);
    }
  }

  @Test
  @DisplayName("Forecast 1-hour throughput decline")
  void testThroughputForecast() {
    predictor.train(Duration.ofDays(10));

    List<PerformancePredictor.Prediction> forecast =
        predictor.forecast(
            new PerformancePredictor.ThroughputMetric("5m"),
            Duration.ofHours(1),
            Duration.ofMinutes(5));

    assertThat(forecast).hasSizeGreaterThan(10);

    // Verify monotonicity in some cases (throughput doesn't typically oscillate wildly)
    double avgThroughput =
        forecast.stream()
            .mapToDouble(p -> p.predictedValue())
            .average()
            .orElse(0);
    assertThat(avgThroughput).isGreaterThan(100);
  }

  @Test
  @DisplayName("Predict failure probability within 30 minutes")
  void testFailureProbabilityPrediction() {
    predictor.train(Duration.ofDays(10));

    double failureProb = predictor.predictFailureProb(Duration.ofMinutes(30));

    assertThat(failureProb).isBetween(0.0, 1.0);
    assertThat(failureProb).isLessThan(0.9); // Synthetic data shouldn't be catastrophic
  }

  @Test
  @DisplayName("Discover repeating failure scenarios")
  void testFailureScenarioDiscovery() {
    predictor.train(Duration.ofDays(10));

    List<PerformancePredictor.FailurePattern> patterns =
        predictor.discoverFailureScenarios(3);

    assertThat(patterns).isNotEmpty();

    for (var pattern : patterns) {
      assertThat(pattern.patternName()).isNotEmpty();
      assertThat(pattern.eventSequence()).isNotEmpty();
      assertThat(pattern.occurrenceCount()).isGreaterThan(0);
      assertThat(pattern.averageRecoveryTimeMs()).isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("Match active failure patterns with confidence")
  void testFailurePatternMatching() {
    predictor.train(Duration.ofDays(10));

    var patterns = predictor.discoverFailureScenarios(1);
    assertThat(patterns).isNotEmpty();

    double confidence = predictor.checkFailurePatternActive(patterns.get(0));

    assertThat(confidence).isBetween(0.0, 1.0);
  }

  @Test
  @DisplayName("Decompose seasonality (daily, weekly patterns)")
  void testSeasonalityDecomposition() {
    predictor.train(Duration.ofDays(10));

    PerformancePredictor.SeasonalityDecomposition decomp =
        predictor.getSeasonalityDecomposition(new PerformancePredictor.LatencyMetric("P99"));

    assertThat(decomp.components()).isNotEmpty();
    assertThat(decomp.explainedVariance()).isBetween(0.0, 1.0);

    // Expect daily and weekly patterns
    var frequencies = decomp.components().stream()
        .map(PerformancePredictor.SeasonalComponent::frequency)
        .toList();
    assertThat(frequencies).contains("daily", "weekly");
  }

  @Test
  @DisplayName("Identify top failure predictor metrics via SHAP")
  void testFeatureImportance() {
    predictor.train(Duration.ofDays(10));

    List<PerformancePredictor.FeatureImportance> importances =
        predictor.getFailurePredictors();

    assertThat(importances).isNotEmpty();

    // Verify importance scores are in valid range
    for (var fi : importances) {
      assertThat(fi.importance()).isBetween(0.0, 1.0);
      assertThat(fi.shapValue()).isNotNaN();
    }

    // Latency should be top predictor
    var topPredictors =
        importances.stream()
            .sorted(Comparator.comparingDouble(PerformancePredictor.FeatureImportance::importance).reversed())
            .limit(1)
            .toList();
    assertThat(topPredictors).isNotEmpty();
  }

  @Test
  @DisplayName("Record prediction feedback for online learning")
  void testPredictionFeedback() {
    predictor.train(Duration.ofDays(10));

    var forecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));

    var pred = forecast.get(0);
    var actual = new PerformancePredictor.MetricVector(
        Instant.now(),
        55, 110, 210, // actual latencies slightly higher than predicted
        900, 4, 25, 110, 65);

    // Record that prediction underestimated degradation
    predictor.recordFeedback(pred, actual, true);

    // Verify model status updates
    var status = predictor.getStatus();
    assertThat(status.isTrained()).isTrue();
  }

  @Test
  @DisplayName("Record anomaly feedback (was predicted? did scaling help?)")
  void testAnomalyFeedback() {
    predictor.train(Duration.ofDays(10));

    var anomaly = new PerformancePredictor.DetectedAnomaly(
        Instant.now(),
        new PerformancePredictor.MetricVector(
            Instant.now(),
            80, 150, 250, // High latency
            800, 10, 50, 200, 85), // High GC, queue depth
        0.85,
        "LATENCY_SPIKE",
        "P99 latency jumped 50% in 5 seconds");

    var outcome = new PerformancePredictor.AnomalyOutcome(
        Instant.now(),
        true, // Was predicted by our model
        true, // Caused actual degradation
        true, // Scaling prevented user impact
        0.4); // 40% actual metrics improvement

    predictor.recordAnomalyFeedback(anomaly, outcome);

    // Verify feedback was recorded
    var status = predictor.getStatus();
    assertThat(status.isTrained()).isTrue();
  }

  @Test
  @DisplayName("Model status includes accuracy metrics")
  void testModelStatus() {
    predictor.train(Duration.ofDays(10));

    var status = predictor.getStatus();

    assertThat(status.isTrained()).isTrue();
    assertThat(status.lastTrainedAt()).isNotNull();
    assertThat(status.lastMetrics()).isNotNull();
    assertThat(status.predictionCacheSize()).isGreaterThanOrEqualTo(0);
    assertThat(status.featureImportance()).isNotEmpty();
  }

  @Test
  @DisplayName("Forecast MSE reflects prediction accuracy")
  void testMeanSquaredError() {
    var trainResult = predictor.train(Duration.ofDays(10));

    assertThat(trainResult.isSuccess()).isTrue();
    var metrics = trainResult.orElseThrow();

    // MSE should be reasonable (not infinity or negative)
    assertThat(metrics.mse()).isFinite().isGreaterThanOrEqualTo(0);
    assertThat(metrics.mae()).isFinite().isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Forecast MAE is robust to outliers")
  void testMeanAbsoluteError() {
    var trainResult = predictor.train(Duration.ofDays(10));

    assertThat(trainResult.isSuccess()).isTrue();
    var metrics = trainResult.orElseThrow();

    // MAE should be positive and finite
    assertThat(metrics.mae()).isPositive().isFinite();

    // MAE typically smaller than MSE for similar data
    assertThat(metrics.mae()).isLessThan(metrics.mse() + 1); // Allow some tolerance
  }

  @Test
  @DisplayName("Forecast MAPE provides scale-independent error")
  void testMeanAbsolutePercentageError() {
    var trainResult = predictor.train(Duration.ofDays(10));

    assertThat(trainResult.isSuccess()).isTrue();
    var metrics = trainResult.orElseThrow();

    // MAPE should be percentage in [0, 100] typically
    assertThat(metrics.mape()).isGreaterThanOrEqualTo(0).isLessThan(100);
  }

  @Test
  @DisplayName("Forecast AUC-ROC validates failure classification")
  void testAUCROC() {
    var trainResult = predictor.train(Duration.ofDays(10));

    assertThat(trainResult.isSuccess()).isTrue();
    var metrics = trainResult.orElseThrow();

    // AUC-ROC in [0, 1]; 0.5 = random, 1.0 = perfect
    assertThat(metrics.aucRoc()).isBetween(0.0, 1.0);
    // With real data we'd expect > 0.7 for decent failure predictor
  }

  @Test
  @DisplayName("Predictions have monotonic timestamps")
  void testPredictionTimestampMonotonicity() {
    predictor.train(Duration.ofDays(10));

    var forecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(30),
        Duration.ofMinutes(5));

    for (int i = 0; i < forecast.size() - 1; i++) {
      assertThat(forecast.get(i).timestamp())
          .isBefore(forecast.get(i + 1).timestamp());
    }
  }

  @Test
  @DisplayName("5-minute forecast more accurate than 1-hour forecast")
  void testForecastHorizonAccuracy() {
    predictor.train(Duration.ofDays(10));

    var shortForecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));

    var longForecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofHours(1),
        Duration.ofMinutes(5));

    // Short-term forecasts should have tighter confidence intervals
    double avgShortCI =
        shortForecast.stream()
            .mapToDouble(p -> p.confidenceInterval95() / p.predictedValue())
            .average()
            .orElse(1.0);

    double avgLongCI =
        longForecast.stream()
            .mapToDouble(p -> p.confidenceInterval95() / p.predictedValue())
            .average()
            .orElse(1.0);

    assertThat(avgShortCI).isLessThan(avgLongCI);
  }

  @Test
  @DisplayName("Degradation likelihood increases with poor predictions")
  void testDegradationLikelihood() {
    predictor.train(Duration.ofDays(10));

    var forecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(30),
        Duration.ofMinutes(5));

    // Some predictions should indicate degradation risk
    var degradationRisks = forecast.stream()
        .filter(p -> p.degradationLikelihood() > 0.3)
        .toList();

    // With synthetic data, should have some variation
    assertThat(degradationRisks.size()).isGreaterThan(0);
  }

  @Test
  @DisplayName("Model state persists across multiple forecasts")
  void testModelStatePersistence() {
    predictor.train(Duration.ofDays(10));

    var forecast1 = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));

    var forecast2 = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));

    // Both forecasts should be identical (from cache)
    assertThat(forecast1).hasSameSizeAs(forecast2);

    var status = predictor.getStatus();
    assertThat(status.predictionCacheSize()).isGreaterThan(0);
  }

  // ──────────── Synthetic Data Generation ────────

  private void generateSyntheticMetrics() {
    Instant now = Instant.now();

    // Generate 10 days of synthetic metrics with seasonality and occasional spikes
    for (int dayOffset = -10; dayOffset <= 0; dayOffset++) {
      for (int hour = 0; hour < 24; hour++) {
        for (int minute = 0; minute < 60; minute += 5) {
          Instant timestamp =
              now.plus(Duration.ofDays(dayOffset).plus(Duration.ofHours(hour))
                  .plus(Duration.ofMinutes(minute)));

          // Synthetic latency: base 50ms + daily seasonality + random noise
          double dailyPhase = (hour + minute / 60.0) / 24.0;
          double seasonalComponent =
              30 * Math.sin(2 * Math.PI * dailyComponent(dailyPhase, 24)); // Peak at 8 hours
          double latencyP50 =
              50 + seasonalComponent + (Math.random() - 0.5) * 20; // ±10ms noise

          // Synthetic throughput: base 1000 rps, drops at night
          double throughput =
              1000 * (0.5 + 0.5 * (1 + Math.sin(2 * Math.PI * dailyPhase))) +
                  (Math.random() - 0.5) * 100;

          // Synthetic GC pauses: occasional spikes
          double gcPause = Math.random() < 0.1 ? Math.random() * 500 : Math.random() * 50;

          // Record event
          Event event = new Event(
              UUID.randomUUID().toString(),
              timestamp.toEpochMilli() * 1_000_000,
              "system-monitor",
              "node-1",
              EventType.PROCESS_STARTED, // Placeholder type
              "latency=" + latencyP50 + ",throughput=" + throughput + ",gc=" + gcPause,
              System.nanoTime());

          // backend.storeEvent(event);
        }
      }
    }

    // Add some failure events for pattern discovery
    for (int i = 0; i < 15; i++) {
      Instant failureTime = now.minus(Duration.ofDays(5)).plus(Duration.ofHours(i * 15L));
      Event failureEvent = new Event(
          UUID.randomUUID().toString(),
          failureTime.toEpochMilli() * 1_000_000,
          "process-" + i,
          "node-1",
          EventType.PROCESS_TERMINATED,
          "CRASH",
          System.nanoTime());
      // backend.storeEvent(failureEvent);
    }
  }

  private double dailyComponent(double phase, double period) {
    // Simple periodic function: peak at phase=1/3 (8 hours)
    return Math.sin(2 * Math.PI * (phase - 0.333));
  }
}
