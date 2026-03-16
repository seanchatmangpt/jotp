#!/bin/bash
# Code Cache & Tiered Compilation Analysis Script

set -e

RESULTS_DIR="docs/validation/performance/code-cache-analysis-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "Code Cache & Tiered Compilation Analysis"
echo "========================================"
echo "Results will be saved to: $RESULTS_DIR"
echo ""

# Test 1: C1 only (TieredStopAtLevel=1)
echo "Test 1: C1 only (client compiler)..."
mvn test -Dtest=JITCompilationAnalysisBenchmark \
    -DforkCount=0 \
    -Djmh.warmupIterations=15 \
    -Djmh.warmupTime=1 \
    -Djmh.measurementIterations=5 \
    -Djmh.measurementTime=1 \
    -Djmh.jvmArgsAppend="-XX:TieredStopAtLevel=1 -XX:+PrintCodeCache" \
    -Djmh.result="$RESULTS_DIR/c1-only-results.json" \
    > "$RESULTS_DIR/c1-only.log" 2>&1

echo "  C1 only completed"

# Test 2: Full C2 (TieredStopAtLevel=4)
echo "Test 2: Full C2 (server compiler)..."
mvn test -Dtest=JITCompilationAnalysisBenchmark \
    -DforkCount=0 \
    -Djmh.warmupIterations=15 \
    -Djmh.warmupTime=1 \
    -Djmh.measurementIterations=5 \
    -Djmh.measurementTime=1 \
    -Djmh.jvmArgsAppend="-XX:TieredStopAtLevel=4 -XX:+PrintCodeCache" \
    -Djmh.result="$RESULTS_DIR/c2-full-results.json" \
    > "$RESULTS_DIR/c2-full.log" 2>&1

echo "  C2 full completed"

# Test 3: Default tiered compilation
echo "Test 3: Default tiered compilation..."
mvn test -Dtest=JITCompilationAnalysisBenchmark \
    -DforkCount=0 \
    -Djmh.warmupIterations=15 \
    -Djmh.warmupTime=1 \
    -Djmh.measurementIterations=5 \
    -Djmh.measurementTime=1 \
    -Djmh.jvmArgsAppend="-XX:+PrintCodeCache -XX:ReservedCodeCacheSize=256m" \
    -Djmh.result="$RESULTS_DIR/default-results.json" \
    > "$RESULTS_DIR/default-tiered.log" 2>&1

echo "  Default tiered completed"

echo ""
echo "Analyzing code cache usage..."

# Extract code cache information
grep -i "code cache" "$RESULTS_DIR/c1-only.log" | head -20 > "$RESULTS_DIR/c1-code-cache.txt" || true
grep -i "code cache" "$RESULTS_DIR/c2-full.log" | head -20 > "$RESULTS_DIR/c2-code-cache.txt" || true
grep -i "code cache" "$RESULTS_DIR/default-tiered.log" | head -20 > "$RESULTS_DIR/default-code-cache.txt" || true

# Extract performance comparison
echo "Configuration,Latency_ns" > "$RESULTS_DIR/tiered-comparison.csv"

for config in "c1-only" "c2-full" "default"; do
    JSON_FILE="$RESULTS_DIR/${config}-results.json"
    if [ -f "$JSON_FILE" ]; then
        if command -v jq &> /dev/null; then
            LATENCY=$(jq '.[0].primaryMetric.score' "$JSON_FILE" 2>/dev/null || echo "NA")
        else
            LATENCY=$(grep -o '"score":[0-9.]*' "$JSON_FILE" | head -1 | cut -d: -f2 || echo "NA")
        fi
        echo "$config,$LATENCY" >> "$RESULTS_DIR/tiered-comparison.csv"
    fi
done

echo ""
echo "Analysis complete!"
echo ""
echo "Results:"
echo "  - C1 code cache: $RESULTS_DIR/c1-code-cache.txt"
echo "  - C2 code cache: $RESULTS_DIR/c2-code-cache.txt"
echo "  - Default code cache: $RESULTS_DIR/default-code-cache.txt"
echo "  - Tiered comparison: $RESULTS_DIR/tiered-comparison.csv"
echo ""
echo "Performance comparison:"
cat "$RESULTS_DIR/tiered-comparison.csv"
echo ""
echo "Code cache exhaustion check:"
echo "  Search logs for 'CodeCache is full' or 'code cache full'"
grep -i "code cache.*full" "$RESULTS_DIR"/*.log || echo "  No code cache exhaustion detected"
