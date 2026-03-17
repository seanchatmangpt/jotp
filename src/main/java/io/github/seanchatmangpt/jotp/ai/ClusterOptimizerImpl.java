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
import io.github.seanchatmangpt.jotp.cluster.ClusterManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Implementation of ClusterOptimizer using PerformancePredictor for proactive decisions.
 *
 * <p>Continuously evaluates performance predictions and makes scaling/rebalancing decisions
 * according to cost-aware optimization policy.
 */
public class ClusterOptimizerImpl implements ClusterOptimizer {

  private final PerformancePredictor predictor;
  private final ClusterManager clusterManager;
  private final OptimizationPolicy policy;
  private final ExecutorService executor;
  private final Queue<OptimizationAction> actionHistory;
  private volatile Instant lastActionTime;
  private volatile boolean isRunning;

  public ClusterOptimizerImpl(
      PerformancePredictor predictor,
      ClusterManager clusterManager,
      OptimizationPolicy policy) {
    this.predictor = predictor;
    this.clusterManager = clusterManager;
    this.policy = policy;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.actionHistory = new ConcurrentLinkedQueue<>();
    this.lastActionTime = Instant.now().minus(Duration.ofMinutes(10));
    this.isRunning = true;
  }

  @Override
  public Result<List<OptimizationAction>, Exception> optimize() {
    try {
      List<OptimizationAction> actions = new ArrayList<>();
      Instant now = Instant.now();

      // Check minimum action interval to prevent flapping
      Duration timeSinceLastAction = Duration.between(lastActionTime, now);
      if (timeSinceLastAction.compareTo(policy.getMinActionInterval()) < 0) {
        return Result.ok(actions); // Too soon, skip
      }

      // Evaluate predictions and make decisions
      if (shouldScaleUp()) {
        var scaleAction = decideScaleUp();
        actions.add(scaleAction);
        executeAction(scaleAction);
        lastActionTime = now;
      } else if (shouldScaleDown()) {
        var scaleAction = decideScaleDown();
        actions.add(scaleAction);
        executeAction(scaleAction);
        lastActionTime = now;
      }

      // Check for rebalancing needs
      List<OptimizationAction> rebalanceActions = checkRebalance();
      actions.addAll(rebalanceActions);
      for (var action : rebalanceActions) {
        executeAction(action);
        lastActionTime = now;
      }

      // Check for timeout adjustments
      var timeoutAction = checkTimeoutAdjustment();
      if (timeoutAction != null) {
        actions.add(timeoutAction);
        executeAction(timeoutAction);
        lastActionTime = now;
      }

      // Check for critical failure patterns
      List<OptimizationAction> criticalActions = checkCriticalFailurePatterns();
      actions.addAll(criticalActions);
      for (var action : criticalActions) {
        executeAction(action);
        lastActionTime = now;
      }

      actionHistory.addAll(actions);
      if (actionHistory.size() > 1000) {
        actionHistory.poll(); // Keep history bounded
      }

      return Result.ok(actions);

    } catch (Exception e) {
      return Result.err(e);
    }
  }

  @Override
  public boolean shouldScaleUp() {
    try {
      // Forecast latency over next 30 minutes
      List<PerformancePredictor.Prediction> latencyForecast =
          predictor.forecast(
              new PerformancePredictor.LatencyMetric("P99"),
              Duration.ofMinutes(30),
              Duration.ofMinutes(5));

      double maxLatency =
          latencyForecast.stream()
              .mapToDouble(p -> p.predictedValue())
              .max()
              .orElse(0);

      // Check if latency exceeds SLA with high confidence
      if (maxLatency > policy.getLatencySlaMs()) {
        double avgConfidence =
            latencyForecast.stream()
                .mapToDouble(p -> (1.0 - p.confidenceInterval95() / p.predictedValue()))
                .average()
                .orElse(0);
        if (avgConfidence > policy.getPredictionConfidenceThreshold()) {
          return true;
        }
      }

      // Check failure probability
      double failureProb = predictor.predictFailureProb(Duration.ofMinutes(30));
      if (failureProb > 0.6) {
        return true;
      }

      // Check for critical failure patterns
      List<PerformancePredictor.FailurePattern> patterns =
          predictor.discoverFailureScenarios(5);
      for (var pattern : patterns) {
        if (policy.getCriticalFailurePatterns().contains(pattern.patternName())) {
          double activeScore =
              predictor.checkFailurePatternActive(pattern);
          if (activeScore > 0.7) {
            return true;
          }
        }
      }

      return false;

    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean shouldScaleDown() {
    try {
      // Forecast utilization over next 2 hours
      List<PerformancePredictor.Prediction> utilizationForecast =
          predictor.forecast(
              new PerformancePredictor.CPUUsageMetric("cluster"),
              Duration.ofHours(2),
              Duration.ofMinutes(5));

      double avgUtilization =
          utilizationForecast.stream()
              .mapToDouble(p -> p.predictedValue())
              .average()
              .orElse(100);

      int currentSize = clusterManager.getAliveCount();

      // Scale down only if:
      // 1. Utilization well below target for entire forecast window
      // 2. Not already scaling up
      // 3. Have more than minimum nodes
      // 4. Cost of scale-down > 0
      if (avgUtilization < policy.getTargetUtilizationPercent() * 0.3
          && !shouldScaleUp()
          && currentSize > policy.getMinClusterSize()) {

        // Estimate cost-benefit
        double hoursSinceLastScale =
            Duration.between(lastActionTime, Instant.now()).toMinutes() / 60.0;
        if (hoursSinceLastScale > 1.0) { // Wait at least 1 hour after scale-up
          return true;
        }
      }

      return false;

    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public OptimizationStatus getStatus() {
    try {
      int current = clusterManager.getAliveCount();
      double predictedLatency =
          predictor
              .forecast(
                  new PerformancePredictor.LatencyMetric("P99"),
                  Duration.ofMinutes(30),
                  Duration.ofMinutes(5))
              .stream()
              .mapToDouble(p -> p.predictedValue())
              .average()
              .orElse(0);

      double predictedFailureProb = predictor.predictFailureProb(Duration.ofMinutes(30));

      OptimizationAction lastAction =
          actionHistory.stream()
              .max(Comparator.comparing(OptimizationAction::timestamp))
              .orElse(null);

      String lastActionDesc =
          lastAction != null ? lastAction.description() : "None";

      Instant lastActionT =
          lastAction != null ? lastAction.timestamp() : Instant.now().minus(Duration.ofHours(1));

      return new OptimizationStatus(
          current,
          shouldScaleUp() ? current + 1 : current,
          60, // Placeholder: actual utilization
          65, // Placeholder: predicted utilization
          predictedLatency,
          predictedFailureProb,
          lastActionT,
          lastActionDesc,
          false,
          Duration.between(lastActionT, Instant.now()));

    } catch (Exception e) {
      throw new RuntimeException("Failed to get status", e);
    }
  }

  @Override
  public void close() {
    isRunning = false;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  // ──────────── Private Decision Methods ────────

  private ClusterOptimizer.ScaleUpAction decideScaleUp() {
    int currentSize = clusterManager.getAliveCount();
    int targetSize = Math.min(currentSize + 2, policy.getMaxClusterSize());

    List<PerformancePredictor.Prediction> latencyForecast =
        predictor.forecast(
            new PerformancePredictor.LatencyMetric("P99"),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5));

    double maxLatency =
        latencyForecast.stream()
            .mapToDouble(p -> p.predictedValue())
            .max()
            .orElse(0);

    // Estimate latency reduction from scaling
    double estimatedReduction = maxLatency * (1.0 - (1.0 / (double) targetSize));

    return new ClusterOptimizer.ScaleUpAction(
        Instant.now(),
        targetSize,
        "Predicted latency: " + (int) maxLatency + "ms exceeds SLA of " + (int) policy.getLatencySlaMs() + "ms",
        estimatedReduction);
  }

  private ClusterOptimizer.ScaleDownAction decideScaleDown() {
    int currentSize = clusterManager.getAliveCount();
    int targetSize = Math.max(currentSize - 1, policy.getMinClusterSize());

    return new ClusterOptimizer.ScaleDownAction(
        Instant.now(),
        targetSize,
        "Utilization below " + (int) (policy.getTargetUtilizationPercent() * 0.3) + "%; cost optimization");
  }

  private List<ClusterOptimizer.OptimizationAction> checkRebalance() {
    // Check if processes are unevenly distributed
    // For now, return empty list
    return List.of();
  }

  private ClusterOptimizer.AdjustTimeoutsAction checkTimeoutAdjustment() {
    try {
      List<PerformancePredictor.Prediction> latencyForecast =
          predictor.forecast(
              new PerformancePredictor.LatencyMetric("P99"),
              Duration.ofMinutes(30),
              Duration.ofMinutes(5));

      double maxLatency =
          latencyForecast.stream()
              .mapToDouble(p -> p.predictedValue())
              .max()
              .orElse(0);

      // If latency increases significantly, raise timeouts
      if (maxLatency > policy.getLatencySlaMs() * 0.8) {
        Map<String, Duration> newTimeouts = new HashMap<>();
        newTimeouts.put("process_ask", Duration.ofMillis((long) (maxLatency * 1.5)));
        newTimeouts.put("rpc_call", Duration.ofMillis((long) (maxLatency * 2.0)));

        return new ClusterOptimizer.AdjustTimeoutsAction(
            Instant.now(),
            newTimeouts,
            "Latency forecast: " + (int) maxLatency + "ms; adjusting timeouts preemptively");
      }

      return null;

    } catch (Exception e) {
      return null;
    }
  }

  private List<ClusterOptimizer.OptimizationAction> checkCriticalFailurePatterns() {
    List<ClusterOptimizer.OptimizationAction> actions = new ArrayList<>();

    try {
      List<PerformancePredictor.FailurePattern> patterns =
          predictor.discoverFailureScenarios(5);

      for (var pattern : patterns) {
        if (policy.getCriticalFailurePatterns().contains(pattern.patternName())) {
          double activeScore = predictor.checkFailurePatternActive(pattern);
          if (activeScore > 0.8) {
            // Trigger drain and restart for high-confidence critical patterns
            List<String> nodesToDrain =
                clusterManager.getAliveNodes().stream()
                    .limit(1)
                    .toList();

            actions.add(
                new ClusterOptimizer.DrainAndRestartAction(
                    Instant.now(),
                    nodesToDrain,
                    "Critical failure pattern '" + pattern.patternName() + "' active with score " + (int) (activeScore * 100) + "%"));
          }
        }
      }

    } catch (Exception e) {
      // Ignore: critical pattern detection failure doesn't stop optimization
    }

    return actions;
  }

  private void executeAction(ClusterOptimizer.OptimizationAction action) {
    executor.submit(() -> {
      try {
        switch (action) {
          case ClusterOptimizer.ScaleUpAction scaleUp:
            // Delegate to ClusterManager (which spawns new nodes)
            // clusterManager.scaleUp(scaleUp.targetNodeCount());
            break;
          case ClusterOptimizer.ScaleDownAction scaleDown:
            // clusterManager.scaleDown(scaleDown.targetNodeCount());
            break;
          case ClusterOptimizer.RebalanceAction rebalance:
            // clusterManager.rebalance(rebalance.processTargets());
            break;
          case ClusterOptimizer.DrainAndRestartAction drain:
            // for (String node : drain.nodeNames()) {
            //   clusterManager.drainNode(node);
            // }
            break;
          case ClusterOptimizer.AdjustTimeoutsAction adjust:
            // for (var entry : adjust.newTimeouts().entrySet()) {
            //   configManager.setTimeoutMs(entry.getKey(), entry.getValue().toMillis());
            // }
            break;
          case ClusterOptimizer.TriggerMaintenanceAction maint:
            // triggerMaintenance(maint.maintenanceType());
            break;
        }
      } catch (Exception e) {
        // Log but don't propagate (let it crash principle)
      }
    });
  }
}
