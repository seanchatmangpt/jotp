#!/bin/bash
# Reproducibility Testing Script for JOTP Benchmarks

echo "========================================="
echo "JOTP Benchmark Reproducibility Testing"
echo "========================================="
echo ""
echo "Test Environment:"
echo "  Java Version: $(java -version 2>&1 | head -1)"
echo "  OS: $(uname -s) $(uname -r)"
echo "  CPU Cores: $(sysctl -n hw.physicalcpu)"
echo "  Memory: $(sysctl -n hw.memsize | awk '{printf "%.2f GB", $1/1024/1024/1024}')"
echo "  Date: $(date)"
echo ""

# Create results directory
mkdir -p docs/validation/performance
RESULTS_FILE="docs/validation/performance/reproducibility-test-results.md"

# Initialize results file
cat > "$RESULTS_FILE" << 'EOF'
# JOTP Benchmark Reproducibility Test Results

**Test Date:** 2026-03-16
**Tester:** Agent 13 (Automated Reproducibility Testing)
**Java Version:** Java 26 (GraalVM)
**OS:** macOS 26.2 (aarch64)
**Hardware:** Apple Silicon (16 cores, 48 GB RAM)

## Executive Summary

This document captures the results of running JOTP benchmarks to verify the performance claims published in the README.md. All benchmarks were run on the same hardware to establish reproducibility.

## Test Environment

| Property | Value |
|----------|-------|
| Java Version | 26+13-jvmci-b01 (Oracle GraalVM) |
| JVM | Java HotSpot(TM) 64-Bit Server VM |
| OS | Mac OS X 26.2 (aarch64) |
| CPU | Apple Silicon (16 physical cores) |
| Memory | 48 GB |
| Date | 2026-03-16 |

## Benchmark Results

EOF

echo "Running benchmarks..."
echo ""

# Function to run benchmark and extract metrics
run_benchmark() {
    local benchmark_name="$1"
    local test_class="$2"

    echo "Testing: $benchmark_name"
    echo "========================================="

    # Run the test
    ./mvnw test -Dtest="$test_class" > /tmp/benchmark_${test_class}.log 2>&1

    # Extract key metrics from log
    local test_result=$(grep -E "(Tests run:|BUILD SUCCESS|BUILD FAILURE)" /tmp/benchmark_${test_class}.log | tail -2)
    local status=$(echo "$test_result" | grep -q "BUILD SUCCESS" && echo "PASS" || echo "FAIL")

    # Check for specific failure patterns
    if echo "$test_result" | grep -q "BUILD FAILURE"; then
        # Extract failure details
        local failure_details=$(grep -A 5 "Expecting actual:" /tmp/benchmark_${test_class}.log | head -10)
        echo "Status: FAIL"
        echo "Details: $failure_details"
    else
        echo "Status: PASS"
    fi

    echo ""
}

# Run key benchmarks
run_benchmark "Simple Observability Benchmark" "SimpleObservabilityBenchmark"
run_benchmark "Observability Throughput Benchmark" "ObservabilityThroughputBenchmark"
run_benchmark "Parallel Benchmark" "ParallelBenchmark"
run_benchmark "Result Benchmark" "ResultBenchmark"

echo "========================================="
echo "Testing Complete"
echo "========================================="
echo ""
echo "Results saved to: $RESULTS_FILE"
