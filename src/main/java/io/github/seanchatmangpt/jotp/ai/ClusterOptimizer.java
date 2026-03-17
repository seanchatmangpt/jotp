package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * AI-driven adaptive cluster optimization following Joe Armstrong's "let it crash" philosophy
 * applied to machine learning.
 *
 * <p>Joe Armstrong: "Let it crash — when something fails, we restart it. This applies to load
 * balancing too. Instead of predicting perfectly, we learn from failures and adapt."
 *
 * <p>ClusterOptimizer implements:
 *
 * <ul>
 *   <li><strong>Predictive Load Balancing:</strong> Simple neural networks predict cluster load
 *       from historical patterns
 *   <li><strong>Auto-Scaling:</strong> Dynamically add/remove nodes based on demand patterns
 *   <li><strong>Bottleneck Prediction:</strong> Detect resource contention before it happens
 *   <li><strong>Historical Learning:</strong> Store and learn from cluster behavior in PostgreSQL
 *   <li><strong>Resource Monitoring:</strong> Track CPU, memory, message queue depth per node
 *   <li><strong>Graceful Crash Recovery:</strong> Failed predictions are logged but don't stop
 *       optimization
 * </ul>
 *
 * <p><strong>Architecture Flow:</strong>
 *
 * <pre>{@code
 * [Cluster Metrics]
 *   ↓
 * [Feature Extraction] → [cpu, memory, queue_depth, message_rate]
 *   ↓
 * [Neural Network] → predict(features) → load_forecast
 *   ↓
 * [Scaling Decision] → {scale_up, scale_down, maintain}
 *   ↓
 * [ClusterManager] → add_nodes() / remove_nodes()
 *   ↓
 * [PostgreSQL] → store training sample for next epoch
 * }</pre>
 *
 * <p><strong>Let It Crash Applied to ML:</strong>
 *
 * <p>If a prediction is wrong, we don't panic. Instead:
 *
 * <ul>
 *   <li>Log the prediction error
 *   <li>Store it as a training sample
 *   <li>Continue operating with conservative fallback (scale up rather than risk underload)
 *   <li>Retrain periodically to incorporate error patterns
 * </ul>
 *
 * <p>This mirrors Erlang's philosophy: fault tolerance through acceptance of failure, not
 * prevention.
 */
public interface ClusterOptimizer {

  /**
   * Resource metrics snapshot for a single cluster node.
   *
   * @param nodeName node identifier
   * @param cpuUsagePercent CPU utilization (0-100)
   * @param memoryUsagePercent memory utilization (0-100)
   * @param messageQueueDepth messages pending in mailbox
   * @param messageRatePerSecond incoming messages per second
   * @param timestamp when this snapshot was taken
   */
  record NodeMetrics(
      String nodeName,
      double cpuUsagePercent,
      double memoryUsagePercent,
      int messageQueueDepth,
      double messageRatePerSecond,
      Instant timestamp) {}

  /**
   * Cluster-wide metrics aggregated from all nodes.
   *
   * @param clusterMetrics per-node metrics
   * @param totalNodes current node count
   * @param timestamp when aggregated
   */
  record ClusterMetrics(
      List<NodeMetrics> clusterMetrics, int totalNodes, Instant timestamp) {}

  /**
   * Scaling decision from the optimizer.
   *
   * @param action {scale_up, scale_down, maintain}
   * @param nodesToAdd number of nodes to provision (if scaling up)
   * @param nodesToRemove target node names for graceful shutdown (if scaling down)
   * @param confidence prediction confidence (0-1.0)
   * @param forecastedLoad predicted average load in next window
   * @param reasoning human-readable explanation of decision
   */
  record ScalingDecision(
      ScalingAction action,
      int nodesToAdd,
      List<String> nodesToRemove,
      double confidence,
      double forecastedLoad,
      String reasoning) {}

  enum ScalingAction {
    SCALE_UP,
    SCALE_DOWN,
    MAINTAIN
  }

  /**
   * Training sample for model retraining.
   *
   * @param features input features [cpu, memory, queue_depth, message_rate]
   * @param actualLoad ground truth load observed after prediction
   * @param predictedLoad what the model predicted
   * @param timestamp when this sample occurred
   * @param nodeCount how many nodes were active
   */
  record TrainingSample(
      double[] features,
      double actualLoad,
      double predictedLoad,
      Instant timestamp,
      int nodeCount) {}

  /**
   * Analyze current cluster metrics and predict required scaling.
   *
   * <p>This is the main entry point. It:
   *
   * <ul>
   *   <li>Extracts features from metrics
   *   <li>Feeds them through the neural network
   *   <li>Generates a scaling decision
   *   <li>Stores the metrics as a training sample
   *   <li>Returns Result.ok(decision) or Result.err(reason) if prediction fails
   * </ul>
   *
   * @param metrics current cluster state
   * @return ScalingDecision wrapped in Result for railway-oriented error handling
   */
  Result<ScalingDecision, String> predictScaling(ClusterMetrics metrics);

  /**
   * Update a single node's resource metrics.
   *
   * <p>Called periodically (e.g., every 1-5 seconds) to track live resource usage. These
   * measurements feed the neural network's input layer.
   *
   * @param nodeMetrics snapshot of this node's current state
   */
  void recordMetrics(NodeMetrics nodeMetrics);

  /**
   * Get the current state of collected metrics.
   *
   * @return immutable snapshot of all recorded metrics
   */
  ClusterMetrics getCurrentMetrics();

  /**
   * Retrain the neural network on historical data from PostgreSQL.
   *
   * <p>Should be called periodically (e.g., every hour or when new data accumulates). This:
   *
   * <ul>
   *   <li>Fetches training samples from PostgreSQL
   *   <li>Runs backpropagation to adjust weights
   *   <li>Evaluates error on validation set
   *   <li>Stores updated weights
   * </ul>
   *
   * <p>If retraining fails (DB unavailable, no samples), returns error but doesn't crash the
   * optimizer.
   *
   * @return Result.ok(accuracy) after successful retraining, or Result.err(reason) if it fails
   */
  Result<Double, String> retrain();

  /**
   * Store a training sample for later model retraining.
   *
   * <p>This is called automatically after each prediction. Can also be called explicitly to
   * record external observations (e.g., "we added 3 nodes and load dropped 20%").
   *
   * @param sample metrics and outcome for this observation
   */
  void recordTrainingSample(TrainingSample sample);

  /**
   * Predict load for the next time window using only historical features.
   *
   * <p>Used for lookahead planning — predict what load will be in 5 minutes based on current
   * trend.
   *
   * @param horizon seconds into the future to predict (e.g., 300 for 5 minutes)
   * @return predicted average load (0-100 scale), or error if prediction fails
   */
  Result<Double, String> forecastLoad(int horizonSeconds);

  /**
   * Detect potential bottlenecks in the cluster.
   *
   * <p>Analyzes per-node metrics to find:
   *
   * <ul>
   *   <li>Nodes with CPU > 80%
   *   <li>Nodes with memory > 85%
   *   <li>Unbalanced message queues (high variance across nodes)
   *   <li>Sustained high message rate without proportional node increase
   * </ul>
   *
   * @return immutable list of bottleneck descriptions
   */
  List<String> detectBottlenecks();

  /**
   * Get model performance metrics.
   *
   * <p>Returns prediction accuracy, MAE (mean absolute error), and training samples seen so far.
   * Useful for observability and deciding when to retrain.
   *
   * @return map of metric names to values (e.g., "accuracy" → 0.87, "mae" → 12.3,
   *     "samples_seen" → 1024)
   */
  Map<String, Double> modelMetrics();

  /**
   * Reset the optimizer state (for testing).
   *
   * <p>Clears all recorded metrics and resets the model to its initial random state.
   */
  void reset();

  /**
   * Gracefully shut down the optimizer.
   *
   * <p>Stops any background threads (metric polling, retraining), flushes pending samples to
   * PostgreSQL.
   */
  @Override
  void close();
}
