#!/bin/bash
# Documentation Link Validation for JOTP
# Validates internal markdown links

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
LINKS_CHECKED=0
BROKEN_LINKS=()

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

echo "▶ Documentation Link Validation"
echo ""

# Check for markdown-link-check
if ! command -v markdown-link-check >/dev/null 2>&1; then
    print_warn "markdown-link-check not installed"
    print_warn "Install with: npm install -g markdown-link-check"
    print_warn "Falling back to basic validation..."
    echo ""
fi

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

for md_file in "${MD_FILES[@]}"; do
    relative_path="${md_file#$PROJECT_ROOT/}"
    echo -n "Checking: $relative_path... "

    if command -v markdown-link-check >/dev/null 2>&1; then
        # Use markdown-link-check for comprehensive validation
        if markdown-link-check "$md_file" -q 2>/dev/null; then
            print_info "✓"
            LINKS_CHECKED=$((LINKS_CHECKED + 1))
        else
            print_error "✗ Broken links found"
            BROKEN_LINKS+=("$relative_path")
        fi
    else
        # Fallback: basic internal link check
        file_errors=0

        # Extract markdown links
        local_links=$(grep -oE '\[([^\]]+)\]\(([^)]+)\)' "$md_file" | grep -vE '^(http|https|ftp|mailto):' | cut -d'(' -f2 | cut -d')' -f1 || true)

        for link in $local_links; do
            # Remove anchors
            link_path=$(echo "$link" | cut -d'#' -f1)

            # Skip empty links
            [ -z "$link_path" ] && continue

            # Resolve relative path
            link_dir=$(dirname "$md_file")
            target_file="$link_dir/$link_path"

            if [ -e "$target_file" ]; then
                : # Link is valid
            else
                if [ $file_errors -eq 0 ]; then
                    print_error "✗ Broken link(s)"
                fi
                echo "    - $link"
                file_errors=$((file_errors + 1))
            fi
        done

        if [ $file_errors -eq 0 ]; then
            print_info "✓"
            LINKS_CHECKED=$((LINKS_CHECKED + 1))
        else
            BROKEN_LINKS+=("$relative_path")
            FAILED=$((FAILED + file_errors))
        fi
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All documentation links are valid"
    exit 0
else
    print_error "✗ Found broken links in ${#BROKEN_LINKS[@]} file(s)"
    echo ""

    print_error "Files with broken links:"
    for broken in "${BROKEN_LINKS[@]}"; do
        echo "  - $broken"
    done
    echo ""

    exit 1
fi
