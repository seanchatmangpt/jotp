#!/usr/bin/env bash
# Master Validation Orchestrator for JOTP
# Runs all validations in sequence and generates summary report

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_FILE="$PROJECT_ROOT/VALIDATION-REPORT.md"
LOG_FILE="$PROJECT_ROOT/validation-output.log"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

# Result tracking
TOTAL=0
PASSED=0
FAILED=0
CRITICAL_FAILED=0
WARNINGS=0

# Results arrays (as space-separated strings)
RESULT_NAMES=""
RESULT_STATUSES=""

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_section() {
    echo ""
    echo -e "${GREEN}▶ $1${NC}"
}

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

add_result() {
    local name=$1
    local status=$2

    RESULT_NAMES="$RESULT_NAMES|$name"
    RESULT_STATUSES="$RESULT_STATUSES|$status"
    TOTAL=$((TOTAL + 1))

    case "$status" in
        "PASS")
            PASSED=$((PASSED + 1))
            ;;
        "FAIL")
            FAILED=$((FAILED + 1))
            ;;
        "FAIL_CRITICAL")
            FAILED=$((FAILED + 1))
            CRITICAL_FAILED=$((CRITICAL_FAILED + 1))
            ;;
        "WARN")
            WARNINGS=$((WARNINGS + 1))
            ;;
    esac
}

run_validation() {
    local name=$1
    local script=$2
    local critical=${3:-false}

    print_section "Running: $name"

    if [ ! -f "$script" ]; then
        print_error "Script not found: $script"
        add_result "$name" "SKIP"
        return 1
    fi

    if [ ! -x "$script" ]; then
        chmod +x "$script"
    fi

    local output
    local exit_code

    if output=$("$script" 2>&1); then
        exit_code=0
    else
        exit_code=$?
    fi

    if [ $exit_code -eq 0 ]; then
        print_info "✓ $name passed"
        add_result "$name" "PASS"
    else
        if [ "$critical" = "true" ]; then
            print_error "✗ $name FAILED (CRITICAL)"
            add_result "$name" "FAIL_CRITICAL"
        else
            print_warn "⚠ $name failed"
            add_result "$name" "FAIL"
        fi
    fi

    return $exit_code
}

check_tool() {
    if command -v "$1" >/dev/null 2>&1; then
        print_info "✓ $1 is installed"
        return 0
    else
        print_warn "⚠ $1 is not installed"
        return 1
    fi
}

generate_report() {
    cat > "$REPORT_FILE" << EOF
# JOTP Validation Report

**Date**: $TIMESTAMP
**Scope**: Distributed/IaC/Primitives Deliverables

## Summary
- Total Validations: $TOTAL
- Passed: $PASSED
- Failed: $FAILED
- Warnings: $WARNINGS

## Critical Results
| Check | Status | Details |
|-------|--------|---------|
EOF

    # Parse results
    IFS='|' read -ra NAMES <<< "$RESULT_NAMES"
    IFS='|' read -ra STATUSES <<< "$RESULT_STATUSES"

    for i in "${!NAMES[@]}"; do
        if [ -n "${NAMES[$i]}" ]; then
            local name="${NAMES[$i]}"
            local status="${STATUSES[$i]}"
            local status_icon

            case "$status" in
                "PASS") status_icon="✅" ;;
                "FAIL") status_icon="❌" ;;
                "FAIL_CRITICAL") status_icon="🚫" ;;
                "WARN") status_icon="⚠️" ;;
                "SKIP") status_icon="➖" ;;
                *) status_icon="❓" ;;
            esac
            echo "| $name | $status_icon | |" >> "$REPORT_FILE"
        fi
    done

    cat >> "$REPORT_FILE" << EOF

## Findings

### Critical Issues
EOF

    if [ $CRITICAL_FAILED -eq 0 ]; then
        echo "None" >> "$REPORT_FILE"
    else
        IFS='|' read -ra NAMES <<< "$RESULT_NAMES"
        IFS='|' read -ra STATUSES <<< "$RESULT_STATUSES"
        for i in "${!NAMES[@]}"; do
            if [ "${STATUSES[$i]}" = "FAIL_CRITICAL" ]; then
                echo "- **${NAMES[$i]}**: Must be fixed before deployment" >> "$REPORT_FILE"
            fi
        done
    fi

    cat >> "$REPORT_FILE" << EOF

### Important Issues
EOF

    if [ $FAILED -eq $CRITICAL_FAILED ]; then
        echo "None" >> "$REPORT_FILE"
    else
        IFS='|' read -ra NAMES <<< "$RESULT_NAMES"
        IFS='|' read -ra STATUSES <<< "$RESULT_STATUSES"
        for i in "${!NAMES[@]}"; do
            if [ "${STATUSES[$i]}" = "FAIL" ]; then
                echo "- **${NAMES[$i]}**: Should be fixed for quality" >> "$REPORT_FILE"
            fi
        done
    fi

    cat >> "$REPORT_FILE" << EOF

### Warnings
EOF

    if [ $WARNINGS -eq 0 ]; then
        echo "None" >> "$REPORT_FILE"
    else
        IFS='|' read -ra NAMES <<< "$RESULT_NAMES"
        IFS='|' read -ra STATUSES <<< "$RESULT_STATUSES"
        for i in "${!NAMES[@]}"; do
            if [ "${STATUSES[$i]}" = "WARN" ]; then
                echo "- **${NAMES[$i]}**: Optional improvements" >> "$REPORT_FILE"
            fi
        done
    fi

    cat >> "$REPORT_FILE" << EOF

## Recommendations

### Immediate Actions Required
1. Address all critical validation failures above
2. Re-run validation suite after fixes
3. Update baseline documentation

### Quality Improvements
1. Fix important validation failures
2. Address code formatting issues
3. Resolve broken documentation links

### Next Steps
1. Review full validation output log
2. Create tickets for any remaining issues
3. Integrate validation into CI/CD pipeline

---

**Report generated by**: \`scripts/validation/validate-all.sh\`
**Project**: JOTP - Java 26 OTP Framework
EOF
}

main() {
    cd "$PROJECT_ROOT"

    print_header "JOTP Distributed/IaC/Primitives Validation"
    echo ""
    print_info "Started at: $TIMESTAMP"
    print_info "Project root: $PROJECT_ROOT"
    echo ""

    # Phase 1: Tool Check
    print_section "Phase 1: Checking Validation Tools"
    check_tool kubectl || true
    check_tool helm || true
    check_tool terraform || true
    check_tool yq || true
    check_tool act || true
    check_tool mvnd || true
    check_tool docker || true
    check_tool python3 || true
    echo ""

    # Phase 2: Critical Validations
    print_section "Phase 2: Critical Validations (Must Pass)"

    # Java Compilation
    echo -n "Java Compilation... "
    if make compile > /dev/null 2>&1; then
        print_info "✓ passed"
        add_result "Java Compilation" "PASS"
    else
        print_error "✗ FAILED (CRITICAL)"
        add_result "Java Compilation" "FAIL_CRITICAL"
    fi

    # Guard Check
    echo -n "Guard Check... "
    if make guard-check > /dev/null 2>&1; then
        print_info "✓ passed"
        add_result "Guard Check" "PASS"
    else
        print_error "✗ FAILED (CRITICAL)"
        add_result "Guard Check" "FAIL_CRITICAL"
    fi

    # Run other critical validations
    run_validation "Kubernetes YAML" "$SCRIPT_DIR/validate-k8s.sh" true || true
    run_validation "Helm Chart" "$SCRIPT_DIR/validate-helm-chart.sh" true || true
    run_validation "Terraform" "$SCRIPT_DIR/validate-terraform.sh" true || true
    run_validation "Documentation Links" "$SCRIPT_DIR/validate-links.sh" true || true
    run_validation "File References" "$SCRIPT_DIR/validate-file-refs.sh" true || true
    echo ""

    # Phase 3: Important Validations
    print_section "Phase 3: Important Validations (Should Pass)"

    # Code Format
    echo -n "Code Format... "
    if mvnd spotless:check > /dev/null 2>&1; then
        print_info "✓ passed"
        add_result "Code Format" "PASS"
    else
        print_warn "⚠ issues found"
        add_result "Code Format" "FAIL"
    fi

    run_validation "Docker Compose" "$SCRIPT_DIR/validate-docker-compose.sh" false || true
    run_validation "GitHub Actions" "$SCRIPT_DIR/validate-workflows.sh" false || true
    run_validation "Shell Scripts" "$SCRIPT_DIR/validate-scripts.sh" false || true
    echo ""

    # Phase 4: Optional Validations
    print_section "Phase 4: Optional Validations (Nice to Have)"

    run_validation "External Links" "$SCRIPT_DIR/validate-external-links.sh" false || true
    run_validation "Terminology Consistency" "$SCRIPT_DIR/validate-terminology.sh" false || true
    echo ""

    # Generate Summary
    print_header "Validation Summary"

    echo ""
    echo "Total Validations: $TOTAL"
    echo -e "${GREEN}Passed: $PASSED${NC}"
    echo -e "${RED}Failed: $FAILED${NC}"
    echo -e "${YELLOW}Warnings: $WARNINGS${NC}"
    echo ""

    # Show failed validations
    if [ $FAILED -gt 0 ]; then
        print_warn "Failed Validations:"
        IFS='|' read -ra NAMES <<< "$RESULT_NAMES"
        IFS='|' read -ra STATUSES <<< "$RESULT_STATUSES"
        for i in "${!NAMES[@]}"; do
            if [ "${STATUSES[$i]}" = "FAIL_CRITICAL" ]; then
                echo -e "  ${RED}✗${NC} ${NAMES[$i]} (CRITICAL)"
            elif [ "${STATUSES[$i]}" = "FAIL" ]; then
                echo -e "  ${YELLOW}⚠${NC} ${NAMES[$i]}"
            fi
        done
        echo ""
    fi

    # Generate report file
    print_section "Generating Validation Report"
    generate_report
    print_info "Report saved to: $REPORT_FILE"
    echo ""

    # Final verdict
    print_header "Final Verdict"

    if [ $CRITICAL_FAILED -gt 0 ]; then
        print_error "VALIDATION FAILED - $CRITICAL_FAILED critical validation(s) failed"
        echo ""
        print_error "Please fix critical issues before proceeding with deployment."
        return 1
    elif [ $FAILED -gt 5 ]; then
        print_warn "VALIDATION PASSED WITH WARNINGS - $FAILED validation(s) failed"
        echo ""
        print_warn "Consider fixing important issues before deployment."
        return 0
    else
        print_info "✓ VALIDATION PASSED"
        echo ""
        print_info "All critical validations passed. Ready for production deployment."
        return 0
    fi
}

main "$@"
