#!/bin/bash
# File Reference Integrity Validation for JOTP
# Checks that actual file references in documentation exist

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
REFS_CHECKED=0

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

echo "▶ File Reference Integrity Validation"
echo ""

# Find all markdown files
MD_FILES=()
while IFS= read -r -d '' file; do
    MD_FILES+=("$file")
done < <(find "$PROJECT_ROOT/docs" -type f -name "*.md" -print0 2>/dev/null)

if [ ${#MD_FILES[@]} -eq 0 ]; then
    print_warn "No markdown files found in docs/"
    exit 0
fi

print_info "Checking ${#MD_FILES[@]} documentation file(s)"
echo ""

for md_file in "${MD_FILES[@]}"; do
    relative_path="${md_file#$PROJECT_ROOT/}"
    echo -n "Checking: $relative_path... "

    file_errors=0

    # Only check markdown links [text](path), not arbitrary text
    # Match: [text](../path/to/file) or [text](path/to/file)
    # Skip http/https/ftp/mailto links
    local_links=$(grep -oE '\[([^\]]+)\]\(([^)]+)\)' "$md_file" | grep -vE '^(http|https|ftp|mailto):' | cut -d'(' -f2 | cut -d')' -f1 || true)

    for link in $local_links; do
        # Remove anchors and query params
        link_path=$(echo "$link" | cut -d'#' -f1 | cut -d'?' -f1)

        # Skip empty links
        [ -z "$link_path" ] && continue

        # Skip if it's just a fragment (anchor only)
        [[ "$link" == "#"* ]] && continue

        # Resolve relative path
        link_dir=$(dirname "$md_file")
        target_file="$link_dir/$link_path"

        # Normalize path
        target_file=$(cd "$link_dir" && cd "$(dirname "$link_path")" 2>/dev/null && pwd)/$(basename "$link_path") 2>/dev/null || true

        if [ -n "$target_file" ] && [ -e "$target_file" ]; then
            : # File exists
        else
            if [ $file_errors -eq 0 ]; then
                echo ""
                echo -e "  ${YELLOW}⚠${NC} Missing references:"
            fi
            echo "    - $link_path"
            file_errors=$((file_errors + 1))
            FAILED=$((FAILED + 1))
        fi
    done

    if [ $file_errors -eq 0 ]; then
        print_info "✓"
    else
        REFS_CHECKED=$((REFS_CHECKED + 1))
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All file references are valid"
    exit 0
else
    print_error "✗ Found $FAILED missing file reference(s)"
    echo ""
    exit 1
fi
