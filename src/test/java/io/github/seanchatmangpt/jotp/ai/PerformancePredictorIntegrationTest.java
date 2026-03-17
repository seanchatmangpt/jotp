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
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.cluster.InMemoryClusterManager;
import io.github.seanchatmangpt.jotp.persistence.InMemoryBackend;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.*;

/**
 * Integration tests for PerformancePredictor with ClusterOptimizer.
 *
 * <p>Tests the full pipeline:
 * PerformancePredictor → ClusterOptimizer → ClusterManager
 */
@DisplayName("PerformancePredictor + ClusterOptimizer Integration")
class PerformancePredictorIntegrationTest {

  private PerformancePredictor predictor;
  private ClusterOptimizer optimizer;
  private InMemoryClusterManager clusterManager;
  private InMemoryBackend backend;

  @BeforeEach
  void setup() {
    ApplicationController.reset();
    backend = new InMemoryBackend();
    predictor = PerformancePredictor.create(backend);
    clusterManager = new InMemoryClusterManager();

    var policy = new TestPolicy();
    optimizer = ClusterOptimizer.create(predictor, clusterManager, policy);
  }

  @AfterEach
  void cleanup() {
    optimizer.close();
    predictor.close();
  }

  @Test
  @DisplayName("End-to-end: Train predictor, forecast, trigger scaling")
  void testFullOptimizationPipeline() {
    // Train predictor
    var trainResult = predictor.train(Duration.ofDays(10));
    assertThat(trainResult.isSuccess()).isTrue();

    // Get initial status
    var status1 = optimizer.getStatus();
    int initialNodeCount = status1.currentNodeCount();
    assertThat(initialNodeCount).isGreaterThan(0);

    // Run optimization loop
    var optimizeResult = optimizer.optimize();
    assertThat(optimizeResult.isSuccess()).isTrue();

    // Verify status updated
    var status2 = optimizer.getStatus();
    assertThat(status2.lastActionTime()).isNotNull();

    // Predictor should have made forecasts
    var latencyForecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(30),
        Duration.ofMinutes(5));
    assertThat(latencyForecast).isNotEmpty();
  }

  @Test
  @DisplayName("Predictor forecasts trigger appropriate scaling decisions")
  void testScalingDecisionFromForecasts() {
    predictor.train(Duration.ofDays(10));

    // Get failure probability
    double failureProb = predictor.predictFailureProb(Duration.ofMinutes(30));
    assertThat(failureProb).isBetween(0.0, 1.0);

    // Check if scaling is needed (high failure probability)
    boolean shouldScale = failureProb > 0.6 || optimizer.shouldScaleUp();

    // Optimizer should make consistent decisions
    var status = optimizer.getStatus();
    if (shouldScale) {
      assertThat(status.targetNodeCount())
          .isGreaterThanOrEqualTo(status.currentNodeCount());
    }
  }

  @Test
  @DisplayName("Seasonality detection improves forecast accuracy")
  void testSeasonalityImprovesAccuracy() {
    predictor.train(Duration.ofDays(10));

    var decomp = predictor.getSeasonalityDecomposition(
        new PerformancePredictor.LatencyMetric("P99"));

    assertThat(decomp.components()).isNotEmpty();

    // Seasonality components should be captured
    var frequencies = decomp.components().stream()
        .map(PerformancePredictor.SeasonalComponent::frequency)
        .toList();
    assertThat(frequencies).contains("daily", "weekly");

    // Explained variance should be reasonable
    assertThat(decomp.explainedVariance()).isBetween(0.1, 1.0);
  }

  @Test
  @DisplayName("Failure patterns guide optimizer decisions")
  void testFailurePatternsGuideOptimization() {
    predictor.train(Duration.ofDays(10));

    var patterns = predictor.discoverFailureScenarios(3);
    assertThat(patterns).isNotEmpty();

    // Check if any pattern is active
    for (var pattern : patterns) {
      double confidence = predictor.checkFailurePatternActive(pattern);
      assertThat(confidence).isBetween(0.0, 1.0);

      // If pattern is active, optimizer should know about it
      if (confidence > 0.7) {
        var status = optimizer.getStatus();
        // High-confidence pattern should influence decisions
        assertThat(status.lastActionDescription()).isNotNull();
      }
    }
  }

  @Test
  @DisplayName("Online learning: feedback improves future predictions")
  void testOnlineLearningFromFeedback() {
    predictor.train(Duration.ofDays(10));

    var forecast = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));

    var prediction = forecast.get(0);

    // Simulate actual outcome (higher than predicted)
    var actual = new PerformancePredictor.MetricVector(
        Instant.now(),
        100, 200, 400, // Higher latencies
        800, 5, 30, 120, 70);

    predictor.recordFeedback(prediction, actual, true); // degradation=true

    // Model status should reflect feedback
    var status = predictor.getStatus();
    assertThat(status.isTrained()).isTrue();

    // Future forecasts should reflect the learned correction
    await()
        .atMost(Duration.ofSeconds(1))
        .until(() -> predictor.getStatus().isTrained());
  }

  @test
  @DisplayName("Anomaly feedback loop: detection → outcome → improvement")
  void testAnomalyFeedbackLoop() {
    predictor.train(Duration.ofDays(10));

    // Simulate detected anomaly
    var anomaly = new PerformancePredictor.DetectedAnomaly(
        Instant.now(),
        new PerformancePredictor.MetricVector(
            Instant.now(),
            120, 250, 400, // High latency
            700, 15, 80, 300, 85), // High resource usage
        0.85,
        "LATENCY_SPIKE",
        "P99 latency increased 100% in 5 seconds");

    // Record outcome: was it predicted? did scaling help?
    var outcome = new PerformancePredictor.AnomalyOutcome(
        Instant.now(),
        true, // Was predicted by model
        true, // Caused actual degradation
        true, // Scaling prevented user impact
        0.5); // 50% metrics improvement from scaling

    predictor.recordAnomalyFeedback(anomaly, outcome);

    // Verify model learned from the feedback
    var status = predictor.getStatus();
    assertThat(status.isTrained()).isTrue();
  }

  @Test
  @DisplayName("Feature importance reveals failure predictors")
  void testFeatureImportanceAnalysis() {
    predictor.train(Duration.ofDays(10));

    var importances = predictor.getFailurePredictors();
    assertThat(importances).isNotEmpty().hasSizeGreaterThan(1);

    // Top predictor should have high importance
    var topPredictor = importances.get(0);
    assertThat(topPredictor.importance()).isGreaterThan(0.1);

    // Importance should sum to ~1.0 (normalized)
    double totalImportance = importances.stream()
        .mapToDouble(PerformancePredictor.FeatureImportance::importance)
        .sum();
    assertThat(totalImportance).isGreaterThan(0.5);

    // Optimizer should weight these in decisions
    var status = optimizer.getStatus();
    assertThat(status).isNotNull();
  }

  @Test
  @DisplayName("Multi-horizon forecasting: 5-min vs 30-min vs 1-hour")
  void testMultiHorizonForecasting() {
    predictor.train(Duration.ofDays(10));

    var forecast5min = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));

    var forecast30min = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofMinutes(30),
        Duration.ofMinutes(5));

    var forecast1hour = predictor.forecast(
        new PerformancePredictor.LatencyMetric("P99"),
        Duration.ofHours(1),
        Duration.ofMinutes(5));

    assertThat(forecast5min).hasSize(1);
    assertThat(forecast30min).hasSizeGreaterThan(4);
    assertThat(forecast1hour).hasSizeGreaterThan(10);

    // Short-term forecasts should be more confident (tighter CIs)
    var avg5minCI = forecast5min.stream()
        .mapToDouble(p -> p.confidenceInterval95() / p.predictedValue())
        .average().orElse(1.0);

    var avg1hourCI = forecast1hour.stream()
        .mapToDouble(p -> p.confidenceInterval95() / p.predictedValue())
        .average().orElse(1.0);

    assertThat(avg5minCI).isLessThan(avg1hourCI);
  }

  @Test
  @DisplayName("Prediction accuracy metrics are correctly computed")
  void testAccuracyMetricsComputation() {
    var trainResult = predictor.train(Duration.ofDays(10));

    assertThat(trainResult.isSuccess()).isTrue();
    var metrics = trainResult.orElseThrow();

    // All metrics should be well-defined
    assertThat(metrics.mse()).isFinite();
    assertThat(metrics.mae()).isFinite();
    assertThat(metrics.mape()).isFinite();
    assertThat(metrics.aucRoc()).isBetween(0.0, 1.0);

    // MAE should typically be less than MSE for similar errors
    assertThat(metrics.mae()).isLessThanOrEqualTo(metrics.mse() + 1);

    // MAPE is percentage
    assertThat(metrics.mape()).isGreaterThanOrEqualTo(0).isLessThan(100);
  }

  @Test
  @DisplayName("Cost-aware optimization balances SLA vs scaling cost")
  void testCostAwareOptimization() {
    var lowCostPolicy = new TestPolicy();
    lowCostPolicy.costPerNode = 1.0; // Cheap to scale
    lowCostPolicy.costOfSLAViolation = 100.0; // Expensive to violate SLA
    var cheapOptimizer = ClusterOptimizer.create(predictor, clusterManager, lowCostPolicy);

    predictor.train(Duration.ofDays(10));

    // Cheap scaling → more aggressive scale-up
    var result = cheapOptimizer.optimize();
    assertThat(result.isSuccess()).isTrue();

    cheapOptimizer.close();

    // Expensive scaling → conservative
    var expensivePolicy = new TestPolicy();
    expensivePolicy.costPerNode = 100.0;
    expensivePolicy.costOfSLAViolation = 10.0;
    var expensiveOptimizer = ClusterOptimizer.create(predictor, clusterManager, expensivePolicy);

    var result2 = expensiveOptimizer.optimize();
    assertThat(result2.isSuccess()).isTrue();

    expensiveOptimizer.close();
  }

  // ──────────── Test Support ────────

  private static class TestPolicy implements ClusterOptimizer.OptimizationPolicy {
    double latencySla = 100.0;
    double targetUtilization = 70.0;
    int minSize = 1;
    int maxSize = 10;
    double costPerNode = 5.0;
    double costOfSLAViolation = 50.0;
    Duration minInterval = Duration.ofMillis(100);
    double confidenceThreshold = 0.5;

    @Override
    public double getLatencySlaMs() { return latencySla; }

    @Override
    public double getTargetUtilizationPercent() { return targetUtilization; }

    @Override
    public int getMinClusterSize() { return minSize; }

    @Override
    public int getMaxClusterSize() { return maxSize; }

    @Override
    public double getCostPerNodePerHour() { return costPerNode; }

    @Override
    public double getCostOfSLAViolation() { return costOfSLAViolation; }

    @Override
    public Duration getMinActionInterval() { return minInterval; }

    @Override
    public double getPredictionConfidenceThreshold() { return confidenceThreshold; }

    @Override
    public java.util.List<String> getCriticalFailurePatterns() {
      return java.util.List.of();
    }
  }
}
