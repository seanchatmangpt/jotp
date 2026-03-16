# JOTP Benchmarking Methodology

**Version:** 1.0
**Last Updated:** 2026-03-16
**Status:** ⚠️ **Transparent Disclosure of Known Issues**

---

## Executive Summary

This document provides **honest, transparent documentation** of JOTP's benchmarking methodology, including **known limitations** and areas requiring improvement. Our goal is to build trust through full disclosure, not to present an artificially polished picture.

**Current State:**
- ✅ **8/19 benchmarks (42%)** are well-designed with proper JMH methodology
- ⚠️ **7/19 benchmarks (37%)** have methodology concerns requiring attention
- ❌ **4/19 benchmarks (21%)** are fundamentally flawed or invalid

**Key Finding:** Several published performance claims require re-validation with improved methodology. See [Known Issues](#known-issues) below.

---

## 1. Benchmark Categories

### 1.1 Microbenchmarks (JMH-based)

**Purpose:** Isolate and measure specific components with nanosecond precision.

**Examples:**
- `FrameworkMetricsProfilingBenchmark` - Volatile reads, system property checks
- `ZeroCostComparativeBenchmark` - Ideal vs. production event bus
- `MemoryAllocationBenchmark` - GC pressure analysis

**Status:** ✅ Generally reliable with proper warmup/fork configuration

---

### 1.2 Integration Benchmarks (JMH-based)

**Purpose:** Measure end-to-end performance of multi-component operations.

**Examples:**
- `ActorBenchmark` - Actor pattern overhead vs. raw queue
- `ParallelBenchmark` - Structured concurrency speedup
- `ResultBenchmark` - Railway pattern vs. try-catch

**Status:** ⚠️ Mixed - some have missing Blackhole or unstable state

---

### 1.3 Ad-Hoc Benchmarks (Non-JMH)

**Purpose:** Quick validation for CI/CD or exploratory testing.

**Examples:**
- `SimpleObservabilityBenchmark` - Quick overhead check
- `ObservabilityThroughputBenchmark` - Throughput comparison
- `PayloadSizeThroughputBenchmark` - Realistic payload analysis

**Status:** ❌ **Critical Issues** - Dead code elimination risks, insufficient warmup

**Critical Limitation:** These benchmarks should **NOT** be used for published performance claims without re-validation.

---

## 2. Current Methodology

### 2.1 JMH Configuration (Well-Designed Benchmarks)

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class WellDesignedBenchmark {
    @Benchmark
    public void benchmarkMethod(Blackhole bh) {
        // Result consumed to prevent dead code elimination
        bh.consume(someComputation());
    }
}
```

**Rationale:**
- **5 warmup iterations × 1 second:** Ensures C2 JIT compilation completes
- **10 measurement iterations:** Provides statistical confidence
- **3 forks:** Detects inter-run variance
- **Blackhole consumption:** Prevents JIT dead code elimination

---

### 2.2 Ad-Hoc Benchmark Configuration (Problematic)

```java
// ❌ AVOID THIS PATTERN
private static final int WARMUP_ITERATIONS = 10_000;

@BeforeEach
void setUp() {
    // Only 10K warmup iterations - insufficient for C2!
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        proc.tell("warmup");
    }
    Thread.sleep(100);  // Arbitrary sleep, not measurement-based
}

@Test
void benchmarkThroughput() {
    long start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
        proc.tell("message");  // ❌ Result discarded - DCE risk!
    }
    long end = System.nanoTime();
    // Calculate and report...
}
```

**Problems:**
1. ❌ **Insufficient warmup:** 10K iterations may not trigger C2 compilation
2. ❌ **Dead code elimination:** JIT may optimize away entire loop
3. ❌ **Manual timing:** Includes System.nanoTime() overhead
4. ❌ **No variance reporting:** Single run, no statistical confidence

---

## 3. Known Issues

### 3.1 ❌ **CRITICAL: Dead Code Elimination Risk**

**Affected Benchmarks:**
- `ObservabilityThroughputBenchmark`
- `SimpleObservabilityBenchmark`
- `BaselineBenchmark`

**Issue:** Loop bodies that don't consume results allow JIT compiler to eliminate code entirely.

**Example:**
```java
while (System.nanoTime() < disabledEnd) {
    baselineProc.tell("message");
    disabledCount.incrementAndGet();  // ← Only side effect
}
```

**Impact:** Published throughput numbers may be **artificially inflated** because the JIT optimized away the actual work.

**Resolution:** Convert to JMH with Blackhole consumption (see [Improvement Plan](#benchmark-improvement-plan)).

---

### 3.2 ❌ **CRITICAL: Insufficient Warmup**

**Affected Benchmarks:** All non-JMH benchmarks

**Issue:** 5,000-10,000 warmup iterations are insufficient for C2 compilation.

**Evidence:**
- Virtual thread JIT compilation typically requires 10,000-20,000 iterations
- Without proper C2 compilation, benchmarks measure partially-interpreted code
- Results may be 2-5× slower than actual steady-state performance

**Impact:** Published latency numbers may be **pessimistic** (worse than reality).

**Resolution:** Use JMH with proper `@Warmup(iterations = 5, time = 1)` or adaptive warmup.

---

### 3.3 ⚠️ **MEDIUM: Missing Blackhole Usage**

**Affected Benchmarks:**
- `ActorBenchmark.tell_throughput()`
- `ParallelBenchmark.parallel_fanout()`
- `ResultBenchmark` (partial)

**Issue:** Return values not consumed, risking dead code elimination.

**Example:**
```java
@Benchmark
public void tell_throughput() {
    countingActor.tell(1);  // ← Result discarded
}
```

**Resolution:** Add Blackhole parameter and consume results.

---

### 3.4 ⚠️ **MEDIUM: Unstable Test State**

**Affected Benchmarks:**
- `ActorBenchmark`
- `EnterpriseCapacityBenchmark`

**Issue:** State setup at `@Setup(Level.Iteration)` creates fresh processes per iteration, preventing JIT warmup.

**Example:**
```java
@Setup(Level.Iteration)  // ← Too frequent!
public void setup() {
    countingActor = new Proc<>(0, (state, msg) -> state + msg);
}
```

**Impact:** Measures cold code every iteration, not steady-state performance.

**Resolution:** Move setup to `@Setup(Level.Trial)`.

---

### 3.5 ⚠️ **MEDIUM: Variance Not Documented**

**Affected Benchmarks:** Most non-JMH benchmarks

**Issue:** Results reported as single numbers without confidence intervals or variance.

**Example:**
> "Proc.tell() latency: 456ns" ← Is this 456±10ns? 456±200ns?

**Impact:** Users cannot assess reliability of measurements.

**Resolution:** Report mean ± standard deviation, or P50/P95/P99 percentiles.

---

## 4. Hardware & Environment

### 4.1 Test Environment

**Hardware:** Apple M3 Max (16-core CPU, 36GB RAM)
**OS:** macOS 15.2 (Darwin 25.2.0)
**JVM:** OpenJDK 26 with `--enable-preview`
**Build Tool:** Maven 4 / mvnd (Maven Daemon)

### 4.2 Hardware-Specific Caveats

⚠️ **Results are hardware-specific!**

- **Apple Silicon:** ARM architecture has different branch prediction, cache sizes than x86_64
- **Virtual Threads:** Performance depends heavily on CPU core count (16 cores here)
- **NUMA Effects:** Not applicable to M3 (unified memory), but affects x86_64 servers

**Recommendation:** Always re-benchmark on target production hardware.

---

## 5. Benchmark Limitations

### 5.1 ❌ **Unrealistic Workloads**

**Issue:** Most benchmarks use empty or tiny messages.

**Example:**
```java
proc.tell("message");  // ← 7-byte string
```

**Reality:** Production systems use 100-1000 byte payloads.

**Evidence from `PayloadSizeThroughputBenchmark`:**
> "Published numbers based on 4.6M msg/sec are **73× higher** than realistic 150-byte frame throughput."

**Impact:** Published throughput numbers are **not achievable** in real-world scenarios.

---

### 5.2 ❌ **No GC Pressure Simulation**

**Issue:** Benchmarks don't account for GC pauses in long-running systems.

**Example:**
```java
for (int i = 0; i < ITERATIONS; i++) {
    proc.tell(new Event(...));  // ← Creates garbage
}
// But never measures GC impact!
}
```

**Reality:** Long-running systems experience periodic GC pauses affecting tail latency.

**Impact:** Published P95/P99 latencies may be **optimistic** (better than reality).

---

### 5.3 ❌ **No Contention Simulation**

**Issue:** Single-threaded benchmarks don't measure contention under concurrent load.

**Example:**
```java
@Benchmark
public void singleThreadedTell() {
    proc.tell(1);  // ← No concurrent writers
}
```

**Reality:** Production systems have dozens/hundreds of concurrent threads contending for the same mailbox.

**Impact:** Published numbers may not reflect **contended performance**.

---

## 6. Reproduction Instructions

### 6.1 Quick Start (JMH Benchmarks)

```bash
# Run all JMH benchmarks
./mvnw test -Dtest=*Benchmark -Djmh.format=json -Djmh.outputDir=target/jmh

# Run specific benchmark
./mvnw test -Dtest=ActorBenchmark -Djmh.format=json

# With GC profiling
./mvnw test -Dtest=MemoryAllocationBenchmark -Djmh.format=json \
    -Djmh.profiles=gc

# With assembly output (requires hsdis)
./mvnw test -Dtest=ActorBenchmark -Djmh.format=json \
    -Djmh.jvmArgsAppend="-XX:+PrintAssembly"
```

---

### 6.2 Quick Validation (Non-JMH Benchmarks)

```bash
# ⚠️ Use ONLY for quick CI/CD validation, NOT for published claims
./mvnw test -Dtest=SimpleObservabilityBenchmark

# With realistic payload size
./mvnw test -Dtest=PayloadSizeThroughputBenchmark
```

**Warning:** Results from non-JMH benchmarks should be labeled as "preliminary estimates" and re-validated with JMH before publication.

---

### 6.3 Reproducing Published Results

**Step 1:** Ensure matching hardware
```bash
# Check CPU info
sysctl -n machdep.cpu.brand_string
# Should be: Apple M3 Max

# Check core count
sysctl -n hw.ncpu
# Should be: 16 (or higher for newer chips)
```

**Step 2:** Use exact JVM version
```bash
java -version
# Should be: openjdk 26-xx
```

**Step 3:** Run with same configuration
```bash
./mvnw clean test -Dtest=ActorBenchmark \
    -Djmh.format=json \
    -Djmh.outputDir=target/jmh \
    -Djmh.warmupIterations=5 \
    -Djmh.measurementIterations=10 \
    -Djmh.forks=3
```

**Step 4:** Compare results
```bash
# Generate report
python scripts/benchmark-report.py \
    --input=target/jmh/results.json \
    --output=benchmark-report.html

# Check variance is within ±20%
```

---

## 7. Performance Claims Validation

### 7.1 ✅ **Verified Claims** (from sound benchmarks)

| Claim | Source | Status | Notes |
|-------|--------|--------|-------|
| Volatile read overhead: 2-5ns | FrameworkMetricsProfilingBenchmark | ✅ Verified | Proper JMH methodology |
| Static field read: 0.5-1ns | FrameworkMetricsProfilingBenchmark | ✅ Verified | Proper JMH methodology |
| System property check: 50-150ns | FrameworkMetricsProfilingBenchmark | ✅ Verified | Proper JMH methodology |
| Ideal event bus (disabled): <50ns | ZeroCostComparativeBenchmark | ✅ Verified | Proper JMH methodology |
| JOTP event bus (disabled): <100ns | ZeroCostComparativeBenchmark | ✅ Verified | Proper JMH methodology |
| Parallel.all() speedup: ≥4× on 8-core | ParallelBenchmark | ✅ Verified | Proper JMH methodology |

---

### 7.2 ⚠️ **Claims Requiring Re-validation**

| Claim | Source | Issue | Action Required |
|-------|--------|-------|-----------------|
| "4.6M msg/sec throughput" | Various benchmarks | Based on empty messages, unrealistic | Re-run with 100+ byte payloads |
| "Zero-overhead observability" | ObservabilityThroughputBenchmark | Dead code elimination risk | Convert to JMH with Blackhole |
| "Proc.tell() latency: 456ns" | Existing results | Insufficient warmup, no variance | Re-run with proper JMH config |
| "Actor pattern ≤15% overhead" | ActorBenchmark | Missing Blackhole, unstable state | Add Blackhole, fix setup level |

---

## 8. Benchmark Improvement Plan

See [BENCHMARKING-IMPROVEMENT-PLAN.md](./BENCHMARKING-IMPROVEMENT-PLAN.md) for detailed remediation steps.

**Quick Summary:**

1. **Immediate Actions (Week 1):**
   - Convert non-JMH benchmarks to JMH
   - Add Blackhole to vulnerable benchmarks
   - Fix unstable state setup

2. **Medium-Term (Month 1):**
   - Standardize warmup/fork configuration
   - Add variance reporting to all benchmarks
   - Document expected results

3. **Long-Term (Quarter 1):**
   - Add realistic workload benchmarks
   - Implement GC pressure simulation
   - Add contention scenarios

---

## 9. Best Practices

### 9.1 Writing New Benchmarks

**✅ DO:**
- Use JMH framework for all microbenchmarks
- Add Blackhole consumption of all results
- Use `@Warmup(iterations = 5, time = 1)` or higher
- Use `@Fork(3)` or higher
- Document expected results in javadoc
- Add assertions to verify correctness

**❌ DON'T:**
- Write manual timing loops with System.nanoTime()
- Use fewer than 10,000 warmup iterations
- Discard benchmark results without consumption
- Publish numbers without variance/confidence intervals
- Use empty messages for throughput benchmarks

---

### 9.2 Reviewing Benchmarks

Use this checklist before accepting benchmark results:

**Must-Have (Critical):**
- [ ] Uses JMH framework
- [ ] Proper warmup: `@Warmup(iterations = 5, time = 1)`
- [ ] Adequate forks: `@Fork(3)` or higher
- [ ] Blackhole consumption of all results
- [ ] No dead code elimination risk

**Should-Have (Important):**
- [ ] Expected results documented in javadoc
- [ ] Component isolation (each benchmark measures one thing)
- [ ] @CompilerControl(DONT_INLINE) to prevent inlining artifacts
- [ ] Assertions to verify correctness
- [ ] Variance reported (mean ± std dev or percentiles)

**Nice-to-Have (Enhanced):**
- [ ] GC profiling (`-prof gc`) for allocation benchmarks
- [ ] Assembly output (`-XX:+PrintAssembly`) for JIT analysis
- [ ] Comparison to baseline/reference implementation
- [ ] Historical performance tracking

---

## 10. Conclusion

### 10.1 Transparency Statement

We acknowledge that several JOTP benchmarks have methodology issues. This document is our commitment to:

1. **Full disclosure** of known issues
2. **Transparent reporting** of benchmark limitations
3. **Active remediation** through the improvement plan
4. **Community trust** through honesty, not marketing claims

### 10.2 Current Reliability Assessment

**Trustworthy Results:**
- ✅ Component-level JMH benchmarks (volatile reads, system property checks)
- ✅ Well-designed JMH benchmarks with proper warmup/fork

**Preliminary Estimates (Re-validation Required):**
- ⚠️ Non-JMH benchmarks (may have DCE or insufficient warmup)
- ⚠️ Benchmarks with missing Blackhole or unstable state

**Not Trustworthy:**
- ❌ Empty benchmark files
- ❌ Benchmarks with dead code elimination risks

### 10.3 Path Forward

1. **Immediate:** Execute benchmark improvement plan
2. **Short-term:** Re-publish validated performance claims
3. **Long-term:** Establish continuous benchmark regression detection

---

**Next Steps:** See [BENCHMARKING-IMPROVEMENT-PLAN.md](./BENCHMARKING-IMPROVEMENT-PLAN.md) for detailed remediation actions.

---

**Document Owner:** JOTP Performance Team
**Review Cycle:** Quarterly (or when methodology changes)
**Feedback:** Please open GitHub issues for benchmark concerns
