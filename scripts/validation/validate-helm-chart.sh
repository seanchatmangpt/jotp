#!/bin/bash
# Helm Chart Validation for JOTP
# Validates Helm chart structure, linting, and template rendering

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHART_DIR="$PROJECT_ROOT/helm/jotp"

FAILED=0

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

echo "▶ Helm Chart Validation"
echo ""

# Check if chart directory exists
if [ ! -d "$CHART_DIR" ]; then
    print_error "Chart directory not found: $CHART_DIR"
    exit 1
fi

print_info "Chart directory: $CHART_DIR"
echo ""

# 1. Chart Structure Validation
echo "1. Validating chart structure..."

required_files=(
    "Chart.yaml"
    "values.yaml"
)

required_dirs=(
    "templates"
)

for file in "${required_files[@]}"; do
    if [ -f "$CHART_DIR/$file" ]; then
        print_info "  ✓ Found: $file"
    else
        print_error "  ✗ Missing: $file"
    fi
done

for dir in "${required_dirs[@]}"; do
    if [ -d "$CHART_DIR/$dir" ]; then
        print_info "  ✓ Found: $dir/"
    else
        print_error "  ✗ Missing: $dir/"
    fi
done
echo ""

# 2. Chart.yaml Validation
echo "2. Validating Chart.yaml..."

if command -v yq >/dev/null 2>&1; then
    CHART_NAME=$(yq eval '.name' "$CHART_DIR/Chart.yaml" 2>/dev/null)
    CHART_VERSION=$(yq eval '.version' "$CHART_DIR/Chart.yaml" 2>/dev/null)
    API_VERSION=$(yq eval '.apiVersion' "$CHART_DIR/Chart.yaml" 2>/dev/null)

    if [ -n "$CHART_NAME" ]; then
        print_info "  ✓ Name: $CHART_NAME"
    else
        print_error "  ✗ Invalid or missing chart name"
    fi

    if [ -n "$CHART_VERSION" ]; then
        print_info "  ✓ Version: $CHART_VERSION"
    else
        print_error "  ✗ Invalid or missing chart version"
    fi

    if [ -n "$API_VERSION" ]; then
        print_info "  ✓ API Version: $API_VERSION"
    else
        print_error "  ✗ Invalid or missing API version"
    fi
else
    print_warn "  ⚠ yq not available - skipping detailed Chart.yaml validation"
fi
echo ""

# 3. Helm Lint
echo "3. Running helm lint..."

if command -v helm >/dev/null 2>&1; then
    if helm lint "$CHART_DIR" 2>&1 | tee /tmp/helm-lint.log; then
        print_info "  ✓ Helm lint passed"
    else
        print_error "  ✗ Helm lint failed - see output above"
    fi
else
    print_warn "  ⚠ helm not available - skipping lint"
fi
echo ""

# 4. Template Rendering Tests
echo "4. Testing template rendering..."

if command -v helm >/dev/null 2>&1; then
    values_files=(
        "values.yaml"
        "values-dev.yaml"
        "values-staging.yaml"
        "values-prod.yaml"
    )

    for values_file in "${values_files[@]}"; do
        if [ -f "$CHART_DIR/$values_file" ]; then
            echo -n "  Testing with $values_file... "
            if helm template test-release "$CHART_DIR" -f "$CHART_DIR/$values_file" >/dev/null 2>&1; then
                print_info "✓"
            else
                print_error "✗ Template rendering failed for $values_file"
            fi
        fi
    done

    # Test with default values
    echo -n "  Testing with default values... "
    if helm template test-release "$CHART_DIR" >/dev/null 2>&1; then
        print_info "✓"
    else
        print_error "✗ Default template rendering failed"
    fi
else
    print_warn "  ⚠ helm not available - skipping template tests"
fi
echo ""

# 5. Required Templates Check
echo "5. Checking required templates..."

required_templates=(
    "deployment.yaml"
    "service.yaml"
    "serviceaccount.yaml"
)

for template in "${required_templates[@]}"; do
    if [ -f "$CHART_DIR/templates/$template" ]; then
        print_info "  ✓ Found: $template"
    else
        print_warn "  ⚠ Optional: $template"
    fi
done

# Check for optional templates
optional_templates=(
    "statefulset.yaml"
    "ingress.yaml"
    "networkpolicy.yaml"
    "poddisruptionbudget.yaml"
    "servicemonitor.yaml"
    "hpa.yaml"
)

for template in "${optional_templates[@]}"; do
    if [ -f "$CHART_DIR/templates/$template" ]; then
        print_info "  ✓ Found optional: $template"
    fi
done
echo ""

# 6. Values Files Validation
echo "6. Validating values files..."

if command -v yq >/dev/null 2>&1; then
    for values_file in "$CHART_DIR"/values*.yaml; do
        if [ -f "$values_file" ]; then
            basename=$(basename "$values_file")
            echo -n "  Checking $basename... "
            if yq eval '.' "$values_file" >/dev/null 2>&1; then
                print_info "✓"
            else
                print_error "✗ Invalid YAML in $basename"
            fi
        fi
    done
else
    print_warn "  ⚠ yq not available - skipping values validation"
fi
echo ""

# Summary
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ $FAILED -eq 0 ]; then
    print_info "✓ Helm chart validation passed"
    exit 0
else
    print_error "✗ Helm chart validation failed ($FAILED error(s))"
    exit 1
fi
