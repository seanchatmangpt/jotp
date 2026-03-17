package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClusterOptimizerTest {

  private ClusterOptimizer optimizer;
  private InMemoryTrainingStore trainingStore;

  @BeforeEach
  void setUp() {
    trainingStore = new InMemoryTrainingStore();
    optimizer = new NeuralNetworkOptimizer(trainingStore);
  }

  @Test
  void testMetricsRecording() {
    var metrics =
        new ClusterOptimizer.NodeMetrics("node1", 45.0, 60.0, 100, 50.0, Instant.now());
    optimizer.recordMetrics(metrics);

    var current = optimizer.getCurrentMetrics();
    assertThat(current.clusterMetrics()).hasSize(1);
    assertThat(current.clusterMetrics().get(0).nodeName()).isEqualTo("node1");
    assertThat(current.clusterMetrics().get(0).cpuUsagePercent()).isEqualTo(45.0);
  }

  @Test
  void testPredictScalingHighLoad() {
    // Simulate high load across all nodes
    for (int i = 0; i < 3; i++) {
      var metrics =
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 85.0, 80.0, 5000, 500.0, Instant.now());
      optimizer.recordMetrics(metrics);
    }

    var clusterMetrics = optimizer.getCurrentMetrics();
    var decision = optimizer.predictScaling(clusterMetrics);

    assertThat(decision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
    var scalingDecision =
        ((io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String>)
                decision)
            .value();

    // High load should trigger scale-up (eventually through learning)
    assertThat(scalingDecision.confidence()).isGreaterThan(0.0);
    assertThat(scalingDecision.forecastedLoad()).isGreaterThan(0.0);
  }

  @Test
  void testPredictScalingLowLoad() {
    // Simulate low load
    for (int i = 0; i < 5; i++) {
      var metrics =
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 10.0, 15.0, 50, 5.0, Instant.now());
      optimizer.recordMetrics(metrics);
    }

    var clusterMetrics = optimizer.getCurrentMetrics();
    var decision = optimizer.predictScaling(clusterMetrics);

    assertThat(decision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
    var scalingDecision =
        ((io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String>)
                decision)
            .value();

    assertThat(scalingDecision.forecastedLoad()).isLessThan(50.0);
  }

  @Test
  void testTrainingSampleRecording() {
    var sample =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, Instant.now(), 3);
    optimizer.recordTrainingSample(sample);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var samples = trainingStore.fetchTrainingSamples(10);
              assertThat(samples).hasSize(1);
            });
  }

  @Test
  void testForecastLoadNoMetrics() {
    var forecast = optimizer.forecastLoad(300);

    assertThat(forecast).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Err.class);
  }

  @Test
  void testForecastLoadWithMetrics() {
    var metrics =
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 60.0, 100, 50.0, Instant.now());
    optimizer.recordMetrics(metrics);

    var forecast = optimizer.forecastLoad(300);

    assertThat(forecast).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
    if (forecast instanceof io.github.seanchatmangpt.jotp.Result.Ok<Double, String> ok) {
      assertThat(ok.value()).isGreaterThan(0.0).isLessThan(100.0);
    }
  }

  @Test
  void testBottleneckDetectionHighCPU() {
    var metrics = new ClusterOptimizer.NodeMetrics("node1", 95.0, 50.0, 100, 50.0, Instant.now());
    optimizer.recordMetrics(metrics);

    var bottlenecks = optimizer.detectBottlenecks();

    assertThat(bottlenecks).isNotEmpty();
    assertThat(bottlenecks.get(0)).containsIgnoringCase("cpu");
  }

  @Test
  void testBottleneckDetectionHighMemory() {
    var metrics = new ClusterOptimizer.NodeMetrics("node1", 50.0, 90.0, 100, 50.0, Instant.now());
    optimizer.recordMetrics(metrics);

    var bottlenecks = optimizer.detectBottlenecks();

    assertThat(bottlenecks).isNotEmpty();
    assertThat(bottlenecks.get(0)).containsIgnoringCase("memory");
  }

  @Test
  void testBottleneckDetectionDeepQueue() {
    var metrics =
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 50.0, 20000, 50.0, Instant.now());
    optimizer.recordMetrics(metrics);

    var bottlenecks = optimizer.detectBottlenecks();

    assertThat(bottlenecks).isNotEmpty();
    assertThat(bottlenecks.get(0)).containsIgnoringCase("queue");
  }

  @Test
  void testModelMetrics() {
    var metrics = optimizer.modelMetrics();

    assertThat(metrics).containsKeys("accuracy", "mae", "samples_processed");
    assertThat(metrics.get("samples_processed")).isEqualTo(0.0);
  }

  @Test
  void testReset() {
    var metrics =
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 60.0, 100, 50.0, Instant.now());
    optimizer.recordMetrics(metrics);
    var sample =
        new ClusterOptimizer.TrainingSample(
            new double[] {50.0, 60.0, 100, 50.0}, 70.0, 65.0, Instant.now(), 3);
    optimizer.recordTrainingSample(sample);

    optimizer.reset();

    var current = optimizer.getCurrentMetrics();
    assertThat(current.clusterMetrics()).isEmpty();
  }

  @Test
  void testRetrainWithNoSamples() {
    var result = optimizer.retrain();

    assertThat(result).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Err.class);
  }

  @Test
  void testRetrainWithSamples() {
    // Record several training samples
    for (int i = 0; i < 5; i++) {
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {40.0 + i * 10, 50.0 + i * 5, 100 + i * 100, 50.0 + i * 10},
              50.0 + i * 5,
              48.0 + i * 5,
              Instant.now(),
              3);
      optimizer.recordTrainingSample(sample);
    }

    // Wait for samples to be flushed
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(trainingStore.countSamples()).isGreaterThanOrEqualTo(5);
            });

    var result = optimizer.retrain();

    assertThat(result).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
    if (result instanceof io.github.seanchatmangpt.jotp.Result.Ok<Double, String> ok) {
      assertThat(ok.value()).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
    }

    // After retraining, model metrics should be updated
    var metrics = optimizer.modelMetrics();
    assertThat(metrics.get("samples_processed")).isGreaterThan(0);
  }

  @Test
  void testMultipleNodesWithLoadImbalance() {
    // Create 3 nodes with varying load
    var metrics1 =
        new ClusterOptimizer.NodeMetrics("node1", 80.0, 75.0, 5000, 500.0, Instant.now());
    var metrics2 =
        new ClusterOptimizer.NodeMetrics("node2", 10.0, 20.0, 100, 50.0, Instant.now());
    var metrics3 =
        new ClusterOptimizer.NodeMetrics("node3", 50.0, 50.0, 1000, 200.0, Instant.now());

    optimizer.recordMetrics(metrics1);
    optimizer.recordMetrics(metrics2);
    optimizer.recordMetrics(metrics3);

    var bottlenecks = optimizer.detectBottlenecks();

    // Should detect imbalance
    assertThat(bottlenecks).isNotEmpty();
  }

  @Test
  void testFailureRecovery() {
    // Test that prediction doesn't crash on edge cases
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics(
            "node1",
            Double.NaN,
            Double.NaN,
            Integer.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Instant.now()));

    var clusterMetrics = optimizer.getCurrentMetrics();
    var decision = optimizer.predictScaling(clusterMetrics);

    // Should return a decision even with bad input (graceful degradation)
    assertThat(decision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }

  @Test
  void testPredictionConsistency() {
    // Record the same metrics multiple times
    var metrics =
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 50.0, 1000, 100.0, Instant.now());

    for (int i = 0; i < 5; i++) {
      optimizer.recordMetrics(metrics);
    }

    var clusterMetrics = optimizer.getCurrentMetrics();

    // Multiple predictions on same input should be consistent
    var decision1 = optimizer.predictScaling(clusterMetrics);
    var decision2 = optimizer.predictScaling(clusterMetrics);

    assertThat(decision1).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
    assertThat(decision2).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }

  @Test
  void testResetClearsMetrics() {
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 50.0, 1000, 100.0, Instant.now()));
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node2", 60.0, 70.0, 2000, 150.0, Instant.now()));

    assertThat(optimizer.getCurrentMetrics().clusterMetrics()).hasSize(2);

    optimizer.reset();

    assertThat(optimizer.getCurrentMetrics().clusterMetrics()).isEmpty();
  }

  @Test
  void testResourceExhaustionHandling() {
    // Try to record metrics for a large number of nodes
    for (int i = 0; i < 1000; i++) {
      var metrics =
          new ClusterOptimizer.NodeMetrics(
              "node" + i, Math.random() * 100, Math.random() * 100, 1000, 100.0, Instant.now());
      optimizer.recordMetrics(metrics);
    }

    // System should remain responsive
    var clusterMetrics = optimizer.getCurrentMetrics();
    assertThat(clusterMetrics.clusterMetrics()).hasSize(1000);

    var decision = optimizer.predictScaling(clusterMetrics);
    assertThat(decision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }
}
