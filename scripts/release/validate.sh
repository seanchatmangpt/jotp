#!/usr/bin/env bash
# JOTP Release Validation Script
# Validates release artifacts before or after deployment
#
# Usage:
#   ./scripts/release/validate.sh [version] [options]
#
# Options:
#   --local          Validate local build artifacts only
#   --remote         Validate artifacts on Maven Central
#   --full           Run complete validation (local + remote)
#   --verbose        Show detailed validation output
#
# Examples:
#   ./scripts/release/validate.sh 1.0.0
#   ./scripts/release/validate.sh 1.0.0 --local
#   ./scripts/release/validate.sh 1.0.0 --remote

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# Configuration
VERSION=""
VALIDATE_LOCAL=false
VALIDATE_REMOTE=false
VERBOSE=false
MAVEN_CMD=""

# Validation results
declare -a CHECKS_PASSED
declare -a CHECKS_FAILED
declare -a CHECKS_WARNING

# Logging
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
    CHECKS_PASSED+=("$1");
}
log_warning() {
    echo -e "${YELLOW}[⚠]${NC} $1"
    CHECKS_WARNING+=("$1");
}
log_error() {
    echo -e "${RED}[✗]${NC} $1"
    CHECKS_FAILED+=("$1");
}
log_verbose() {
    if [[ $VERBOSE == true ]]; then
        echo -e "${BLUE}[DEBUG]${NC} $1"
    fi
}

# Parse arguments
parse_args() {
    if [[ $# -eq 0 ]]; then
        log_error "Version argument is required"
        echo "Usage: $0 [version] [options]"
        exit 1
    fi

    VERSION="$1"
    shift

    # Default to local validation if no mode specified
    VALIDATE_LOCAL=true

    while [[ $# -gt 0 ]]; do
        case $1 in
            --local)
                VALIDATE_LOCAL=true
                shift
                ;;
            --remote)
                VALIDATE_REMOTE=true
                shift
                ;;
            --full)
                VALIDATE_LOCAL=true
                VALIDATE_REMOTE=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            *)
                log_error "Unknown option: $1"
                exit 1
                ;;
        esac
    done
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking validation prerequisites..."

    # Check for Maven command
    if command -v mvnd &> /dev/null; then
        MAVEN_CMD="mvnd"
    elif [[ -f "$PROJECT_ROOT/mvnw" ]]; then
        MAVEN_CMD="./mvnw"
    else
        log_error "Neither mvnd nor mvnw found"
        exit 1
    fi

    log_verbose "Maven command: $MAVEN_CMD"

    # Check for required tools
    local required_tools=("git" "curl")
    for tool in "${required_tools[@]}"; do
        if ! command -v "$tool" &> /dev/null; then
            log_warning "Tool not found: $tool (some validations may fail)"
        fi
    done

    # Check for Java
    if ! command -v java &> /dev/null; then
        log_warning "Java not found (Javadoc validation will be skipped)"
    fi

    # Check for GPG
    if ! command -v gpg &> /dev/null; then
        log_warning "GPG not found (signature validation will be skipped)"
    fi

    log_success "Prerequisites check complete"
}

# Validate local artifacts
validate_local_artifacts() {
    if [[ $VALIDATE_LOCAL == false ]]; then
        return
    fi

    echo ""
    log_info "======================================="
    log_info "Validating Local Artifacts"
    log_info "======================================="
    echo ""

    # Check target directory exists
    if [[ ! -d "$PROJECT_ROOT/target" ]]; then
        log_error "Target directory not found: target/"
        log_info "Run ./scripts/release/perform.sh $VERSION first"
        return 1
    fi

    # Validate required files exist
    local required_files=(
        "jotp-${VERSION}.jar"
        "jotp-${VERSION}.pom"
        "jotp-${VERSION}-sources.jar"
        "jotp-${VERSION}-javadoc.jar"
    )

    log_info "Checking required files..."
    for file in "${required_files[@]}"; do
        local filepath="$PROJECT_ROOT/target/$file"
        if [[ -f "$filepath" ]]; then
            local size=$(ls -lh "$filepath" | awk '{print $5}')
            log_success "$file ($size)"
            log_verbose "File: $filepath"
        else
            log_error "Missing: $file"
        fi
    done

    # Validate GPG signatures
    log_info "Checking GPG signatures..."
    if command -v gpg &> /dev/null; then
        for file in "${required_files[@]}"; do
            local asc_file="$PROJECT_ROOT/target/${file}.asc"
            if [[ -f "$asc_file" ]]; then
                # Verify signature
                if gpg --verify "$asc_file" "$PROJECT_ROOT/target/$file" >/dev/null 2>&1; then
                    log_success "${file}.asc (valid)"
                else
                    log_error "${file}.asc (invalid signature)"
                fi
            else
                log_warning "${file}.asc (not found)"
            fi
        done
    else
        log_warning "GPG not available, skipping signature validation"
    fi

    # Validate Javadoc
    log_info "Checking Javadoc completeness..."
    local javadoc_jar="$PROJECT_ROOT/target/jotp-${VERSION}-javadoc.jar"
    if [[ -f "$javadoc_jar" ]]; then
        # Check jar contains files
        if jar tf "$javadoc_jar" | grep -q ".html"; then
            local html_count=$(jar tf "$javadoc_jar" | grep -c ".html")
            log_success "Javadoc contains $html_count HTML files"
        else
            log_warning "Javadoc jar appears empty or malformed"
        fi
    else
        log_error "Javadoc jar not found"
    fi

    # Validate POM structure
    log_info "Validating POM structure..."
    local pom_file="$PROJECT_ROOT/target/jotp-${VERSION}.pom"
    if [[ -f "$pom_file" ]]; then
        # Check for required POM elements
        local required_elements=(
            "groupId"
            "artifactId"
            "version"
            "name"
            "description"
            "url"
            "licenses"
            "developers"
            "scm"
        )

        for element in "${required_elements[@]}"; do
            if grep -q "<$element" "$pom_file"; then
                log_success "POM contains <$element>"
            else
                log_warning "POM missing <$element>"
            fi
        done
    else
        log_error "POM file not found"
    fi

    # Check checksum files
    log_info "Checking checksum files..."
    for file in "${required_files[@]}"; do
        for algo in "md5" "sha1" "sha256"; do
            local checksum_file="$PROJECT_ROOT/target/${file}.${algo}"
            if [[ -f "$checksum_file" ]]; then
                log_verbose "Found ${file}.$algo"
            fi
        done
    done

    # Verify artifact integrity
    log_info "Verifying artifact integrity..."
    local main_jar="$PROJECT_ROOT/target/jotp-${VERSION}.jar"
    if [[ -f "$main_jar" ]]; then
        # Check jar is valid
        if jar tf "$main_jar" >/dev/null 2>&1; then
            log_success "Main JAR is valid"

            # Check for module descriptor
            if jar tf "$main_jar" | grep -q "module-info.class"; then
                log_success "Contains module-info.class"
            else
                log_warning "Missing module-info.class"
            fi

            # Check for main classes
            local class_count=$(jar tf "$main_jar" | grep -c "\.class$" || echo 0)
            log_verbose "Main JAR contains $class_count class files"

            if [[ $class_count -eq 0 ]]; then
                log_warning "Main JAR appears to have no compiled classes"
            fi
        else
            log_error "Main JAR is corrupted or invalid"
        fi
    fi
}

# Validate remote artifacts on Maven Central
validate_remote_artifacts() {
    if [[ $VALIDATE_REMOTE == false ]]; then
        return
    fi

    echo ""
    log_info "======================================="
    log_info "Validating Maven Central Artifacts"
    log_info "======================================="
    echo ""

    if ! command -v curl &> /dev/null; then
        log_error "curl not found, cannot validate remote artifacts"
        return 1
    fi

    # Maven Central URLs
    local base_url="https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp"
    local version_url="${base_url}/${VERSION}"
    local artifact_urls=(
        "${version_url}/jotp-${VERSION}.jar"
        "${version_url}/jotp-${VERSION}.pom"
        "${version_url}/jotp-${VERSION}-sources.jar"
        "${version_url}/jotp-${VERSION}-javadoc.jar"
    )

    log_info "Checking artifact availability on Maven Central..."
    log_info "URL: $version_url"
    echo ""

    # Check if version directory exists
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" "$version_url")
    if [[ "$http_code" == "200" ]]; then
        log_success "Version directory exists on Maven Central"
    else
        log_error "Version directory not found (HTTP $http_code)"
        log_info "The release may still be syncing (can take 10-30 minutes)"
        return 1
    fi

    # Check each artifact
    for url in "${artifact_urls[@]}"; do
        local artifact=$(basename "$url")
        local http_code=$(curl -s -o /dev/null -w "%{http_code}" "$url")

        if [[ "$http_code" == "200" ]]; then
            log_success "$artifact is available"

            # Download and verify size if verbose
            if [[ $VERBOSE == true ]]; then
                local temp_file="/tmp/${artifact}"
                curl -s "$url" -o "$temp_file"
                local size=$(ls -lh "$temp_file" | awk '{print $5}')
                log_verbose "Downloaded: $artifact ($size)"
                rm -f "$temp_file"
            fi
        else
            log_error "$artifact not found (HTTP $http_code)"
        fi
    done

    # Check signatures
    log_info "Checking GPG signatures on Maven Central..."
    for url in "${artifact_urls[@]}"; do
        local sig_url="${url}.asc"
        local artifact=$(basename "$url")
        local http_code=$(curl -s -o /dev/null -w "%{http_code}" "$sig_url")

        if [[ "$http_code" == "200" ]]; then
            log_success "${artifact}.asc is available"
        else
            log_warning "${artifact}.asc not found (HTTP $http_code)"
        fi
    done

    # Check metadata
    log_info "Checking Maven metadata..."
    local metadata_url="${base_url}/maven-metadata.xml"
    if curl -s "$metadata_url" | grep -q "<version>$VERSION</version>"; then
        log_success "Version $VERSION found in maven-metadata.xml"
    else
        log_warning "Version $VERSION not in maven-metadata.xml (may need time to sync)"
    fi

    # Test with Maven
    log_info "Testing Maven dependency resolution..."
    if [[ -f "$PROJECT_ROOT/pom.xml" ]]; then
        local test_cmd="mvn dependency:get -DremoteRepositories=central -Dartifact=io.github.seanchatmangpt:jotp:$VERSION"

        if [[ $VERBOSE == true ]]; then
            log_verbose "Running: $test_cmd"
        fi

        if $test_cmd >/dev/null 2>&1; then
            log_success "Maven can resolve the artifact"
        else
            log_warning "Maven dependency resolution failed (artifact may still be syncing)"
        fi
    fi

    # Provide Central Portal link
    log_info "Central Portal:"
    echo "  https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp/$VERSION"
    echo ""
}

# Generate validation report
generate_report() {
    local report_file="$PROJECT_ROOT/target/validation-report-${VERSION}-$(date +%Y%m%d-%H%M%S).txt"

    log_info "Generating validation report..."

    cat > "$report_file" << EOF
JOTP Release Validation Report
===============================
Version: $VERSION
Date: $(date)
Validation Type: $([ "$VALIDATE_LOCAL" == true ] && echo "Local " || echo "")$([ "$VALIDATE_REMOTE" == true ] && echo "Remote" || echo "")

Summary:
--------
Passed: ${#CHECKS_PASSED[@]}
Failed: ${#CHECKS_FAILED[@]}
Warnings: ${#CHECKS_WARNING[@]}

Checks Passed:
--------------
EOF

    for check in "${CHECKS_PASSED[@]}"; do
        echo "  ✓ $check" >> "$report_file"
    done

    if [[ ${#CHECKS_WARNING[@]} -gt 0 ]]; then
        echo "" >> "$report_file"
        echo "Warnings:" >> "$report_file"
        echo "---------" >> "$report_file"
        for warning in "${CHECKS_WARNING[@]}"; do
            echo "  ⚠ $warning" >> "$report_file"
        done
    fi

    if [[ ${#CHECKS_FAILED[@]} -gt 0 ]]; then
        echo "" >> "$report_file"
        echo "Failed Checks:" >> "$report_file"
        echo "-------------" >> "$report_file"
        for failure in "${CHECKS_FAILED[@]}"; do
            echo "  ✗ $failure" >> "$report_file"
        done
    fi

    cat >> "$report_file" << EOF

Recommendation:
---------------
EOF

    if [[ ${#CHECKS_FAILED[@]} -eq 0 ]] && [[ ${#CHECKS_WARNING[@]} -eq 0 ]]; then
        echo "All checks passed! Release is ready for production use." >> "$report_file"
    elif [[ ${#CHECKS_FAILED[@]} -eq 0 ]]; then
        echo "Release is valid with minor warnings. Review warnings before use." >> "$report_file"
    else
        echo "Release validation failed! Please address the failed checks above." >> "$report_file"
    fi

    log_success "Validation report: $report_file"
}

# Display summary
show_summary() {
    echo ""
    echo "======================================================================"
    echo "Validation Summary"
    echo "======================================================================"
    echo ""
    echo "Version: $VERSION"
    echo ""
    echo "Results:"
    echo "  Passed:  ${#CHECKS_PASSED[@]}"
    echo "  Failed:  ${#CHECKS_FAILED[@]}"
    echo "  Warnings: ${#CHECKS_WARNING[@]}"
    echo ""

    if [[ ${#CHECKS_FAILED[@]} -gt 0 ]]; then
        echo -e "${RED}VALIDATION FAILED${NC}"
        echo ""
        echo "Failed checks:"
        printf '  - %s\n' "${CHECKS_FAILED[@]}"
        echo ""
        echo "Recommendations:"
        echo "  1. Review and fix failed checks"
        echo "  2. Rebuild with: ./scripts/release/perform.sh $VERSION"
        echo "  3. Re-validate with: ./scripts/release/validate.sh $VERSION"
        echo ""
        return 1
    elif [[ ${#CHECKS_WARNING[@]} -gt 0 ]]; then
        echo -e "${YELLOW}VALIDATION PASSED WITH WARNINGS${NC}"
        echo ""
        echo "Warnings:"
        printf '  - %s\n' "${CHECKS_WARNING[@]}"
        echo ""
        echo "Recommendations:"
        echo "  1. Review warnings above"
        echo "  2. If acceptable, release is ready for use"
        echo "  3. Otherwise, address warnings and rebuild"
        echo ""
    else
        echo -e "${GREEN}ALL VALIDATIONS PASSED${NC}"
        echo ""
        echo "The release is ready for production use!"
        echo ""
        echo "Maven Central:"
        echo "  https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/$VERSION"
        echo ""
        echo "Central Portal:"
        echo "  https://central.sonatype.com/artifact/io.github/seanchatmangpt/jotp/$VERSION"
        echo ""
    fi
}

# Main execution
main() {
    echo "======================================"
    echo "JOTP Release Validation"
    echo "======================================"
    echo ""

    parse_args "$@"
    check_prerequisites
    validate_local_artifacts
    validate_remote_artifacts
    generate_report
    show_summary
}

# Run main
main "$@"
