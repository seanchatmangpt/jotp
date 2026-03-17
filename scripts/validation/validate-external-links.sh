#!/bin/bash
# External Link Validation for JOTP
# Checks that external HTTP/HTTPS links resolve

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILED=0
LINKS_CHECKED=0

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

echo "▶ External Link Validation (Nice to Have)"
echo ""

# Check for curl
if ! command -v curl >/dev/null 2>&1; then
    print_warn "curl is not installed - skipping external link check"
    exit 0
fi

print_warn "Note: This validation can be slow and may produce false positives"
print_warn "      (some servers may block automated requests)"
echo ""

# Find all markdown files
MD_FILES=()
while IFS= read -r -d '' file; do
    MD_FILES+=("$file")
done < <(find "$PROJECT_ROOT/docs" -type f -name "*.md" -print0 2>/dev/null | head -10) # Limit to 10 files for speed

if [ ${#MD_FILES[@]} -eq 0 ]; then
    print_warn "No markdown files found"
    exit 0
fi

print_info "Sampling external links from ${#MD_FILES[@]} file(s)"
echo ""

# Collect unique external links
EXTERNAL_LINKS=()
for md_file in "${MD_FILES[@]}"; do
    # Extract HTTP/HTTPS links
    links=$(grep -oE '\[([^\]]+)\]\((https?://[^)]+)\)' "$md_file" | cut -d'(' -f2 | cut -d')' -f1 || true)
    for link in $links; do
        EXTERNAL_LINKS+=("$link")
    done
done

# Deduplicate
unique_links=$(printf '%s\n' "${EXTERNAL_LINKS[@]}" | sort -u)

print_info "Found $(echo "$unique_links" | wc -l | tr -d ' ') unique external link(s)"
print_warn "Checking up to 10 links (sample)..."
echo ""

# Check up to 10 links
checked=0
echo "$unique_links" | head -10 | while read -r link; do
    [ -z "$link" ] && continue

    echo -n "Checking: $link... "

    # Use curl with timeout and follow redirects
    http_code=$(curl -L -s -o /dev/null -w "%{http_code}" --max-time 5 "$link" 2>/dev/null || echo "000")

    if [ "$http_code" = "200" ] || [ "$http_code" = "301" ] || [ "$http_code" = "302" ]; then
        print_info "✓ ($http_code)"
        LINKS_CHECKED=$((LINKS_CHECKED + 1))
    else
        print_warn "⚠ HTTP $http_code"
    fi

    checked=$((checked + 1))
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_info "External link check completed"
print_warn "This is a 'nice to have' validation - failures are non-blocking"
exit 0
