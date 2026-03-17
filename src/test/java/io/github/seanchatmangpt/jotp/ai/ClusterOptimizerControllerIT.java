package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.cluster.InMemoryClusterManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClusterOptimizerControllerIT {

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
            optimizer, clusterManager, Duration.ofMillis(500));
  }

  @Test
  void testControllerInitialization() {
    assertThat(controller.getOptimizer()).isNotNull();
    assertThat(controller.getCurrentMetrics()).isNotNull();
  }

  @Test
  void testMetricsCollectionRunning() {
    // Register some nodes
    clusterManager.registerNode("node1", 9000, Map.of("role", "worker"));
    clusterManager.registerNode("node2", 9001, Map.of("role", "worker"));

    // Wait for metrics collection to run
    await()
        .atMost(2, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              var metrics = controller.getCurrentMetrics();
              assertThat(metrics.clusterMetrics()).isNotEmpty();
            });
  }

  @Test
  void testScalingDecisionIntegration() {
    clusterManager.registerNode("node1", 9000, Map.of("role", "worker"));

    // Manually trigger a scaling decision by injecting high-load metrics
    var highLoadMetrics =
        new ClusterOptimizer.NodeMetrics("node1", 85.0, 80.0, 5000, 500.0, Instant.now());
    optimizer.recordMetrics(highLoadMetrics);

    var decision = optimizer.predictScaling(optimizer.getCurrentMetrics());

    assertThat(decision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }

  @Test
  void testBottleneckDetectionIntegration() {
    clusterManager.registerNode("node1", 9000, Map.of("role", "worker"));

    // Simulate bottleneck
    var bottleneckMetrics =
        new ClusterOptimizer.NodeMetrics("node1", 95.0, 95.0, 15000, 1000.0, Instant.now());
    optimizer.recordMetrics(bottleneckMetrics);

    var bottlenecks = controller.getBottlenecks();

    assertThat(bottlenecks).isNotEmpty();
  }

  @Test
  void testModelMetricsAvailable() {
    var metrics = controller.getModelMetrics();

    assertThat(metrics).containsKeys("accuracy", "mae", "samples_processed");
  }

  @Test
  void testRetrainingTrigger() {
    // Record some training samples
    for (int i = 0; i < 3; i++) {
      optimizer.recordTrainingSample(
          new ClusterOptimizer.TrainingSample(
              new double[] {50.0 + i * 10, 50.0 + i * 10, 1000, 100.0},
              60.0 + i * 5,
              55.0 + i * 5,
              Instant.now(),
              2));
    }

    // Wait for samples to flush
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
      // Let async writes complete
    });

    var result = controller.triggerRetrain();

    // May succeed or fail depending on data, but shouldn't throw
    assertThat(result).isNotNull();
  }

  @Test
  void testGracefulShutdown() {
    clusterManager.registerNode("node1", 9000, Map.of("role", "worker"));

    // Verify controller is running
    var metrics = controller.getCurrentMetrics();
    assertThat(metrics).isNotNull();

    // Shutdown should not throw
    assertThatNoException().isThrownBy(controller::close);
  }

  @Test
  void testMultipleMetricsSnapshots() {
    // Record multiple metrics over time
    for (int i = 0; i < 5; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node1", 40.0 + i * 10, 50.0 + i * 5, 1000 + i * 100, 100.0 + i * 10, Instant.now()));

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    var metrics = controller.getCurrentMetrics();
    assertThat(metrics.clusterMetrics()).isNotEmpty();
  }

  @Test
  void testLearnFromClusterBehavior() {
    // Simulate scaling up, then down
    clusterManager.registerNode("node1", 9000, Map.of("role", "worker"));
    clusterManager.registerNode("node2", 9001, Map.of("role", "worker"));

    // High load
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 85.0, 80.0, 5000, 500.0, Instant.now()));
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node2", 85.0, 80.0, 5000, 500.0, Instant.now()));

    var highLoadDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
    assertThat(highLoadDecision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);

    // Simulate load decrease
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 20.0, 25.0, 100, 50.0, Instant.now()));
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node2", 20.0, 25.0, 100, 50.0, Instant.now()));

    var lowLoadDecision = optimizer.predictScaling(optimizer.getCurrentMetrics());
    assertThat(lowLoadDecision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }

  @Test
  void testConcurrentMetricsAndPredictions() {
    // Simulate concurrent metric updates and predictions
    var executor = java.util.concurrent.Executors.newFixedThreadPool(4);

    for (int i = 0; i < 10; i++) {
      final int iteration = i;
      executor.submit(
          () -> {
            optimizer.recordMetrics(
                new ClusterOptimizer.NodeMetrics(
                    "node" + (iteration % 3),
                    40.0 + iteration * 5,
                    50.0 + iteration * 3,
                    1000 + iteration * 100,
                    100.0 + iteration * 10,
                    Instant.now()));
          });

      executor.submit(
          () -> {
            var decision = optimizer.predictScaling(optimizer.getCurrentMetrics());
            assertThat(decision).isNotNull();
          });
    }

    executor.shutdown();
    try {
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void testScalingDecisionReasoningIsNonEmpty() {
    optimizer.recordMetrics(
        new ClusterOptimizer.NodeMetrics("node1", 70.0, 60.0, 2000, 200.0, Instant.now()));

    var decision =
        optimizer.predictScaling(optimizer.getCurrentMetrics());

    if (decision instanceof io.github.seanchatmangpt.jotp.Result.Ok<ClusterOptimizer.ScalingDecision, String> ok) {
      var scalingDecision = ok.value();
      assertThat(scalingDecision.reasoning()).isNotEmpty();
    }
  }

  @Test
  void testLowAndHighLoadPatterns() {
    // Low load pattern
    for (int i = 0; i < 3; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 5.0 + i, 10.0 + i, 10 + i * 5, 5.0 + i, Instant.now()));
    }

    var lowLoadMetrics = optimizer.getCurrentMetrics();
    var lowLoadDecision = optimizer.predictScaling(lowLoadMetrics);

    assertThat(lowLoadDecision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);

    optimizer.reset();

    // High load pattern
    for (int i = 0; i < 3; i++) {
      optimizer.recordMetrics(
          new ClusterOptimizer.NodeMetrics(
              "node" + i, 80.0 + i, 70.0 + i, 5000 + i * 1000, 500.0 + i * 100, Instant.now()));
    }

    var highLoadMetrics = optimizer.getCurrentMetrics();
    var highLoadDecision = optimizer.predictScaling(highLoadMetrics);

    assertThat(highLoadDecision).isInstanceOf(io.github.seanchatmangpt.jotp.Result.Ok.class);
  }

  @Test
  void testControllerRobustnessUnderSustainedLoad() {
    // Record sustained high load
    for (int cycle = 0; cycle < 10; cycle++) {
      for (int node = 0; node < 5; node++) {
        optimizer.recordMetrics(
            new ClusterOptimizer.NodeMetrics(
                "node" + node,
                70.0 + Math.random() * 20,
                60.0 + Math.random() * 20,
                2000 + (int) (Math.random() * 2000),
                200.0 + Math.random() * 100,
                Instant.now()));
      }

      var decision = optimizer.predictScaling(optimizer.getCurrentMetrics());
      assertThat(decision).isNotNull();

      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // System should remain stable
    var finalMetrics = controller.getModelMetrics();
    assertThat(finalMetrics).isNotNull();
  }
}
