#!/bin/bash
# Example workflow for running JMH benchmarks and generating reports

set -e

echo "=== JOTP Benchmark Reporting Example ==="
echo

# 1. Create output directories
mkdir -p target/jmh
mkdir -p benchmark-reports
mkdir -p benchmark-history

echo "Step 1: Running JMH benchmarks..."
# Run JMH benchmarks with JSON output
./mvnw test -Dtest=*Benchmark \
    -Djmh.format=json \
    -Djmh.outputDir=target/jmh \
    -Djmh.warmupIterations=3 \
    -Djmh.measurementIterations=5 \
    -q

echo "✓ Benchmarks complete"
echo

# 2. Archive results for historical tracking
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
echo "Step 2: Archiving results..."
cp target/jmh/results.json "benchmark-history/jmh-results-$TIMESTAMP.json"
echo "✓ Archived to: benchmark-history/jmh-results-$TIMESTAMP.json"
echo

# 3. Generate HTML report
echo "Step 3: Generating HTML report..."
python scripts/benchmark-report.py \
    --input=target/jmh/results.json \
    --format=html \
    --output=benchmark-reports/latest-report.html \
    --historical-dir=benchmark-history \
    --max-historical=10

echo "✓ HTML report generated: benchmark-reports/latest-report.html"
echo

# 4. Generate Markdown report
echo "Step 4: Generating Markdown report..."
python scripts/benchmark-report.py \
    --input=target/jmh/results.json \
    --format=markdown \
    --output=benchmark-reports/latest-report.md

echo "✓ Markdown report generated: benchmark-reports/latest-report.md"
echo

# 5. Show summary
echo "=== Summary ==="
echo "Reports generated:"
ls -lh benchmark-reports/
echo
echo "Historical runs:"
ls -1 benchmark-history/ | tail -5
echo

echo "Open the report:"
echo "  open benchmark-reports/latest-report.html"
echo

# Optional: Generate programmatic report using Java
echo "Step 5: Generating programmatic report (Java)..."
./mvnw test -Dtest=BenchmarkReportGeneratorTest -q
echo "✓ Programmatic report complete"
echo

echo "=== All done! ==="
