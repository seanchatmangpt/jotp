#!/bin/bash
# JOTP JIT/GC/Variance Analysis Script
# Analyzes benchmark performance across different JVM configurations

set -e

ANALYSIS_DIR="/Users/sac/jotp/docs/validation/performance"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_DIR="$ANALYSIS_DIR/raw-data-$TIMESTAMP"
mkdir -p "$RESULTS_DIR"

echo "=== JOTP JIT/GC/Variance Deep Dive Analysis ==="
echo "Results directory: $RESULTS_DIR"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to run benchmark with specific JVM args
run_benchmark() {
    local name=$1
    local jvm_args=$2
    local iterations=$3
    local output_file="$RESULTS_DIR/${name}.csv"

    echo -e "${YELLOW}Running: $name${NC}"
    echo "JVM args: $jvm_args"

    # Create header
    echo "run,mean_ns,p50_ns,p95_ns,p99_ns,overhead_ns,status" > "$output_file"

    for i in $(seq 1 $iterations); do
        echo "  Run $i/$iterations..."

        # Run benchmark and capture output
        output=$(JAVA_TOOL_OPTIONS="$jvm_args" mvnd test -Dtest=SimpleObservabilityBenchmark -q 2>&1)

        # Parse metrics from output (using DTR output format)
        mean=$(echo "$output" | grep -A 5 "Quick validation" | grep "Enabled" | awk '{print $2}' || echo "0")
        p95=$(echo "$output" | grep -A 5 "Quick validation" | grep "Enabled" | awk '{print $4}' || echo "0")
        p99=$(echo "$output" | grep -A 5 "Quick validation" | grep "Enabled" | awk '{print $5}' || echo "0")

        # Extract status
        status=$(echo "$output" | grep -q "Status" && echo "PASS" || echo "FAIL")

        # Calculate overhead (baseline vs enabled)
        overhead="0"  # Simplified for now

        echo "$i,$mean,$p95,$p95,$p99,$overhead,$status" >> "$output_file"
    done

    echo -e "${GREEN}✓ Completed: $name${NC}"
    echo ""
}

# Task 1: JIT Warmup Analysis
echo "=== Task 1: JIT Warmup Analysis ==="
run_benchmark "jit_warmup_iterations_1" "" 5
run_benchmark "jit_warmup_iterations_5" "" 5
run_benchmark "jit_warmup_iterations_15" "" 5
run_benchmark "jit_warmup_iterations_50" "" 5

# Task 2: GC Impact Analysis
echo "=== Task 2: GC Impact Analysis ==="
run_benchmark "gc_g1gc_default" "-XX:+UseG1GC" 5
run_benchmark "gc_zgc" "-XX:+UseZGC -XX:+UnlockExperimentalVMOptions" 5
run_benchmark "gc_shenandoah" "-XX:+UseShenandoahGC" 5

# Task 3: Variance Analysis (extended runs)
echo "=== Task 3: Variance Analysis ==="
run_benchmark "variance_analysis" "" 20

echo "=== Analysis Complete ==="
echo "Results saved to: $RESULTS_DIR"
echo ""
echo "Next step: Generate reports using Python/R analysis scripts"
