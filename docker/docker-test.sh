#!/usr/bin/env bash
set -euo pipefail

# Docker Test Script for JOTP Project
# Usage: ./docker/docker-test.sh [OPTIONS]
#
# Options:
#   --tag=TAG          Docker image tag [default: latest]
#   --build            Build test image before running
#   --coverage         Generate coverage report
#   --integration      Run integration tests
#   --unit             Run unit tests only
#   --parallel         Run tests in parallel
#   --help             Show this help message

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default values
TAG="latest"
BUILD=""
COVERAGE=""
INTEGRATION=""
UNIT=""
PARALLEL="-T1C"
IMAGE_NAME="jotp-test"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --tag=*)
            TAG="${1#*=}"
            shift
            ;;
        --build)
            BUILD="true"
            shift
            ;;
        --coverage)
            COVERAGE="true"
            shift
            ;;
        --integration)
            INTEGRATION="true"
            shift
            ;;
        --unit)
            UNIT="true"
            shift
            ;;
        --parallel=*)
            PARALLEL="-T${1#*=}"
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --tag=TAG          Docker image tag [default: latest]"
            echo "  --build            Build test image before running"
            echo "  --coverage         Generate coverage report"
            echo "  --integration      Run integration tests"
            echo "  --unit             Run unit tests only"
            echo "  --parallel=N       Run tests with N threads [default: auto]"
            echo "  --help             Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Build test image if requested
if [ -n "${BUILD}" ]; then
    echo "Building test image..."
    bash "${SCRIPT_DIR}/docker-build.sh" --target=test --tag="${TAG}"
fi

# Full image name
FULL_IMAGE_NAME="${IMAGE_NAME}:${TAG}"

echo "Running tests in Docker..."
echo "  Image: ${FULL_IMAGE_NAME}"
echo ""

# Change to project root
cd "$PROJECT_ROOT"

# Prepare Maven command
MAVEN_CMD="./mvnw test"

if [ -n "${UNIT}" ]; then
    MAVEN_CMD="./mvnw test -DskipITs"
elif [ -n "${INTEGRATION}" ]; then
    MAVEN_CMD="./mvnw verify"
else
    MAVEN_CMD="./mvnw verify"
fi

if [ -n "${COVERAGE}" ]; then
    MAVEN_CMD="${MAVEN_CMD} jacoco:report"
fi

# Run tests in container
docker run --rm \
    --name "jotp-test" \
    -v "$(pwd)/target:/build/target" \
    -v "jotp-test-results:/build/target/test-results" \
    --network jotp-network \
    -e MAVEN_OPTS="${MAVEN_OPTS:--Xmx1024m}" \
    "${FULL_IMAGE_NAME}" \
    bash -c "${MAVEN_CMD} ${PARALLEL}"

# Check test results
if [ $? -eq 0 ]; then
    echo ""
    echo "✓ All tests passed"
    echo ""

    # Show coverage if available
    if [ -n "${COVERAGE}" ] && [ -f "target/site/jacoco/index.html" ]; then
        echo "Coverage report: target/site/jacoco/index.html"
    fi
else
    echo ""
    echo "✗ Tests failed"
    echo ""
    echo "To view test logs:"
    echo "  docker logs jotp-test"
    exit 1
fi
