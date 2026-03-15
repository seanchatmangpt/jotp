#!/usr/bin/env bash
set -euo pipefail

# Docker Run Script for JOTP Project
# Usage: ./docker/docker-run.sh [OPTIONS]
#
# Options:
#   --target=TARGET    Build target (runtime|test|development) [default: runtime]
#   --tag=TAG          Docker image tag [default: latest]
#   --port=PORT        Host port mapping [default: 8080:8080]
#   --debug            Enable debug port (5005)
#   --build            Build image before running
#   --detach           Run in detached mode
#   --rm               Remove container on exit
#   --env=FILE         Load environment variables from file
#   --help             Show this help message

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default values
TARGET="runtime"
TAG="latest"
HOST_PORT="8080"
DEBUG=""
BUILD=""
DETACH="-it"
RM=""
ENV_FILE=""
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
        --port=*)
            HOST_PORT="${1#*=}"
            shift
            ;;
        --debug)
            DEBUG="-p 5005:5005"
            shift
            ;;
        --build)
            BUILD="true"
            shift
            ;;
        --detach)
            DETACH="-d"
            shift
            ;;
        --rm)
            RM="--rm"
            shift
            ;;
        --env=*)
            ENV_FILE="--env-file ${1#*=}"
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --target=TARGET    Build target (runtime|test|development) [default: runtime]"
            echo "  --tag=TAG          Docker image tag [default: latest]"
            echo "  --port=PORT        Host port mapping [default: 8080]"
            echo "  --debug            Enable debug port (5005)"
            echo "  --build            Build image before running"
            echo "  --detach           Run in detached mode"
            echo "  --rm               Remove container on exit"
            echo "  --env=FILE         Load environment variables from file"
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

# Build if requested
if [ -n "${BUILD}" ]; then
    echo "Building Docker image..."
    bash "${SCRIPT_DIR}/docker-build.sh" --target="${TARGET}" --tag="${TAG}"
fi

# Full image name
FULL_IMAGE_NAME="${IMAGE_NAME}:${TAG}"

echo "Running Docker container..."
echo "  Image: ${FULL_IMAGE_NAME}"
echo "  Target: ${TARGET}"
echo "  Port: ${HOST_PORT}:8080"
if [ -n "${DEBUG}" ]; then
    echo "  Debug: 5005:5005"
fi
echo ""

# Change to project root
cd "$PROJECT_ROOT"

# Prepare volume mounts
VOLUMES="-v jotp-logs:/app/logs"
if [ "${TARGET}" = "development" ]; then
    VOLUMES="${VOLUMES} -v ${PROJECT_ROOT}/src:/workspace/src"
fi

# Prepare environment
ENV_VARS="-e SPRING_PROFILES_ACTIVE=prod -e LOG_LEVEL=INFO"

# Run container
docker run ${DETACH} ${RM} \
    --name "jotp-${TARGET}" \
    -p "${HOST_PORT}:8080" \
    ${DEBUG} \
    ${VOLUMES} \
    ${ENV_VARS} \
    ${ENV_FILE} \
    --network jotp-network \
    "${FULL_IMAGE_NAME}"

# Show running status
if [ "${DETACH}" = "-d" ]; then
    echo ""
    echo "Container started in detached mode."
    echo "View logs: docker logs -f jotp-${TARGET}"
    echo "Stop container: docker stop jotp-${TARGET}"
    echo "Remove container: docker rm jotp-${TARGET}"
fi
