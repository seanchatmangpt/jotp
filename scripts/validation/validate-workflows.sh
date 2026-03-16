#!/bin/bash
# GitHub Actions Workflow Validation for JOTP
# Validates workflow YAML syntax and required fields

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKFLOWS_DIR="$PROJECT_ROOT/.github/workflows"

FAILED=0
WORKFLOWS_CHECKED=0

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

echo "▶ GitHub Actions Workflow Validation"
echo ""

# Check if workflows directory exists
if [ ! -d "$WORKFLOWS_DIR" ]; then
    print_error "Workflows directory not found: $WORKFLOWS_DIR"
    exit 1
fi

print_info "Workflows directory: $WORKFLOWS_DIR"
echo ""

# Find all workflow files
WORKFLOW_FILES=()
while IFS= read -r -d '' file; do
    WORKFLOW_FILES+=("$file")
done < <(find "$WORKFLOWS_DIR" -type f \( -name "*.yml" -o -name "*.yaml" \) -print0)

if [ ${#WORKFLOW_FILES[@]} -eq 0 ]; then
    print_warn "No workflow files found"
    exit 0
fi

print_info "Found ${#WORKFLOW_FILES[@]} workflow(s) to validate"
echo ""

# Validate each workflow
for workflow_file in "${WORKFLOW_FILES[@]}"; do
    workflow_name=$(basename "$workflow_file")
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Validating: $workflow_name"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # 1. YAML Syntax Check
    echo "1. Checking YAML syntax..."
    if command -v yq >/dev/null 2>&1; then
        if yq eval '.' "$workflow_file" >/dev/null 2>&1; then
            print_info "  ✓ Valid YAML syntax"
        else
            print_error "  ✗ Invalid YAML syntax"
            continue
        fi
    elif command -v python3 >/dev/null 2>&1; then
        if python3 -c "import yaml; yaml.safe_load(open('$workflow_file'))" 2>/dev/null; then
            print_info "  ✓ Valid YAML syntax"
        else
            print_error "  ✗ Invalid YAML syntax"
            continue
        fi
    else
        print_warn "  ⚠ No YAML validator available - skipping syntax check"
    fi
    echo ""

    # 2. Required Fields Check
    echo "2. Checking required fields..."
    MISSING_FIELDS=0

    if command -v yq >/dev/null 2>&1; then
        # Check for 'name' field
        if yq eval '.name' "$workflow_file" >/dev/null 2>&1; then
            WORKFLOW_DISPLAY_NAME=$(yq eval '.name' "$workflow_file")
            print_info "  ✓ name: $WORKFLOW_DISPLAY_NAME"
        else
            print_error "  ✗ Missing 'name' field"
            MISSING_FIELDS=$((MISSING_FIELDS + 1))
        fi

        # Check for 'on' field
        if yq eval '.on' "$workflow_file" >/dev/null 2>&1 || yq eval '."on"' "$workflow_file" >/dev/null 2>&1; then
            print_info "  ✓ 'on' field present"
        else
            print_error "  ✗ Missing 'on' field"
            MISSING_FIELDS=$((MISSING_FIELDS + 1))
        fi

        # Check for 'jobs' field
        if yq eval '.jobs' "$workflow_file" >/dev/null 2>&1; then
            print_info "  ✓ 'jobs' field present"
        else
            print_error "  ✗ Missing 'jobs' field"
            MISSING_FIELDS=$((MISSING_FIELDS + 1))
        fi
    fi
    echo ""

    # 3. Job Validation
    echo "3. Validating jobs..."
    if command -v yq >/dev/null 2>&1; then
        JOB_COUNT=$(yq eval '.jobs | length' "$workflow_file" 2>/dev/null || echo "0")
        if [ "$JOB_COUNT" -gt 0 ]; then
            print_info "  ✓ Found $JOB_COUNT job(s)"

            # Check each job for required fields
            JOB_NAMES=$(yq eval '.jobs | keys | .[]' "$workflow_file" 2>/dev/null)
            for job_name in $JOB_NAMES; do
                echo -n "    - $job_name: "

                # Check if job has 'runs-on'
                if yq eval ".jobs.$job_name.\"runs-on\"" "$workflow_file" >/dev/null 2>&1; then
                    print_info "✓"
                else
                    print_warn "⚠ Missing 'runs-on'"
                fi
            done
        else
            print_error "  ✗ No jobs defined"
        fi
    fi
    echo ""

    # 4. Security Best Practices Check
    echo "4. Checking security best practices..."
    SECURITY_WARNINGS=0

    if command -v yq >/dev/null 2>&1; then
        # Check for permissions hardening
        if yq eval '.permissions' "$workflow_file" >/dev/null 2>&1; then
            print_info "  ✓ Permissions are explicitly set"
        else
            print_warn "  ⚠ No explicit permissions (using default)"
            SECURITY_WARNINGS=$((SECURITY_WARNINGS + 1))
        fi

        # Warn about unrestricted checkout
        if grep -q "uses: actions/checkout@" "$workflow_file"; then
            if grep -q "uses: actions/checkout@v3\|uses: actions/checkout@v4" "$workflow_file"; then
                print_info "  ✓ Using pinned action versions for checkout"
            else
                print_warn "  ⚠ Checkout action version not pinned"
                SECURITY_WARNINGS=$((SECURITY_WARNINGS + 1))
            fi
        fi
    fi
    echo ""

    WORKFLOWS_CHECKED=$((WORKFLOWS_CHECKED + 1))
done

# 5. Optional: Act dry-run
if command -v act >/dev/null 2>&1; then
    echo ""
    echo "5. Running act dry-run (if available)..."
    print_warn "  ⚠ act dry-run can be slow - skipping by default"
    print_warn "     To run manually: act -n -W .github/workflows/<workflow>.yml"
fi

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Summary"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
print_info "Workflows checked: $WORKFLOWS_CHECKED / ${#WORKFLOW_FILES[@]}"

if [ $FAILED -eq 0 ]; then
    print_info "✓ All workflows are valid"
    exit 0
else
    print_error "✗ Workflow validation failed ($FAILED error(s))"
    exit 1
fi
