#!/bin/bash
# Quick Pre-commit Validation for JOTP
# Runs in < 30 seconds - ideal for git pre-commit hooks

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
WARNED=0

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    WARNED=$((WARNED + 1))
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    FAILED=$((FAILED + 1))
}

echo "▶ JOTP Quick Validation (pre-commit)"
echo ""

# 1. Guard Check (H_TODO, H_MOCK, H_STUB)
echo -n "Checking for guard violations... "
if grep -r "H_TODO\|H_MOCK\|H_STUB" "$PROJECT_ROOT/src/main/java" 2>/dev/null | grep -v "Binary file" | head -5; then
    print_error "Found guard violations in production code"
else
    print_info "✓ No guard violations"
fi

# 2. Spotless Format Check (sample only)
echo -n "Checking code format (sample)... "
if command -v mvnd >/dev/null 2>&1; then
    # Quick check - just verify some files are formatted
    SAMPLE_FILE=$(find "$PROJECT_ROOT/src/main/java" -name "*.java" -type f | head -1)
    if [ -n "$SAMPLE_FILE" ]; then
        if mvnd spotless:check -q 2>/dev/null; then
            print_info "✓ Code format check passed"
        else
            print_warn "Some files need formatting"
        fi
    else
        print_warn "No Java files found to check"
    fi
else
    print_warn "mvnd not available - skipping format check"
fi

# 3. YAML Syntax Check (sample)
echo -n "Checking YAML syntax (sample)... "
if command -v python3 >/dev/null 2>&1; then
    YAML_ERRORS=0
    for yaml_file in $(find "$PROJECT_ROOT" -name "*.yaml" -o -name "*.yml" | head -5); do
        if ! python3 -c "import yaml; yaml.safe_load(open('$yaml_file'))" 2>/dev/null; then
            print_error "Invalid YAML: $yaml_file"
            YAML_ERRORS=1
        fi
    done
    if [ $YAML_ERRORS -eq 0 ]; then
        print_info "✓ Sample YAML files valid"
    fi
else
    print_warn "python3 not available - skipping YAML check"
fi

# 4. Script Executability Check
echo -n "Checking script executability... "
SCRIPT_ERRORS=0
for script in $(find "$PROJECT_ROOT/scripts" -name "*.sh" -type f | head -5); do
    if [ ! -x "$script" ]; then
        print_warn "Script not executable: $script"
        SCRIPT_ERRORS=1
    fi
done
if [ $SCRIPT_ERRORS -eq 0 ]; then
    print_info "✓ Sample scripts are executable"
fi

# 5. Basic Shell Script Syntax
echo -n "Checking shell script syntax (sample)... "
if command -v bash >/dev/null 2>&1; then
    SYNTAX_ERRORS=0
    for script in $(find "$PROJECT_ROOT/scripts" -name "*.sh" -type f | head -5); do
        if ! bash -n "$script" 2>/dev/null; then
            print_error "Syntax error in: $script"
            SYNTAX_ERRORS=1
        fi
    done
    if [ $SYNTAX_ERRORS -eq 0 ]; then
        print_info "✓ Sample scripts have valid syntax"
    fi
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    print_info "✓ Quick validation passed"
    exit 0
else
    print_error "✗ Quick validation failed ($FAILED error(s))"
    exit 1
fi
