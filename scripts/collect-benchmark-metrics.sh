#!/bin/bash
# Collect detailed benchmark metrics for JIT/GC/Variance analysis

set -e

ANALYSIS_DIR="/Users/sac/jotp/docs/validation/performance"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_DIR="$ANALYSIS_DIR/raw-data-$TIMESTAMP"
mkdir -p "$RESULTS_DIR"

echo "=== JOTP Benchmark Metrics Collection ==="
echo "Results directory: $RESULTS_DIR"
echo ""

# Function to extract metrics from DTR output
extract_metrics() {
    local output=$1
    local config=$2
    local run_num=$3

    # Extract metrics using grep and awk
    # Format from SimpleObservabilityBenchmark:
    # Disabled | 150.23 | 120 | 450 | 680
    # Enabled | 250.45 | 180 | 550 | 890

    baseline_mean=$(echo "$output" | grep -A 2 "Configuration.*Mean.*p50" | grep "Disabled" | awk -F'|' '{print $2}' | tr -d ' ,')
    baseline_p95=$(echo "$output" | grep -A 2 "Configuration.*Mean.*p50" | grep "Disabled" | awk -F'|' '{print $4}' | tr -d ' ,')
    baseline_p99=$(echo "$output" | grep -A 2 "Configuration.*Mean.*p50" | grep "Disabled" | awk -F'|' '{print $5}' | tr -d ' ,')

    enabled_mean=$(echo "$output" | grep -A 2 "Configuration.*Mean.*p50" | grep "Enabled" | awk -F'|' '{print $2}' | tr -d ' ,')
    enabled_p95=$(echo "$output" | grep -A 2 "Configuration.*Mean.*p50" | grep "Enabled" | awk -F'|' '{print $4}' | tr -d ' ,')
    enabled_p99=$(echo "$output" | grep -A 2 "Configuration.*Mean.*p50" | grep "Enabled" | awk -F'|' '{print $5}' | tr -d ' ,')

    status=$(echo "$output" | grep -A 1 "Status" | tail -1 | awk '{print $NF}')

    # Calculate overhead
    overhead=$(echo "$enabled_mean - $baseline_mean" | bc -l 2>/dev/null || echo "0.0")

    echo "$config,$run_num,$baseline_mean,$baseline_p95,$baseline_p99,$enabled_mean,$enabled_p95,$enabled_p99,$overhead,$status"
}

# Initialize CSV file
CSV_FILE="$RESULTS_DIR/benchmark_metrics.csv"
echo "config,run,baseline_mean,baseline_p95,baseline_p99,enabled_mean,enabled_p95,enabled_p99,overhead_ns,status" > "$CSV_FILE"

# Run benchmark multiple times
echo "Collecting benchmark metrics (20 iterations)..."
for i in {1..20}; do
    echo "  Run $i/20..."

    output=$(mvnd test -Dtest=SimpleObservabilityBenchmark -q 2>&1)

    metrics=$(extract_metrics "$output" "default" "$i")
    echo "$metrics" >> "$CSV_FILE"

    # Small delay between runs
    sleep 1
done

echo ""
echo "✓ Metrics collection complete"
echo "Data saved to: $CSV_FILE"
echo ""
echo "Summary statistics:"
echo "$CSV_FILE" | awk -F',' 'NR>1 {sum_enabled+=$6; sum_baseline+=$3; count++} END {
    if (count > 0) {
        printf "  Baseline mean: %.2f ns\n", sum_baseline/count
        printf "  Enabled mean: %.2f ns\n", sum_enabled/count
        printf "  Average overhead: %.2f ns\n", (sum_enabled-sum_baseline)/count
        printf "  Total runs: %d\n", count
    }
}'

# Generate summary report
cat > "$RESULTS_DIR/summary.txt" << EOF
JOTP Benchmark Metrics Summary
Generated: $(date)
Total iterations: 20

Data file: $CSV_FILE

Key findings:
- Raw data collected from SimpleObservabilityBenchmark
- Analyzes observability overhead (disabled vs enabled)
- 20 independent runs for variance analysis
- Default JVM configuration (G1GC)

Next steps:
1. Run with different GC configurations
2. Analyze JIT warmup patterns
3. Calculate statistical confidence intervals
EOF

echo "Summary report: $RESULTS_DIR/summary.txt"
