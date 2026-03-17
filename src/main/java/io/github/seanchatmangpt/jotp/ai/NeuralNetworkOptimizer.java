package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Neural network-based cluster optimizer with PostgreSQL persistence.
 *
 * <p>Implements a simple 3-layer neural network:
 *
 * <pre>{@code
 * Input Layer (4 neurons):     [cpu, memory, queue_depth, message_rate]
 *                                       ↓
 * Hidden Layer (8 neurons):   tanh activation
 *                                       ↓
 * Output Layer (3 neurons):   softmax → [p_scale_up, p_scale_down, p_maintain]
 * }</pre>
 *
 * <p>Weights are updated via backpropagation when {@link #retrain()} is called with historical
 * data from PostgreSQL.
 */
public class NeuralNetworkOptimizer implements ClusterOptimizer {
  private static final Logger logger =
      Logger.getLogger(NeuralNetworkOptimizer.class.getName());

  private final PostgresTrainingStore trainingStore;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final ConcurrentHashMap<String, NodeMetrics> latestMetrics = new ConcurrentHashMap<>();
  private final LinkedList<TrainingSample> recentSamples = new LinkedList<>();
  private final int maxRecentSamples = 1000;

  // Neural network weights
  private double[][] weightsInputHidden; // 4×8
  private double[] biasHidden; // 8
  private double[][] weightsHiddenOutput; // 8×3
  private double[] biasOutput; // 3

  // Model metrics
  private volatile double accuracy = 0.0;
  private volatile double meanAbsoluteError = 0.0;
  private volatile long samplesProcessed = 0;

  // Learning configuration
  private static final double LEARNING_RATE = 0.01;
  private static final int HIDDEN_NEURONS = 8;
  private static final int INPUT_FEATURES = 4;
  private static final int OUTPUT_CLASSES = 3;

  // Scaling thresholds
  private static final double SCALE_UP_THRESHOLD = 0.7;
  private static final double SCALE_DOWN_THRESHOLD = 0.3;
  private static final double MIN_CONFIDENCE = 0.5;

  public NeuralNetworkOptimizer(PostgresTrainingStore trainingStore) {
    this.trainingStore = trainingStore;
    initializeWeights();

    // Schedule periodic retraining
    scheduler.scheduleAtFixedRate(this::retrain, 1, 1, TimeUnit.HOURS);
  }

  private void initializeWeights() {
    var random = new Random(42);

    weightsInputHidden = new double[INPUT_FEATURES][HIDDEN_NEURONS];
    for (int i = 0; i < INPUT_FEATURES; i++) {
      for (int j = 0; j < HIDDEN_NEURONS; j++) {
        weightsInputHidden[i][j] = (random.nextDouble() - 0.5) * 2;
      }
    }

    biasHidden = new double[HIDDEN_NEURONS];
    for (int i = 0; i < HIDDEN_NEURONS; i++) {
      biasHidden[i] = (random.nextDouble() - 0.5) * 2;
    }

    weightsHiddenOutput = new double[HIDDEN_NEURONS][OUTPUT_CLASSES];
    for (int i = 0; i < HIDDEN_NEURONS; i++) {
      for (int j = 0; j < OUTPUT_CLASSES; j++) {
        weightsHiddenOutput[i][j] = (random.nextDouble() - 0.5) * 2;
      }
    }

    biasOutput = new double[OUTPUT_CLASSES];
    for (int i = 0; i < OUTPUT_CLASSES; i++) {
      biasOutput[i] = (random.nextDouble() - 0.5) * 2;
    }
  }

  @Override
  public Result<ScalingDecision, String> predictScaling(ClusterMetrics metrics) {
    try {
      // Extract features from metrics
      var features = extractFeatures(metrics);

      // Forward pass through network
      var hiddenActivations = forwardHidden(features);
      var outputScores = forwardOutput(hiddenActivations);

      // Convert to probabilities (softmax)
      var probabilities = softmax(outputScores);

      // Extract max probability and its action
      var maxProbIdx = argmax(probabilities);
      var action = ScalingAction.values()[maxProbIdx];
      var confidence = probabilities[maxProbIdx];

      // Compute forecasted load
      var forecastedLoad = (features[0] + features[1]) / 2.0;

      // Determine scaling parameters
      var scaling = computeScaling(action, metrics, confidence);

      var decision =
          new ScalingDecision(
              scaling.action(),
              scaling.nodesToAdd(),
              scaling.nodesToRemove(),
              confidence,
              forecastedLoad,
              generateReasoning(action, confidence, features, metrics));

      // Record this as a training sample (actual load not yet known)
      recordTrainingSample(
          new TrainingSample(
              features, forecastedLoad, forecastedLoad, Instant.now(), metrics.totalNodes()));

      return Result.ok(decision);
    } catch (Exception e) {
      logger.warning("Prediction failed, falling back to conservative scaling: " + e.getMessage());
      // Fail-safe: scale up rather than risk underload
      return Result.ok(
          new ScalingDecision(
              ScalingAction.SCALE_UP,
              1,
              List.of(),
              0.0,
              100.0,
              "Prediction failed, scaling up conservatively"));
    }
  }

  @Override
  public void recordMetrics(NodeMetrics nodeMetrics) {
    latestMetrics.put(nodeMetrics.nodeName(), nodeMetrics);
  }

  @Override
  public ClusterMetrics getCurrentMetrics() {
    var metrics = new ArrayList<>(latestMetrics.values());
    return new ClusterMetrics(
        Collections.unmodifiableList(metrics), latestMetrics.size(), Instant.now());
  }

  @Override
  public Result<Double, String> retrain() {
    try {
      var samples = trainingStore.fetchTrainingSamples(1000);
      if (samples.isEmpty()) {
        return Result.err("No training samples available");
      }

      // Mini-batch gradient descent
      double totalError = 0.0;
      for (var sample : samples) {
        var prediction = predict(sample.features());
        var actualLoadClass = loadToClass(sample.actualLoad());
        var error = computeLoss(prediction, actualLoadClass);
        totalError += Math.abs(error);

        backpropagate(sample.features(), actualLoadClass);
      }

      var mae = totalError / samples.size();
      this.meanAbsoluteError = mae;
      this.samplesProcessed += samples.size();

      // Compute accuracy on validation set (simplified: just use MAE)
      var accuracy = Math.max(0, 1.0 - (mae / 100.0));
      this.accuracy = accuracy;

      logger.info(
          String.format(
              "Retrain complete: accuracy=%.2f, MAE=%.2f, samples=%d",
              accuracy, mae, samples.size()));

      return Result.ok(accuracy);
    } catch (Exception e) {
      logger.warning("Retraining failed: " + e.getMessage());
      return Result.err("Retraining failed: " + e.getMessage());
    }
  }

  @Override
  public void recordTrainingSample(TrainingSample sample) {
    synchronized (recentSamples) {
      recentSamples.addLast(sample);
      if (recentSamples.size() > maxRecentSamples) {
        recentSamples.removeFirst();
      }
    }

    // Async persist to PostgreSQL
    trainingStore.saveSampleAsync(sample);
  }

  @Override
  public Result<Double, String> forecastLoad(int horizonSeconds) {
    try {
      var currentMetrics = getCurrentMetrics();
      if (currentMetrics.clusterMetrics().isEmpty()) {
        return Result.err("No metrics available for forecast");
      }

      // Simple trend extrapolation: average current load + decay factor
      var currentLoad =
          currentMetrics.clusterMetrics().stream()
              .mapToDouble(m -> (m.cpuUsagePercent() + m.memoryUsagePercent()) / 2.0)
              .average()
              .orElse(50.0);

      // Apply exponential decay: load will decrease over time if no new traffic
      var decayFactor = Math.exp(-horizonSeconds / 300.0); // 5-minute half-life
      var forecastedLoad = currentLoad * decayFactor + 30.0 * (1 - decayFactor);

      return Result.ok(forecastedLoad);
    } catch (Exception e) {
      return Result.err("Forecast failed: " + e.getMessage());
    }
  }

  @Override
  public List<String> detectBottlenecks() {
    var bottlenecks = new ArrayList<String>();
    var metrics = getCurrentMetrics();

    for (var nodeMetrics : metrics.clusterMetrics()) {
      if (nodeMetrics.cpuUsagePercent() > 80) {
        bottlenecks.add(nodeMetrics.nodeName() + " has high CPU: " + nodeMetrics.cpuUsagePercent() + "%");
      }
      if (nodeMetrics.memoryUsagePercent() > 85) {
        bottlenecks.add(
            nodeMetrics.nodeName() + " has high memory: " + nodeMetrics.memoryUsagePercent() + "%");
      }
      if (nodeMetrics.messageQueueDepth() > 10000) {
        bottlenecks.add(
            nodeMetrics.nodeName()
                + " has deep queue: "
                + nodeMetrics.messageQueueDepth()
                + " messages");
      }
    }

    // Check for unbalanced loads
    if (metrics.clusterMetrics().size() > 1) {
      var cpuValues =
          metrics.clusterMetrics().stream()
              .mapToDouble(NodeMetrics::cpuUsagePercent)
              .toArray();
      var avgCpu = Arrays.stream(cpuValues).average().orElse(0);
      var variance =
          Arrays.stream(cpuValues).map(v -> (v - avgCpu) * (v - avgCpu)).average().orElse(0);
      if (variance > 400) { // std dev > 20
        bottlenecks.add("Cluster has unbalanced load (variance=" + variance + ")");
      }
    }

    return Collections.unmodifiableList(bottlenecks);
  }

  @Override
  public Map<String, Double> modelMetrics() {
    return Map.of(
        "accuracy", accuracy,
        "mae", meanAbsoluteError,
        "samples_processed", (double) samplesProcessed);
  }

  @Override
  public void reset() {
    initializeWeights();
    latestMetrics.clear();
    synchronized (recentSamples) {
      recentSamples.clear();
    }
    accuracy = 0.0;
    meanAbsoluteError = 0.0;
    samplesProcessed = 0;
  }

  @Override
  public void close() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    trainingStore.close();
  }

  // ───────────────────────────────────────────────────────────────────────
  // Private neural network methods
  // ───────────────────────────────────────────────────────────────────────

  private double[] extractFeatures(ClusterMetrics metrics) {
    if (metrics.clusterMetrics().isEmpty()) {
      return new double[] {50.0, 50.0, 1000, 100.0};
    }

    var avgCpu =
        metrics.clusterMetrics().stream()
            .mapToDouble(NodeMetrics::cpuUsagePercent)
            .average()
            .orElse(50.0);
    var avgMemory =
        metrics.clusterMetrics().stream()
            .mapToDouble(NodeMetrics::memoryUsagePercent)
            .average()
            .orElse(50.0);
    var totalQueueDepth =
        metrics.clusterMetrics().stream()
            .mapToInt(NodeMetrics::messageQueueDepth)
            .sum();
    var avgMessageRate =
        metrics.clusterMetrics().stream()
            .mapToDouble(NodeMetrics::messageRatePerSecond)
            .average()
            .orElse(100.0);

    return new double[] {avgCpu, avgMemory, totalQueueDepth, avgMessageRate};
  }

  private double[] forwardHidden(double[] features) {
    var hidden = new double[HIDDEN_NEURONS];
    for (int j = 0; j < HIDDEN_NEURONS; j++) {
      var z = biasHidden[j];
      for (int i = 0; i < INPUT_FEATURES; i++) {
        z += features[i] * weightsInputHidden[i][j];
      }
      hidden[j] = tanh(z);
    }
    return hidden;
  }

  private double[] forwardOutput(double[] hidden) {
    var output = new double[OUTPUT_CLASSES];
    for (int k = 0; k < OUTPUT_CLASSES; k++) {
      var z = biasOutput[k];
      for (int j = 0; j < HIDDEN_NEURONS; j++) {
        z += hidden[j] * weightsHiddenOutput[j][k];
      }
      output[k] = z;
    }
    return output;
  }

  private double[] softmax(double[] logits) {
    var max = Arrays.stream(logits).max().orElse(0);
    var exp = new double[logits.length];
    var sum = 0.0;
    for (int i = 0; i < logits.length; i++) {
      exp[i] = Math.exp(logits[i] - max);
      sum += exp[i];
    }
    for (int i = 0; i < logits.length; i++) {
      exp[i] /= sum;
    }
    return exp;
  }

  private double[] predict(double[] features) {
    var hidden = forwardHidden(features);
    var output = forwardOutput(hidden);
    return softmax(output);
  }

  private int argmax(double[] arr) {
    var maxIdx = 0;
    for (int i = 1; i < arr.length; i++) {
      if (arr[i] > arr[maxIdx]) {
        maxIdx = i;
      }
    }
    return maxIdx;
  }

  private void backpropagate(double[] features, int targetClass) {
    // Compute forward pass
    var hidden = forwardHidden(features);
    var logits = forwardOutput(hidden);
    var probabilities = softmax(logits);

    // Output layer gradient
    var outputDelta = new double[OUTPUT_CLASSES];
    for (int k = 0; k < OUTPUT_CLASSES; k++) {
      outputDelta[k] = probabilities[k] - (k == targetClass ? 1.0 : 0.0);
    }

    // Hidden layer gradient
    var hiddenDelta = new double[HIDDEN_NEURONS];
    for (int j = 0; j < HIDDEN_NEURONS; j++) {
      var delta = 0.0;
      for (int k = 0; k < OUTPUT_CLASSES; k++) {
        delta += outputDelta[k] * weightsHiddenOutput[j][k];
      }
      hiddenDelta[j] = delta * (1 - hidden[j] * hidden[j]); // tanh derivative
    }

    // Update weights (output layer)
    for (int j = 0; j < HIDDEN_NEURONS; j++) {
      for (int k = 0; k < OUTPUT_CLASSES; k++) {
        weightsHiddenOutput[j][k] -= LEARNING_RATE * outputDelta[k] * hidden[j];
      }
    }

    // Update biases (output layer)
    for (int k = 0; k < OUTPUT_CLASSES; k++) {
      biasOutput[k] -= LEARNING_RATE * outputDelta[k];
    }

    // Update weights (hidden layer)
    for (int i = 0; i < INPUT_FEATURES; i++) {
      for (int j = 0; j < HIDDEN_NEURONS; j++) {
        weightsInputHidden[i][j] -= LEARNING_RATE * hiddenDelta[j] * features[i];
      }
    }

    // Update biases (hidden layer)
    for (int j = 0; j < HIDDEN_NEURONS; j++) {
      biasHidden[j] -= LEARNING_RATE * hiddenDelta[j];
    }
  }

  private int loadToClass(double load) {
    if (load > SCALE_UP_THRESHOLD * 100) {
      return 0; // SCALE_UP
    } else if (load < SCALE_DOWN_THRESHOLD * 100) {
      return 1; // SCALE_DOWN
    } else {
      return 2; // MAINTAIN
    }
  }

  private double computeLoss(double[] probabilities, int targetClass) {
    return -Math.log(Math.max(probabilities[targetClass], 1e-10));
  }

  private double tanh(double x) {
    return Math.tanh(x);
  }

  private ScalingDecision computeScaling(
      ScalingAction action, ClusterMetrics metrics, double confidence) {
    return switch (action) {
      case SCALE_UP -> {
        var nodesToAdd =
            confidence > 0.8 ? 2 : 1; // More aggressive if confident
        yield new ScalingDecision(
            action, nodesToAdd, List.of(), confidence, 100.0, "Scaling up");
      }
      case SCALE_DOWN -> {
        var nodesToRemove = new ArrayList<String>();
        if (confidence > MIN_CONFIDENCE && metrics.totalNodes() > 2) {
          // Pick lowest-load node for removal
          var lowestNode =
              metrics.clusterMetrics().stream()
                  .min(
                      Comparator.comparingDouble(
                          m ->
                              (m.cpuUsagePercent() + m.memoryUsagePercent()) / 2.0))
                  .map(NodeMetrics::nodeName)
                  .orElse("");
          if (!lowestNode.isEmpty()) {
            nodesToRemove.add(lowestNode);
          }
        }
        yield new ScalingDecision(action, 0, nodesToRemove, confidence, 0.0, "Scaling down");
      }
      case MAINTAIN -> new ScalingDecision(action, 0, List.of(), confidence, 50.0, "Maintaining");
    };
  }

  private String generateReasoning(
      ScalingAction action,
      double confidence,
      double[] features,
      ClusterMetrics metrics) {
    var cpu = features[0];
    var memory = features[1];
    var queueDepth = features[2];
    var messageRate = features[3];

    return String.format(
        "%s (conf=%.2f): cpu=%.1f%%, mem=%.1f%%, queue=%d, rate=%.0f msg/s, nodes=%d",
        action, confidence, cpu, memory, (int) queueDepth, messageRate, metrics.totalNodes());
  }
}
