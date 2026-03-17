#!/bin/bash
# Terminology Consistency Validation for JOTP
# Checks for consistent use of JOTP terminology

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
WARNINGS=0

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    WARNINGS=$((WARNINGS + 1))
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    FAILED=$((FAILED + 1))
}

echo "▶ Terminology Consistency Validation (Nice to Have)"
echo ""

# Find all markdown files
MD_FILES=()
while IFS= read -r -d '' file; do
    MD_FILES+=("$file")
done < <(find "$PROJECT_ROOT/docs" -type f -name "*.md" -print0 2>/dev/null)

if [ ${#MD_FILES[@]} -eq 0 ]; then
    print_warn "No markdown files found"
    exit 0
fi

print_info "Checking ${#MD_FILES[@]} documentation file(s)"
echo ""

# Common terminology issues to check
echo "Checking for common terminology issues..."
echo ""

# 1. Check for "actor" vs "Actor" (should be capitalized when referring to the model)
echo "1. Actor terminology..."
if grep -r "actor model" "$PROJECT_ROOT/docs"/*.md 2>/dev/null | grep -v "Actor model" | head -5; then
    print_warn "⚠ Found lowercase 'actor model' (should be 'Actor model')"
fi

# 2. Check for "OTP" vs "Erlang/OTP" (should be "Erlang/OTP" on first mention)
echo "2. OTP terminology..."
if grep -r " OTP " "$PROJECT_ROOT/docs"/*.md 2>/dev/null | head -5; then
    print_warn "⚠ Found standalone 'OTP' (consider 'Erlang/OTP')"
fi

# 3. Check for Proc/ProcRef consistency
echo "3. Proc/ProcRef usage..."
proc_count=$(grep -r "Proc" "$PROJECT_ROOT/docs"/*.md 2>/dev/null | wc -l | tr -d ' ')
procref_count=$(grep -r "ProcRef" "$PROJECT_ROOT/docs"/*.md 2>/dev/null | wc -l | tr -d ' ')
print_info "  Proc mentions: $proc_count"
print_info "  ProcRef mentions: $procref_count"

# 4. Check for Supervisor vs supervisor
echo "4. Supervisor terminology..."
supervisor_count=$(grep -r "Supervisor" "$PROJECT_ROOT/docs"/*.md 2>/dev/null | wc -l | tr -d ' ')
print_info "  Supervisor mentions: $supervisor_count"

# 5. Check for virtual threads terminology
echo "5. Virtual threads terminology..."
vt_count=$(grep -r "virtual thread" "$PROJECT_ROOT/docs"/*.md 2>/dev/null | wc -l | tr -d ' ')
print_info "  Virtual thread mentions: $vt_count"

# 6. Check for process vs Process (should be Process when referring to JOTP processes)
echo "6. Process terminology..."
if grep -r " jotp process" "$PROJECT_ROOT/docs"/*.md 2>/dev/null | head -5; then
    print_warn "⚠ Found lowercase 'jotp process' (should be 'JOTP Process')"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    if [ $WARNINGS -gt 0 ]; then
        print_warn "✓ Terminology check completed with $WARNINGS warning(s)"
        print_warn "  These are suggestions - not blocking issues"
    else
        print_info "✓ Terminology appears consistent"
    fi
    exit 0
else
    print_error "✗ Terminology validation failed"
    exit 1
fi
