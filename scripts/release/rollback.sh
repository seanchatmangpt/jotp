#!/usr/bin/env bash
# JOTP Release Rollback Script
# Rolls back a release by deleting tags, branches, and notifying Maven Central
#
# Usage:
#   ./scripts/release/rollback.sh [version] [options]
#
# Options:
#   --dry-run        Show what would be done without making changes
#   --local-only     Only rollback local changes (keep remote deployment)
#   --force          Skip confirmation prompts
#
# Examples:
#   ./scripts/release/rollback.sh 1.0.0
#   ./scripts/release/rollback.sh 1.0.0 --dry-run
#   ./scripts/release/rollback.sh 1.0.0 --local-only

set -euo pipefall

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
FORCE=false
ROLLBACK_TYPES=()

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
            --force)
                FORCE=true
                shift
                ;;
            *)
                log_error "Unknown option: $1"
                exit 1
                ;;
        esac
    done
}

# Check what can be rolled back
check_rollback_state() {
    log_info "Checking rollback state for version $VERSION..."

    local has_local_tag=false
    local has_remote_tag=false
    local has_local_branch=false
    local has_remote_branch=false
    local has_maven_deployment=false

    # Check local tag
    if git rev-parse "v$VERSION" >/dev/null 2>&1; then
        has_local_tag=true
        ROLLBACK_TYPES+=("local_tag")
    fi

    # Check remote tag
    if git ls-remote --tags origin "refs/tags/v$VERSION" | grep -q "v$VERSION"; then
        has_remote_tag=true
        ROLLBACK_TYPES+=("remote_tag")
    fi

    # Check local branch
    if git rev-parse --verify "release/$VERSION" >/dev/null 2>&1; then
        has_local_branch=true
        ROLLBACK_TYPES+=("local_branch")
    fi

    # Check remote branch
    if git ls-remote --heads origin "refs/heads/release/$VERSION" | grep -q "release/$VERSION"; then
        has_remote_branch=true
        ROLLBACK_TYPES+=("remote_branch")
    fi

    # Check Maven Central (best effort)
    if command -v curl &> /dev/null; then
        local central_url="https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/$VERSION/"
        if curl -s -o /dev/null -w "%{http_code}" "$central_url" | grep -q "200"; then
            has_maven_deployment=true
            ROLLBACK_TYPES+=("maven_central")
        fi
    fi

    # Display rollback options
    echo ""
    echo "Detected rollback targets:"
    echo "  Local tag:       $([ "$has_local_tag" == true ] && echo "Yes" || echo "No")"
    echo "  Remote tag:      $([ "$has_remote_tag" == true ] && echo "Yes" || echo "No")"
    echo "  Local branch:    $([ "$has_local_branch" == true ] && echo "Yes" || echo "No")"
    echo "  Remote branch:   $([ "$has_remote_branch" == true ] && echo "Yes" || echo "No")"
    echo "  Maven Central:   $([ "$has_maven_deployment" == true ] && echo "Yes (requires manual action)" || echo "No")"
    echo ""

    if [[ ${#ROLLBACK_TYPES[@]} -eq 0 ]]; then
        log_error "Nothing to rollback for version $VERSION"
        exit 1
    fi

    # Confirm rollback
    if [[ $FORCE == false ]] && [[ $DRY_RUN == false ]]; then
        log_warning "This will rollback the following:"
        printf '  - %s\n' "${ROLLBACK_TYPES[@]}"
        echo ""
        read -p "Continue? (yes/NO): " -r
        echo
        if [[ ! $REPLY == "yes" ]]; then
            log_info "Rollback cancelled"
            exit 0
        fi
    fi
}

# Rollback local tag
rollback_local_tag() {
    if ! git rev-parse "v$VERSION" >/dev/null 2>&1; then
        return
    fi

    log_info "Rolling back local tag: v$VERSION"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would delete local tag v$VERSION"
        return
    fi

    git tag -d "v$VERSION"
    log_success "Local tag deleted"
}

# Rollback remote tag
rollback_remote_tag() {
    if ! git ls-remote --tags origin "refs/tags/v$VERSION" | grep -q "v$VERSION"; then
        return
    fi

    log_info "Rolling back remote tag: v$VERSION"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would delete remote tag v$VERSION"
        return
    fi

    git push origin ":refs/tags/v$VERSION"
    log_success "Remote tag deleted"
}

# Rollback local branch
rollback_local_branch() {
    if ! git rev-parse --verify "release/$VERSION" >/dev/null 2>&1; then
        return
    fi

    log_info "Rolling back local branch: release/$VERSION"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would delete local branch release/$VERSION"
        return
    fi

    # Check if we're on the branch
    local current_branch=$(git rev-parse --abbrev-ref HEAD)
    if [[ "$current_branch" == "release/$VERSION" ]]; then
        git checkout main
        log_info "Switched to main branch"
    fi

    git branch -D "release/$VERSION"
    log_success "Local branch deleted"
}

# Rollback remote branch
rollback_remote_branch() {
    if ! git ls-remote --heads origin "refs/heads/release/$VERSION" | grep -q "release/$VERSION"; then
        return
    fi

    log_info "Rolling back remote branch: release/$VERSION"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would delete remote branch release/$VERSION"
        return
    fi

    git push origin ":refs/heads/release/$VERSION"
    log_success "Remote branch deleted"
}

# Instructions for Maven Central rollback
rollback_maven_central_instructions() {
    local central_url="https://repo1.maven.org/maven2/io/github/seanchatmangpt/jotp/$VERSION/"

    # Check if artifact exists
    if ! curl -s -o /dev/null -w "%{http_code}" "$central_url" | grep -q "200"; then
        return
    fi

    log_warning "Maven Central rollback requires MANUAL action"
    echo ""
    echo "To rollback from Maven Central, follow these steps:"
    echo ""
    echo "1. Log in to Sonatype Central Portal:"
    echo "   https://central.sonatype.com"
    echo ""
    echo "2. Navigate to your deployed artifact:"
    echo "   $central_url"
    echo ""
    echo "3. Click 'Drop' or request deletion of the version"
    echo ""
    echo "4. Alternatively, contact Sonatype support:"
    echo "   https://issues.sonatype.org"
    echo ""
    echo "Note: Once artifacts are synced to Maven Central, they cannot be"
    echo "completely removed. You can only mark them as deleted or superseded."
    echo ""
}

# Restore previous version
restore_previous_version() {
    log_info "Determining previous version..."

    # Get the tag before this version
    local prev_tag=$(git describe --tags --abbrev=0 "v$VERSION^" 2>/dev/null || echo "")

    if [[ -z $prev_tag ]]; then
        log_warning "No previous version found"
        return
    fi

    local prev_version=${prev_tag#v}

    log_info "Previous version: $prev_version"

    # Checkout main branch
    git checkout main

    # Restore pom.xml version
    log_info "Restoring pom.xml version to $prev_version"
    sed -i.bak "s/<version>$VERSION<\/version>/<version>$prev_version<\/version>/g" "$PROJECT_ROOT/pom.xml"
    rm -f "$PROJECT_ROOT/pom.xml.bak"

    # Commit the restore
    if [[ $DRY_RUN == false ]]; then
        git add "$PROJECT_ROOT/pom.xml"
        git commit -m "chore(rollback): Restore version to $prev_version after failed $VERSION release"
        log_success "Version restored to $prev_version"
    else
        log_warning "DRY RUN: Would restore pom.xml version to $prev_version"
    fi
}

# Generate rollback report
generate_rollback_report() {
    local report_file="$PROJECT_ROOT/target/rollback-report-${VERSION}-$(date +%Y%m%d-%H%M%S).txt"

    log_info "Generating rollback report..."

    cat > "$report_file" << EOF
JOTP Release Rollback Report
=============================
Version: $VERSION
Date: $(date)
Rollback Types: ${ROLLBACK_TYPES[*]}
Dry Run: $([ "$DRY_RUN" == true ] && echo "Yes" || echo "No")

Actions Performed:
EOF

    for type in "${ROLLBACK_TYPES[@]}"; do
        echo "  - Rolled back: $type" >> "$report_file"
    done

    cat >> "$report_file" << EOF

Remaining Manual Actions (if any):
EOF

    if [[ " ${ROLLBACK_TYPES[*]} " =~ " maven_central " ]]; then
        echo "  - Maven Central: See instructions above" >> "$report_file"
    fi

    cat >> "$report_file" << EOF

Next Steps:
1. Verify local repository state: git status
2. Check remote branches: git branch -r
3. Check remote tags: git ls-remote --tags origin
4. If Maven Central rollback needed, follow manual instructions
5. To retry release, run: ./scripts/release/prepare.sh $VERSION

Recovery Commands:
  # Return to main branch
  git checkout main

  # Pull latest changes
  git pull origin main

  # Cleanup local branches
  git branch -d release/$VERSION 2>/dev/null || true

  # Restart release process
  ./scripts/release/prepare.sh $VERSION
EOF

    log_success "Rollback report: $report_file"
}

# Display completion
show_completion() {
    echo ""
    echo "======================================================================"
    log_success "Rollback complete!"
    echo "======================================================================"
    echo ""
    echo "Version: $VERSION"
    echo "Actions: ${ROLLBACK_TYPES[*]}"
    echo "Dry Run: $([ "$DRY_RUN" == true ] && echo "Yes" || echo "No")"
    echo ""

    if [[ " ${ROLLBACK_TYPES[*]} " =~ " maven_central " ]]; then
        log_warning "Maven Central rollback requires manual action"
        echo "See instructions above"
        echo ""
    fi

    echo "Repository state:"
    echo "  Current branch: $(git rev-parse --abbrev-ref HEAD)"
    echo "  Local tags: $(git tag | grep "v$VERSION" || echo "None")"
    echo "  Remote tags: $(git ls-remote --tags origin | grep "v$VERSION" || echo "None (check manually)")"
    echo ""
    echo "To retry the release:"
    echo "  ./scripts/release/prepare.sh $VERSION"
    echo ""
}

# Main execution
main() {
    echo "======================================"
    echo "JOTP Release Rollback"
    echo "======================================"
    echo ""

    parse_args "$@"
    check_rollback_state

    # Perform rollbacks in safe order
    rollback_local_tag
    rollback_remote_tag
    rollback_local_branch
    rollback_remote_branch
    rollback_maven_central_instructions

    # Restore previous version if not dry run
    if [[ $DRY_RUN == false ]] && [[ $LOCAL_ONLY == false ]]; then
        restore_previous_version
    fi

    generate_rollback_report
    show_completion
}

# Run main
main "$@"
