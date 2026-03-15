#!/bin/bash
# Baseline Performance Test Runner
# Measures observability overhead with 100K+ iterations

set -e

export JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal
export PATH=$JAVA_HOME/bin:$PATH

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║     JOTP OBSERVABILITY BASELINE PERFORMANCE TEST               ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Java Version:"
java -version 2>&1 | head -3
echo ""
echo "Maven Version:"
./mvnw --version | head -3
echo ""

# Clean compile to avoid stale class files
echo "Cleaning and compiling..."
./mvnw clean compile -q -DskipTests

# Run the baseline test
echo ""
echo "Running BaselinePerformanceTest..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
./mvnw test -Dtest=BaselinePerformanceTest -Dspotless.check.skip=true 2>&1 | tee baseline-results/test-output.log

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Test complete! Results saved to: baseline-results/test-output.log"
