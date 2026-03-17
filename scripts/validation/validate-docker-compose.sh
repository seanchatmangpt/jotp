#!/bin/bash
# Docker Compose Validation for JOTP
# Validates docker-compose files

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
FILES_CHECKED=0

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

echo "▶ Docker Compose Validation"
echo ""

# Check if docker compose is available
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    print_error "Neither docker compose nor docker-compose is available"
    exit 1
fi

print_info "Using: $COMPOSE_CMD"
echo ""

# Find all docker-compose files
COMPOSE_FILES=()
while IFS= read -r -d '' file; do
    COMPOSE_FILES+=("$file")
done < <(find "$PROJECT_ROOT" -type f -name "docker-compose*.yml" -o -name "docker-compose*.yaml" | grep -v "/.git/" | grep -v "/node_modules/" | sort -u)

if [ ${#COMPOSE_FILES[@]} -eq 0 ]; then
    print_warn "No docker-compose files found"
    exit 0
fi

print_info "Found ${#COMPOSE_FILES[@]} file(s) to validate"
echo ""

for compose_file in "${COMPOSE_FILES[@]}"; do
    relative_path="${compose_file#$PROJECT_ROOT/}"
    echo -n "Validating: $relative_path... "

    # Validate docker-compose file
    cd "$(dirname "$compose_file")"
    if $COMPOSE_CMD -f "$(basename "$compose_file")" config >/dev/null 2>&1; then
        print_info "✓"
        FILES_CHECKED=$((FILES_CHECKED + 1))
    else
        print_error "✗ Validation failed"
        echo "  Run: $COMPOSE_CMD -f $relative_path config"
    fi
    cd "$PROJECT_ROOT"
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All docker-compose files are valid"
    exit 0
else
    print_error "✗ Docker Compose validation failed ($FAILED error(s))"
    exit 1
fi
