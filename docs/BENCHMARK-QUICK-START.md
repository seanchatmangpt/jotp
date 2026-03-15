# Benchmark Reporting Quick Start

## 5-Minute Guide to Benchmark Reporting

### Step 1: Run a Benchmark (1 minute)

```bash
# Run the example benchmark
./mvnw test -Dtest=ExampleBenchmark \
    -Djmh.format=json \
    -Djmh.outputDir=target/jmh
```

### Step 2: Generate a Report (1 minute)

```bash
# Generate HTML report
python scripts/benchmark-report.py \
    --input=target/jmh/results.json \
    --format=html \
    --output=benchmark-report.html
```

### Step 3: View the Report (1 minute)

```bash
# Open in browser
open benchmark-report.html

# Or read Markdown
python scripts/benchmark-report.py \
    --input=target/jmh/results.json \
    --format=markdown \
    --output=benchmark-report.md
cat benchmark-report.md
```

### Step 4: Track Trends (2 minutes)

```bash
# Create history directory
mkdir -p benchmark-history

# Run multiple times and archive
for i in {1..3}; do
    ./mvnw test -Dtest=ExampleBenchmark -Djmh.format=json -q
    cp target/jmh/results.json \
       "benchmark-history/run-$i-$(date +%s).json"
    sleep 1
done

# Generate trended report
python scripts/benchmark-report.py \
    --historical-dir=benchmark-history \
    --max-historical=3 \
    --output=benchmark-trends.html
```

## Common Commands

### Generate Reports

```bash
# HTML report
python scripts/benchmark-report.py --format=html

# Markdown report
python scripts/benchmark-report.py --format=markdown

# Both formats
python scripts/benchmark-report.py --format=both
```

### Specify Input/Output

```bash
# Custom input file
python scripts/benchmark-report.py --input=my-results.json

# Custom output location
python scripts/benchmark-report.py --output=reports/my-report

# Both
python scripts/benchmark-report.py \
    --input=data/results.json \
    --output=reports/summary
```

### Trend Analysis

```bash
# Analyze last 10 runs
python scripts/benchmark-report.py \
    --historical-dir=benchmark-history \
    --max-historical=10

# Custom history directory
python scripts/benchmark-report.py \
    --historical-dir=/path/to/history \
    --max-historical=20
```

## Java API Usage

```java
// Load JMH results
var report = BenchmarkReport.fromJson(Path.of("target/jmh/results.json"));

// Generate HTML report
report.writeTo(
    Path.of("report.html"),
    BenchmarkReport.ReportFormat.HTML
);

// Generate JSON report
String json = report.generateJsonReport();

// Analyze specific benchmark
Optional<BenchmarkResult> result = report.findResult("ExampleBenchmark.sumArray");
result.ifPresent(r -> {
    System.out.println("Throughput: " + r.score() + " ops/s");
    System.out.println("P95: " + r.getPercentile("95.0").orElse(0) + " ms");
});
```

## Report Sections

| Section | Description |
|---------|-------------|
| **Executive Summary** | High-level metrics and SLA compliance |
| **Detailed Results** | Per-benchmark metrics and percentiles |
| **Trend Analysis** | Comparison with previous runs |
| **Recommendations** | Performance improvement suggestions |
| **Appendix** | Raw data and environment details |

## SLA Targets

| Metric | Target | Check |
|--------|--------|-------|
| Throughput | ≥ 10,000 ops/s | Green if ≥ 10,000 |
| P95 Latency | ≤ 1.0 ms | Green if ≤ 1.0 |
| P99 Latency | ≤ 5.0 ms | Green if ≤ 5.0 |
| Error Rate | ≤ 0.01% | Green if ≤ 0.01% |

## Troubleshooting

### No results file generated

```bash
# Check if JMH ran
ls target/jmh/results.json

# Re-run with verbose output
./mvnw test -Dtest=ExampleBenchmark -Djmh.format=json -X
```

### Python script fails

```bash
# Check Python version (requires 3.7+)
python --version

# Run with verbose output
python scripts/benchmark-report.py --verbose
```

### Empty trends

```bash
# Check history directory
ls -la benchmark-history/

# Ensure files are named: jmh-results-*.json
mv benchmark-history/results.json benchmark-history/jmh-results-001.json
```

## Next Steps

- Read [BENCHMARK-REPORTING.md](./BENCHMARK-REPORTING.md) for detailed documentation
- Create custom benchmarks following `ExampleBenchmark.java`
- Integrate reports into CI/CD pipeline
- Set up automated historical tracking

## Resources

- **Template**: `src/test/resources/benchmark/BenchmarkResultsTemplate.md`
- **Python Script**: `scripts/benchmark-report.py`
- **Java API**: `BenchmarkReport.java`
- **Example Benchmark**: `ExampleBenchmark.java`
- **Documentation**: `docs/BENCHMARK-REPORTING.md`
