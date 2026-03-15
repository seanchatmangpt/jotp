# JMH Best Practices Checklist

**Purpose:** Ensure accurate microbenchmarks by avoiding common pitfalls
**Version:** 1.0
**Last Updated:** 2026-03-14

---

## ✅ MANDATORY Practices (Do NOT skip)

### 1. State Management

- [ ] **Always use `@State(Scope.Benchmark)` for shared state**
  ```java
  @State(Scope.Benchmark)
  public class MyBenchmark {
      private MyObject sharedState;

      @Setup(Level.Trial)
      public void setup() {
          sharedState = new MyObject();
      }
  }
  ```

- [ ] **Prefer `Scope.Benchmark` over `Scope.Thread` unless thread-local state is needed**
  - `Scope.Benchmark`: Shared across all threads (most common)
  - `Scope.Thread`: Separate instance per thread (rarely needed)

- [ ] **Always use `@Setup(Level.Trial)` for initialization**
  - `Level.Trial`: Runs once per JVM fork (recommended)
  - `Level.Iteration`: Runs before each measurement iteration (use only if needed)
  - `Level.Invocation`: Runs before each benchmark call (AVOID - too slow)

### 2. Dead Code Elimination Prevention

- [ ] **Always use `Blackhole` parameter to prevent JIT optimization**
  ```java
  @Benchmark
  public void myBenchmark(Blackhole bh) {
      long result = compute();
      bh.consume(result); // Prevents dead code elimination
  }
  ```

- [ ] **Consume ALL computed values, not just some**
  ```java
  // BAD: JIT may optimize away first computation
  @Benchmark
  public void badBenchmark(Blackhole bh) {
      long a = computeA();
      long b = computeB();
      bh.consume(b); // 'a' may be optimized away!
  }

  // GOOD: Consume all results
  @Benchmark
  public void goodBenchmark(Blackhole bh) {
      long a = computeA();
      long b = computeB();
      bh.consume(a);
      bh.consume(b);
  }
  ```

- [ ] **NEVER return values without consuming them**
  ```java
  // BAD: Return value may be optimized away
  @Benchmark
  public long badBenchmark() {
      return compute();
  }

  // GOOD: Consume in Blackhole
  @Benchmark
  public void goodBenchmark(Blackhole bh) {
      bh.consume(compute());
  }
  ```

### 3. Benchmark Mode Selection

- [ ] **Use `Mode.AverageTime` for mean latency measurement**
  ```java
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @Benchmark
  public void latencyBenchmark(Blackhole bh) {
      bh.consume(operation());
  }
  ```

- [ ] **Use `Mode.Throughput` for operations per second**
  ```java
  @BenchmarkMode(Mode.Throughput)
  @OutputTimeUnit(TimeUnit.SECONDS)
  @Benchmark
  public void throughputBenchmark(Blackhole bh) {
      bh.consume(operation());
  }
  ```

- [ ] **Use `Mode.SampleTime` ONLY for latency distributions (p50, p95, p99)**
  ```java
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  @Benchmark
  public void sampleLatencyBenchmark(Blackhole bh) {
      bh.consume(operation());
  }
  ```

- [ ] **Use `Mode.SingleShotTime` ONLY for one-time operations (cold start)**
  ```java
  @BenchmarkMode(Mode.SingleShotTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Benchmark
  public void coldStartBenchmark(Blackhole bh) {
      bh.consume(expensiveInitialization());
  }
  ```

### 4. Warmup and Measurement

- [ ] **Always use warmup iterations (minimum 5, recommended 10+)**
  ```java
  @Warmup(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
  @Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
  ```

- [ ] **Use longer warmup for GraalVM JIT (15-20 iterations)**
  ```java
  @Warmup(iterations = 15, time = 2, timeUnit = TimeUnit.SECONDS)
  ```

- [ ] **Use more measurement iterations for better statistical significance**
  ```java
  @Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
  ```

### 5. Fork Configuration

- [ ] **Always use multiple forks (minimum 2, recommended 3-5)**
  ```java
  @Fork(3)  // 3 separate JVM processes for statistical reliability
  ```

- [ ] **Use `@Fork(value = 3, warmups = 1)` for explicit warmup fork**
  ```java
  @Fork(value = 3, warmups = 1)  // 1 warmup fork + 3 measurement forks
  ```

### 6. Time Units

- [ ] **Always specify explicit `@OutputTimeUnit`**
  ```java
  @OutputTimeUnit(TimeUnit.NANOSECONDS)   // For fast operations (<1μs)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)  // For medium operations (1-1000μs)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)  // For slow operations (>1ms)
  @OutputTimeUnit(TimeUnit.SECONDS)       // For throughput (ops/sec)
  ```

- [ ] **Match time unit to expected latency**
  - <1μs: Use `NANOSECONDS`
  - 1-1000μs: Use `MICROSECONDS`
  - >1ms: Use `MILLISECONDS`
  - Throughput: Use `SECONDS`

---

## ❌ COMMON ANTI-PATTERNS (Avoid these)

### 1. Dead Code Elimination Risks

- ❌ **NEVER compute values without consuming them**
  ```java
  // BAD: JIT optimizes away entire method
  @Benchmark
  public long badBenchmark() {
      long sum = 0;
      for (int i = 0; i < 1000; i++) {
          sum += i;
      }
      return sum; // May be optimized away!
  }
  ```

- ❌ **NEVER use loops without Blackhole**
  ```java
  // BAD: Loop may be unrolled and optimized away
  @Benchmark
  public void badLoopBenchmark() {
      for (int i = 0; i < 1000; i++) {
          compute(i); // May be optimized away!
      }
  }

  // GOOD: Let JMH handle loops
  @Benchmark
  public void goodLoopBenchmark(Blackhole bh) {
      bh.consume(compute(42)); // JMH calls this repeatedly
  }
  ```

### 2. State Sharing Issues

- ❌ **NEVER share state across benchmarks without @State**
  ```java
  // BAD: Shared state causes thread-safety issues
  public class MyBenchmark {
      private List<Integer> list = new ArrayList<>(); // Shared across forks!

      @Benchmark
      public void badBenchmark() {
          list.add(42); // Thread-safety issues!
      }
  }

  // GOOD: Use @State for proper lifecycle
  @State(Scope.Benchmark)
  public class MyBenchmark {
      private List<Integer> list;

      @Setup(Level.Trial)
      public void setup() {
          list = new ArrayList<>();
      }

      @Benchmark
      public void goodBenchmark() {
          list.add(42);
      }
  }
  ```

### 3. Constant Folding

- ❌ **NEVER use compile-time constants**
  ```java
  // BAD: JIT folds entire expression to constant
  @Benchmark
  public long badBenchmark() {
      return 42 + 42; // Compiles to: return 84;
  }

  // GOOD: Use non-constant inputs
  @Benchmark
  public long goodBenchmark(Blackhole bh) {
      long input = System.nanoTime();
      bh.consume(input + 42);
  }
  ```

### 4. Manual Loops

- ❌ **NEVER write manual loops in benchmark methods**
  ```java
  // BAD: Manual loop interferes with JMH measurement
  @Benchmark
  public void badBenchmark() {
      for (int i = 0; i < 1000; i++) {  // Don't do this!
          compute(i);
      }
  }

  // GOOD: Let JMH handle loops
  @Benchmark
  public void goodBenchmark(Blackhole bh) {
      bh.consume(compute(42));  // JMH calls this 1000s of times
  }
  ```

### 5. Manual Timing

- ❌ **NEVER use System.nanoTime() manually**
  ```java
  // BAD: Manual timing is error-prone
  @Benchmark
  public long badBenchmark() {
      long start = System.nanoTime();
      compute();
      long end = System.nanoTime();
      return end - start;
  }

  // GOOD: Let JMH handle timing
  @Benchmark
  public void goodBenchmark(Blackhole bh) {
      bh.consume(compute());  // JMH measures this
  }
  ```

### 6. Wrong Benchmark Mode

- ❌ **NEVER use Mode.SampleTime for average latency**
  ```java
  // BAD: SampleTime is for distributions, not mean
  @BenchmarkMode(Mode.SampleTime)
  @Benchmark
  public void badBenchmark(Blackhole bh) {
      bh.consume(compute());
  }

  // GOOD: Use AverageTime for mean latency
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @Benchmark
  public void goodBenchmark(Blackhole bh) {
      bh.consume(compute());
  }
  ```

---

## ⚠️ ADVANCED Topics (Use with caution)

### 1. Compiler Control

- [ ] **Use `@CompilerControl` ONLY for specific validation**
  ```java
  // Prevent inlining to measure call overhead
  @Benchmark
  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  public void noInlineBenchmark(Blackhole bh) {
      bh.consume(compute());
  }

  // Force inline to validate inlining benefit
  @Benchmark
  @CompilerControl(CompilerControl.Mode.INLINE)
  public void forceInlineBenchmark(Blackhole bh) {
      bh.consume(compute());
  }
  ```

- [ ] **NEVER use CompilerControl in production benchmarks**
  - Alters natural JIT behavior
  - Results may not reflect real-world performance

### 2. JVM Arguments

- [ ] **Use specific JVM args ONLY for validation**
  ```bash
  # Check JIT compilation activity
  java -XX:+PrintCompilation -jar benchmarks.jar

  # Check GC activity
  java -XX:+PrintGCDetails -jar benchmarks.jar

  # Disable background compilation (for validation only)
  java -XX:-BackgroundCompilation -jar benchmarks.jar
  ```

- [ ] **NEVER use exotic JVM flags in production benchmarks**
  - `-XX:+AggressiveOpts`: May not be available in all JVMs
  - `-XX:+UseNUMA`: Only for NUMA architectures
  - `-XX:+AlwaysPreTouch`: Alters startup behavior

### 3. Profiling

- [ ] **Use JMH profilers for specific analysis**
  ```bash
  # GC profiling
  java -jar benchmarks.jar -prof gc

  # Stack profiling
  java -jar benchmarks.jar -prof stack

  # Linux perf profiling
  java -jar benchmarks.jar -prof perf
  ```

- [ ] **NEVER profile without baseline comparison**
  - Always run: `java -jar benchmarks.jar` (no profiler) first
  - Then run: `java -jar benchmarks.jar -prof gc`
  - Compare results to isolate profiler overhead

---

## 📋 VALIDATION CHECKLIST

Run this checklist before committing any benchmark:

### Pre-Benchmark Validation

- [ ] Benchmark uses `@State(Scope.Benchmark)` for shared state
- [ ] All benchmark methods have `Blackhole` parameter
- [ ] `@Setup(Level.Trial)` used for initialization
- [ ] `@BenchmarkMode` matches measurement goal (AverageTime for latency, Throughput for ops/sec)
- [ ] `@OutputTimeUnit` matches expected latency magnitude
- [ ] `@Fork(3)` or higher for statistical reliability
- [ ] `@Warmup(iterations >= 10)` for JIT stability
- [ ] `@Measurement(iterations >= 10)` for statistical significance

### Anti-Pattern Check

- [ ] No manual loops in benchmark methods
- [ ] No manual timing (System.nanoTime())
- [ ] No constant values in benchmark methods
- [ ] No shared state without `@State`
- [ ] No return values without Blackhole consumption
- [ ] No `@Scope.Thread` unless thread-local state is required

### Code Review Check

- [ ] Benchmark method does one thing (single operation)
- [ ] Benchmark method is public and non-static
- [ ] Benchmark method has no parameters (except Blackhole)
- [ ] Benchmark method throws no checked exceptions
- [ ] Benchmark class is public and has no-arg constructor

---

## 🎯 COMMON PITFALLS (Symptoms and Fixes)

### Pitfall 1: Results are 0ns or extremely fast

**Symptom:** Benchmark returns <1ns when operation should be slower

**Diagnosis:** Dead code elimination (JIT optimized away benchmark)

**Fix:**
```java
@Benchmark
public void myBenchmark(Blackhole bh) {  // Add Blackhole parameter
    bh.consume(compute());                // Consume result
}
```

---

### Pitfall 2: Results vary wildly between runs

**Symptom:** 10-100% variance across multiple executions

**Diagnosis:** Insufficient warmup or measurement iterations

**Fix:**
```java
@Warmup(iterations = 15, time = 2)  // Increase warmup
@Measurement(iterations = 20, time = 1)  // Increase measurement
@Fork(3)  // Ensure multiple forks
```

---

### Pitfall 3: Results are slower than expected

**Symptom:** Benchmark is 2-5x slower than manual timing

**Diagnosis:** Including warmup iterations in measurement

**Fix:** Ensure `@Setup(Level.Trial)` is used, not `@Setup(Level.Invocation)`

---

### Pitfall 4: Results change with different fork counts

**Symptom:** 1 fork = 100ns, 3 forks = 300ns, 5 forks = 500ns

**Diagnosis:** Shared state pollution or insufficient warmup

**Fix:**
```java
@State(Scope.Benchmark)  // Proper lifecycle
@Setup(Level.Trial)      // Initialize once per fork
@Warmup(iterations = 15)  // Ensure JIT warmup
```

---

### Pitfall 5: Results are inconsistent with similar benchmarks

**Symptom:** Benchmark A = 100ns, similar Benchmark B = 500ns

**Diagnosis:** Different measurement modes or units

**Fix:** Ensure consistent configuration:
```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
```

---

## 📖 REFERENCE EXAMPLES

### Example 1: Simple Latency Benchmark

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Benchmark)
public class SimpleLatencyBenchmark {

    private MyObject object;

    @Setup(Level.Trial)
    public void setup() {
        object = new MyObject();
    }

    @Benchmark
    public void benchmarkMethodCall(Blackhole bh) {
        bh.consume(object.compute());
    }
}
```

### Example 2: Throughput Benchmark

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Benchmark)
public class ThroughputBenchmark {

    private Queue<Integer> queue;

    @Setup(Level.Trial)
    public void setup() {
        queue = new LinkedBlockingQueue<>();
    }

    @Benchmark
    public void benchmarkEnqueue(Blackhole bh) {
        bh.consume(queue.offer(42));
    }
}
```

### Example 3: Latency Distribution Benchmark

```java
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Benchmark)
public class LatencyDistributionBenchmark {

    private MyService service;

    @Setup(Level.Trial)
    public void setup() {
        service = new MyService();
    }

    @Benchmark
    public void benchmarkP99Latency(Blackhole bh) {
        bh.consume(service.process());
    }
}
```

---

**Last Updated:** 2026-03-14
**Maintained By:** JOTP Benchmark Team
**Questions?** Open an issue at https://github.com/seanchatmangpt/jotp
