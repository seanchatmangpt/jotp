#!/usr/bin/env bash
set -euo pipefail

# Docker Build Script for JOTP Project
# Usage: ./docker/docker-build.sh [OPTIONS]
#
# Options:
#   --target=TARGET    Build target (runtime|test|development) [default: runtime]
#   --tag=TAG          Docker image tag [default: latest]
#   --no-cache         Build without using cache
#   --push             Push to registry after build
#   --help             Show this help message

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default values
TARGET="runtime"
TAG="latest"
NO_CACHE=""
PUSH=""
REGISTRY="docker.io"
IMAGE_NAME="jotp"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --target=*)
            TARGET="${1#*=}"
            shift
            ;;
        --tag=*)
            TAG="${1#*=}"
            shift
            ;;
        --no-cache)
            NO_CACHE="--no-cache"
            shift
            ;;
        --push)
            PUSH="true"
            shift
            ;;
        --registry=*)
            REGISTRY="${1#*=}"
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --target=TARGET    Build target (runtime|test|development) [default: runtime]"
            echo "  --tag=TAG          Docker image tag [default: latest]"
            echo "  --no-cache         Build without using cache"
            echo "  --push             Push to registry after build"
            echo "  --registry=URL     Docker registry [default: docker.io]"
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

# Full image name
FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${TAG}"

echo "Building Docker image..."
echo "  Target: ${TARGET}"
echo "  Tag: ${FULL_IMAGE_NAME}"
echo "  Project: ${PROJECT_ROOT}"
echo ""

# Change to project root
cd "$PROJECT_ROOT"

# Build Docker image
docker build \
    --target="${TARGET}" \
    --tag="${FULL_IMAGE_NAME}" \
    ${NO_CACHE} \
    --build-arg "VERSION=${TAG}" \
    .

# Check build status
if [ $? -eq 0 ]; then
    echo "✓ Build successful: ${FULL_IMAGE_NAME}"
    echo ""
    echo "Image details:"
    docker images "${IMAGE_NAME}:${TAG}" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"

    # Security scan (if Trivy is available)
    if command -v trivy &> /dev/null; then
        echo ""
        echo "Running security scan..."
        trivy image --severity HIGH,CRITICAL "${FULL_IMAGE_NAME}" || true
    fi

    # Push to registry if requested
    if [ -n "${PUSH}" ]; then
        echo ""
        echo "Pushing image to registry..."
        docker push "${FULL_IMAGE_NAME}"
        echo "✓ Push complete"
    fi

    echo ""
    echo "To run the container:"
    echo "  docker run -p 8080:8080 ${FULL_IMAGE_NAME}"
    echo ""
    echo "Or use docker-compose:"
    echo "  docker-compose up -d"
else
    echo "✗ Build failed"
    exit 1
fi
