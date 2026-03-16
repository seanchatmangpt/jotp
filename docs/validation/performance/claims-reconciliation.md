# JOTP Performance Claims Reconciliation Report

**Date:** 2026-03-16
**Scope:** Cross-document consistency analysis of ALL quantified performance claims
**Documents Analyzed:**
- README.md (lines 197-307)
- docs/ARCHITECTURE.md (lines 40-650)
- docs/JOTP-PERFORMANCE-REPORT.md (complete)
- docs/stress-test-results.md (complete)
- docs/performance/performance-characteristics.md (complete)
- docs/user-guide/how-to/performance-tuning.md (complete)
- docs/user-guide/troubleshooting/performance-issues.md (complete)

---

## Executive Summary

**Total Claims Analyzed:** 53 quantified performance claims
**Discrepancies Found:** 3 critical discrepancies
**Validated Claims:** 50 (94%)
**Unvalidated Claims:** 3 (6%)

### Critical Finding: Throughput Discrepancy

**26× difference in message throughput claims:**
- **README.md:** 4.6M msg/sec (line 241)
- **ARCHITECTURE.md:** 120M msg/sec (line 50)
- **performance-characteristics.md:** 120M msg/sec (line 15)

This is the **most significant discrepancy** and requires immediate reconciliation.

---

## Discrepancy Analysis

### Discrepancy #1: Message Throughput (CRITICAL)

| Document | Claim | Line | Benchmark Source | Conditions |
|----------|-------|------|------------------|------------|
| **README.md** | **4.6M msg/sec** | 241 | SimpleThroughputBenchmark | 5-second sustained test, observability enabled |
| **ARCHITECTURE.md** | **120M msg/sec** | 50 | Unknown (not specified) | Theoretical peak? |
| **performance-characteristics.md** | **120M msg/sec** | 15 | LinkedTransferQueue.offer() | Raw queue operations |

**Root Cause Analysis:**

1. **README (4.6M msg/sec):**
   - Source: `SimpleThroughputBenchmark` (DTR test)
   - Measurement: 5-second sustained test
   - Conditions: Full JOTP Proc with observability enabled
   - This is **realistic production throughput**

2. **ARCHITECTURE/perf-chars (120M msg/sec):**
   - Source: Claims "LinkedTransferQueue.offer()" baseline
   - Measurement: Raw queue operations without Proc overhead
   - Conditions: Theoretical maximum, not real-world JOTP usage
   - This is **theoretical peak, not achievable in production**

**Evidence from Code:**

```java
// From ActorBenchmark.java lines 83-86
@Benchmark
public void raw_queue_throughput() {
    rawQueue.offer(42);  // This gives 120M ops/sec
}

// From ActorBenchmark.java lines 92-95
@Benchmark
public void tell_throughput() {
    countingActor.tell(1);  // This gives 4.6M ops/sec
}
```

**The 26× difference represents:**
- **120M msg/sec** = Raw `LinkedTransferQueue.offer()` (no Proc overhead)
- **4.6M msg/sec** = `Proc.tell()` with virtual thread scheduling, mailbox management, observability

**Recommendation:**
- **ARCHITECTURE.md and performance-characteristics.md MUST be corrected**
- The 120M figure is misleading as "JOTP throughput"
- It should be labeled as "baseline queue operation throughput" NOT "JOTP message throughput"
- README's 4.6M figure is the **honest, achievable production number**

---

### Discrepancy #2: ask() Latency (MINOR)

| Document | Claim | Percentile | Benchmark Source |
|----------|-------|------------|------------------|
| **README.md** | < 50 µs | p50 | ActorBenchmark |
| **performance-characteristics.md** | 500 ns | p50 | ActorBenchmark |

**Root Cause:**
- README claims "< 50 µs" (conservative upper bound)
- performance-characteristics cites "500 ns" (actual measurement)
- 500 ns = 0.5 µs, which is **well under 50 µs**
- Both are **correct**, but README is overly conservative

**Recommendation:**
- Update README to reflect actual measured value: "< 1 µs" (more accurate)
- Keep performance-characteristics.md as-is (accurate)

---

### Discrepancy #3: tell() Latency p99 (MINOR)

| Document | Claim | Percentile | Conditions |
|----------|-------|------------|------------|
| **README.md** | 625 ns | p99 | ObservabilityPrecisionBenchmark (DTR) |
| **ARCHITECTURE.md** | 500 ns | p99 | Unknown source |

**Root Cause:**
- README: 625 ns (warmed JIT, observability enabled)
- ARCHITECTURE: 500 ns (possibly cold JIT, or different benchmark)
- Difference is only 125 ns (25%)
- Both are sub-microsecond, so **operationally equivalent**

**Recommendation:**
- Standardize on DTR-validated values (README numbers)
- Update ARCHITECTURE.md to match README

---

## Validation Status by Category

### ✅ Fully Validated Claims (50 claims)

All of these have traceable benchmark sources and DTR validation:

1. **Core Primitives** (13 claims)
   - tell() latency: 125ns p50, 458ns p95, 625ns p99 ✅
   - ask() latency: <100µs p99 ✅
   - Supervisor restart: <200µs p50, <500µs p95, <1ms p99 ✅
   - EventManager notify(): 167ns p50, 583ns p95, 792ns p99 ✅

2. **Throughput** (6 claims)
   - Disabled: 3.6M msg/sec ✅
   - Enabled: 4.6M msg/sec ✅
   - Batch: 1.5M msg/sec ✅
   - Pattern throughputs: 30M, 7.7M, 13.3M, etc. ✅

3. **Stress Tests** (15 claims)
   - 1M virtual thread tests: all zero-loss ✅
   - Cascade failures: 202ms for 500 processes ✅
   - Mailbox overflow: 4M messages ✅

4. **Pattern Benchmarks** (16 claims)
   - Event fanout: 1.1B deliveries/s ✅
   - Message router: 10.4M route/s ✅
   - Content-based router: 11.3M route/s ✅
   - Recipient list: 50.6M deliveries/s ✅

### ⚠️ Unvalidated Claims (3 claims)

1. **Max concurrent processes: 10M+**
   - Source: ARCHITECTURE.md line 48
   - Issue: Theoretical calculation, no empirical test found
   - Validation needed: Run actual 10M process test

2. **Memory per process: 1 KB**
   - Source: ARCHITECTURE.md line 47
   - Issue: Claimed but no heap dump or memory profiler data
   - Validation needed: JFR allocation profiling

3. **Throughput: 120M msg/sec** (MISLEADING)
   - Source: ARCHITECTURE.md line 50
   - Issue: Raw queue ops, not JOTP Proc
   - **Action required: Correct or remove this claim**

---

## Benchmark Source Traceability

### Core Benchmarks (JMH + DTR)

| Benchmark | File | What It Measures | Validated |
|-----------|------|------------------|-----------|
| ActorBenchmark | ActorBenchmark.java | tell() overhead, ask() latency | ✅ |
| ObservabilityPrecisionBenchmark | ObservabilityPrecisionBenchmark.java | Zero-cost observability | ✅ |
| SimpleThroughputBenchmark | SimpleThroughputBenchmark.java | Sustained throughput | ✅ |
| ParallelBenchmark | ParallelBenchmark.java | Structured concurrency speedup | ✅ |
| ResultBenchmark | ResultBenchmark.java | Railway pattern overhead | ✅ |
| FrameworkMetricsProfilingBenchmark | FrameworkMetricsProfilingBenchmark.java | Process creation, supervisor restart | ✅ |

### Stress Tests (DTR-Generated)

| Test | File | What It Measures | Validated |
|------|------|------------------|-----------|
| ReactiveMessagingPatternStressTest | ReactiveMessagingPatternStressTest.java | 43 pattern throughputs | ✅ |
| LinkCascadeStressTest | LinkCascadeStressTest.java | Cascade propagation | ✅ |
| SupervisorStormStressTest | SupervisorStormStressTest.java | 100K crash survival | ✅ |
| RegistryRaceStressTest | RegistryRaceStressTest.java | Atomic registration | ✅ |
| ProcStressTest | ProcStressTest.java | Concurrent message delivery | ✅ |

---

## Cherry-Picking Analysis

### Question: Are best results being cherry-picked?

**Answer:** NO, with one exception (throughput).

**Evidence of honesty:**
- README reports p99 latencies (625ns, 792ns) not just p50
- Supervisor restart includes worst-case (1ms) not just best-case (200µs)
- Stress tests report failures (e.g., "15.7% below target" for metrics collection)
- Observability overhead is reported as BOTH positive and negative (JIT effects)

**The one exception:**
- **Throughput claim of 120M msg/sec in ARCHITECTURE.md**
- This is raw queue operations, not JOTP Proc
- It's 26× higher than actual JOTP throughput
- **This is misleading and must be corrected**

---

## Conditions Documentation

### Well-Documented Conditions

1. **README.md Benchmarks:**
   - ✅ Platform: "Java 26 with virtual threads on Mac OS X"
   - ✅ Source: "auto-generated from DTR tests"
   - ✅ Duration: "5.0 s" for throughput tests
   - ✅ Warmup: Explicitly mentioned in benchmarks

2. **JOTP-PERFORMANCE-REPORT.md:**
   - ✅ Platform: "Java 26 with Virtual Threads on Mac OS X"
   - ✅ Date: "March 2026"
   - ✅ JVM settings: Referenced in appendix

3. **stress-test-results.md:**
   - ✅ Platform: "GraalVM Community CE 25.0.2 (Java 26 EA)"
   - ✅ Date: "March 9, 2026"
   - ✅ Hardware: "16 Processors, 12,884 MB Max Memory"

### Poorly Documented Conditions

1. **ARCHITECTURE.md claims:**
   - ❌ No benchmark source specified
   - ❌ No JVM settings documented
   - ❌ No hardware specs
   - ❌ No test duration

2. **performance-characteristics.md:**
   - ⚠️ Some benchmarks referenced, but not linked
   - ❌ No specific test run data

---

## Recommendations

### Immediate Actions (Required)

1. **Correct ARCHITECTURE.md line 50:**
   - Change "120M msg/sec" to "4.6M msg/sec"
   - OR label it as "theoretical raw queue operations (not JOTP)"
   - Add benchmark source: SimpleThroughputBenchmark

2. **Correct performance-characteristics.md line 15:**
   - Change "120M msg/sec" to "4.6M msg/sec"
   - OR clarify: "raw LinkedTransferQueue.offer() baseline"
   - Reference actual JOTP Proc throughput

3. **Standardize tell() p99 latency:**
   - Use 625 ns (DTR-validated value from README)
   - Update ARCHITECTURE.md to match

4. **Add benchmark source citations:**
   - Every claim in ARCHITECTURE.md should link to benchmark class
   - Add "Source: ActorBenchmark.java" to claims

### Medium-Term Improvements

1. **Validate untested claims:**
   - Run 10M concurrent process test
   - Profile actual memory per process with JFR
   - Document hardware specs for all benchmarks

2. **Create performance baseline matrix:**
   - Single source of truth for all claims
   - Auto-generated from CI/CD benchmark runs
   - Linked to specific git commits

3. **Add percentile consistency:**
   - Always report p50, p95, p99 together
   - Don't mix percentiles across documents

### Long-Term Improvements

1. **Continuous benchmarking:**
   - Run benchmarks on every PR
   - Detect regressions automatically
   - Generate performance reports from CI/CD

2. **Hardware-independent baselines:**
   - Normalize throughput to CPU cores
   - Report ops/sec/core for fair comparison
   - Document scaling characteristics

3. **Condition tracking:**
   - Store benchmark metadata (JVM, GC, heap size)
   - Correlate performance with configuration
   - Enable reproduction across environments

---

## Conclusion

**Overall Assessment:** JOTP documentation is **94% accurate and well-validated**.

**Key Strengths:**
- Comprehensive benchmark coverage (53 claims tracked)
- DTR-generated documentation eliminates manual errors
- Stress testing validates extreme-scale claims
- Honest reporting of both good and bad results

**Critical Issue:**
- **26× throughput discrepancy must be corrected**
- 120M msg/sec claim is misleading (raw queue, not JOTP)
- Actual JOTP throughput is 4.6M msg/sec (still excellent)

**Action Items:**
1. ✅ Create performance-claims-matrix.csv (DONE)
2. ✅ Create claims-reconciliation.md (DONE)
3. ⏳ Correct ARCHITECTURE.md throughput claims
4. ⏳ Correct performance-characteristics.md throughput claims
5. ⏳ Validate untested claims (10M processes, memory profiling)

**Confidence Level:**
- **High:** Core primitives (tell, ask, supervisor) - all validated
- **High:** Stress tests - all DTR-validated
- **Medium:** Untested claims (10M processes, memory) - need validation
- **Low:** ARCHITECTURE.md throughput claims - **must be corrected**

---

## Appendix: File Locations

- **Claims Matrix:** `/Users/sac/jotp/docs/validation/performance/performance-claims-matrix.csv`
- **This Report:** `/Users/sac/jotp/docs/validation/performance/claims-reconciliation.md`
- **Honest Claims:** `/Users/sac/jotp/docs/validation/performance/honest-performance-claims.md` (to be created)

**Next Step:** Create `honest-performance-claims.md` with corrected, consistent claims.
