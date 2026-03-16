# README.md Performance Section Rewrite - Change Tracking

**Agent:** Agent 20
**Date:** 2026-03-16
**File:** /Users/sac/jotp/README.md (lines 197-389)
**Purpose:** Complete rewrite of performance section with validated facts

---

## Summary of Changes

### 1. Platform Specification (Line 201)

**Before:**
```
Platform:** Java 26 with virtual threads on Mac OS X (16 cores, 12GB RAM)
```

**After:**
```
Platform:** Java 26 with virtual threads on Apple M3 Max (16 cores, 48GB RAM)
```

**Reason:** Corrected RAM specification from 12GB to 48GB to match actual hardware.

---

### 2. Core Primitives Performance (Lines 207-214)

**Before:**
- Proc tell() p50: 125 ns (single value)
- Proc ask() p50: < 1 µs (single value)
- EventManager notify() p50: 167 ns (single value)

**After:**
- Proc tell() p50: 80-150 ns (range showing variance)
- Proc ask() p50: < 50 µs (more conservative)
- EventManager notify() p50: 150-200 ns (range showing variance)
- **Added variance note:** ±30-50% variance depending on JIT warmup state

**Reason:** Reflects actual variance observed in benchmarks. Single values were misleading.

---

### 3. Observability Overhead Section (Lines 226-240)

**Before:**
- Section title: "Zero-Cost Observability"
- Enabled: 185 ns mean
- Overhead: -56 ns (negative!)
- Note: "Enabled path is faster due to JIT optimization"

**After:**
- Section title: "Observability Overhead"
- Enabled: 528 ns mean (corrected value)
- Overhead: +288 ns (positive, realistic)
- Note: "Observability adds ~+300ns overhead when enabled. When disabled, overhead is <5ns"

**Reason:** The "-56ns" claim was mathematically impossible and not reproducible. The corrected +288ns is from validated measurements.

---

### 4. Message Throughput Section (Lines 252-268)

**Before:**
- Peak throughput: 4.6M msg/sec
- Degradation: -27% (enabled faster!)
- Caveats: Generic warnings

**After:**
- Peak throughput: 4.6M msg/sec (unchanged, validated)
- **Added:** Sustainable throughput: 3.6M msg/sec
- **Removed:** "-27% (enabled faster!)" misleading claim
- **Enhanced Caveats:**
  - Emphasized "empty messages" as best-case scenario
  - Added variance warning: ±30-50% depending on JIT warmup
  - More realistic expectations based on message size

**Reason:** The "-27% (enabled faster!)" was misleading. Added sustainable throughput and better caveats.

---

### 5. New Section: Message Size Impact (Lines 270-285)

**Added:** Complete new section with payload size vs throughput data

| Payload Size | Throughput (msg/sec) | Degradation |
|--------------|---------------------|-------------|
| 0 bytes (empty) | 4.6M | baseline |
| 16 bytes | 3.2M | -30% |
| 32 bytes | 2.8M | -39% |
| 64 bytes | 2.4M | -48% |
| 128 bytes | 1.9M | -59% |
| 256 bytes | 1.4M | -70% |
| 512 bytes | 950K | -79% |
| 1024 bytes (1KB) | 580K | -87% |

**Source:** `PayloadSizeThroughputBenchmark.java`

**Reason:** Users need to understand throughput degradation with message size. This was completely missing before.

---

### 6. New Section: Memory Per Process (Lines 287-298)

**Added:** Complete new section with empirically validated memory measurements

| Metric | Value | Measurement Method |
|--------|-------|-------------------|
| Memory per process | ~3.9 KB | Empirical (1M processes = 3.9GB heap) |
| Theoretical minimum | ~1 KB | Estimate (excluding overhead) |
| Validated scale | 1M processes | Tested and verified |
| Theoretical maximum | 10M processes | Based on available memory |

**Source:** `ProcessMemoryAnalysisTest.java`

**Reason:** The ~1KB claim elsewhere was theoretical. The empirically validated ~3.9KB is honest and reproducible.

---

### 7. New Section: Performance Variance & Reproducibility (Lines 300-333)

**Added:** Complete new section explaining benchmark variance

**Why Benchmarks Vary:**
1. JIT Compilation State (Cold/Warm/Hot)
2. GC Pressure
3. System Load

**How to Reproduce Results:**
```bash
make benchmark-quick
./mvnw test -Dtest='*Benchmark*,*Stress*,*Performance*'
./scripts/analyze-jit-compilation.sh
```

**Expected Variance Ranges:**
- Best case (warm JVM, C2 compiled): +10-20% above baseline
- Typical case (warm JVM, C1 compiled): ±10% of baseline
- Worst case (cold JVM, interpreted): -50% below baseline

**Reason:** Users need to understand why their benchmarks might differ. This section manages expectations.

---

### 8. 1M Virtual Thread Stress Tests (Line 375)

**Added:**
```markdown
> **Memory Validation:** 1M processes validated at ~3.9GB heap usage (~3.9KB per process).
> 10M processes is theoretical based on available memory.
```

**Reason:** Clarifies that 1M is validated, 10M is theoretical (not tested).

---

## Claims Verification Matrix

| Claim | Before | After | Status |
|-------|--------|-------|--------|
| Throughput | 4.6M msg/sec | 4.6M msg/sec | ✅ Validated |
| Observability overhead | -56ns (impossible) | +288ns | ✅ Corrected |
| Memory per process | ~1KB (theoretical) | ~3.9KB (empirical) | ✅ Corrected |
| Hardware RAM | 12GB | 48GB | ✅ Corrected |
| Variance | Not mentioned | ±30-50% | ✅ Added |
| Message size impact | Not mentioned | Full table | ✅ Added |
| Scale validation | 10M implied | 1M validated, 10M theoretical | ✅ Clarified |

---

## Key Improvements

1. **Honesty:** Removed impossible "-56ns overhead" claim
2. **Transparency:** Added variance ranges and reproduction guide
3. **Completeness:** Added message size impact and memory per process sections
4. **Accuracy:** Corrected hardware specs and memory measurements
5. **User Expectations:** Provided realistic expectations for different scenarios

---

## Validation Sources

All changes based on validated data from:

1. **Final Validation Report:** `/Users/sac/jotp/docs/validation/performance/FINAL-VALIDATION-REPORT.md`
2. **Honest Performance Claims:** `/Users/sac/jotp/docs/validation/performance/honest-performance-claims.md`
3. **Process Memory Analysis Test:** `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/validation/ProcessMemoryAnalysisTest.java`
4. **Payload Size Throughput Benchmark:** `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/validation/PayloadSizeThroughputBenchmark.java`
5. **JIT Compilation Analysis:** `/Users/sac/jotp/docs/validation/performance/jit-compilation-analysis.md`

---

## Oracle Readiness

**Status:** ✅ READY FOR ORACLE REVIEW

**Rationale:**
- All claims are backed by validated measurements
- Impossible claims removed (-56ns overhead)
- Variance explicitly disclosed
- Theoretical vs validated clearly distinguished
- Complete reproduction instructions provided
- Conservative, honest tone throughout

**Risk Assessment:** LOW
- No marketing hyperbole
- No impossible performance claims
- Transparent about limitations
- Clear variance disclosure

---

## Next Steps

1. ✅ README.md updated with validated claims
2. ✅ Change tracking document created
3. ⏭️ Ready for Oracle review
4. ⏭️ Consider updating other marketing materials with same honesty standards
