# Self-Healing System for JOTP

Comprehensive autonomous failure diagnosis and recovery system for the JOTP framework.

## Components

### 1. SelfHealer.java (Orchestrator)
**Location:** `src/main/java/io/github/seanchatmangpt/jotp/ai/SelfHealer.java`

Central coordinator that runs the healing loop continuously:
- Detect → Diagnose → Decide → Repair → Record

**Key sealed types:**
- `Symptom`: 7 types (HighLatency, MemoryLeak, ExceptionStorm, CpuSaturation, CascadingCrash, CircuitBreakerTrip, DeadlockDetected)
- `Diagnosis`: 7 root causes (NetworkIssue, SlowNode, MemoryLeakProcess, CascadingFailure, ResourceExhaustion, SoftwareBug, ExternalDependencyFailure)
- `Repair`: 8 strategies (RestartProcess, RestartNode, Failover, Rebalance, ScaleUp, DrainAndRestart, CircuitBreakerOpen, GracefulShutdown)
- `RepairOutcome`: Success, Failure, PartialSuccess

**Usage:**
```java
var healer = SelfHealer.create(
    Duration.ofSeconds(5),      // scan interval
    Duration.ofSeconds(60),     // anomaly window
    Duration.ofSeconds(30)      // repair timeout
);
healer.start();
var metrics = healer.metrics();
System.out.println("Success rate: " + metrics.successRate() * 100 + "%");
healer.stop();
```

### 2. AnomalyDetector.java
**Location:** `src/main/java/io/github/seanchatmangpt/jotp/ai/AnomalyDetector.java`

Continuous monitoring system that collects and analyzes metrics:
- JVM memory (heap, GC overhead)
- CPU utilization and thread count
- Process latency (p50, p95, p99)
- Exception rates
- Supervisor health (crash counts)
- Circuit breaker state

**Anomaly detection methods:**
- Threshold-based: p99 latency > 500ms, exceptions > 100/sec
- Trend-based: GC overhead increasing in window
- Distribution-based: Response time distribution shift
- State-based: Deadlock detection

**Usage:**
```java
var detector = new AnomalyDetector(Duration.ofSeconds(60));
detector.recordProcessLatency("proc-1", 150);
detector.recordException("NullPointerException");
detector.recordCircuitBreakerState("api", 45, 5);
var symptoms = detector.scan(); // Returns List<SelfHealer.Symptom>
```

### 3. FailureDiagnoser.java
**Location:** `src/main/java/io/github/seanchatmangpt/jotp/ai/FailureDiagnoser.java`

Root cause analysis engine using diagnostic rules:

**Diagnostic Rules:**
| Symptom | Diagnosis |
|---------|-----------|
| HighLatency | SlowNode, NetworkIssue, or GC pause |
| MemoryLeak | MemoryLeakProcess or ResourceExhaustion |
| ExceptionStorm | SoftwareBug or CascadingFailure |
| CpuSaturation | ResourceExhaustion |
| CascadingCrash | CascadingFailure or ExternalDependencyFailure |
| CircuitBreakerTrip | ExternalDependencyFailure |
| DeadlockDetected | SoftwareBug |

**Usage:**
```java
var diagnoser = new FailureDiagnoser();
var symptom = new SelfHealer.Symptom.HighLatency(900, 700, 5);
var diagnosis = diagnoser.diagnose(symptom);
// diagnosis is a SelfHealer.Diagnosis subtype
```

### 4. AutoRepair.java
**Location:** `src/main/java/io/github/seanchatmangpt/jotp/ai/AutoRepair.java`

Automatic repair executor that applies recovery strategies:

**Repair Strategies:**
- `RestartProcess`: Clean shutdown and restart of a single process
- `RestartNode`: Drain connections and restart entire node
- `Failover`: Shift traffic to standby supervisor
- `Rebalance`: Redistribute load among processes
- `ScaleUp`: Add more worker processes
- `DrainAndRestart`: Gracefully terminate clients before restart
- `CircuitBreakerOpen`: Trip circuit to fail-fast
- `GracefulShutdown`: Orderly termination

**Usage:**
```java
var autoRepair = new AutoRepair(Duration.ofSeconds(30));
var repair = new SelfHealer.Repair.RestartProcess("proc-1", Duration.ofSeconds(5));
var outcome = autoRepair.execute(repair);

// outcome is one of:
// - RepairOutcome.Success(repair, durationMs, detail)
// - RepairOutcome.Failure(repair, cause, detail)
// - RepairOutcome.PartialSuccess(repair, durationMs, successfulCount, failedCount)
```

### 5. RepairDecisionTree.java
**Location:** `src/main/java/io/github/seanchatmangpt/jotp/ai/RepairDecisionTree.java`

Decision tree that maps (symptom, diagnosis) pairs to repair strategies.

**Decision Logic:**
- HighLatency + SlowNode → RestartProcess
- HighLatency + NetworkIssue → Rebalance
- MemoryLeak + MemoryLeakProcess → GracefulShutdown
- ExceptionStorm + CascadingFailure → DrainAndRestart
- CpuSaturation → ScaleUp or Rebalance
- CascadingCrash + Repairable → DrainAndRestart
- CascadingCrash + NonRepairable → Failover
- CircuitBreakerTrip → CircuitBreakerOpen
- DeadlockDetected → RestartNode

**Escalation:** If a repair fails, escalate to more aggressive strategy:
- RestartProcess → DrainAndRestart → RestartNode → Failover

**Usage:**
```java
var tree = new RepairDecisionTree();
var repair = tree.selectRepair(symptom, diagnosis);

// If repair fails, escalate
var escalated = tree.escalateRepair(failedRepair, symptom);
```

### 6. SelfHealerMetrics.java
**Location:** `src/main/java/io/github/seanchatmangpt/jotp/ai/SelfHealerMetrics.java`

Metrics collection and analysis for self-healing operations.

**Tracked Metrics:**
- Total repair attempts
- Success/failure/partial success counts
- Time-to-recovery (TTR) statistics (min, max, avg)
- Repair duration statistics
- Root cause distribution
- Most frequent symptoms and diagnoses
- Most effective repair strategies

**Usage:**
```java
var metrics = healer.metrics(); // Returns SelfHealerMetrics.Snapshot

System.out.println("Success rate: " + metrics.successRate());
System.out.println("Avg TTR: " + metrics.avgTimeToRecovery() + "ms");
System.out.println("Most frequent symptom: " + metrics.getMostFrequentSymptom());
System.out.println(metrics); // Pretty-printed summary
```

## Example: Full Healing Workflow

```java
// 1. Create healer
var healer = SelfHealer.create(
    Duration.ofSeconds(5),      // fast scans for demo
    Duration.ofSeconds(2),      // short anomaly window
    Duration.ofSeconds(10)      // repair timeout
);

// 2. Start autonomous healing
healer.start();

// 3. Simulate a system failure (e.g., high latency)
var detector = new AnomalyDetector(Duration.ofSeconds(2));
for (int i = 0; i < 10; i++) {
    detector.recordProcessLatency("proc-1", 600 + i * 50);
    Thread.sleep(100);
}

// 4. System detects, diagnoses, repairs automatically
var symptoms = detector.scan();
if (!symptoms.isEmpty()) {
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptoms.get(0));
    
    var tree = new RepairDecisionTree();
    var repair = tree.selectRepair(symptoms.get(0), diagnosis);
    
    var autoRepair = new AutoRepair(Duration.ofSeconds(10));
    var outcome = autoRepair.execute(repair);
    
    System.out.println("Repair outcome: " + outcome);
}

// 5. Check metrics
var metrics = healer.metrics();
System.out.println(metrics);

// 6. Stop healing
healer.stop();
```

## Tests

### Unit Tests
**Location:** `src/test/java/io/github/seanchatmangpt/jotp/ai/SelfHealerTest.java`

Coverage:
- High latency detection and repair
- Memory leak detection and repair
- Cascading crash detection and escalation
- Exception storm detection
- CPU saturation detection
- Circuit breaker trip detection
- Deadlock detection
- Metrics collection accuracy
- Event logging
- Multiple concurrent symptoms

### Integration Tests
**Location:** `src/test/java/io/github/seanchatmangpt/jotp/ai/SelfHealerIT.java`

Scenarios:
- Sustained high latency recovery
- Memory leak progression and repair
- Cascading crash recovery with escalation
- Circuit breaker trip and recovery
- Mixed failure modes with concurrent repairs
- Repair success rate tracking
- Time-to-recovery metrics
- Diagnosis accuracy
- Decision tree coverage
- Long-running healer stability

## Repair Examples in Action

### 1. High Latency → Restart Slow Node
```
Symptom: HighLatency(p99=900ms, p95=700ms, affectedProcesses=5)
  ↓
Diagnosis: SlowNode(nodeId="node-1", responseTime=900ms, bottleneck="CPU bottleneck")
  ↓
Repair: RestartProcess("node-1", timeout=5s)
  ↓
Outcome: Success(duration=150ms, detail="Process node-1 restarted")
```

### 2. Memory Leak → Graceful Shutdown
```
Symptom: MemoryLeak(gcOverhead=0.50, heapUsed=1GB, leakingProcesses=2)
  ↓
Diagnosis: MemoryLeakProcess(processId="proc-leak", leakRate=1MB/s, heapUsage=95%)
  ↓
Repair: GracefulShutdown("proc-leak", timeout=10s)
  ↓
Outcome: Success(duration=400ms, detail="Gracefully shut down proc-leak")
```

### 3. Cascading Crash → Drain and Restart
```
Symptom: CascadingCrash(crashedProcesses=8, timeSinceFirstCrash=15s, rootCause="Primary down")
  ↓
Diagnosis: CascadingFailure(initialCrash="Primary", affectedDownstream=8, isRepairable=true)
  ↓
Repair: DrainAndRestart("proc-root", drainTimeout=5s)
  ↓
Outcome: PartialSuccess(duration=500ms, successfulItems=95, failedItems=5)
```

### 4. Repair Failure → Escalation
```
Symptom: CascadingCrash(...)
  ↓
Diagnosis: CascadingFailure(...)
  ↓
Repair 1: DrainAndRestart(...) → FAILS
  ↓
Escalate to: RestartNode("node-cascade", timeout=10s) → SUCCESS
```

## Architecture Decisions

### Why Sealed Types?
- Compiler-enforced exhaustiveness in switch expressions
- Type-safe pattern matching for symptoms, diagnoses, repairs
- Clear separation between different failure modes

### Why Virtual Threads?
- Lightweight monitoring loop can run continuously
- Repair executors don't block on I/O or synchronization
- Thousands of concurrent repairs without thread pool exhaustion

### Why Railway-Oriented Error Handling?
- Result<T, E> for repair outcomes (Success/Failure/PartialSuccess)
- Explicit error propagation without exceptions
- Composable recovery pipelines

### Repair vs. Recovery
- **Repair**: Specific action to fix a symptom (e.g., restart a process)
- **Recovery**: Broader strategy including fallback and escalation
- System implements repair-based recovery with automatic escalation

## Configuration & Tuning

### Scan Interval
Frequency of anomaly detection. Shorter = faster response, higher CPU.
```java
Duration.ofSeconds(5)  // Default: scan every 5 seconds
```

### Anomaly Window
Time window for trend analysis. Longer = smoother detection, higher latency.
```java
Duration.ofSeconds(60) // Default: 1-minute window
```

### Repair Timeout
Maximum duration for a repair attempt before failure.
```java
Duration.ofSeconds(30) // Default: 30-second timeout
```

### Thresholds (in AnomalyDetector)
- Latency p99: 500ms
- Exception rate: 100/sec
- GC overhead: 40%
- CPU usage: 80%
- Cascading crash threshold: 5+ crashes in window
- Circuit breaker error rate: 20%

## Performance Characteristics

- **Detection latency**: ~scan_interval (default 5s)
- **Diagnosis time**: ~1-10ms
- **Average repair duration**: 100-500ms depending on strategy
- **Metrics overhead**: <1% CPU per healer instance
- **Memory per healer**: ~10MB (event/repair logs)

## Future Enhancements

1. Machine learning for diagnosis accuracy
2. Predictive healing (before symptoms appear)
3. Cross-node repair coordination
4. Distributed consensus for failover
5. Custom repair strategies via plugins
6. Probabilistic repair selection
7. Cost-aware repair optimization
