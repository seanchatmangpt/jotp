# JOTP Profiling Guide

**Version:** 1.0
**Last Updated:** 2026-03-15

## Overview

This guide covers profiling JOTP applications using JMH benchmarks, Java Flight Recorder, Async Profiler, and flame graph analysis to identify performance bottlenecks and optimization opportunities.

---

## 1. JMH Benchmark Usage

### 1.1 Running JMH Benchmarks

**Quick Start:**
```bash
# Run all benchmarks
./mvnd test -Pbenchmark

# Run specific benchmark class
./mvnd test -Dtest=ObservabilityThroughputBenchmark -Pbenchmark

# Run with GC profiling
./mvnd test -Dtest=ObservabilityPrecisionBenchmark -Pbenchmark -Djmh.profiler=gc

# Run with async-profiler
java -jar target/benchmarks.jar -prof async
```

### 1.2 JMH Configuration

**Standard JMH Annotations:**
```java
@BenchmarkMode(Mode.Throughput)           // Measure ops/sec
@OutputTimeUnit(TimeUnit.NANOSECONDS)     // Report in nanoseconds
@Warmup(iterations = 5, time = 1)         // 5 iterations, 1 second each
@Measurement(iterations = 10, time = 1)   // 10 iterations, 1 second each
@Fork(3)                                  // Run in 3 separate JVMs
@State(Scope.Benchmark)                   // Shared state across benchmark iterations
public class MyBenchmark {
    @Benchmark
    public void benchmarkMethod() {
        // Code to benchmark
    }
}
```

### 1.3 Benchmark Modes

| Mode | Measures | Use Case | Output |
|------|----------|----------|--------|
| **Throughput** | Operations per second | Max capacity | ops/sec |
| **AverageTime** | Average operation time | Typical latency | ns/op |
| **SampleTime** | Latency distribution | Percentiles | P50, P95, P99 |
| **SingleShotTime** | One-time operation | Cold start | μs/op |

### 1.4 Common Benchmark Patterns

**Microbenchmark (fast path):**
```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public void benchmarkFastPath() {
    proc.tell(message);  // Measure single operation
}
```

**Throughput benchmark (sustained load):**
```java
@Benchmark
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public void benchmarkThroughput() {
    proc.tell(message);
    // JMH runs this continuously for 1 second
}
```

**Stateful benchmark:**
```java
@State(Scope.Benchmark)
public class BenchmarkState {
    Proc<S, M> proc;

    @Setup(Level.Trial)
    public void setup() {
        proc = Proc.create(initialState);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        proc.stop();
    }
}
```

---

## 2. Java Flight Recorder (JFR)

### 2.1 Enabling JFR

**Start application with JFR:**
```bash
# Start with JFR recording
java -XX:StartFlightRecording=filename=jotp.jfr,duration=60s \
     -jar target/jotp.jar

# Start with continuous recording
java -XX:StartFlightRecording=filename=jotp.jfr,maxage=1h,maxsize=1G \
     -jar target/jotp.jar

# Start with profiling settings
java -XX:StartFlightRecording=settings=profile \
     -jar target/jotp.jar
```

### 2.2 JFR Events for JOTP

| Event Type | What it Measures | JOTP Relevance |
|------------|------------------|----------------|
| **Thread Sleep** | Virtual thread parking | Mailbox idle time |
| **Thread Park** | Virtual thread blocking | Synchronization contention |
| **Allocation in new TLAB** | Heap allocations | Process creation overhead |
| **Allocation outside TLAB** | Large allocations | Message object creation |
| **GC Heap Summary** | Heap usage | Mailbox memory pressure |
| **CPU Load** | CPU utilization | Carrier thread saturation |

### 2.3 Analyzing JFR Recordings

**Using JDK Mission Control (jmc):**
```bash
# Open JFR recording
jmc jotp.jfr

# Key areas to inspect:
# 1. Thread -> Virtual Thread parking (identify bottlenecks)
# 2. Memory -> Allocation rate (find hot allocations)
# 3. GC -> Pause times (identify GC pressure)
# 4. Code -> Hot methods (find optimization targets)
```

**Using jfr command-line tool:**
```bash
# Print summary
jfr print jotp.jfr

# Extract thread information
jfr print --events jdk.ThreadPark jotp.jfr

# Extract allocation information
jfr print --events jdk.ObjectAllocationInNewTLAB jotp.jfr

# Export to CSV
jfr export --events jdk.ThreadPark jotp.jfr > thread-park.csv
```

### 2.4 JFR for Virtual Threads

**Virtual thread-specific events:**
```bash
# Focus on virtual thread operations
jfr print --events jdk.VirtualThreadPinned \
          --events jdk.VirtualThreadSubmitFailed \
          jotp.jfr
```

**Analyzing pinning:**
```bash
# Find pinned virtual threads (blocks carrier threads)
jfr print --events jdk.VirtualThreadPinned jotp.jfr | \
  grep "Pinned duration" | \
  awk '{print $3}' | \
  sort -n
```

---

## 3. Async Profiler

### 3.1 Installation

```bash
# Download async-profiler
wget https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.9/async-profiler-2.9-linux-x64.tar.gz

# Extract
tar -xzf async-profiler-2.9-linux-x64.tar.gz

# Set up environment
export ASYNC_PROFILER_DIR=/path/to/async-profiler
```

### 3.2 CPU Profiling

**Start profiling:**
```bash
# Profile CPU for 30 seconds
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f cpu-profile.html -o flamegraph \
  -j --include "io.github.seanchatmangpt.jotp.*" \
  pid

# Profile with JVM arguments
java -agentpath:/path/to/libasyncProfiler.so=start,svg,file=profile.svg,interval=1ms \
     -jar target/jotp.jar
```

**CPU Profiling Options:**
| Option | Description | Recommended Value |
|--------|-------------|-------------------|
| `-d` | Duration | 30-60 seconds |
| `-i` | Sampling interval | 1-10ms |
| `-j` | Java stack traces | Always use |
| `-o` | Output format | `flamegraph`, `tree`, `collapsed` |
| `--include` | Filter packages | `io.github.seanchatmangpt.jotp.*` |

### 3.3 Allocation Profiling

**Profile memory allocations:**
```bash
# Profile allocations
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f alloc-profile.html -o flamegraph \
  --alloc -j --include "io.github.seanchatmangpt.jotp.*" \
  pid

# Profile TLAB allocations only
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f tlab-profile.html \
  --alloc --only-tlab \
  pid
```

### 3.4 Wall-Clock Profiling

**Profile wall-clock time (includes sleep/park):**
```bash
# Wall-clock profiling (identifies blocking)
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f wall-clock.html \
  -o flamegraph -e cpu,wall \
  pid
```

---

## 4. Flame Graph Analysis

### 4.1 Reading Flame Graphs

**Flame graph basics:**
- **X-axis:** Population (not time)
- **Y-axis:** Stack depth
- **Width:** How often that stack frame appears
- **Color:** Random (to differentiate adjacent frames)

**Interpreting flame graphs:**
```
           proc.tell()          ← Wide = frequent hot path
           /         \
    mailbox.put()   validate()
    /        \
transfer()   park()   ← Wide = bottleneck
```

### 4.2 Generating Flame Graphs

**From async-profiler:**
```bash
# Generate interactive flame graph
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f flamegraph.html \
  -o flamegraph pid

# Generate collapsed stack format
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f stacks.txt \
  -o collapsed pid

# Generate traditional flame graph (FlameGraph tool)
perl flamegraph.pl --color=java --title="JOTP CPU Profile" \
  stacks.txt > jotp-cpu.svg
```

### 4.3 Hot Spot Identification

**Common JOTP hot spots:**
| Method | Expected Width | Action if Wide |
|--------|----------------|----------------|
| `Proc.tell()` | Wide | Normal hot path |
| `LinkedTransferQueue.transfer()` | Medium | Queue overhead |
| `VirtualThread.park()` | Narrow | Should be minimal |
| `ThreadLocal.get()` | Wide | Consider scoped values |
| `synchronized block` | Wide | **Bad** - pins carrier |

---

## 5. Hot Spot Identification

### 5.1 CPU Hot Spots

**Tools for identification:**
- **JMH:** Use `-prof stack` to get stack traces
- **JFR:** Use "Code" -> "Hot Methods"
- **async-profiler:** Use flame graphs

**Example: Finding hot methods with JFR**
```bash
jfr print --events jdk.ExecutionSample \
  --format json \
  jotp.jfr | \
  jq '.events | group_by(.stackTrace.topFrame) |
      map({frame: .[0].stackTrace.topFrame, count: length}) |
      sort_by(.count) | reverse' | \
  head -20
```

### 5.2 Memory Hot Spots

**Allocation profiling:**
```bash
# Find high-allocation methods
$ASYNC_PROFILER_DIR/profiler.sh -d 30 -f alloc.html \
  --alloc -j pid

# Look for:
# - Proc object creation (spawn overhead)
# - Message object creation (consider reuse)
# - Mailbox node allocation (queue overhead)
```

### 5.3 Lock Contention Hot Spots

**Lock profiling:**
```bash
# Enable lock profiling
java -XX:StartFlightRecording=settings=profile \
     -Djdk.Lock.preamble=true \
     -jar target/jotp.jar

# Analyze in JMC:
# - JVM Internal -> Lock -> Contended
# - Look for synchronized blocks in hot path
```

---

## 6. Performance Regression Detection

### 6.1 Baseline Establishment

**Run baseline benchmarks:**
```bash
# Establish v1.0 baseline
./mvnd test -Pbenchmark
cp target/jmh-results.json benchmark-results/baseline-v1.0.json

# Generate HTML report
java -cp target/classes:target/test-classes \
  io.github.seanchatmangpt.jotp.benchmark.report.BenchmarkReport \
  benchmark-results/baseline-v1.0.json \
  benchmark-results/baseline-v1.0.html
```

### 6.2 Regression Detection

**Compare against baseline:**
```bash
# Run new benchmarks
./mvnd test -Pbenchmark

# Detect regressions
java -cp target/classes:target/test-classes \
  io.github.seanchatmangpt.jotp.benchmark.util.BenchmarkRegressionDetector \
  benchmark-results/baseline-v1.0.json \
  target/jmh-results.json
```

**Regression thresholds:**
| Degradation | Status | Action |
|-------------|--------|--------|
| <5% | ✅ Stable | No action |
| 5-10% | ⚠️ Warning | Review changes |
| >10% | 🚨 Critical | Investigate, potential rollback |

---

## 7. Profiling Checklist

### 7.1 Pre-Profiling

- [ ] Ensure representative workload
- [ ] Disable unrelated services
- [ ] Use production-like data
- [ ] Warm up JVM (5-10 minutes)
- [ ] Clear previous profiling data

### 7.2 During Profiling

- [ ] Profile for sufficient duration (30-60s minimum)
- [ ] Capture multiple metrics (CPU, memory, GC)
- [ ] Include JVM compilation phase
- [ ] Monitor system resources
- [ ] Document profiling conditions

### 7.3 Post-Profiling

- [ ] Verify profile data is valid
- [ ] Identify top 10 hot spots
- [ ] Correlate with business metrics
- [ ] Create before/after comparison
- [ ] Document optimization opportunities

---

## 8. Common Profiling Pitfalls

### 8.1 Microbenchmarking Errors

**Pitfall 1: Dead code elimination**
```java
// BAD: Compiler optimizes away
@Benchmark
public void benchmark() {
    proc.tell(message);  // Result unused
}

// GOOD: Use Blackhole
@Benchmark
public void benchmark(Blackhole bh) {
    proc.tell(message);
    bh.consume(result);
}
```

**Pitfall 2: Constant folding**
```java
// BAD: Compiler computes at compile time
@Benchmark
public int benchmark() {
    return 42 * 42;  // Folded to 1764
}

// GOOD: Use state
@State(Scope.Benchmark)
public class BenchmarkState {
    public int value = 42;
}

@Benchmark
public int benchmark(BenchmarkState state) {
    return state.value * state.value;
}
```

**Pitfall 3: Loop optimization**
```java
// BAD: JIT optimizes entire loop
@Benchmark
public void benchmark() {
    for (int i = 0; i < 1000; i++) {
        proc.tell(message);
    }
}

// GOOD: Let JMH handle loops
@Benchmark
@OperationsPerInvocation(1000)
public void benchmark() {
    for (int i = 0; i < 1000; i++) {
        proc.tell(message);
    }
}
```

### 8.2 Production Profiling Pitfalls

**Pitfall 1: Overhead too high**
- Avoid profiling during peak load
- Use sampling (1ms intervals, not continuous)
- Profile for short durations (30-60 seconds)

**Pitfall 2: Sampling bias**
- Profile multiple time windows
- Include different workload patterns
- Compare weekday vs weekend patterns

**Pitfall 3: Misinterpreting data**
- Correlation ≠ causation
- Check for external factors (GC, system load)
- Validate findings with controlled experiments

---

## 9. Quick Reference

### JMH Command-Line Options
```bash
# Run benchmark with GC profiling
java -jar target/benchmarks.jar -prof gc

# Run with stack profiling
java -jar target/benchmarks.jar -prof stack

# Run with async-profiler
java -jar target/benchmarks.jar -prof async

# Run specific benchmark
java -jar target/benchmarks.jar MyBenchmark.benchmarkMethod
```

### JFR Recording Options
```bash
# Start with JFR
java -XX:StartFlightRecording=filename=jotp.jfr,duration=60s \
     -jar target/jotp.jar

# Start with custom settings
java -XX:StartFlightRecording=settings=profile.jfc \
     -jar target/jotp.jar

# Dump on demand
jcmd <pid> JFR.dump name=recording filename=jotp.jfr
```

### Async Profiler Options
```bash
# CPU profiling
profiler.sh -d 30 -f cpu.html -o flamegraph pid

# Allocation profiling
profiler.sh -d 30 -f alloc.html --alloc pid

# Wall-clock profiling
profiler.sh -d 30 -f wall.html -e cpu,wall pid
```

---

## 10. Next Steps

1. **Set up continuous benchmarking** - Add to CI/CD pipeline
2. **Establish performance budgets** - Define acceptable thresholds
3. **Create profiling dashboards** - Visualize trends over time
4. **Document optimization opportunities** - Track improvement pipeline
5. **Train team on profiling** - Share knowledge and best practices

---

**See Also:**
- [Performance Characteristics](performance-characteristics.md)
- [JVM Tuning Guide](jvm-tuning.md)
- [Optimization Patterns](optimization-patterns.md)
- [Troubleshooting Performance](troubleshooting-performance.md)
