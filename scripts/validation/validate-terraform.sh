#!/bin/bash
# Terraform Configuration Validation for JOTP
# Validates Terraform configs across all cloud providers

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TERRAFORM_DIR="$PROJECT_ROOT/docs/infrastructure/terraform"

FAILED=0
PROVIDERS_VALIDATED=0

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

echo "▶ Terraform Configuration Validation"
echo ""

# Check if terraform directory exists
if [ ! -d "$TERRAFORM_DIR" ]; then
    print_error "Terraform directory not found: $TERRAFORM_DIR"
    exit 1
fi

print_info "Terraform directory: $TERRAFORM_DIR"
echo ""

# Check if terraform is available
if ! command -v terraform >/dev/null 2>&1; then
    print_error "terraform is not installed"
    exit 1
fi

print_info "terraform version: $(terraform version -json | jq -r '.terraform_version' 2>/dev/null || echo 'unknown')"
echo ""

# Find all provider directories
PROVIDER_DIRS=()
if [ -d "$TERRAFORM_DIR" ]; then
    while IFS= read -r -d '' dir; do
        # Check if directory contains .tf files
        if find "$dir" -maxdepth 1 -name "*.tf" -print -quit | grep -q .; then
            PROVIDER_DIRS+=("$dir")
        fi
    done < <(find "$TERRAFORM_DIR" -mindepth 1 -maxdepth 1 -type d -print0)
fi

if [ ${#PROVIDER_DIRS[@]} -eq 0 ]; then
    print_warn "No Terraform provider directories found"
    exit 0
fi

print_info "Found ${#PROVIDER_DIRS[@]} provider(s) to validate"
echo ""

# Validate each provider
for provider_dir in "${PROVIDER_DIRS[@]}"; do
    provider_name=$(basename "$provider_dir")
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Validating: $provider_name"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    cd "$provider_dir"

    # 1. Check for required files
    echo "1. Checking required files..."
    if [ -f "main.tf" ]; then
        print_info "  ✓ Found: main.tf"
    else
        print_error "  ✗ Missing: main.tf"
        cd "$PROJECT_ROOT"
        continue
    fi

    if [ -f "variables.tf" ]; then
        print_info "  ✓ Found: variables.tf"
    else
        print_warn "  ⚠ Optional: variables.tf"
    fi

    if [ -f "outputs.tf" ]; then
        print_info "  ✓ Found: outputs.tf"
    else
        print_warn "  ⚠ Optional: outputs.tf"
    fi
    echo ""

    # 2. Initialize terraform (without backend)
    echo "2. Initializing Terraform..."
    if terraform init -backend=false -no-color > /tmp/tf-init-$provider_name.log 2>&1; then
        print_info "  ✓ Terraform init successful"
    else
        print_error "  ✗ Terraform init failed"
        cat /tmp/tf-init-$provider_name.log | tail -20
        cd "$PROJECT_ROOT"
        continue
    fi
    echo ""

    # 3. Validate configuration
    echo "3. Validating configuration..."
    if terraform validate -no-color > /tmp/tf-validate-$provider_name.log 2>&1; then
        print_info "  ✓ Configuration is valid"
        PROVIDERS_VALIDATED=$((PROVIDERS_VALIDATED + 1))
    else
        print_error "  ✗ Configuration validation failed"
        cat /tmp/tf-validate-$provider_name.log
    fi
    echo ""

    # 4. Check formatting
    echo "4. Checking formatting..."
    if terraform fmt -check -no-color > /tmp/tf-fmt-$provider_name.log 2>&1; then
        print_info "  ✓ Formatting is correct"
    else
        print_warn "  ⚠ Some files need formatting"
        echo "    Run: terraform fmt"
    fi
    echo ""

    cd "$PROJECT_ROOT"
done

# Summary
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Summary"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
print_info "Providers validated: $PROVIDERS_VALIDATED / ${#PROVIDER_DIRS[@]}"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All Terraform configurations are valid"
    exit 0
else
    print_error "✗ Terraform validation failed ($FAILED error(s))"
    exit 1
fi
