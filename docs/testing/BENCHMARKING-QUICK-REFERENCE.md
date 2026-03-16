# Benchmarking Quick Reference

**Version:** 1.0
**Last Updated:** 2026-03-16

---

## ⚡ TL;DR

- ✅ **Trust JMH benchmarks** with proper warmup/fork configuration
- ⚠️ **Treat non-JMH benchmarks** as preliminary estimates only
- ❌ **Never publish claims** without re-validation
- 📖 **Read full methodology:** [BENCHMARKING.md](./BENCHMARKING.md)

---

## 🚨 Critical Issues Summary

| Issue | Impact | Affected Benchmarks | Fix |
|-------|--------|---------------------|-----|
| Dead Code Elimination | Inflated throughput | ObservabilityThroughput, SimpleObservability | Convert to JMH + Blackhole |
| Insufficient Warmup | Pessimistic latency | All non-JMH benchmarks | Use @Warmup(iterations=5, time=1) |
| Missing Blackhole | DCE risk | ActorBenchmark, ParallelBenchmark | Add Blackhole parameter |
| Unstable State | Cold code measured | ActorBenchmark | Move setup to Level.Trial |
| No Variance Reporting | Unknown reliability | Most benchmarks | Report P50/P95/P99 |

**See:** [BENCHMARKING.md Section 3](./BENCHMARKING.md#3-known-issues) for details

---

## 📊 Current Benchmark Quality

| Quality | Count | Percentage | Examples |
|---------|-------|------------|----------|
| ✅ Sound | 8/19 | 42% | FrameworkMetricsProfilingBenchmark, ZeroCostComparativeBenchmark |
| ⚠️ Concerns | 7/19 | 37% | ActorBenchmark, SimpleObservabilityBenchmark |
| ❌ Flawed | 4/19 | 21% | BaselineBenchmark, empty files |

**See:** [benchmark-code-review.md](../validation/performance/benchmark-code-review.md) for full analysis

---

## ✅ Verified Performance Claims

**Ready for Publication:**

- ✅ Volatile read overhead: **2-5ns** (FrameworkMetricsProfilingBenchmark)
- ✅ Static field read: **0.5-1ns** (FrameworkMetricsProfilingBenchmark)
- ✅ System property check: **50-150ns** (FrameworkMetricsProfilingBenchmark)
- ✅ Ideal event bus (disabled): **<50ns** (ZeroCostComparativeBenchmark)
- ✅ JOTP event bus (disabled): **<100ns** (ZeroCostComparativeBenchmark)
- ✅ Parallel.all() speedup: **≥4× on 8-core** (ParallelBenchmark)

---

## ⚠️ Claims Requiring Re-validation

**Do NOT Publish Until Fixed:**

- ⚠️ "4.6M msg/sec throughput" - Based on empty messages, unrealistic
- ⚠️ "Zero-overhead observability" - Dead code elimination risk
- ⚠️ "Proc.tell() latency: 456ns" - Insufficient warmup, no variance
- ⚠️ "Actor pattern ≤15% overhead" - Missing Blackhole, unstable state

**See:** [BENCHMARKING-IMPROVEMENT-PLAN.md](./BENCHMARKING-IMPROVEMENT-PLAN.md) for remediation timeline

---

## 🏃 Quick Start

### Run a Benchmark (JMH)

```bash
# Run all JMH benchmarks
./mvnw test -Dtest=*Benchmark -Djmh.format=json -Djmh.outputDir=target/jmh

# Run specific benchmark
./mvnw test -Dtest=ActorBenchmark -Djmh.format=json

# With GC profiling
./mvnw test -Dtest=MemoryAllocationBenchmark -Djmh.format=json -Djmh.profiles=gc
```

### Generate a Report

```bash
python scripts/benchmark-report.py \
    --input=target/jmh/results.json \
    --format=html \
    --output=benchmark-report.html
```

### Reproduce Published Results

```bash
# 1. Ensure matching hardware (Apple M3 Max)
sysctl -n machdep.cpu.brand_string

# 2. Use exact JVM version
java -version  # Should be openjdk 26-xx

# 3. Run with same configuration
./mvnw clean test -Dtest=ActorBenchmark \
    -Djmh.format=json \
    -Djmh.warmupIterations=5 \
    -Djmh.measurementIterations=10 \
    -Djmh.forks=3
```

---

## 📝 Writing New Benchmarks

### ✅ DO

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class GoodBenchmark {

    @Setup(Level.Trial)
    public void setupTrial() {
        // Setup once per trial
    }

    @Benchmark
    public void benchmarkMethod(Blackhole bh) {
        // Do work
        bh.consume(result);  // ✅ Prevent DCE
    }
}
```

### ❌ DON'T

```java
// ❌ Manual timing loop
@Test
void benchmarkThroughput() {
    long start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
        proc.tell("message");  // ❌ Result discarded
    }
    long end = System.nanoTime();
    // ...
}

// ❌ Insufficient warmup
@BeforeEach
void setUp() {
    for (int i = 0; i < 5_000; i++) {  // ❌ Too few iterations
        proc.tell("warmup");
    }
    Thread.sleep(100);  // ❌ Arbitrary, not measurement-based
}
```

---

## 🔍 Benchmark Review Checklist

### Must-Have (Critical)
- [ ] Uses JMH framework
- [ ] Proper warmup: `@Warmup(iterations = 5, time = 1)`
- [ ] Adequate forks: `@Fork(3)` or higher
- [ ] Blackhole consumption of all results
- [ ] No dead code elimination risk

### Should-Have (Important)
- [ ] Expected results documented in javadoc
- [ ] Component isolation (each benchmark measures one thing)
- [ ] @CompilerControl(DONT_INLINE) to prevent inlining artifacts
- [ ] Assertions to verify correctness
- [ ] Variance reporting (P50/P95/P99 or mean ± std dev)

### Nice-to-Have (Enhanced)
- [ ] GC profiling (`-prof gc`) for allocation benchmarks
- [ ] Assembly output (`-XX:+PrintAssembly`) for JIT analysis
- [ ] Comparison to baseline/reference implementation
- [ ] Historical performance tracking

---

## 🛠️ Common Fixes

### Fix 1: Add Blackhole

**Before:**
```java
@Benchmark
public void tell_throughput() {
    countingActor.tell(1);
}
```

**After:**
```java
@Benchmark
public void tell_throughput(Blackhole bh) {
    countingActor.tell(1);
    bh.consume(countingActor);
}
```

---

### Fix 2: Fix Unstable State

**Before:**
```java
@Setup(Level.Iteration)  // ❌ Too frequent
public void setup() {
    proc = new Proc<>(0, handler);
}
```

**After:**
```java
@Setup(Level.Trial)  // ✅ Once per trial
public void setup() {
    proc = new Proc<>(0, handler);
}
```

---

### Fix 3: Convert Non-JMH to JMH

**Before:**
```java
@Test
void benchmark() {
    // Warmup
    for (int i = 0; i < 5_000; i++) {
        proc.tell("warmup");
    }
    Thread.sleep(50);

    // Measurement
    for (int i = 0; i < ITERATIONS; i++) {
        long start = System.nanoTime();
        proc.tell("msg");
        long end = System.nanoTime();
        latencies.add(end - start);
    }
}
```

**After:**
```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class Benchmark {

    @Setup(Level.Trial)
    public void setupTrial() {
        proc = new Proc<>(0, handler);
    }

    @Benchmark
    public void benchmarkMethod(Blackhole bh) {
        proc.tell("msg");
        bh.consume(proc);
    }
}
```

---

## 📚 Full Documentation

- **[BENCHMARKING.md](./BENCHMARKING.md)** - Comprehensive methodology guide
- **[BENCHMARKING-IMPROVEMENT-PLAN.md](./BENCHMARKING-IMPROVEMENT-PLAN.md)** - Remediation plan
- **[BENCHMARKING-INLINE-DOCS.md](./BENCHMARKING-INLINE-DOCS.md)** - Template for source files
- **[benchmark-code-review.md](../validation/performance/benchmark-code-review.md)** - Agent 10 findings

---

## 🤝 Contributing

Found a benchmark issue? Please:

1. **Check existing documentation** - Is it already documented in BENCHMARKING.md?
2. **Add disclosure header** - See BENCHMARKING-INLINE-DOCS.md
3. **Open an issue** - Describe the problem with evidence
4. **Propose a fix** - Use the improvement plan as a template

---

## 📅 Improvement Timeline

- **Week 1:** Convert non-JMH benchmarks, add Blackhole, fix state
- **Month 1:** Standardize methodology, document expected results
- **Quarter 1:** Add realistic workloads, GC pressure, contention

**See:** [BENCHMARKING-IMPROVEMENT-PLAN.md](./BENCHMARKING-IMPROVEMENT-PLAN.md) for details

---

## ⚠️ Hardware-Specific Caveats

- **Tested on:** Apple M3 Max (16-core CPU, 36GB RAM)
- **ARM architecture:** Different branch prediction, cache sizes than x86_64
- **Virtual threads:** Performance depends heavily on CPU core count
- **Recommendation:** Always re-benchmark on target production hardware

---

## 🎯 Key Takeaways

1. **Not all benchmarks are equal** - Check quality before trusting results
2. **Methodology matters** - Proper warmup, forks, and Blackhole are critical
3. **Transparency builds trust** - We disclose issues, not hide them
4. **Improvement is ongoing** - See the improvement plan for what's next

---

**Remember:** "It is better to be roughly right than precisely wrong." - John Maynard Keynes

**Our approach:** Be honest about being roughly right, and work on becoming precisely right. 🎯
