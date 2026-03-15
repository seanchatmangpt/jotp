# JOTP Benchmark Data Format Documentation

This document describes the data format used for JOTP benchmark results in the dashboard.

## Overview

Benchmark data is processed from multiple source formats (JMH JSON, Markdown, logs) into a standardized JSON format for visualization and analysis.

## Data Processing Pipeline

```
Source Files → Parser → Normalizer → Aggregator → JSON Output
```

### 1. Source Files

- **JMH JSON**: `benchmark-results/jmh-results.json`
- **Markdown**: `benchmark-results/*-results.md`
- **Logs**: `benchmark-results/*-execution.log`

### 2. Parser (`lib/benchmark-data-parser.ts`)

Extracts benchmark data from source formats into `BenchmarkResult` objects.

### 3. Normalizer (`lib/benchmark-data-normalizer.ts`)

Converts all metrics to consistent units:
- Latency: nanoseconds (ns)
- Throughput: operations per second (ops/sec)
- Memory: bytes

### 4. Aggregator (`lib/benchmark-data-aggregator.ts`)

Generates summaries and statistics:
- Overall summary
- Category summaries
- SLA compliance
- Capacity profiles
- Time series data

### 5. Output Files

Located in `benchmark-site/public/data/`:
- `benchmarks.json` - All benchmark results
- `summary.json` - Aggregated summary
- `timeseries.json` - Historical data
- `sla-compliance.json` - SLA validation
- `capacity-profiles.json` - Capacity planning
- `hot-path-validation.json` - Hot path analysis
- `throughput-scaling.json` - Scaling analysis

## Data Schema

### BenchmarkResult

```typescript
interface BenchmarkResult {
  id: string                          // Unique identifier
  name: string                        // Benchmark name
  category: BenchmarkCategory         // Category (see below)
  timestamp: Date                     // When benchmark ran
  config: BenchmarkConfig             // Execution configuration
  metrics: BenchmarkMetrics           // Performance metrics
  status: 'pass' | 'fail' | 'warning' // Overall status
  source: string                      // Source file name
}
```

### BenchmarkCategory

```typescript
type BenchmarkCategory =
  | 'baseline'        // Baseline measurements
  | 'throughput'      // Throughput benchmarks
  | 'capacity'        // Capacity planning
  | 'precision'       // Precision/latency tests
  | 'stress'          // Stress tests
  | 'observability'   // Observability overhead
  | 'hot-path'        // Hot path validation
  | 'architecture'    // Architecture tests
  | 'memory'          // Memory allocation
  | 'jit'             // JIT optimization
  | 'profiling'       // Profiling data
```

### BenchmarkConfig

```typescript
interface BenchmarkConfig {
  jvmVersion: string           // JVM version (e.g., "26.0.1+11")
  jvmVendor: string            // Vendor (e.g., "Oracle Corporation")
  javaVersion: string          // Java version (e.g., "26")
  platform: string             // OS platform (e.g., "darwin")
  mode: string                 // Benchmark mode
  threads: number              // Number of threads
  forks: number                // Number of forks
  warmupIterations: number     // Warmup iterations
  measurementIterations: number // Measurement iterations
}
```

### BenchmarkMetrics

```typescript
interface BenchmarkMetrics {
  // Primary score
  score?: number              // Primary metric value
  scoreUnit?: string          // Unit of score (e.g., "ops/s", "ns/op")
  scoreError?: number         // Error margin
  scoreConfidence?: [number, number] // 99.9% CI

  // Throughput metrics
  throughput?: number         // ops/sec
  throughputMin?: number
  throughputMax?: number

  // Latency metrics (nanoseconds)
  latency?: number            // Average latency
  p50?: number                // Median
  p90?: number                // 90th percentile
  p95?: number                // 95th percentile
  p99?: number                // 99th percentile
  p999?: number               // 99.9th percentile
  min?: number
  max?: number

  // Resource metrics
  cpu?: number                // CPU percentage (0-100)
  cpuOverhead?: number        // CPU overhead percentage
  memory?: number             // Memory bytes
  memoryOverhead?: number     // Memory overhead bytes

  // Percentiles object from JMH
  scorePercentiles?: Record<string, number>

  // Raw data
  rawData?: number[][]        // Raw measurement data
}
```

## Processing Benchmark Data

### Manual Processing

```bash
cd benchmark-site
npm run process-benchmarks
```

This will:
1. Read all benchmark result files
2. Parse and normalize data
3. Generate JSON files in `public/data/`

### Automated Processing

The processing script can be integrated into CI/CD:

```yaml
# Example GitHub Actions workflow
- name: Process Benchmark Data
  run: |
    cd benchmark-site
    npm run process-benchmarks
```

## Data Validation

Run validation to check data integrity:

```bash
npm run validate-benchmarks
```

This checks:
- Required fields are present
- Metric values are valid (non-negative, non-NaN)
- Percentile ordering is correct (p50 <= p90 <= p95 <= p99)
- CPU percentages are in 0-100 range
- Outliers are detected (z-score > 3)
- Time series consistency

## Examples

### JMH JSON Input

```json
[
  {
    "benchmark": "io.github.seanchatmangpt.jotp.observability.FrameworkMetricsBenchmark.benchmarkProcessCreation",
    "mode": "Throughput",
    "threads": 1,
    "primaryMetric": {
      "score": 15234.567,
      "scoreUnit": "ops/s",
      "scorePercentiles": {
        "50.0": 15200.000,
        "99.0": 15600.000
      }
    },
    "vmVersion": "26.0.1+11"
  }
]
```

### Normalized Output

```json
{
  "id": "jmh-0-1710457200000",
  "name": "benchmarkProcessCreation",
  "category": "observability",
  "timestamp": "2024-03-14T12:00:00.000Z",
  "config": {
    "jvmVersion": "26.0.1+11",
    "javaVersion": "26",
    "platform": "darwin"
  },
  "metrics": {
    "throughput": 15234.567,
    "p50": 15200.0,
    "p99": 15600.0
  },
  "status": "pass",
  "source": "jmh-results.json"
}
```

## SLA Compliance

SLA targets are defined per category:

| Category | Metric | Target |
|----------|--------|--------|
| baseline | latency | < 100ns |
| baseline | p99 | < 1000ns |
| throughput | throughput | > 1M ops/sec |
| precision | overhead | < 100ns |
| observability | overhead | < 100ns |

## Best Practices

### Adding New Benchmarks

1. Run benchmarks and output to `benchmark-results/`
2. Name files descriptively: `{category}-results.md`
3. Include metadata in markdown:
   ```markdown
   **Date:** 2024-03-14
   **Java Version:** 26
   **Status:** ✅ PASS
   ```
4. Run `npm run process-benchmarks`

### Updating Source Data

1. Update source files in `benchmark-results/`
2. Re-run processing script
3. Validate output: `npm run validate-benchmarks`
4. Commit both source and processed data

### Data Quality

- Ensure all timestamps are valid ISO dates
- Use consistent units (nanoseconds for latency, ops/sec for throughput)
- Include confidence intervals where applicable
- Document any anomalies or outliers

## Troubleshooting

### Missing Data

If benchmarks don't appear in dashboard:
1. Check source files exist in `benchmark-results/`
2. Verify file naming matches expected patterns
3. Check processing logs for errors
4. Validate JSON output is valid

### Invalid Metrics

If metrics show as invalid:
1. Check for negative values
2. Verify percentile ordering (p50 <= p99)
3. Ensure units are consistent
4. Run validation script

### Outliers

If outliers are detected:
1. Check raw data for anomalies
2. Verify test execution was stable
3. Consider removing or flagging outliers
4. Document reason for exclusion

## API Reference

### BenchmarkDataParser

```typescript
// Parse JMH JSON
BenchmarkDataParser.parseJMHResults(data: JMHResult[]): BenchmarkResult[]

// Parse Markdown
BenchmarkDataParser.parseMarkdownResults(
  content: string,
  category: BenchmarkCategory,
  source: string
): BenchmarkResult[]

// Parse Logs
BenchmarkDataParser.parseLogResults(
  content: string,
  category: BenchmarkCategory,
  source: string
): BenchmarkResult[]
```

### BenchmarkDataNormalizer

```typescript
// Normalize all results
BenchmarkDataNormalizer.normalizeResults(results: BenchmarkResult[]): BenchmarkResult[]

// Format for display
BenchmarkDataNormalizer.formatLatency(nanoseconds: number): string
BenchmarkDataNormalizer.formatThroughput(opsPerSec: number): string
BenchmarkDataNormalizer.formatMemory(bytes: number): string
```

### BenchmarkDataAggregator

```typescript
// Generate summary
BenchmarkDataAggregator.generateSummary(results: BenchmarkResult[]): BenchmarkSummary

// Generate time series
BenchmarkDataAggregator.generateTimeSeries(results: BenchmarkResult[]): TimeSeriesDataPoint[]

// SLA compliance
BenchmarkDataAggregator.generateSLACompliance(results: BenchmarkResult[]): SLACompliance[]

// Capacity profiles
BenchmarkDataAggregator.generateCapacityProfiles(results: BenchmarkResult[]): CapacityPlanningProfile[]
```

### BenchmarkDataValidator

```typescript
// Validate single result
BenchmarkDataValidator.validateResult(result: BenchmarkResult): ValidationResult

// Validate all results
BenchmarkDataValidator.validateResults(results: BenchmarkResult[]): ValidationResult

// Detect outliers
BenchmarkDataValidator.detectOutliers(
  results: BenchmarkResult[],
  metric: keyof BenchmarkMetrics
): Outlier[]

// Validate time series
BenchmarkDataValidator.validateTimeSeries(results: BenchmarkResult[]): TimeSeriesValidation
```

## Future Enhancements

- [ ] Add support for CSV input format
- [ ] Real-time streaming processing
- [ ] Incremental updates (append-only)
- [ ] Data versioning and rollback
- [ ] Automated data quality checks
- [ ] Trend analysis and anomaly detection

---

**Last Updated:** 2024-03-14
**Version:** 1.0.0
