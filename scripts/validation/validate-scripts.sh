#!/bin/bash
# Shell Script Validation for JOTP
# Validates executability, shebangs, and syntax

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
SCRIPTS_CHECKED=0

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

echo "▶ Shell Script Validation"
echo ""

# Find all shell scripts
SCRIPT_FILES=()
while IFS= read -r -d '' file; do
    SCRIPT_FILES+=("$file")
done < <(find "$PROJECT_ROOT" -type f \( -name "*.sh" -o -name "*.bash" \) -print0 | grep -v "/.git/" | grep -v "/target/" | grep -v "/node_modules/")

if [ ${#SCRIPT_FILES[@]} -eq 0 ]; then
    print_warn "No shell scripts found"
    exit 0
fi

print_info "Checking ${#SCRIPT_FILES[@]} script(s)"
echo ""

for script_file in "${SCRIPT_FILES[@]}"; do
    relative_path="${script_file#$PROJECT_ROOT/}"
    echo -n "Checking: $relative_path... "

    errors=0

    # 1. Executability Check
    if [ -x "$script_file" ]; then
        : # Script is executable
    else
        if [ $errors -eq 0 ]; then
            echo ""
            echo -e "  ${YELLOW}⚠${NC} Issues found:"
        fi
        echo "    - Not executable"
        errors=$((errors + 1))
        FAILED=$((FAILED + 1))
    fi

    # 2. Shebang Check
    first_line=$(head -n 1 "$script_file")
    if [[ "$first_line" == "#!"* ]]; then
        shebang_ok=true

        # Check if shebang points to valid interpreter
        interpreter=$(echo "$first_line" | cut -d'!' -f2 | cut -d' ' -f1)
        if [ -n "$interpreter" ]; then
            if [ ! -e "$interpreter" ] && ! command -v "$(basename "$interpreter")" >/dev/null 2>&1; then
                # Some shebangs use env (e.g., /usr/bin/env bash)
                if [[ ! "$interpreter" == *"/env"* ]]; then
                    if [ $errors -eq 0 ]; then
                        echo ""
                        echo -e "  ${YELLOW}⚠${NC} Issues found:"
                    fi
                    echo "    - Shebang interpreter not found: $interpreter"
                    errors=$((errors + 1))
                    shebang_ok=false
                fi
            fi
        fi

        if [ "$shebang_ok" = true ]; then
            : # Shebang is valid
        fi
    else
        if [ $errors -eq 0 ]; then
            echo ""
            echo -e "  ${YELLOW}⚠${NC} Issues found:"
        fi
        echo "    - Missing shebang"
        errors=$((errors + 1))
        FAILED=$((FAILED + 1))
    fi

    # 3. Syntax Check
    if [[ "$first_line" == *"bash"* ]]; then
        if ! bash -n "$script_file" 2>/dev/null; then
            if [ $errors -eq 0 ]; then
                echo ""
                echo -e "  ${YELLOW}⚠${NC} Issues found:"
            fi
            echo "    - Syntax error"
            errors=$((errors + 1))
            FAILED=$((FAILED + 1))
        fi
    elif [[ "$first_line" == *"sh"* ]]; then
        if ! sh -n "$script_file" 2>/dev/null; then
            if [ $errors -eq 0 ]; then
                echo ""
                echo -e "  ${YELLOW}⚠${NC} Issues found:"
            fi
            echo "    - Syntax error"
            errors=$((errors + 1))
            FAILED=$((FAILED + 1))
        fi
    fi

    if [ $errors -eq 0 ]; then
        print_info "✓"
    else
        SCRIPTS_CHECKED=$((SCRIPTS_CHECKED + 1))
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All scripts are valid"
    exit 0
else
    print_error "✗ Script validation failed ($FAILED error(s))"
    exit 1
fi
