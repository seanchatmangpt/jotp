#!/bin/bash
# Java Compilation Validation for JOTP
# Validates that all Java sources compile successfully

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    FAILED=$((FAILED + 1))
}

echo "▶ Java Compilation Validation"
echo ""

# Check if Java is installed
if ! command -v java >/dev/null 2>&1; then
    print_error "Java is not installed"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
print_info "Java version: $JAVA_VERSION"
echo ""

# Check for mvnd or mvn
if command -v mvnd >/dev/null 2>&1; then
    MVN_CMD="mvnd"
elif [ -f "$PROJECT_ROOT/mvnw" ]; then
    MVN_CMD="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
    MVN_CMD="mvn"
else
    print_error "No Maven command found (mvnd, mvn, or mvnw)"
    exit 1
fi

print_info "Using Maven: $MVN_CMD"
echo ""

# Run compilation
echo "Running compilation..."
echo ""

if $MVN_CMD compile -q --enable-preview 2>&1 | tee /tmp/jotp-compile.log; then
    print_info "✓ Compilation successful"
else
    print_error "✗ Compilation failed"
    echo ""
    echo "Last 20 lines of compile output:"
    tail -20 /tmp/jotp-compile.log
    FAILED=$((FAILED + 1))
fi

echo ""

if [ $FAILED -eq 0 ]; then
    exit 0
else
    exit 1
fi
