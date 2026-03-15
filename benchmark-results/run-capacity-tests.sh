#!/bin/bash
# Capacity Planning Test Execution Script
# This script runs the observability capacity planning tests and captures results

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Project directory
PROJECT_DIR="/Users/sac/jotp"
RESULTS_DIR="$PROJECT_DIR/benchmark-results"

# Log file
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOG_FILE="$RESULTS_DIR/capacity-test-$TIMESTAMP.log"

echo -e "${GREEN}=== JOTP Observability Capacity Planning Tests ===${NC}"
echo "Log file: $LOG_FILE"
echo ""

# Check Java installation
echo "Checking Java installation..."
if ! command -v java &> /dev/null; then
    echo -e "${RED}ERROR: Java not found${NC}"
    echo "Please install OpenJDK 26:"
    echo "  brew install openjdk@26"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1)
echo "Found: $JAVA_VERSION"

if ! echo "$JAVA_VERSION" | grep -q "26"; then
    echo -e "${YELLOW}WARNING: Java 26 not detected. Tests require Java 26.${NC}"
    echo "Current version: $JAVA_VERSION"
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Create results directory
mkdir -p "$RESULTS_DIR"

# Navigate to project directory
cd "$PROJECT_DIR"

# Function to run a specific test
run_test() {
    local test_name=$1
    echo -e "${GREEN}Running: $test_name${NC}"
    echo "---" | tee -a "$LOG_FILE"
    ./mvnw test -Dtest="ObservabilityCapacityPlanner#$test_name" 2>&1 | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"
}

# Function to extract JSON reports
extract_reports() {
    echo -e "${GREEN}Extracting JSON reports...${NC}"
    grep -A 30 "CAPACITY REPORT" "$LOG_FILE" > "$RESULTS_DIR/capacity-reports-$TIMESTAMP.json" || true
    echo "Reports saved to: $RESULTS_DIR/capacity-reports-$TIMESTAMP.json"
}

# Main execution
echo -e "${GREEN}Starting test execution...${NC}"
echo "Timestamp: $TIMESTAMP"
echo "" | tee "$LOG_FILE"

# Option 1: Run all tests at once
echo "Choose execution mode:"
echo "  1) Run all tests in one command (recommended)"
echo "  2) Run each test individually"
read -p "Enter choice (1 or 2): " -n 1 -r
echo ""

case $REPLY in
    1)
        echo -e "${GREEN}Running all capacity planning tests...${NC}"
        ./mvnw clean test -Dtest=ObservabilityCapacityPlanner 2>&1 | tee -a "$LOG_FILE"
        ;;
    2)
        echo -e "${GREEN}Running tests individually...${NC}"
        run_test "smallInstance"
        run_test "mediumInstance"
        run_test "largeInstance"
        run_test "enterpriseInstance"
        run_test "memoryOverhead"
        run_test "hotPathContamination"
        ;;
    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

# Extract reports
extract_reports

# Summary
echo ""
echo -e "${GREEN}=== Test Execution Complete ===${NC}"
echo "Log file: $LOG_FILE"
echo "JSON reports: $RESULTS_DIR/capacity-reports-$TIMESTAMP.json"
echo ""
echo "To view results:"
echo "  cat $LOG_FILE"
echo "  cat $RESULTS_DIR/capacity-reports-$TIMESTAMP.json"
echo ""
echo "To update summary document:"
echo "  vim $RESULTS_DIR/capacity-planning-results.md"
