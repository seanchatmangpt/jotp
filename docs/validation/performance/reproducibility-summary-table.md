# Reproducibility Test Summary - Quick Reference

## Benchmark Comparison Table

| Benchmark | Published Claim | Actual (Best) | Actual (Typical) | Variance | Status |
|-----------|----------------|---------------|------------------|----------|--------|
| **SimpleObservability** | | | | | |
| Overhead | -56 ns | -55 ns | +130 ns | 196 ns | ❌ Poor |
| p95 latency | < 1000 ns | ✓ | ✓ | - | ✅ Pass |
| **ObservabilityThroughput** | | | | | |
| Disabled | 3.64M msg/s | 3.6M | 3.2M | 12% | ⚠️ Typical |
| Enabled | 4.64M msg/s | 4.6M | 3.0M | 35% | ❌ Poor |
| Degradation | -27% | -27% | +7% | 34 pp | ❌ Poor |
| **ParallelBenchmark** | | | | | |
| Speedup (8 tasks) | ≥4x | 4.0x | 4.0x | ±5% | ✅ Excellent |
| **ResultBenchmark** | | | | | |
| vs try-catch | ≤2x | 2.5x | 2.5x | +25% | ⚠️ Optimistic |

## Reproducibility Assessment

### ✅ HIGHLY REPRODUCIBLE (Variance < 10%)
- ParallelBenchmark - Consistently achieves 4x speedup

### ⚠️ MODERATELY REPRODUCIBLE (Variance 10-30%)
- ResultBenchmark - Consistently 2.5x (not 2x as claimed)

### ❌ POORLY REPRODUCIBLE (Variance > 30%)
- SimpleObservabilityBenchmark - Varies from -56ns to +140ns
- ObservabilityThroughputBenchmark - Varies from -27% to +7%

## Key Findings

1. **Published Numbers Are Best-Case Scenarios**
   - README shows peak performance under optimal JIT compilation
   - Typical performance is 30-50% worse
   - Not fabrications, but incomplete disclosure

2. **JIT Warmup Is Critical**
   - First run often matches published numbers
   - Subsequent runs show worse performance
   - JIT compiler makes different optimization decisions

3. **Tests Pass Intermittently**
   - Sometimes all benchmarks pass
   - Sometimes some fail
   - Makes CI/CD unreliable

4. **No Fundamental Performance Bugs**
   - All benchmarks show reasonable performance
   - Variance is inherent to JVM benchmarking
   - Performance is directionally correct

## Recommendations

### For README.md
- ✅ Add variance disclosure (± X%)
- ✅ Document JIT warmup requirements
- ✅ Show ranges (best/typical/worst)
- ✅ Link to reproducibility test results

### For Benchmark Tests
- ✅ Adjust thresholds to realistic values (100ns → 150ns)
- ✅ Run multiple iterations (3x, take median)
- ✅ Add variance metrics (std dev, min, max)

## System Information

**Test Environment:**
- Java: 26+13-jvmci-b01 (Oracle GraalVM)
- OS: macOS 26.2 (aarch64)
- CPU: Apple Silicon (16 cores)
- Memory: 48 GB
- Date: 2026-03-16

**Test Commands:**
```bash
./mvnw test -Dtest=SimpleObservabilityBenchmark
./mvnw test -Dtest=ObservabilityThroughputBenchmark
./mvnw test -Dtest=ParallelBenchmark
./mvnw test -Dtest=ResultBenchmark
```

## Conclusion

**JOTP performance is fundamentally sound**, but README benchmarks represent ideal conditions. Users should expect:
- **Throughput:** 3-4.6M msg/sec (not just 4.6M)
- **Latency:** Sub-microsecond (not always sub-200ns)
- **Overhead:** +100-150ns typical (not -56ns)

The benchmarks are not fabrications, but they are "best-case scenarios" that may not reflect typical user experience.

---

**Full Report:** [AGENT-13-REPRODUCIBILITY-REPORT.md](AGENT-13-REPRODUCIBILITY-REPORT.md)
**Detailed Results:** [reproducibility-test-results.md](reproducibility-test-results.md)
