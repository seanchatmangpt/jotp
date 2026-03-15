# Performance Regression Analysis: v{NEW_VERSION} vs v{BASELINE_VERSION}

**Analysis Date:** {DATE}
**Baseline Version:** v{BASELINE_VERSION}
**Current Version:** v{NEW_VERSION}
**Analysis Status:** {STATUS}

---

## Executive Summary

**Overall Status:** {OVERALL_STATUS}
- **Regressions Detected:** {REGRESSION_COUNT}
- **Improvements Found:** {IMPROVEMENT_COUNT}
- **Stable Benchmarks:** {STABLE_COUNT}

{EXECUTIVE_SUMMARY_TEXT}

---

## Critical Regressions (>10% degradation)

{CRITICAL_REGRESSIONS_TABLE}

---

## Warning Regressions (5-10% degradation)

{WARNING_REGRESSIONS_TABLE}

---

## Performance Improvements (>5% improvement)

{IMPROVEMENTS_TABLE}

---

## Stable Benchmarks (±5% change)

{STABLE_BENCHMARKS_TABLE}

---

## Detailed Analysis by Category

### Core Performance

{CORE_PERFORMANCE_ANALYSIS}

### Observability

{OBSERVABILITY_ANALYSIS}

### Framework Overhead

{FRAMEWORK_OVERHEAD_ANALYSIS}

### Enterprise Patterns

{ENTERPRISE_PATTERNS_ANALYSIS}

---

## Statistical Significance

### JVM Configuration

**Baseline:**
- JVM Version: {BASELINE_JVM}
- OS: {BASELINE_OS}
- CPU: {BASELINE_CPU}

**Current:**
- JVM Version: {CURRENT_JVM}
- OS: {CURRENT_OS}
- CPU: {CURRENT_CPU}

### Measurement Validity

- **Warmup iterations:** 5
- **Measurement iterations:** 10
- **Forks:** 3
- **Confidence interval:** 99%

### Noise Floor

Typical variation across runs: ±2-3%
Acceptable regression threshold: >5%
Critical regression threshold: >10%

---

## Recommendations

{RECOMMENDATIONS}

---

## Appendix: Raw Data

### Regression Detection Output

```
{RAW_REGRESSION_OUTPUT}
```

### Benchmark Results Comparison

{RAW_COMPARISON_TABLE}

---

**Analysis Method:** BenchmarkRegressionDetector.java
**Report Generated:** {TIMESTAMP}
**Next Review:** {NEXT_REVIEW_DATE}
