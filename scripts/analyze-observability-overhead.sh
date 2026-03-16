#!/bin/bash
# Analysis script for observability overhead
# Runs SimpleObservabilityBenchmark multiple times to check variance

set -e

echo "=== Observability Overhead Analysis ==="
echo "Running SimpleObservabilityBenchmark 5 times..."
echo ""

for i in {1..5}; do
  echo "--- Run $i ---"
  mvn test -Dtest=SimpleObservabilityBenchmark -q 2>&1 | \
    grep -A 15 "Configuration" | head -20 || echo "Run failed"
  echo ""
done

echo "=== Analysis Complete ==="
