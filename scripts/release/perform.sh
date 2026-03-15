#!/usr/bin/env bash
# JOTP Release Performance Script
# Deploys a prepared release to Maven Central
#
# Usage:
#   ./scripts/release/perform.sh [version] [options]
#
# Options:
#   --dry-run        Show what would be done without deploying
#   --local-only     Build and sign locally without deploying
#   --skip-sign      Skip GPG signing (for testing)
#
# Examples:
#   ./scripts/release/perform.sh 1.0.0
#   ./scripts/release/perform.sh 1.0.0 --dry-run
#   ./scripts/release/perform.sh 1.0.0 --local-only

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
DRY_RUN=false
LOCAL_ONLY=false
SKIP_SIGN=false
MAVEN_CMD=""

# Logging
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Parse arguments
parse_args() {
    if [[ $# -eq 0 ]]; then
        log_error "Version argument is required"
        echo "Usage: $0 [version] [options]"
        exit 1
    fi

    VERSION="$1"
    shift

    while [[ $# -gt 0 ]]; do
        case $1 in
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --local-only)
                LOCAL_ONLY=true
                shift
                ;;
            --skip-sign)
                SKIP_SIGN=true
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
    log_info "Checking deployment prerequisites..."

    # Check if tag exists
    if ! git rev-parse "v$VERSION" >/dev/null 2>&1; then
        log_error "Git tag v$VERSION not found"
        log_error "Run ./scripts/release/prepare.sh $VERSION first"
        exit 1
    fi

    # Check out the tag
    log_info "Checking out tag: v$VERSION"
    if [[ $DRY_RUN == false ]]; then
        git checkout "v$VERSION"
    else
        log_warning "DRY RUN: Would checkout tag v$VERSION"
    fi

    # Verify version in pom.xml
    local pom_version=$(grep -A 1 "<artifactId>jotp</artifactId>" "$PROJECT_ROOT/pom.xml" | grep "<version>" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')
    if [[ "$pom_version" != "$VERSION" ]]; then
        log_error "Version mismatch: tag=$VERSION, pom.xml=$pom_version"
        exit 1
    fi

    # Check for Maven command
    if command -v mvnd &> /dev/null; then
        MAVEN_CMD="mvnd"
    elif [[ -f "$PROJECT_ROOT/mvnw" ]]; then
        MAVEN_CMD="./mvnw"
    else
        log_error "Neither mvnd nor mvnw found"
        exit 1
    fi

    log_info "Maven command: $MAVEN_CMD"

    # Check GPG for signing
    if [[ $SKIP_SIGN == false ]] && command -v gpg &> /dev/null; then
        if [[ -z "${GPG_KEYNAME:-}" ]]; then
            log_warning "GPG_KEYNAME not set. Trying to detect..."
            GPG_KEYNAME=$(gpg --list-secret-keys --keyid-format LONG 2>/dev/null | grep "sec" | head -1 | awk '{print $2}' | cut -d'/' -f2)
            if [[ -n $GPG_KEYNAME ]]; then
                log_info "Detected GPG key: $GPG_KEYNAME"
            else
                log_error "No GPG key found. Set GPG_KEYNAME or use --skip-sign"
                exit 1
            fi
        fi
    fi

    # Check Maven Central credentials
    if [[ $LOCAL_ONLY == false ]] && [[ $DRY_RUN == false ]]; then
        if [[ -z "${OSSRH_TOKEN:-}" ]] && [[ -z "${CENTRAL_TOKEN:-}" ]]; then
            log_error "OSSRH_TOKEN or CENTRAL_TOKEN not set"
            log_error "Set with: export OSSRH_TOKEN=your_token"
            log_error "Or add to ~/.m2/settings.xml"
            exit 1
        fi
        log_info "Maven Central credentials configured"
    fi

    log_success "Prerequisites check passed"
}

# Clean and build
build_project() {
    log_info "Building project..."

    local maven_args=(
        clean
        package
        -T1C
        -DskipTests=false
        -Drelease=true
    )

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would build with: ${MAVEN_CMD} ${maven_args[*]}"
        return
    fi

    # Execute build
    "$MAVEN_CMD" "${maven_args[@]}"

    # Verify artifacts exist
    local jar_file="$PROJECT_ROOT/target/jotp-${VERSION}.jar"
    if [[ ! -f "$jar_file" ]]; then
        log_error "Build artifact not found: $jar_file"
        exit 1
    fi

    log_success "Build completed successfully"
}

# Generate GPG signatures
sign_artifacts() {
    if [[ $SKIP_SIGN == true ]]; then
        log_warning "Skipping GPG signing due to --skip-sign flag"
        return
    fi

    log_info "Signing artifacts with GPG..."

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would sign artifacts"
        return
    fi

    # Maven GPG plugin is configured in pom.xml
    # Sign during verify phase
    "$MAVEN_CMD" verify -T1C -Dgpg.keyname="$GPG_KEYNAME"

    # Verify signatures exist
    local asc_file="$PROJECT_ROOT/target/jotp-${VERSION}.jar.asc"
    if [[ ! -f "$asc_file" ]]; then
        log_error "GPG signature not found: $asc_file"
        exit 1
    fi

    log_success "Artifacts signed successfully"
}

# Validate deployment bundle
validate_bundle() {
    log_info "Validating deployment bundle..."

    local required_files=(
        "target/jotp-${VERSION}.jar"
        "target/jotp-${VERSION}.pom"
        "target/jotp-${VERSION}-sources.jar"
        "target/jotp-${VERSION}-javadoc.jar"
    )

    local missing_files=()

    for file in "${required_files[@]}"; do
        if [[ ! -f "$PROJECT_ROOT/$file" ]]; then
            missing_files+=("$file")
        fi
    done

    if [[ ${#missing_files[@]} -gt 0 ]]; then
        log_error "Missing required files:"
        printf '  - %s\n' "${missing_files[@]}"
        exit 1
    fi

    # Check signatures if not skipped
    if [[ $SKIP_SIGN == false ]]; then
        for file in "${required_files[@]}"; do
            local asc_file="${file}.asc"
            if [[ ! -f "$PROJECT_ROOT/$asc_file" ]]; then
                log_error "Missing signature: $asc_file"
                missing_files+=("$asc_file")
            fi
        done
    fi

    log_success "Deployment bundle is valid"
}

# Deploy to Maven Central
deploy_to_central() {
    if [[ $LOCAL_ONLY == true ]]; then
        log_warning "Skipping deployment due to --local-only flag"
        log_info "Artifacts are ready in target/ directory"
        return
    fi

    log_info "Deploying to Maven Central..."

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would deploy to Maven Central"
        return
    fi

    # Deploy using central-publishing-maven-plugin
    local deploy_args=(
        deploy
        -T1C
        -Drelease=true
        -DskipTests=true
    )

    if [[ $SKIP_SIGN == false ]]; then
        deploy_args+=(-Dgpg.keyname="$GPG_KEYNAME")
    fi

    "$MAVEN_CMD" "${deploy_args[@]}"

    log_success "Deployment initiated successfully"
}

# Verify deployment on Central Portal
verify_deployment() {
    if [[ $LOCAL_ONLY == true ]] || [[ $DRY_RUN == true ]]; then
        return
    fi

    log_info "Verifying deployment on Central Portal..."

    # Wait a moment for the deployment to register
    sleep 5

    # Check if artifact is accessible
    local central_url="https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp/$VERSION"

    log_info "Check deployment status at:"
    echo "  $central_url"
    echo ""
    log_info "Or use the Central Portal:"
    echo "  https://central.sonatype.com"
    echo ""
}

# Generate deployment report
generate_report() {
    local report_file="$PROJECT_ROOT/target/deployment-report-${VERSION}.txt"

    log_info "Generating deployment report..."

    cat > "$report_file" << EOF
JOTP Release Deployment Report
================================
Version: $VERSION
Date: $(date)
Tag: v$VERSION
Branch: $(git rev-parse --abbrev-ref HEAD)
Commit: $(git rev-parse HEAD)

Build Artifacts:
EOF

    ls -lh "$PROJECT_ROOT/target"/*.jar | grep "jotp-${VERSION}" | tee -a "$report_file"

    cat >> "$report_file" << EOF

Deployment Status:
- Maven Central: $([ "$LOCAL_ONLY" == true ] && echo "Skipped (local-only)" || echo "Deployed")
- GPG Signing: $([ "$SKIP_SIGN" == true ] && echo "Skipped" || echo "Complete")
- Dry Run: $([ "$DRY_RUN" == true ] && echo "Yes" || echo "No")

Next Steps:
1. Verify at: https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp/$VERSION
2. Wait for Maven Central sync (usually 10-30 minutes)
3. Test with: mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:$VERSION
4. Create GitHub release if not already created
5. Announce release to community

Rollback (if needed):
  ./scripts/release/rollback.sh $VERSION
EOF

    log_success "Deployment report: $report_file"
    cat "$report_file"
}

# Display completion message
show_completion() {
    echo ""
    echo "======================================================================"
    log_success "Release deployment complete!"
    echo "======================================================================"
    echo ""
    echo "Version: $VERSION"
    echo "Deployment: $([ "$LOCAL_ONLY" == true ] && echo "Local build only" || echo "Maven Central")"
    echo "Status: $([ "$DRY_RUN" == true ] && echo "DRY RUN - not deployed" || echo "Deployed")"
    echo ""
    echo "Verification steps:"
    echo ""
    echo "1. Check Central Portal:"
    echo "   https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp/$VERSION"
    echo ""
    echo "2. Verify artifact availability (may take 10-30 minutes):"
    echo "   mvn dependency:get -Dartifact=io.github.seanchatmangpt:jotp:$VERSION"
    echo ""
    echo "3. Check GitHub Actions workflow:"
    echo "   https://github.com/seanchatmangpt/jotp/actions"
    echo ""
    if [[ "$LOCAL_ONLY" == false ]] && [[ "$DRY_RUN" == false ]]; then
        echo "4. Merge release branch to main:"
        echo "   git checkout main"
        echo "   git merge release/$VERSION"
        echo "   git push origin main"
        echo ""
    fi
    echo "To rollback if issues occur:"
    echo "   ./scripts/release/rollback.sh $VERSION"
    echo ""
}

# Main execution
main() {
    echo "======================================"
    echo "JOTP Release Deployment"
    echo "======================================"
    echo ""

    parse_args "$@"
    check_prerequisites
    build_project
    sign_artifacts
    validate_bundle
    deploy_to_central
    verify_deployment
    generate_report
    show_completion
}

# Run main
main "$@"
