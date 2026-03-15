#!/bin/bash

# JIT Compilation Analysis Script for JOTP
# This script runs benchmarks with various JIT compilation flags to analyze optimization

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR"

echo "=============================================================================="
echo "JIT COMPILATION ANALYSIS FOR JOTP"
echo "=============================================================================="
echo "Results directory: $RESULTS_DIR"
echo "Project root: $PROJECT_ROOT"
echo ""

# Create results directory if it doesn't exist
mkdir -p "$RESULTS_DIR"

# Function to run benchmark with specific JVM flags
run_benchmark() {
    local name=$1
    local jvm_flags=$2
    local output_file="$RESULTS_DIR/jit-analysis-${name}.log"

    echo "Running benchmark: $name"
    echo "JVM flags: $jvm_flags"
    echo "Output: $output_file"

    cd "$PROJECT_ROOT"

    # Run with inline measurements
    mvnd test \
        -Dtest=*Throughput* \
        -Djmh.jvmArgs="$jvm_flags" \
        -Djmh.resultFormat=json \
        -Djmh.outputFile="$output_file" \
        -Djmh.iterations=3 \
        -Djmh.warmupIterations=2 \
        2>&1 | tee -a "$output_file"

    echo "Completed: $name"
    echo ""
}

# Analysis 1: Baseline (default JIT settings)
echo "=============================================================================="
echo "ANALYSIS 1: BASELINE (Default JIT Settings)"
echo "=============================================================================="
run_benchmark "baseline" ""

# Analysis 2: Print compilation decisions
echo "=============================================================================="
echo "ANALYSIS 2: Print Compilation Decisions"
echo "=============================================================================="
run_benchmark "compilation" "-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"

# Analysis 3: Aggressive inlining
echo "=============================================================================="
echo "ANALYSIS 3: Aggressive Inlining"
echo "=============================================================================="
run_benchmark "aggressive-inline" "-XX:MaxInlineSize=100 -XX:FreqInlineSize=500"

# Analysis 4: Conservative inlining
echo "=============================================================================="
echo "ANALYSIS 4: Conservative Inlining"
echo "=============================================================================="
run_benchmark "conservative-inline" "-XX:MaxInlineSize=20 -XX:FreqInlineSize=200"

# Analysis 5: Disable tiered compilation
echo "=============================================================================="
echo "ANALYSIS 5: Disable Tiered Compilation (C2 Only)"
echo "=============================================================================="
run_benchmark "no-tiered" "-XX:-TieredCompilation"

# Analysis 6: Tiered compilation with C1 only
echo "=============================================================================="
echo "ANALYSIS 6: Tiered Compilation (C1 Only)"
echo "=============================================================================="
run_benchmark "c1-only" "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"

# Analysis 7: Log compilation for JITWatch
echo "=============================================================================="
echo "ANALYSIS 7: Log Compilation (JITWatch Format)"
echo "=============================================================================="
run_benchmark "jitwatch" "-XX:+LogCompilation -XX:LogFile=$RESULTS_DIR/jitwatch-compilation.log"

# Analysis 8: Print assembly (if hsdis is available)
echo "=============================================================================="
echo "ANALYSIS 8: Print Assembly Code (Requires hsdis)"
echo "=============================================================================="
run_benchmark "assembly" "-XX:+PrintAssembly -XX:+PrintInterpreter" || echo "Assembly printing failed (hsdis not installed)"

# Analysis 9: Virtual thread diagnostics
echo "=============================================================================="
echo "ANALYSIS 9: Virtual Thread Diagnostics"
echo "=============================================================================="
run_benchmark "virtual-threads" "-XX:+UnlockDiagnosticVMOptions -XX:+PrintVirtualThreadOperations"

# Analysis 10: GC impact on JIT
echo "=============================================================================="
echo "ANALYSIS 10: GC Impact on JIT Compilation"
echo "=============================================================================="
run_benchmark "g1-gc" "-XX:+UseG1GC -XX:MaxGCPauseMillis=50"
run_benchmark "parallel-gc" "-XX:+UseParallelGC"
run_benchmark "z-gc" "-XX:+UseZGC"

echo "=============================================================================="
echo "JIT ANALYSIS COMPLETE"
echo "=============================================================================="
echo ""
echo "Results saved to: $RESULTS_DIR"
echo ""
echo "Key files generated:"
echo "  - jit-analysis-*.log: Benchmark results with different JIT settings"
echo "  - jitwatch-compilation.log: JITWatch-compatible compilation log"
echo ""
echo "To analyze with JITWatch:"
echo "  1. Download JITWatch from: https://github.com/AdoptOpenJDK/jitwatch"
echo "  2. Run: java -jar jitwatch.jar"
echo "  3. Open: $RESULTS_DIR/jitwatch-compilation.log"
echo ""
echo "To compare compilation logs:"
echo "  diff $RESULTS_DIR/jit-analysis-baseline.log $RESULTS_DIR/jit-analysis-aggressive-inline.log"
echo ""
echo "To view inlining decisions:"
echo "  grep 'inline' $RESULTS_DIR/jit-analysis-compilation.log | head -50"
echo ""
