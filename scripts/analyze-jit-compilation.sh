#!/bin/bash
# JIT Compilation Analysis Script
# Runs benchmarks with detailed JIT compilation logging

set -e

RESULTS_DIR="docs/validation/performance/jit-compilation-analysis-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "JIT Compilation Analysis"
echo "======================="
echo "Results will be saved to: $RESULTS_DIR"
echo ""

# Run benchmark with JIT compilation logging
echo "Running benchmark with -XX:+PrintCompilation..."
mvn test -Dtest=JITCompilationAnalysisBenchmark \
    -DforkCount=0 \
    -Djmh.warmupIterations=15 \
    -Djmh.warmupTime=2 \
    -Djmh.measurementIterations=10 \
    -Djmh.measurementTime=1 \
    -Djmh.jvmArgsAppend="-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining" \
    -Djmh.result="$RESULTS_DIR/benchmark-results.json" \
    -Djmh.output="$RESULTS_DIR/benchmark.log" \
    > "$RESULTS_DIR/mvn-output.log" 2>&1

echo "Benchmark completed!"
echo ""

# Extract compilation information
echo "Analyzing compilation logs..."

# Extract compiled methods
grep "compile" "$RESULTS_DIR/benchmark.log" | grep -E "(Proc|LinkedTransferQueue)" > "$RESULTS_DIR/compiled-methods.txt" || true

# Extract inlining decisions
grep "inline" "$RESULTS_DIR/benchmark.log" | head -50 > "$RESULTS_DIR/inlining-decisions.txt" || true

# Extract tiered compilation info
grep -E "tiered|level [0-9]" "$RESULTS_DIR/benchmark.log" > "$RESULTS_DIR/tiered-compilation.txt" || true

echo ""
echo "Analysis complete!"
echo ""
echo "Results:"
echo "  - Compiled methods: $RESULTS_DIR/compiled-methods.txt"
echo "  - Inlining decisions: $RESULTS_DIR/inlining-decisions.txt"
echo "  - Tiered compilation: $RESULTS_DIR/tiered-compilation.txt"
echo "  - Full log: $RESULTS_DIR/benchmark.log"
echo ""
echo "Key findings:"
wc -l "$RESULTS_DIR/compiled-methods.txt" 2>/dev/null || echo "  No compiled methods found"
echo ""
echo "To view inlining decisions:"
echo "  cat $RESULTS_DIR/inlining-decisions.txt"
