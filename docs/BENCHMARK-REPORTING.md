# Benchmark Reporting Infrastructure

**⚠️ Important:** Please read [docs/testing/BENCHMARKING.md](./testing/BENCHMARKING.md) for **critical methodology disclosures** before using this reporting infrastructure.

This directory contains the benchmark reporting infrastructure for JOTP, providing comprehensive analysis and visualization of JMH benchmark results.

## Components

### 1. BenchmarkResultsTemplate.md
**Location**: `src/test/resources/benchmark/BenchmarkResultsTemplate.md`

A comprehensive Markdown template for benchmark reports including:
- Executive Summary with key metrics
- Detailed Results for each benchmark
- SLA Compliance analysis
- Trend comparison across runs
- JVM and System Information
- Performance recommendations

### 2. benchmark-report.py
**Location**: `scripts/benchmark-report.py`

Python script for generating reports from JMH JSON results.

**Features**:
- Parses JMH JSON output files
- Calculates percentiles (P50, P90, P95, P99, P999)
- Analyzes trends across multiple runs
- Generates HTML reports with CSS styling
- Generates Markdown reports
- SLA compliance checking

**Usage**:
```bash
# Generate HTML report
python scripts/benchmark-report.py --format=html --output=benchmark-report.html

# Generate Markdown report
python scripts/benchmark-report.py --format=markdown --output=benchmark-report.md

# Specify custom input file
python scripts/benchmark-report.py --input=target/jmh/results.json --format=html

# Include historical data for trend analysis
python scripts/benchmark-report.py --historical-dir=benchmark-history --max-historical=10

# Verbose output
python scripts/benchmark-report.py --format=html --verbose
```

**Requirements**:
- Python 3.7+
- No external dependencies (uses standard library only)

### 3. BenchmarkReport.java
**Location**: `src/test/java/io/github/seanchatmangpt/jotp/benchmark/report/BenchmarkReport.java`

Java utility class for programmatic report generation.

**Features**:
- Parse JMH JSON results
- Calculate statistics (throughput, latency, percentiles)
- Generate JSON reports
- Generate HTML reports with styling
- Trend analysis across multiple runs
- SLA compliance checking

**Usage Example**:
```java
// Load JMH results
var report = BenchmarkReport.fromJson(Path.of("jmh-results.json"));

// Generate HTML report
String html = report.generateHtmlReport();
Files.writeString(Path.of("benchmark-report.html"), html);

// Generate JSON report
String json = report.generateJsonReport();
Files.writeString(Path.of("benchmark-report.json"), json);

// Analyze trends
List<BenchmarkReport> previousRuns = List.of(
    BenchmarkReport.fromJson(Path.of("run1.json")),
    BenchmarkReport.fromJson(Path.of("run2.json"))
);
TrendAnalysis trends = report.calculateTrends(previousRuns);

// Check SLA compliance
for (BenchmarkResult result : report.results()) {
    if (result.score() >= 10000) {
        System.out.println(result.name() + " meets SLA");
    }
}
```

## Workflow Integration

### Running Benchmarks with Reporting

1. **Run JMH benchmarks**:
```bash
./mvnw test -Dtest=*Benchmark -Djmh.format=json -Djmh.outputDir=target/jmh
```

2. **Generate reports**:
```bash
# Using Python script
python scripts/benchmark-report.py --input=target/jmh/results.json

# Or programmatically in tests
@Test
void generateBenchmarkReport() throws IOException {
    var report = BenchmarkReport.fromJson(Path.of("target/jmh/results.json"));
    report.writeTo(Path.of("benchmark-report.html"), ReportFormat.HTML);
}
```

### Continuous Monitoring

For continuous performance monitoring:

```bash
# Run benchmarks and store with timestamp
./mvnw test -Dtest=*Benchmark -Djmh.format=json
timestamp=$(date +%Y%m%d-%H%M%S)
cp target/jmh/results.json "benchmark-history/jmh-results-$timestamp.json"

# Generate report with trend analysis
python scripts/benchmark-report.py \
    --historical-dir=benchmark-history \
    --max-historical=20 \
    --output=benchmark-report-trended.html
```

## Report Sections

### Executive Summary
- **Performance Overview**: Key metrics (throughput, latency, error rate)
- **SLA Compliance**: Pass/fail status against defined SLAs
- **Key Findings**: Automated insights and recommendations

### Detailed Results
- **Performance Metrics**: Score, error margins, min/max/avg
- **Percentiles**: P50, P90, P95, P99, P999 latencies
- **Memory Metrics**: Heap usage, GC counts
- **Thread Metrics**: Active and peak thread counts

### Trend Analysis
- **Current vs Previous**: Direct comparison with last run
- **Change Percentage**: Percentage change for each metric
- **Trend Direction**: Improving/Degraded/Stable classification

### Recommendations
- **Performance Optimizations**: Specific suggestions for improvement
- **Configuration Changes**: JVM and system tuning recommendations
- **Code Improvements**: Code-level optimization suggestions

## SLA Targets

Default SLA targets configured:

| Metric | Target | Purpose |
|--------|--------|---------|
| Throughput | ≥ 10,000 ops/s | Minimum throughput for production use |
| P95 Latency | ≤ 1.0 ms | Tail latency for 95% of requests |
| P99 Latency | ≤ 5.0 ms | Tail latency for 99% of requests |
| Error Rate | ≤ 0.01% | Maximum acceptable error rate |

## Customization

### Customizing SLA Targets

Edit the SLA values in `benchmark-report.py` or `BenchmarkReport.java`:

```python
# In benchmark-report.py
def _sla_status(self, actual: float, target: float, operator: str) -> str:
    if operator == ">=":
        return "PASS" if actual >= target else "FAIL"  # Modify target here
```

```java
// In BenchmarkReport.java
long slaPassCount = results.stream()
    .filter(r -> r.score() >= 10000)  // Modify target here
    .count();
```

### Customizing Report Template

Edit `BenchmarkResultsTemplate.md` to add sections or modify formatting:

```markdown
## Custom Section

| Custom Metric | Value | Status |
|---------------|-------|--------|
| {{custom_metric}} | {{custom_value}} | {{custom_status}} |
```

Then update `benchmark-report.py` to provide values:

```python
replacements = {
    "custom_metric": "My Metric",
    "custom_value": "42.0",
    "custom_status": "PASS",
    # ... other replacements
}
```

## Troubleshooting

### "Input file not found"
Ensure JMH has generated results:
```bash
./mvnw test -Dtest=*Benchmark -Djmh.format=json
ls target/jmh/results.json
```

### "No historical data"
Create a history directory and populate with previous runs:
```bash
mkdir -p benchmark-history
cp target/jmh/results.json benchmark-history/jmh-results-$(date +%Y%m%d).json
```

### "Percentiles showing as 0"
Ensure JMH is running with enough iterations and warmup:
```bash
./mvnw test -Dtest=*Benchmark \
    -Djmh.warmupIterations=5 \
    -Djmh.measurementIterations=10 \
    -Djmh.format=json
```

## Examples

See the test directory for usage examples:
- `BenchmarkReportTest.java` - Java API usage examples
- Sample reports in `target/benchmark-reports/` (after running tests)

## Related Documentation

- [JMH Documentation](https://openjdk.org/projects/code-tools/jmh/)
- [JOTP Benchmark Patterns](../docs/BENCHMARK-PATTERNS.md)
- [Performance Testing Guide](../docs/PERFORMANCE-TESTING.md)
