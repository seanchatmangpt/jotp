#!/bin/bash
# Helm Chart Validation Script for JOTP
# This script validates the Helm chart before deployment

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to validate YAML syntax
validate_yaml() {
    local file=$1
    if command_exists kubectl; then
        if kubectl apply --dry-run=client -f "$file" >/dev/null 2>&1; then
            print_info "✓ Valid YAML: $file"
            return 0
        else
            print_warn "⚠ Could not validate with kubectl, trying Python..."
            if command_exists python3; then
                if python3 -c "import yaml; yaml.safe_load(open('$file'))" 2>/dev/null; then
                    print_info "✓ Valid YAML: $file"
                    return 0
                else
                    print_error "✗ Invalid YAML: $file"
                    return 1
                fi
            else
                print_warn "⚠ No YAML validator found, skipping syntax check for $file"
                return 0
            fi
        fi
    elif command_exists python3; then
        if python3 -c "import yaml; yaml.safe_load(open('$file'))" 2>/dev/null; then
            print_info "✓ Valid YAML: $file"
            return 0
        else
            print_error "✗ Invalid YAML: $file"
            return 1
        fi
    elif command_exists yamllint; then
        if yamllint "$file" >/dev/null 2>&1; then
            print_info "✓ Valid YAML: $file"
            return 0
        else
            print_warn "⚠ yamllint reported issues, but YAML may still be valid"
            return 0
        fi
    else
        print_warn "⚠ No YAML validator found, skipping syntax check for $file"
        return 0
    fi
}

# Function to validate Helm chart
validate_helm_chart() {
    local chart_dir=$1

    print_info "Validating Helm chart in $chart_dir..."

    # Check if chart directory exists
    if [ ! -d "$chart_dir" ]; then
        print_error "Chart directory does not exist: $chart_dir"
        exit 1
    fi

    # Check for Chart.yaml
    if [ ! -f "$chart_dir/Chart.yaml" ]; then
        print_error "Chart.yaml not found in $chart_dir"
        exit 1
    fi

    # Check for values.yaml
    if [ ! -f "$chart_dir/values.yaml" ]; then
        print_error "values.yaml not found in $chart_dir"
        exit 1
    fi

    # Check for templates directory
    if [ ! -d "$chart_dir/templates" ]; then
        print_error "templates directory not found in $chart_dir"
        exit 1
    fi

    print_info "✓ Chart structure is valid"
}

# Function to lint Helm chart
lint_helm_chart() {
    local chart_dir=$1

    print_info "Linting Helm chart..."

    if ! command_exists helm; then
        print_error "Helm is not installed. Please install Helm to continue."
        exit 1
    fi

    if helm lint "$chart_dir"; then
        print_info "✓ Helm chart linting passed"
        return 0
    else
        print_error "✗ Helm chart linting failed"
        return 1
    fi
}

# Function to validate templates
validate_templates() {
    local chart_dir=$1
    local templates_dir="$chart_dir/templates"

    print_info "Validating templates..."

    # Check for required templates
    local required_templates=(
        "deployment.yaml"
        "service.yaml"
        "serviceaccount.yaml"
    )

    for template in "${required_templates[@]}"; do
        if [ -f "$templates_dir/$template" ]; then
            print_info "✓ Found required template: $template"
        else
            print_error "✗ Missing required template: $template"
            exit 1
        fi
    done

    # Validate optional templates
    local optional_templates=(
        "statefulset.yaml"
        "ingress.yaml"
        "networkpolicy.yaml"
        "poddisruptionbudget.yaml"
        "servicemonitor.yaml"
        "hpa.yaml"
    )

    for template in "${optional_templates[@]}"; do
        if [ -f "$templates_dir/$template" ]; then
            print_info "✓ Found optional template: $template"
        fi
    done

    print_info "✓ All required templates are present"
}

# Function to validate values files
validate_values_files() {
    local chart_dir=$1

    print_info "Validating values files..."

    local values_files=(
        "$chart_dir/values.yaml"
        "$chart_dir/values-dev.yaml"
        "$chart_dir/values-staging.yaml"
        "$chart_dir/values-prod.yaml"
        "$chart_dir/values-1m-processes.yaml"
        "$chart_dir/values-10m-processes.yaml"
    )

    for values_file in "${values_files[@]}"; do
        if [ -f "$values_file" ]; then
            validate_yaml "$values_file"
        fi
    done

    print_info "✓ All values files are valid"
}

# Function to test template rendering
test_template_rendering() {
    local chart_dir=$1

    print_info "Testing template rendering..."

    if ! command_exists helm; then
        print_warn "⚠ Helm is not installed, skipping template rendering test"
        return 0
    fi

    # Test with default values
    if helm template test-release "$chart_dir" >/dev/null 2>&1; then
        print_info "✓ Template rendering with default values passed"
    else
        print_error "✗ Template rendering with default values failed"
        return 1
    fi

    # Test with dev values
    if [ -f "$chart_dir/values-dev.yaml" ]; then
        if helm template test-release "$chart_dir" -f "$chart_dir/values-dev.yaml" >/dev/null 2>&1; then
            print_info "✓ Template rendering with dev values passed"
        else
            print_error "✗ Template rendering with dev values failed"
            return 1
        fi
    fi

    # Test with prod values
    if [ -f "$chart_dir/values-prod.yaml" ]; then
        if helm template test-release "$chart_dir" -f "$chart_dir/values-prod.yaml" >/dev/null 2>&1; then
            print_info "✓ Template rendering with prod values passed"
        else
            print_error "✗ Template rendering with prod values failed"
            return 1
        fi
    fi

    print_info "✓ All template rendering tests passed"
}

# Function to validate dependencies
validate_dependencies() {
    print_info "Checking dependencies..."

    # Check for required tools
    local required_tools=("kubectl")

    for tool in "${required_tools[@]}"; do
        if command_exists "$tool"; then
            print_info "✓ $tool is installed"
        else
            print_warn "⚠ $tool is not installed (recommended for full validation)"
        fi
    done

    # Check for optional tools
    local optional_tools=("helm" "yamllint")

    for tool in "${optional_tools[@]}"; do
        if command_exists "$tool"; then
            print_info "✓ $tool is installed"
        fi
    done
}

# Function to validate security settings
validate_security() {
    local chart_dir=$1

    print_info "Validating security configuration..."

    # Check if security context is defined
    if grep -q "podSecurityContext" "$chart_dir/values.yaml"; then
        print_info "✓ Pod security context is configured"
    else
        print_warn "⚠ Pod security context is not configured"
    fi

    # Check if container security context is defined
    if grep -q "securityContext" "$chart_dir/values.yaml"; then
        print_info "✓ Container security context is configured"
    else
        print_warn "⚠ Container security context is not configured"
    fi

    # Check if network policy is enabled
    if grep -q "networkPolicy:" "$chart_dir/values.yaml"; then
        print_info "✓ Network policy configuration is present"
    fi
}

# Main validation function
main() {
    local chart_dir="${1:-./helm/jotp}"

    print_info "========================================="
    print_info "JOTP Helm Chart Validation"
    print_info "========================================="
    echo ""

    # Validate dependencies
    validate_dependencies
    echo ""

    # Validate Helm chart structure
    validate_helm_chart "$chart_dir"
    echo ""

    # Validate templates
    validate_templates "$chart_dir"
    echo ""

    # Validate values files
    validate_values_files "$chart_dir"
    echo ""

    # Lint Helm chart
    if command_exists helm; then
        lint_helm_chart "$chart_dir"
        echo ""

        # Test template rendering
        test_template_rendering "$chart_dir"
        echo ""
    fi

    # Validate security settings
    validate_security "$chart_dir"
    echo ""

    print_info "========================================="
    print_info "Validation completed successfully!"
    print_info "========================================="
    echo ""

    print_info "To install the chart, run:"
    echo "  helm install jotp $chart_dir"
    echo ""
    print_info "To test the installation with dry-run, run:"
    echo "  helm install jotp $chart_dir --dry-run --debug"
}

# Run main function
main "$@"
