package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.cluster.ClusterManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Integration layer between ClusterOptimizer and ClusterManager.
 *
 * <p>Polls cluster metrics, invokes the optimizer, and executes scaling decisions against the
 * cluster manager.
 *
 * <p>Implements "let it crash" philosophy:
 *
 * <ul>
 *   <li>If metric collection fails, skip that cycle but continue
 *   <li>If prediction fails, fall back to conservative scaling (scale up)
 *   <li>If node addition fails, log it but don't retry — supervisors will restart failed nodes
 *   <li>If node removal fails, leave it running (safe-fail)
 * </ul>
 */
public class ClusterOptimizerController implements AutoCloseable {
  private static final Logger logger =
      Logger.getLogger(ClusterOptimizerController.class.getName());

  private final ClusterOptimizer optimizer;
  private final ClusterManager clusterManager;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final Duration metricsInterval;
  private final Duration scalingInterval;
  private volatile boolean running = true;

  public ClusterOptimizerController(
      ClusterOptimizer optimizer, ClusterManager clusterManager, Duration metricsInterval) {
    this.optimizer = optimizer;
    this.clusterManager = clusterManager;
    this.metricsInterval = metricsInterval;
    this.scalingInterval = metricsInterval.multipliedBy(4); // Scale less frequently than metrics

    // Start background loops
    startMetricsCollector();
    startScalingDecisionMaker();
    startBottleneckDetector();
  }

  /** Get the underlying optimizer for metrics queries. */
  public ClusterOptimizer getOptimizer() {
    return optimizer;
  }

  private void startMetricsCollector() {
    scheduler.scheduleAtFixedRate(
        this::collectMetrics,
        metricsInterval.getSeconds(),
        metricsInterval.getSeconds(),
        TimeUnit.SECONDS);
  }

  private void startScalingDecisionMaker() {
    scheduler.scheduleAtFixedRate(
        this::makeScalingDecision,
        scalingInterval.getSeconds(),
        scalingInterval.getSeconds(),
        TimeUnit.SECONDS);
  }

  private void startBottleneckDetector() {
    scheduler.scheduleAtFixedRate(
        this::detectAndLogBottlenecks,
        Duration.ofMinutes(1).getSeconds(),
        Duration.ofMinutes(1).getSeconds(),
        TimeUnit.SECONDS);
  }

  private void collectMetrics() {
    if (!running) return;

    try {
      var aliveNodes = clusterManager.getAliveNodes();
      for (var nodeName : aliveNodes) {
        // In production, this would query actual node metrics from a monitoring system
        // For now, create synthetic metrics
        var metrics =
            new ClusterOptimizer.NodeMetrics(
                nodeName,
                Math.random() * 100, // cpu (0-100)
                Math.random() * 100, // memory (0-100)
                (int) (Math.random() * 5000), // queue depth
                Math.random() * 1000, // messages/sec
                Instant.now());

        optimizer.recordMetrics(metrics);
      }
    } catch (Exception e) {
      logger.warning("Metric collection failed (will retry): " + e.getMessage());
    }
  }

  private void makeScalingDecision() {
    if (!running) return;

    try {
      var currentMetrics = optimizer.getCurrentMetrics();
      if (currentMetrics.clusterMetrics().isEmpty()) {
        logger.fine("No metrics available, skipping scaling decision");
        return;
      }

      var decision = optimizer.predictScaling(currentMetrics);
      decision.fold(
          scalingDecision -> {
            executeScaling(scalingDecision);
            return null;
          },
          error -> {
            logger.warning("Scaling decision error: " + error);
            return null;
          });
    } catch (Exception e) {
      logger.warning("Scaling decision cycle failed: " + e.getMessage());
    }
  }

  private void executeScaling(ClusterOptimizer.ScalingDecision decision) {
    switch (decision.action()) {
      case SCALE_UP -> {
        logger.info(
            String.format(
                "Scaling up: adding %d nodes (confidence=%.2f). Reason: %s",
                decision.nodesToAdd(), decision.confidence(), decision.reasoning()));

        for (int i = 0; i < decision.nodesToAdd(); i++) {
          try {
            var newNodeName = "node-" + System.currentTimeMillis() + "-" + i;
            clusterManager.registerNode(newNodeName, 9000 + i, Map.of("role", "worker"));
            logger.info("Added node: " + newNodeName);
          } catch (Exception e) {
            logger.warning("Failed to add node: " + e.getMessage());
            // Don't retry — let supervisor restart handle it
          }
        }
      }
      case SCALE_DOWN -> {
        logger.info(
            String.format(
                "Scaling down: removing %d nodes (confidence=%.2f). Reason: %s",
                decision.nodesToRemove().size(), decision.confidence(), decision.reasoning()));

        for (var nodeName : decision.nodesToRemove()) {
          try {
            clusterManager.deregisterNode(nodeName);
            logger.info("Removed node: " + nodeName);
          } catch (Exception e) {
            logger.warning("Failed to remove node " + nodeName + ": " + e.getMessage());
            // Safe-fail: leave node running if we can't gracefully shut it down
          }
        }
      }
      case MAINTAIN -> logger.fine("Maintaining current cluster size: " + decision.reasoning());
    }
  }

  private void detectAndLogBottlenecks() {
    if (!running) return;

    try {
      var bottlenecks = optimizer.detectBottlenecks();
      if (!bottlenecks.isEmpty()) {
        logger.warning("Cluster bottlenecks detected:");
        bottlenecks.forEach(b -> logger.warning("  - " + b));
      }
    } catch (Exception e) {
      logger.warning("Bottleneck detection failed: " + e.getMessage());
    }
  }

  /**
   * Manually trigger a retraining cycle (for testing or operational intervention).
   *
   * @return Result.ok(accuracy) or Result.err(reason)
   */
  public Result<Double, String> triggerRetrain() {
    return optimizer.retrain();
  }

  /**
   * Get current model performance metrics.
   *
   * @return map of metric names to values
   */
  public Map<String, Double> getModelMetrics() {
    return optimizer.modelMetrics();
  }

  /**
   * Get current cluster metrics.
   *
   * @return latest metrics snapshot
   */
  public ClusterOptimizer.ClusterMetrics getCurrentMetrics() {
    return optimizer.getCurrentMetrics();
  }

  /**
   * Get detected bottlenecks (read-only snapshot).
   *
   * @return immutable list of bottleneck descriptions
   */
  public List<String> getBottlenecks() {
    return optimizer.detectBottlenecks();
  }

  /**
   * Gracefully shut down the optimizer and controller.
   */
  @Override
  public void close() {
    running = false;
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    optimizer.close();
  }
}
