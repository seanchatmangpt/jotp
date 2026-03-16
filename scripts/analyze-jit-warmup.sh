#!/bin/bash
# JIT Warmup Analysis Script
# Runs benchmarks with varying warmup iterations to identify stabilization point

set -e

RESULTS_DIR="docs/validation/performance/jit-warmup-analysis-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "JIT Warmup Analysis"
echo "==================="
echo "Results will be saved to: $RESULTS_DIR"
echo ""

# Test different warmup iterations
WARMUP_ITERATIONS=(1 3 5 10 15 20 30 50)

echo "Running benchmarks with varying warmup iterations..."
echo ""

for iter in "${WARMUP_ITERATIONS[@]}"; do
    echo "Testing with $iter warmup iterations..."

    OUTPUT_FILE="$RESULTS_DIR/warmup-${iter}iters.json"

    mvn test -Dtest=JITCompilationAnalysisBenchmark \
        -DforkCount=0 \
        -Djmh.warmupIterations="$iter" \
        -Djmh.warmupTime=1 \
        -Djmh.measurementIterations=10 \
        -Djmh.measurementTime=1 \
        -Djmh.resultFormat=json \
        -Djmh.result="$OUTPUT_FILE" \
        -Djmh.output="$RESULTS_DIR/warmup-${iter}iters.log" \
        > /dev/null 2>&1 &

    # Run in parallel but limit concurrency
    if (( $(jobs -r | wc -l) >= 4 )); then
        wait -n
    fi
done

# Wait for all background jobs
wait

echo ""
echo "All benchmarks completed!"
echo ""
echo "Analyzing results..."

# Extract key metrics from JSON files
echo "Iteration,Latency_ns,Throughput_ops_per_us" > "$RESULTS_DIR/warmup-curve.csv"

for iter in "${WARMUP_ITERATIONS[@]}"; do
    JSON_FILE="$RESULTS_DIR/warmup-${iter}iters.json"

    if [ -f "$JSON_FILE" ]; then
        # Extract average latency from JSON (using jq if available, otherwise fallback to grep)
        if command -v jq &> /dev/null; then
            LATENCY=$(jq '.[0].primaryMetric.score' "$JSON_FILE" 2>/dev/null || echo "NA")
        else
            LATENCY=$(grep -o '"score":[0-9.]*' "$JSON_FILE" | head -1 | cut -d: -f2 || echo "NA")
        fi

        # Calculate throughput (operations per microsecond)
        if [ "$LATENCY" != "NA" ]; then
            THROUGHPUT=$(echo "scale=2; 1000 / $LATENCY" | bc 2>/dev/null || echo "NA")
        else
            THROUGHPUT="NA"
        fi

        echo "$iter,$LATENCY,$THROUGHPUT" >> "$RESULTS_DIR/warmup-curve.csv"
        echo "  Warmup $iter: latency=${LATENCY}ns, throughput=${THROUGHPUT} ops/us"
    fi
done

echo ""
echo "Results saved to:"
echo "  - $RESULTS_DIR/warmup-curve.csv"
echo "  - $RESULTS_DIR/warmup-*-iters.json"
echo ""
echo "To visualize the warmup curve:"
echo "  - Import warmup-curve.csv into your favorite tool"
echo "  - Plot: x=Iteration, y=Latency_ns"
echo "  - Identify where the curve flattens (stabilization point)"
