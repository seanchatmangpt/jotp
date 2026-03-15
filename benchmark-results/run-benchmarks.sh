#!/bin/bash

# JOTP Benchmark Execution Script
#
# This script runs the comprehensive JMH benchmark suite and generates
# a v1.0 performance baseline for regression detection.
#
# Usage: ./run-benchmarks.sh

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "JOTP Benchmark Execution Script"
echo "=========================================="
echo ""

# Check Java version
echo -e "${YELLOW}[1/6] Checking Java installation...${NC}"
if ! command -v java &> /dev/null; then
    echo -e "${RED}ERROR: Java not found. Please install OpenJDK 26:${NC}"
    echo "  brew install openjdk@26"
    echo "  export JAVA_HOME=/usr/lib/jvm/openjdk-26"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "Found Java version: $JAVA_VERSION"

if [ "$JAVA_VERSION" -lt 26 ]; then
    echo -e "${YELLOW}WARNING: Java 26+ recommended for preview features${NC}"
fi

# Check JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    echo -e "${YELLOW}WARNING: JAVA_HOME not set${NC}"
    echo "Setting JAVA_HOME to /usr/lib/jvm/openjdk-26"
    export JAVA_HOME=/usr/lib/jvm/openjdk-26
fi

echo -e "${GREEN}✓ Java installation verified${NC}"
echo ""

# Create benchmark results directory
echo -e "${YELLOW}[2/6] Creating benchmark-results directory...${NC}"
mkdir -p /Users/sac/jotp/benchmark-results
echo -e "${GREEN}✓ Directory ready${NC}"
echo ""

# Navigate to project directory
echo -e "${YELLOW}[3/6] Navigating to project directory...${NC}"
cd /Users/sac/jotp
echo -e "${GREEN}✓ Working directory: $(pwd)${NC}"
echo ""

# Compile project
echo -e "${YELLOW}[4/6] Compiling project...${NC}"
if [ -f "./mvnw" ]; then
    ./mvnw clean compile -q -DskipTests
    echo -e "${GREEN}✓ Project compiled successfully${NC}"
else
    echo -e "${RED}ERROR: Maven wrapper (./mvnw) not found${NC}"
    exit 1
fi
echo ""

# Run benchmarks
echo -e "${YELLOW}[5/6] Running JMH benchmark suite...${NC}"
echo "This may take 10-15 minutes..."
echo ""

# Create timestamp for results
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
RESULTS_FILE="/Users/sac/jotp/benchmark-results/baseline-v1.0-${TIMESTAMP}.json"
HTML_REPORT="/Users/sac/jotp/benchmark-results/baseline-v1.0-${TIMESTAMP}.html"

# Run all benchmarks
./mvnw test -Dtest=*Benchmark -Pbenchmark \
    -Djmh.format=json \
    -Djmh.output="$RESULTS_FILE" \
    -Djmh.htmlReport="$HTML_REPORT" \
    || {
    echo -e "${RED}ERROR: Benchmark execution failed${NC}"
    echo "Check that all benchmark classes are in src/test/java/.../benchmark/"
    exit 1
}

echo ""
echo -e "${GREEN}✓ Benchmarks completed successfully${NC}"
echo ""

# Generate regression report
echo -e "${YELLOW}[6/6] Generating regression analysis...${NC}"
echo "Results saved to: $RESULTS_FILE"
echo "HTML report: $HTML_REPORT"

# Create symlink for easy access
ln -sf "$RESULTS_FILE" /Users/sac/jotp/benchmark-results/latest.json
ln -sf "$HTML_REPORT" /Users/sac/jotp/benchmark-results/latest.html

echo "Created symlinks:"
echo "  latest.json -> $RESULTS_FILE"
echo "  latest.html -> $HTML_REPORT"
echo ""

# Summary
echo "=========================================="
echo -e "${GREEN}BENCHMARK EXECUTION COMPLETE${NC}"
echo "=========================================="
echo ""
echo "Next steps:"
echo "  1. Review results: cat $RESULTS_FILE"
echo "  2. Open HTML report: open $HTML_REPORT"
echo "  3. Compare with future runs using BenchmarkRegressionDetector"
echo ""
echo "To run regression detection in the future:"
echo "  java -cp target/classes:target/test-classes \\"
echo "    io.github.seanchatmangpt.jotp.benchmark.util.BenchmarkRegressionDetector \\"
echo "    /Users/sac/jotp/benchmark-results/baseline-v1.0-${TIMESTAMP}.json \\"
echo "    /Users/sac/jotp/benchmark-results/baseline-vX.Y.json"
echo ""
