# JOTP Benchmark Improvement Plan

**Version:** 1.0
**Created:** 2026-03-16
**Status:** 🚧 **In Progress**
**Owner:** Agent 26 (Benchmark Documentation Update)

---

## Executive Summary

This plan addresses critical methodology issues identified in [benchmark-code-review.md](../validation/performance/benchmark-code-review.md). Our goal is to rebuild trust through transparent remediation.

**Timeline:**
- **Week 1:** Critical fixes (convert non-JMH, add Blackhole)
- **Month 1:** Methodology standardization
- **Quarter 1:** Production-ready benchmarks with realistic workloads

---

## Phase 1: Immediate Actions (Week 1)

### 1.1 Convert Non-JMH Benchmarks to JMH

**Priority:** 🔴 **CRITICAL**

**Affected Files:**
- `ObservabilityThroughputBenchmark.java`
- `SimpleObservabilityBenchmark.java`
- `BaselineBenchmark.java`
- `ObservabilityPrecisionBenchmark.java`

**Action:**

#### Step 1: Create JMH Template

```java
// File: src/test/java/io/github/seanchatmangpt/jotp/benchmark/TemplatedBenchmark.java
package io.github.seanchatmangpt.jotp.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

/**
 * Template for JMH benchmarks with proper configuration.
 *
 * <p>Expected results: Document in javadoc (e.g., "Expected: <100ns on M3 Max")
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class TemplatedBenchmark {

    private Proc<Integer, Integer> proc;

    @Setup(Level.Trial)
    public void setupTrial() {
        // Setup once per trial (after warmup)
        this.proc = new Proc<>(0, (state, msg) -> state + msg);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        // Cleanup once per trial
        this.proc.stop();
    }

    @Benchmark
    public void benchmarkMethod(Blackhole bh) {
        // Measure the operation
        proc.tell(1);

        // CRITICAL: Consume result to prevent dead code elimination
        bh.consume(proc);
    }
}
```

#### Step 2: Migrate `SimpleObservabilityBenchmark`

**Before (Non-JMH):**
```java
@Test
void quickOverheadCheck() throws Exception {
    // Warmup
    for (int i = 0; i < 5_000; i++) {
        proc1.tell("warmup");
    }
    Thread.sleep(50);  // ❌ Arbitrary

    // Measurement
    for (int i = 0; i < ITERATIONS; i++) {
        long start = System.nanoTime();  // ❌ Manual timing
        proc1.tell("msg");
        baselineLatencies.add(System.nanoTime() - start);
    }
}
```

**After (JMH):**
```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class SimpleObservabilityBenchmarkJMH {

    @Param({"false", "true"})
    public boolean observabilityEnabled;

    private Proc<Integer, String> proc;

    @Setup(Level.Trial)
    public void setupTrial() {
        if (observabilityEnabled) {
            System.setProperty("jotp.observability.enabled", "true");
        }
        proc = new Proc<>(0, (state, msg) -> state + 1);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        proc.stop();
        System.clearProperty("jotp.observability.enabled");
    }

    @Benchmark
    public void procTell(Blackhole bh) {
        proc.tell("msg");
        bh.consume(proc);
    }

    /**
     * Expected Results (on M3 Max):
     * - Disabled: <50ns (P95)
     * - Enabled: <150ns (P95)
     * - Overhead: <100ns
     */
}
```

**Benefits:**
- ✅ Proper warmup (5 iterations × 1 second)
- ✅ Dead code elimination prevention (Blackhole)
- ✅ Statistical confidence (10 measurement iterations, 3 forks)
- ✅ Variance reporting (automatic with JMH)

---

#### Step 3: Migrate `ObservabilityThroughputBenchmark`

**Before (Non-JMH):**
```java
while (System.nanoTime() < disabledEnd) {
    baselineProc.tell("message");
    disabledCount.incrementAndGet();  // ❌ Only side effect
}
```

**After (JMH):**
```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class ObservabilityThroughputBenchmarkJMH {

    @Param({"false", "true"})
    public boolean observabilityEnabled;

    private Proc<Integer, String> proc;

    @Setup(Level.Trial)
    public void setupTrial() {
        if (observabilityEnabled) {
            System.setProperty("jotp.observability.enabled", "true");
        }
        proc = new Proc<>(0, (state, msg) -> state + 1);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        proc.stop();
        System.clearProperty("jotp.observability.enabled");
    }

    @Benchmark
    public void tellThroughput(Blackhole bh) {
        proc.tell("message");
        bh.consume(proc);
    }

    /**
     * Expected Results (on M3 Max):
     * - Disabled: >10M ops/sec
     * - Enabled: >5M ops/sec (should be ≥50% of disabled)
     */
}
```

---

### 1.2 Add Blackhole to Vulnerable Benchmarks

**Priority:** 🔴 **CRITICAL**

**Affected Files:**
- `ActorBenchmark.java` (tell_throughput method)
- `ParallelBenchmark.java` (parallel_fanout method)
- `ResultBenchmark.java` (some methods)

**Action:**

#### Fix `ActorBenchmark.tell_throughput()`

**Before:**
```java
@Benchmark
public void tell_throughput() {
    countingActor.tell(1);  // ❌ Result discarded
}
```

**After:**
```java
@Benchmark
public void tell_throughput(Blackhole bh) {
    countingActor.tell(1);
    bh.consume(countingActor);  // ✅ Prevent DCE
}
```

#### Fix `ParallelBenchmark.parallel_fanout()`

**Before:**
```java
@Benchmark
public List<String> parallel_fanout() {
    return Parallel.all(List.of(
        () -> task1(),
        () -> task2(),
        () -> task3(),
        () -> task4()
    ));
}
```

**After:**
```java
@Benchmark
public void parallel_fanout(Blackhole bh) {
    List<String> results = Parallel.all(List.of(
        () -> task1(),
        () -> task2(),
        () -> task3(),
        () -> task4()
    ));
    bh.consume(results);  // ✅ Prevent DCE
}
```

---

### 1.3 Fix Unstable State Setup

**Priority:** 🟡 **HIGH**

**Affected Files:**
- `ActorBenchmark.java`
- `EnterpriseCapacityBenchmark.java`

**Action:**

#### Fix `ActorBenchmark.setup()`

**Before:**
```java
@Setup(Level.Iteration)  // ❌ Too frequent
public void setup() throws Exception {
    countingActor = new Proc<>(0, (state, msg) -> state + msg);
    echoActor = new Proc<>(0, (_, msg) -> msg);
    rawQueue = new LinkedTransferQueue<>();
    // ...
}
```

**After:**
```java
@Setup(Level.Trial)  // ✅ Once per trial
public void setup() throws Exception {
    countingActor = new Proc<>(0, (state, msg) -> state + msg);
    echoActor = new Proc<>(0, (_, msg) -> msg);
    rawQueue = new LinkedTransferQueue<>();
    // ...
}
```

**Rationale:** Creating processes at `Level.Iteration` prevents JIT warmup because the process is recreated before every measurement iteration. Moving to `Level.Trial` ensures the same process is used across all measurement iterations, allowing proper C2 compilation.

---

### 1.4 Delete Empty Benchmark Files

**Priority:** 🟢 **MEDIUM**

**Affected Files:**
- `BaselinePerformanceBenchmark.java` (empty)
- `ObservabilityThroughputBenchmark.java` (root package, empty)
- `SimpleThroughputBenchmark.java` (empty)

**Action:**
```bash
git rm src/test/java/io/github/seanchatmangpt/jotp/benchmark/BaselinePerformanceBenchmark.java
git rm src/test/java/io/github/seanchatmangpt/jotp/benchmark/ObservabilityThroughputBenchmark.java
git rm src/test/java/io/github/seanchatmangpt/jotp/benchmark/SimpleThroughputBenchmark.java
```

---

## Phase 2: Methodology Standardization (Month 1)

### 2.1 Create Benchmark Template

**File:** `src/test/java/io/github/seanchatmangpt/jotp/benchmark/BenchmarkTemplate.java`

```java
package io.github.seanchatmangpt.jotp.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

/**
 * Standard JOTP benchmark template.
 *
 * <p><strong>Configuration:</strong>
 * <ul>
 *   <li>Warmup: 5 iterations × 1 second (ensures C2 compilation)</li>
 *   <li>Measurement: 10 iterations × 1 second (statistical confidence)</li>
 *   <li>Forks: 3 (detects inter-run variance)</li>
 *   <li>Time unit: NANOSECONDS for microbenchmarks, SECONDS for throughput</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @Benchmark
 * public void myOperation(Blackhole bh) {
 *     // Do work
 *     bh.consume(result);
 * }
 * }</pre>
 *
 * <p><strong>Expected Results:</strong>
 * Document expected performance in javadoc (e.g., "Expected: <100ns on M3 Max").
 *
 * <p><strong>Assertions:</strong>
 * Add assertions in @TearDown to verify correctness:
 * <pre>{@code
 * @TearDown(Level.Trial)
 * public void tearDownTrial() {
 *     assertThat(proc.state()).isEqualTo(expectedState);
 * }
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class BenchmarkTemplate {

    /**
     * Setup method - runs ONCE per trial (after warmup).
     *
     * <p>Use Level.Trial for expensive setup (process creation, connections).
     * Use Level.Iteration for per-iteration state reset.
     */
    @Setup(Level.Trial)
    public void setupTrial() {
        // Setup code here
    }

    /**
     * Teardown method - runs ONCE per trial.
     *
     * <p>Use for cleanup and assertions.
     */
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        // Cleanup and assertions here
    }

    /**
     * Benchmark method.
     *
     * <p><strong>Critical:</strong> Always consume results with Blackhole to prevent
     * dead code elimination.
     *
     * @param bh Blackhole for consuming results
     */
    @Benchmark
    public void benchmarkMethod(Blackhole bh) {
        // Benchmark code here
        bh.consume(/* result */);
    }
}
```

---

### 2.2 Add Compiler Control Annotations

**Priority:** 🟡 **HIGH**

**Purpose:** Prevent inlining artifacts that skew microbenchmark results.

**Affected Files:**
- `JITCompilationAnalysisBenchmark.java`
- All benchmarks measuring component-level overhead

**Action:**

```java
@Benchmark
@CompilerControl(CompilerControl.Mode.DONT_INLINE)
public void volatileRead(Blackhole bh) {
    boolean value = volatileField;
    bh.consume(value);
}
```

**Rationale:** Prevents JIT from inlining the benchmark method itself, which can artificially reduce measured overhead.

---

### 2.3 Document Expected Results

**Priority:** 🟡 **HIGH**

**Action:** Add javadoc to all @Benchmark methods documenting expected performance.

**Example:**

```java
/**
 * Benchmarks Proc.tell() latency with observability disabled.
 *
 * <p><strong>Expected Results (on Apple M3 Max):</strong>
 * <ul>
 *   <li>Mean: 40-60ns</li>
 *   <li>P95: <100ns</li>
 *   <li>P99: <200ns</li>
 * </ul>
 *
 * <p><strong>Rationale:</strong>
 * LinkedTransferQueue.offer() is ~30ns, plus virtual thread overhead (~10ns).
 *
 * @param bh Blackhole for consuming results
 */
@Benchmark
public void procTell_disabled(Blackhole bh) {
    proc.tell("message");
    bh.consume(proc);
}
```

---

### 2.4 Add Variance Reporting

**Priority:** 🟡 **HIGH**

**Action:** Update benchmark reporting to include variance metrics.

**Script:** `scripts/benchmark-report.py` (update existing)

```python
def calculate_variance(results):
    """Calculate variance metrics for JMH results."""
    scores = [r['score'] for r in results]

    mean = statistics.mean(scores)
    stdev = statistics.stdev(scores) if len(scores) > 1 else 0

    # Calculate coefficient of variation (CV)
    cv = (stdev / mean) * 100 if mean > 0 else 0

    return {
        'mean': mean,
        'stdev': stdev,
        'min': min(scores),
        'max': max(scores),
        'cv_percent': cv,
        'confidence_95': 1.96 * stdev / math.sqrt(len(scores))
    }

# In report generation
variance = calculate_variance(benchmark_results)
print(f"| Mean | {variance['mean']:.2f} ± {variance['stdev']:.2f} |")
print(f"| 95% CI | ±{variance['confidence_95']:.2f} |")
print(f"| CV | {variance['cv_percent']:.1f}% |")
```

---

## Phase 3: Production-Ready Benchmarks (Quarter 1)

### 3.1 Add Realistic Workload Benchmarks

**Priority:** 🟢 **MEDIUM**

**Issue:** Current benchmarks use empty or tiny messages, inflating throughput numbers.

**Action:** Create `RealisticWorkloadBenchmark.java`

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class RealisticWorkloadBenchmark {

    @Param({
        "empty",
        "small",    // 32 bytes (simulates ID + timestamp)
        "medium",   // 256 bytes (simulates domain event)
        "large"     // 4096 bytes (simulates document)
    })
    public String payloadSize;

    private Proc<Integer, byte[]> proc;
    private byte[] message;

    @Setup(Level.Trial)
    public void setupTrial() {
        proc = new Proc<>(0, (state, msg) -> state + msg.length);

        // Create realistic payload
        message = switch (payloadSize) {
            case "empty" -> new byte[0];
            case "small" -> new byte[32];
            case "medium" -> new byte[256];
            case "large" -> new byte[4096];
            default -> throw new IllegalArgumentException();
        };
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        proc.stop();
    }

    @Benchmark
    public void tellThroughput(Blackhole bh) {
        proc.tell(message);
        bh.consume(proc);
    }

    /**
     * Expected Results (on M3 Max):
     * - Empty: >10M ops/sec
     * - Small (32B): >5M ops/sec
     * - Medium (256B): >1M ops/sec
     * - Large (4KB): >100K ops/sec
     *
     * <p>Note: Large payload throughput is ~100× lower than empty messages.
     */
}
```

---

### 3.2 Add GC Pressure Simulation

**Priority:** 🟢 **MEDIUM**

**Purpose:** Measure impact of GC pauses on tail latency.

**Action:** Create `GCPressureBenchmark.java`

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class GCPressureBenchmark {

    @Param({
        "false",  // No GC pressure
        "true"    // With GC pressure
    })
    public boolean gcPressure;

    private Proc<Integer, String> proc;

    @Setup(Level.Trial)
    public void setupTrial() {
        proc = new Proc<>(0, (state, msg) -> state + 1);

        if (gcPressure) {
            // Pre-allocate 10MB of garbage to trigger GC
            for (int i = 0; i < 10_000; i++) {
                new byte[1024];  // 1KB × 10K = 10MB
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        proc.stop();
    }

    @Benchmark
    public void tellWithGC(Blackhole bh) {
        if (gcPressure) {
            // Allocate garbage during measurement
            new byte[128];  // 128 bytes per message
        }
        proc.tell("message");
        bh.consume(proc);
    }

    /**
     * Expected Results (on M3 Max):
     * - No GC pressure: <100ns (P95)
     * - With GC pressure: <500ns (P95)
     *
     * <p>Note: GC pressure increases tail latency by ~5×.
     */
}
```

**Run with GC profiling:**
```bash
./mvnw test -Dtest=GCPressureBenchmark -Djmh.format=json \
    -Djmh.profiles=gc
```

---

### 3.3 Add Contention Scenarios

**Priority:** 🟢 **MEDIUM**

**Purpose:** Measure performance under concurrent load.

**Action:** Create `ContentionBenchmark.java`

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Group)
public class ContentionBenchmark {

    @Param({"1", "2", "4", "8", "16"})
    public int threads;

    private Proc<Integer, Integer> proc;

    @Setup(Level.Trial)
    public void setupTrial() {
        proc = new Proc<>(0, (state, msg) -> state + msg);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        proc.stop();
    }

    @Benchmark
    @Group(threads = 1)  // Will be overridden by @Param
    public void concurrentTell(Blackhole bh) {
        proc.tell(1);
        bh.consume(proc);
    }

    /**
     * Expected Results (on M3 Max with 16 cores):
     * - 1 thread: <100ns (P95)
     * - 2 threads: <150ns (P95)
     * - 4 threads: <250ns (P95)
     * - 8 threads: <500ns (P95)
     * - 16 threads: <1000ns (P95)
     *
     * <p>Note: Contention increases latency linearly with thread count.
     */
}
```

---

## Phase 4: Continuous Benchmark Regression Detection

### 4.1 Setup Nightly Benchmarks

**Priority:** 🟢 **LOW (Future)**

**Action:** Create GitHub Actions workflow

```yaml
# File: .github/workflows/nightly-benchmarks.yml
name: Nightly Benchmarks

on:
  schedule:
    - cron: '0 2 * * *'  # 2 AM UTC
  workflow_dispatch:

jobs:
  benchmark:
    runs-on: macos-15  # Match production hardware (Apple Silicon)
    steps:
      - uses: actions/checkout@v4

      - name: Setup Java 26
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '26'

      - name: Run Benchmarks
        run: |
          ./mvnw test -Dtest=*Benchmark -Djmh.format=json \
            -Djmh.outputDir=target/jmh

      - name: Generate Report
        run: |
          python scripts/benchmark-report.py \
            --input=target/jmh/results.json \
            --output=benchmark-report.html

      - name: Upload Results
        uses: actions/upload-artifact@v4
        with:
          name: benchmark-results
          path: |
            target/jmh/results.json
            benchmark-report.html

      - name: Detect Regressions
        run: |
          python scripts/detect-regressions.py \
            --current=target/jmh/results.json \
            --baseline=benchmark-history/latest.json \
            --threshold=10  # Alert if >10% degradation
```

---

### 4.2 Track Historical Performance

**Priority:** 🟢 **LOW (Future)**

**Action:** Create `scripts/detect-regressions.py`

```python
#!/usr/bin/env python3
"""Detect benchmark regressions by comparing current vs baseline."""

import json
import sys

def load_results(path):
    with open(path) as f:
        return json.load(f)

def compare_benchmarks(current, baseline, threshold_pct=10):
    """Compare current results to baseline, alert on regression."""
    regressions = []

    for curr_bench in current:
        name = curr_bench['benchmark']
        baseline_bench = next((b for b in baseline if b['benchmark'] == name), None)

        if not baseline_bench:
            continue

        curr_score = curr_bench['primaryMetric']['score']
        base_score = baseline_bench['primaryMetric']['score']

        # Calculate percentage change
        change_pct = ((curr_score - base_score) / base_score) * 100

        # Alert on regression (higher is worse for latency)
        if change_pct > threshold_pct:
            regressions.append({
                'benchmark': name,
                'baseline': base_score,
                'current': curr_score,
                'change_pct': change_pct
            })

    return regressions

if __name__ == '__main__':
    current = load_results(sys.argv[1])
    baseline = load_results(sys.argv[2])
    threshold = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0

    regressions = compare_benchmarks(current, baseline, threshold)

    if regressions:
        print("🚨 BENCHMARK REGRESSIONS DETECTED:")
        for r in regressions:
            print(f"  {r['benchmark']}: +{r['change_pct']:.1f}% " +
                  f"({r['baseline']:.2f} → {r['current']:.2f})")
        sys.exit(1)
    else:
        print("✅ No regressions detected")
        sys.exit(0)
```

---

## Success Criteria

### Phase 1 (Week 1)
- [ ] All non-JMH benchmarks converted to JMH
- [ ] Blackhole added to all vulnerable benchmarks
- [ ] Unstable state setup fixed
- [ ] Empty benchmark files deleted

### Phase 2 (Month 1)
- [ ] Benchmark template created and documented
- [ ] @CompilerControl annotations added
- [ ] Expected results documented for all benchmarks
- [ ] Variance reporting implemented

### Phase 3 (Quarter 1)
- [ ] Realistic workload benchmarks created
- [ ] GC pressure simulation implemented
- [ ] Contention scenarios added
- [ ] All documentation updated

### Phase 4 (Future)
- [ ] Nightly benchmark workflow in CI/CD
- [ ] Regression detection script operational
- [ ] Historical performance tracking dashboard

---

## Next Actions

1. **Immediate (Today):**
   - Review this improvement plan with team
   - Prioritize benchmarks for conversion
   - Create JMH template file

2. **This Week:**
   - Convert `SimpleObservabilityBenchmark` to JMH
   - Add Blackhole to `ActorBenchmark`
   - Fix unstable state in `ActorBenchmark`

3. **Next Sprint:**
   - Complete Phase 1 actions
   - Start Phase 2 standardization
   - Update documentation with known issues

---

**Document Owner:** Agent 26 (Benchmark Documentation Update)
**Review Date:** 2026-04-16 (1 month)
**Status:** 🚧 In Progress - Awaiting Implementation
