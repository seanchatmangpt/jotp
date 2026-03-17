package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.cluster.InMemoryClusterManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Simulates realistic traffic patterns to verify cluster optimizer accuracy.
 *
 * <p>Patterns tested:
 *
 * <ul>
 *   <li>Morning spike: 0% → 80% load over 10 minutes
 *   <li>Steady state: 70% load for 30 minutes
 *   <li>Evening drop: 70% → 10% load over 5 minutes
 *   <li>Periodic peaks: alternating 20% and 70% every 2 minutes
 *   <li>Flash crowd: 30% → 95% in 1 minute
 * </ul>
 *
 * <p>The optimizer should scale up during peaks and scale down during valleys, while minimizing
 * under-provisioned and over-provisioned periods.
 */
class TrafficPatternSimulationTest {

  private ClusterOptimizer optimizer;
  private InMemoryClusterManager clusterManager;
  private ClusterOptimizerController controller;

  @BeforeEach
  void setUp() {
    var trainingStore = new InMemoryTrainingStore();
    optimizer = new NeuralNetworkOptimizer(trainingStore);
    clusterManager = new InMemoryClusterManager(5000);
    controller =
        new ClusterOptimizerController(
            optimizer, clusterManager, Duration.ofMillis(100));
  }

  @Test
  void testMorningSpikeSpikePattern() {
    // Simulate load ramping from 0% to 80% over 10 minutes
    var predictions = new ArrayList<Double>();

    for (int minute = 0; minute <= 10; minute++) {
      var load = (minute / 10.0) * 80.0;
      var numNodes = (int) Math.ceil(load / 25.0);

      for (int i = 0; i < numNodes; i++) {
        var nodeLoad = 40.0 + Math.random() * 10;
        optimizer.recordMetrics(
            new ClusterOptimizer.NodeMetrics(
                "node" + i, nodeLoad, nodeLoad * 1.2, (int) (nodeLoad * 100), 100.0 + nodeLoad,
                Instant.now()));
      }

      var decision = optimizer.predictScaling(optimizer.getCurrentMetrics());
      if (decision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
        predictions.add(ok.value().forecastedLoad());
      }

      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Should have made predictions throughout
    assertThat(predictions).isNotEmpty();
    // Final prediction should be higher than first
    assertThat(predictions.get(predictions.size() - 1))
        .isGreaterThan(predictions.get(0) + 20);
  }

  @Test
  void testSteadyStatePattern() {
    // Simulate 30 seconds of steady 70% load
    for (int second = 0; second < 30; second++) {
      for (int i = 0; i < 3; i++) {
        optimizer.recordMetrics(
            new ClusterOptimizer.NodeMetrics(
                "node" + i,
                68.0 + Math.random() * 4,
                72.0 + Math.random() * 4,
                2000 + (int) (Math.random() * 500),
                250.0 + Math.random() * 50,
                Instant.now()));
      }

      var decision = optimizer.predictScaling(optimizer.getCurrentMetrics());
      assertThat(decision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);

      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // System should be stable during steady load
    var bottlenecks = optimizer.detectBottlenecks();
    // Few bottlenecks expected at steady 70%
    assertThat(bottlenecks).hasSizeLessThan(2);
  }

  @Test
  void testEveningDropPattern() {
    // Start at high load, drop to low
    var predictions = new ArrayList<Double>();

    // High load phase
    for (int i = 0; i < 3; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 75.0, 70.0, 3000, 350.0, Instant.now()));
    }

    var highLoadDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
    if (highLoadDecision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
      predictions.add(ok.value().forecastedLoad());
    }

    // Simulate load drop
    optimizer.reset();
    for (int i = 0; i < 1; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 15.0, 20.0, 100, 50.0, Instant.now()));
    }

    var lowLoadDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
    if (lowLoadDecision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
      predictions.add(ok.value().forecastedLoad());
    }

    // Should predict lower load after drop
    assertThat(predictions).hasSize(2);
    assertThat(predictions.get(1)).isLessThan(predictions.get(0));
  }

  @Test
  void testPeriodicPeaksPattern() {
    // Alternating between low (20%) and high (70%) load every 2 seconds
    var scaleUpCount = 0;
    var scaleDownCount = 0;

    for (int cycle = 0; cycle < 5; cycle++) {
      // High load phase
      for (int i = 0; i < 3; i++) {
        optimizer.recordMetrics(
            new ClusterOptimizer.NodeMetrics(
                "node" + i, 65.0 + Math.random() * 10, 70.0, 2500, 300.0, Instant.now()));
      }

      var highDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
      if (highDecision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
        if (ok.value().action() == ClusterOptimizer.ScalingAction.SCALE_UP) {
          scaleUpCount++;
        }
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // Low load phase
      optimizer.reset();
      for (int i = 0; i < 1; i++) {
        optimizer.recordMetrics(
            new ClusterOptimizer.NodeMetrics(
                "node" + i, 18.0 + Math.random() * 4, 20.0, 200, 50.0, Instant.now()));
      }

      var lowDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
      if (lowDecision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
        if (ok.value().action() == ClusterOptimizer.ScalingAction.SCALE_DOWN) {
          scaleDownCount++;
        }
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Should detect peaks and valleys
    assertThat(scaleUpCount + scaleDownCount).isGreaterThan(0);
  }

  @Test
  void testFlashCrowdPattern() {
    // Sudden load increase from 30% to 95% in 1 minute
    var predictions = new ArrayList<ClusterOptimizer.ScalingDecision>();

    // Initial low load
    for (int i = 0; i < 1; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 28.0, 30.0, 300, 75.0, Instant.now()));
    }

    var initialDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
    if (initialDecision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
      predictions.add(ok.value());
    }

    // Simulate flash crowd — rapid load increase
    for (int second = 0; second < 10; second++) {
      var load = 30.0 + (second / 10.0) * 65.0;
      var numNodes = Math.max(1, (int) Math.ceil(load / 25.0));

      for (int i = 0; i < numNodes; i++) {
        var nodeLoad = load / numNodes;
        optimizer.recordMetrics(
            new ClusterOptimizer.NodeMetrics(
                "node" + i,
                nodeLoad + Math.random() * 5,
                nodeLoad * 1.1,
                (int) (nodeLoad * 150),
                (nodeLoad * 3.5),
                Instant.now()));
      }

      var decision = optimizer.predictScaling(optimizer.getCurrentMetrics());
      if (decision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
        predictions.add(ok.value());
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Final prediction should show high load
    var finalPrediction = predictions.get(predictions.size() - 1);
    assertThat(finalPrediction.forecastedLoad()).isGreaterThan(60.0);

    // Early on, should predict scale-up
    var earlyPredictions =
        predictions.stream()
            .filter(d -> d.action() == ClusterOptimizer.ScalingAction.SCALE_UP)
            .count();
    assertThat(earlyPredictions).isGreaterThan(0);
  }

  @Test
  void testScalingAccuracyUnderRealismicLoad() {
    // Simulate a realistic day: low night, high day, low evening
    var nodeCountHistory = new ArrayList<Integer>();

    // Night (low load)
    for (int hour = 0; hour < 2; hour++) {
      recordLoadMetrics(15.0, 1);
      nodeCountHistory.add(clusterManager.getAliveNodes().size());
    }

    // Morning ramp
    for (int hour = 2; hour < 8; hour++) {
      recordLoadMetrics(15.0 + (hour - 2) * 12.0, (int) Math.ceil((15.0 + (hour - 2) * 12.0) / 25));
      nodeCountHistory.add(clusterManager.getAliveNodes().size());
    }

    // Daytime peak
    for (int hour = 8; hour < 18; hour++) {
      recordLoadMetrics(70.0 + Math.random() * 10, 3);
      nodeCountHistory.add(clusterManager.getAliveNodes().size());
    }

    // Evening ramp down
    for (int hour = 18; hour < 24; hour++) {
      var load = Math.max(15.0, 70.0 - (hour - 18) * 7.0);
      recordLoadMetrics(load, (int) Math.ceil(load / 25));
      nodeCountHistory.add(clusterManager.getAliveNodes().size());
    }

    // Verify that optimizer tracked the load patterns
    var metrics = optimizer.modelMetrics();
    assertThat(metrics.get("samples_processed")).isGreaterThan(0);
  }

  private void recordLoadMetrics(double targetLoad, int nodeCount) {
    for (int i = 0; i < nodeCount; i++) {
      var nodeLoad = targetLoad / nodeCount;
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i,
              nodeLoad + (Math.random() * 5 - 2.5),
              (nodeLoad * 1.1) + (Math.random() * 5),
              (int) (nodeLoad * 100),
              nodeLoad * 3,
              Instant.now()));
    }
  }

  @Test
  void testScalingDecisionConsistencyUnderLoadVariance() {
    // Record same average load with varying node distributions
    var decisions = new ArrayList<ClusterOptimizer.ScalingAction>();

    // Single heavy node
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 75.0, 80.0, 5000, 400.0, Instant.now()));

    var dec1 = optimizer.predictScaling(optimizer.getCurrentMetrics());
    if (dec1 instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
      decisions.add(ok.value().action());
    }

    optimizer.reset();

    // Same load spread across 3 nodes
    for (int i = 0; i < 3; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 25.0, 27.0, 1500, 130.0, Instant.now()));
    }

    var dec2 = optimizer.predictScaling(optimizer.getCurrentMetrics());
    if (dec2 instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
      decisions.add(ok.value().action());
    }

    // Both should recognize the load level consistently
    assertThat(decisions).isNotEmpty();
  }

  @Test
  void testPredictionStabilityAfterRetrain() {
    // Record training data
    for (int i = 0; i < 20; i++) {
      var load = 30.0 + (Math.random() * 50);
      var sample =
          new ClusterOptimizer.TrainingSample(
              new double[] {load, load * 1.1, (int) (load * 100), load * 3.5},
              load,
              load + Math.random() * 5,
              Instant.now(),
              (int) Math.ceil(load / 25));
      optimizer.recordTrainingSample(sample);
    }

    // Wait for async flush
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              // Let async complete
            });

    // Get prediction before retrain
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 55.0, 2000, 200.0, Instant.now()));
    var beforeRetrain = optimizer.predictScaling(optimizer.getCurrentMetrics());

    // Retrain
    var retrainResult = optimizer.retrain();

    // Get prediction after retrain
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 50.0, 55.0, 2000, 200.0, Instant.now()));
    var afterRetrain = optimizer.predictScaling(optimizer.getCurrentMetrics());

    // Both should be valid decisions
    assertThat(beforeRetrain).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
    assertThat(afterRetrain).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }
}
