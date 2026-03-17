# PerformancePredictor: LSTM-Based Degradation Forecasting

## Overview

`PerformancePredictor` is a machine learning system that predicts performance degradation **before it affects users**. Using LSTM (Long Short-Term Memory) neural networks trained on PostgreSQL event logs, it forecasts:

- **Latency increases** (P50, P95, P99)
- **Throughput drops** (requests per second)
- **Failure probability** (likelihood of service crash in next window)
- **Repeating failure scenarios** (pattern recognition from historical events)

The system integrates with `ClusterOptimizer` to trigger **preventive scaling** and **maintenance** decisions.

## Architecture

```
PostgreSQL Event Log
       ↓
Time-Series Aggregator (5-min buckets)
       ↓
Metric Normalization (z-score)
       ↓
Feature Engineering (lags, rolling stats, seasonality)
       ↓
LSTM Training (2-layer encoder-decoder with attention)
       ↓
Seasonality Decomposition (DFT: daily, weekly patterns)
       ↓
Anomaly Detection (Isolation Forest on residuals)
       ↓
Ensemble Voting (LSTM + AutoRegressive + Gaussian Process)
       ↓
PerformancePredictor (trained model)
       ↓
Forecast Generation (5-min, 30-min, 1-hour horizons)
       ↓
ClusterOptimizer (decision making)
       ↓
ClusterManager (execution: scale, rebalance, drain)
```

## Key Features

### 1. LSTM Time-Series Forecasting

**Model Architecture:**
- 2-layer LSTM with 128 hidden units
- Attention mechanism for sequence alignment
- Temporal convolution layer
- Output: point estimate + 95% confidence intervals

**Training Pipeline:**
```
events → aggregate(5min) → normalize(z-score)
→ features(lags, rolling_stats)
→ split(70/15/15)
→ train(100 epochs, early_stopping)
```

**Example:**
```java
// Train on 10 days of historical data
Result<TrainingMetrics, Exception> result = predictor.train(Duration.ofDays(10));

// Forecast next 30 minutes of latency
List<Prediction> forecast = predictor.forecast(
    new LatencyMetric("P99"),
    Duration.ofMinutes(30),
    Duration.ofMinutes(5)  // 5-min resolution
);

// Check predictions
for (var pred : forecast) {
    System.out.println("Time: " + pred.timestamp());
    System.out.println("Predicted P99: " + pred.predictedValue() + "ms");
    System.out.println("Confidence±: " + pred.confidenceInterval95() + "ms");
    System.out.println("Degradation Risk: " + (int)(pred.degradationLikelihood()*100) + "%");
}
```

### 2. Seasonality Decomposition

Automatically discovers periodic patterns in metrics:

```
Daily Pattern:   8am-2pm peak, 2am-6am low
Weekly Pattern:  weekday vs weekend differences
Event-Based:     deploy windows, batch jobs, backups
```

**Usage:**
```java
SeasonalityDecomposition decomp = predictor.getSeasonalityDecomposition(
    new LatencyMetric("P99")
);

for (var component : decomp.components()) {
    System.out.println(component.frequency() + ": amplitude=" + component.amplitude());
}
```

**Benefit:** Distinguishes normal variation from true degradation.

### 3. Failure Pattern Discovery

Pattern recognition identifies repeating scenarios that lead to crashes:

```
Pattern 1: "GC Pause → Queue Overflow"
  Sequence: [GC_PAUSE_500ms, LATENCY_SPIKE, QUEUE_GROWTH, ERROR_SPIKE]
  Frequency: 15 occurrences
  Recovery Time: 45 seconds average

Pattern 2: "Cascade Failure"
  Sequence: [NODE_A_DOWN, NODE_B_OVERLOAD, TIMEOUT, CASCADE]
  Frequency: 8 occurrences
  Recovery Time: 120 seconds average
```

**Usage:**
```java
// Discover top failure patterns
List<FailurePattern> patterns = predictor.discoverFailureScenarios(5);

// Check if a pattern is currently active
for (var pattern : patterns) {
    double confidence = predictor.checkFailurePatternActive(pattern);
    if (confidence > 0.8) {
        System.out.println("WARNING: " + pattern.patternName() + " is active!");
        optimizer.drainAndRestart(pattern);
    }
}
```

### 4. Online Learning from Feedback

The model continuously improves from production observations:

```java
// After a prediction window, record actual outcome
var prediction = forecast.get(0);
var actual = new MetricVector(...);
boolean degradationOccurred = actual.latencyP99() > slaThreshold;

predictor.recordFeedback(prediction, actual, degradationOccurred);

// Or record anomaly feedback
var anomaly = new DetectedAnomaly(...);
var outcome = new AnomalyOutcome(
    wasDetected=true,
    causedDegradation=true,
    scalingPrevented=true
);
predictor.recordAnomalyFeedback(anomaly, outcome);
```

### 5. Accuracy Metrics

Training produces multiple accuracy metrics:

| Metric | Meaning | Target |
|--------|---------|--------|
| **MSE** | Mean Squared Error | <50 |
| **MAE** | Mean Absolute Error (ms) | <10 |
| **MAPE** | Percentage Error | <15% |
| **AUC-ROC** | Failure Classification | >0.85 |

```java
var trainResult = predictor.train(Duration.ofDays(10));
var metrics = trainResult.orElseThrow();

System.out.println("MSE: " + metrics.mse());
System.out.println("MAE: " + metrics.mae() + "ms");
System.out.println("MAPE: " + (int)(metrics.mape()*10) / 10.0 + "%");
System.out.println("AUC-ROC: " + (int)(metrics.aucRoc()*100) + "%");
```

## Integration with ClusterOptimizer

The predictor feeds directly into optimization decisions:

```java
var predictor = PerformancePredictor.create(backend);
var optimizer = ClusterOptimizer.create(predictor, clusterManager, policy);

// Run optimization loop
var result = optimizer.optimize();

// Checks performed:
// 1. Latency forecast > SLA? → Scale up
// 2. Failure probability > 0.6? → Scale up or drain-restart
// 3. Utilization forecast < 30% target? → Scale down
// 4. Critical pattern active? → Drain and restart
// 5. Latency increasing? → Adjust timeouts
```

## Forecast Horizons

PerformancePredictor produces predictions at three key horizons:

### 5-Minute Forecast
- **Use case:** Immediate scaling decisions
- **Accuracy:** Highest (tight confidence intervals)
- **Triggering:** Latency oscillations, sudden queue spikes
- **Example:** "P99 will spike to 150ms in 3 minutes"

### 30-Minute Forecast
- **Use case:** Proactive scaling (add capacity before SLA miss)
- **Accuracy:** Good (moderate confidence intervals)
- **Triggering:** Trending latency increase, sustained high utilization
- **Example:** "Throughput will drop 20% over next 30 minutes due to peak"

### 1-Hour Forecast
- **Use case:** Long-term capacity planning, maintenance windows
- **Accuracy:** Moderate (wider confidence intervals)
- **Triggering:** Major load shifts, cascading failures
- **Example:** "Failure probability will exceed 80% in 50 minutes; maintenance needed"

## Failure Probability Calculation

PerformancePredictor combines multiple signals to estimate failure likelihood:

```
failure_prob = max(
    latency_degradation * throughput_drop * 0.5,  // Combined metric degradation
    anomaly_score * 0.3                            // Anomaly intensity
)

where:
  latency_degradation = min(1.0, predicted_p99 / sla_threshold)
  throughput_drop = max(0.0, 1.0 - (predicted_rps / current_rps))
  anomaly_score = average(degradation_likelihood for each prediction)
```

**Example:**
```java
double failureProb = predictor.predictFailureProb(Duration.ofMinutes(30));

if (failureProb > 0.8) {
    logger.warn("Failure imminent; triggering emergency scaling");
    optimizer.scaleUp(currentNodeCount + 3);
} else if (failureProb > 0.6) {
    logger.info("Risk detected; preemptive scaling");
    optimizer.scaleUp(currentNodeCount + 1);
}
```

## Example Scenario: Preventing a Cascading Failure

```
Time: 08:00:00 – Morning peak begins
- Latency forecast: P99 = 80ms (normal)
- Failure prob: 0.1 (low)
- Action: None

Time: 08:15:00 – Load increases
- Latency forecast: P99 = 120ms (trending up)
- Pattern "GC Pause → Queue Overflow" confidence: 0.45
- Failure prob: 0.35
- Action: Monitor (no action yet)

Time: 08:25:00 – Degradation accelerating
- Latency forecast: P99 = 180ms (exceeds 100ms SLA!)
- Pattern "GC Pause → Queue Overflow" confidence: 0.72 (HIGH)
- Failure prob: 0.68
- Action: Scale up 1 node + adjust timeouts

Time: 08:35:00 – New capacity absorbs load
- Latency forecast: P99 = 110ms (back to normal)
- Pattern confidence: 0.15 (pattern ended)
- Failure prob: 0.12 (low)
- Action: Continue monitoring

USER EXPERIENCE: No SLA miss, no alerts, transparent scaling
```

## Training Requirements

- **Minimum lookback window:** 7 days (for seasonality detection)
- **Minimum failure events:** 50 (for robust failure prediction)
- **Time-series resolution:** 5-minute aggregates (configurable)
- **Feature count:** 8+ metrics (latency, throughput, errors, GC, queue depth, CPU, memory)

```java
// Insufficient data
Result<TrainingMetrics, Exception> result = predictor.train(Duration.ofDays(3));
// Returns: Err("Lookback window must be at least 7 days for seasonality detection")

// Sufficient data
result = predictor.train(Duration.ofDays(14));
// Returns: Ok(TrainingMetrics { mse: 15.2, mae: 3.1, aucRoc: 0.89, ... })
```

## Configuration via OptimizationPolicy

```java
public class MyOptimizationPolicy implements ClusterOptimizer.OptimizationPolicy {
    @Override
    public double getLatencySlaMs() { return 100.0; }  // P99 SLA

    @Override
    public double getTargetUtilizationPercent() { return 70.0; }  // Scale down at 21%

    @Override
    public Duration getMinActionInterval() {
        return Duration.ofSeconds(30);  // Prevent flapping
    }

    @Override
    public double getPredictionConfidenceThreshold() {
        return 0.7;  // Only act on 70%+ confident forecasts
    }

    @Override
    public List<String> getCriticalFailurePatterns() {
        return List.of(
            "Cascade Failure",
            "GC Pause → Queue Overflow",
            "Node Partition"
        );
    }
}
```

## Monitoring & Observability

Check model status and prediction quality:

```java
var status = predictor.getStatus();

System.out.println("Trained: " + status.isTrained());
System.out.println("Last training: " + status.lastTrainedAt());
System.out.println("Accuracy (MSE): " + status.lastMetrics().mse());
System.out.println("Failure events found: " + status.lastMetrics().failureEventsFound());
System.out.println("Feature importance: " + status.featureImportance());

// Top failure predictors
var importances = predictor.getFailurePredictors();
importances.forEach(fi ->
    System.out.println(fi.metric() + ": " + (int)(fi.importance()*100) + "%")
);
```

**Expected output:**
```
Trained: true
Last training: 2026-03-17T10:30:00Z
Accuracy (MSE): 12.5
Failure events found: 47
Feature importance: {
    latency_p99: 0.35,
    gc_pause: 0.28,
    queue_depth: 0.22,
    throughput: 0.15
}
```

## Limitations & Caveats

1. **Cold start problem:** First 7 days of training needed before useful predictions
2. **Concept drift:** Model accuracy degrades if system behavior changes significantly
3. **Feedback lag:** Online learning requires delayed feedback (wait for outcome)
4. **Cascade failures:** May miss failures that cascade faster than forecast resolution
5. **Exogenous shocks:** Unexpected events (DDoS, infrastructure failure) outside model's knowledge

## Performance Characteristics

| Operation | Time | Notes |
|-----------|------|-------|
| Train (10 days) | 2-5 min | CPU-intensive, run async |
| Forecast (30 min) | <100ms | Cached, virtual thread safe |
| Pattern matching | <10ms | Fast anomaly matching |
| Model update | <1s | Online learning batch |

## Testing

Run tests with:
```bash
mvn test -Dtest=PerformancePredictorTest
mvn test -Dtest=ClusterOptimizerTest
mvn test -Dtest=PerformancePredictorIntegrationTest
```

**Test coverage:**
- ✓ LSTM model training on synthetic metrics
- ✓ Multi-horizon forecasting (5-min, 30-min, 1-hour)
- ✓ Seasonality decomposition (daily, weekly)
- ✓ Failure pattern discovery
- ✓ Online learning feedback
- ✓ Accuracy metrics computation (MSE, MAE, MAPE, AUC-ROC)
- ✓ Integration with ClusterOptimizer
- ✓ Cost-aware scaling decisions
- ✓ Action history and idempotency

## See Also

- `PerformancePredictor.java` – Interface and data types
- `LSTMPerformancePredictorImpl.java` – LSTM implementation
- `ClusterOptimizer.java` – Optimization policy and actions
- `ClusterOptimizerImpl.java` – Decision engine
- `PerformancePredictorTest.java` – Unit tests
- `PerformancePredictorIntegrationTest.java` – Integration tests
