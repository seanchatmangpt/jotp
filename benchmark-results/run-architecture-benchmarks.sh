#!/usr/bin/env bash
# Architecture Alternative Benchmarks Runner
#
# Executes comprehensive JMH benchmarks comparing 5 alternative architectures
# for framework observability targeting <100ns overhead when disabled.
#
# Usage:
#   ./run-architecture-benchmarks.sh [--quick | --full | --validate]
#
# Options:
#   --quick    Quick validation (1 fork, 3 iterations) ~5 minutes
#   --full     Full benchmark suite (3 forks, 10 iterations) ~30 minutes
#   --validate Production validation (5 forks, 20 iterations) ~60 minutes
#
# Output:
#   - benchmark-results/architecture-alternatives-raw.json (JMH raw data)
#   - benchmark-results/ARCHITECTURE-ALTERNATIVES-REPORT.html (visual report)
#   - benchmark-results/ARCHITECTURE-EXECUTIVE-SUMMARY.md (summary)

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BENCHMARK_CLASS="io.github.seanchatmangpt.jotp.observability.architecture.ArchitectureAlternativeBenchmarks"
RESULTS_DIR="${RESULTS_DIR:-benchmark-results}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOG_FILE="${RESULTS_DIR}/architecture-benchmark-${TIMESTAMP}.log"

# Parse command line arguments
MODE="${1:---quick}"

case "$MODE" in
    --quick)
        FORKS=1
        WARMUP_ITER=3
        WARMUP_TIME=1
        MEASUREMENT_ITER=3
        MEASUREMENT_TIME=1
        MODE_DESC="Quick Validation"
        ;;
    --full)
        FORKS=3
        WARMUP_ITER=5
        WARMUP_TIME=1
        MEASUREMENT_ITER=10
        MEASUREMENT_TIME=1
        MODE_DESC="Full Benchmark Suite"
        ;;
    --validate)
        FORKS=5
        WARMUP_ITER=10
        WARMUP_TIME=2
        MEASUREMENT_ITER=20
        MEASUREMENT_TIME=2
        MODE_DESC="Production Validation"
        ;;
    *)
        echo "Usage: $0 [--quick | --full | --validate]"
        exit 1
        ;;
esac

# Print header
echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Architecture Alternative Benchmarks${NC}"
echo -e "${BLUE}  Mode: ${MODE_DESC}${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"
echo ""

# Create results directory
mkdir -p "${RESULTS_DIR}"

# Check if benchmark class exists
if [ ! -f "target/test-classes/${BENCHMARK_CLASS//./\/}.class" ]; then
    echo -e "${YELLOW}Benchmark class not found. Compiling...${NC}"
    mvnd clean compile test-compile -q
fi

# Build JVM arguments for JIT compilation logging
JVM_ARGS=(
    "-XX:+PrintCompilation"
    "-XX:+PrintInlining"
    "-XX:+PrintSafepointStatistics"
    "-XX:PrintSafepointStatisticsCount=1"
    "-Xlog:gc*:file=${RESULTS_DIR}/gc-${TIMESTAMP}.log"
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:+LogCompilation"
    "-XX:LogFile=${RESULTS_DIR}/hotspot-${TIMESTAMP}.log"
)

# Build JMH arguments
JMH_ARGS=(
    "-jvmArgsAppend" "${JVM_ARGS[@]}"
    "-prof" "gc"
    "-prof" "stack"
    "-rf" "json"
    "-rff" "${RESULTS_DIR}/architecture-alternatives-${TIMESTAMP}.json"
    "-o" "${RESULTS_DIR}/architecture-alternatives-${TIMESTAMP}.txt"
    "-wm" "warmup"
    "-wmt" "${WARMUP_TIME}"
    "-w" "${WARMUP_ITER}"
    "-t" "${MEASUREMENT_TIME}"
    "-i" "${MEASUREMENT_ITER}"
    "-f" "${FORKS}"
    "-tu" "ns"
    "-bm" "avgt"
)

# Print configuration
echo -e "${GREEN}Configuration:${NC}"
echo "  Forks:            ${FORKS}"
echo "  Warmup iters:     ${WARMUP_ITER} × ${WARMUP_TIME}s"
echo "  Measurement iters: ${MEASUREMENT_ITER} × ${MEASUREMENT_TIME}s"
echo "  Results file:     ${RESULTS_DIR}/architecture-alternatives-${TIMESTAMP}.json"
echo "  Log file:         ${LOG_FILE}"
echo ""

# Run benchmark
echo -e "${BLUE}Starting benchmark execution...${NC}"
echo ""

START_TIME=$(date +%s)

if mvnd -pl . test-compile \
    && java -jar target/benchmarks.jar \
        "${JMH_CLASSPATH:-}" \
        "${JMH_ARGS[@]}" \
        "${BENCHMARK_CLASS}" \
    2>&1 | tee "${LOG_FILE}"; then

    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))

    echo ""
    echo -e "${GREEN}✓ Benchmark completed successfully in ${DURATION}s${NC}"

    # Generate summary
    echo ""
    echo -e "${BLUE}Generating summary...${NC}"

    cat > "${RESULTS_DIR}/ARCHITECTURE-EXECUTIVE-SUMMARY-${TIMESTAMP}.md" <<EOF
# Architecture Alternatives - Executive Summary

**Execution Date:** $(date)
**Mode:** ${MODE_DESC}
**Duration:** ${DURATION}s
**Configuration:** ${FORKS} forks, ${MEASUREMENT_ITER} iterations

## Results

| Architecture | Latency (ns) | Improvement | Target Met? |
|--------------|--------------|-------------|-------------|
| **Control (no overhead)** | TBD | - | - |
| **Current Baseline** | TBD | - | ❌ No |
| **Compile-Time Elimination** | TBD | TBD | TBD |
| **Method Handle Indirection** | TBD | TBD | TBD |
| **Static Final Delegation** | TBD | TBD | TBD |
| **Unsafe Memory Operations** | TBD | TBD | TBD |

## Analysis

Full results: \`architecture-alternatives-${TIMESTAMP}.json\`
Log file: \`architecture-benchmark-${TIMESTAMP}.log\`
GC log: \`gc-${TIMESTAMP}.log\`
HotSpot log: \`hotspot-${TIMESTAMP}.log\`

## Recommendation

[To be filled after benchmark execution]

EOF

    echo -e "${GREEN}✓ Summary generated: ${RESULTS_DIR}/ARCHITECTURE-EXECUTIVE-SUMMARY-${TIMESTAMP}.md${NC}"

    # Parse and display quick results
    echo ""
    echo -e "${BLUE}Quick Results (first pass):${NC}"

    if command -v jq &> /dev/null; then
        echo ""
        echo "Average Latency (ns):"
        jq -r '.[] | select(.primaryMetric.unit == "ns/op") | "\(.benchmark): \(.primaryMetric.score) ± \(.primaryMetadata.scoreError)"' \
            "${RESULTS_DIR}/architecture-alternatives-${TIMESTAMP}.json" | head -10
    else
        echo -e "${YELLOW}jq not found. Raw JSON available for analysis.${NC}"
    fi

    echo ""
    echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}Benchmark execution complete!${NC}"
    echo -e "${GREEN}Results saved to: ${RESULTS_DIR}${NC}"
    echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"

else
    echo ""
    echo -e "${RED}✗ Benchmark execution failed${NC}"
    echo -e "${RED}Check log file: ${LOG_FILE}${NC}"
    exit 1
fi
