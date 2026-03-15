#!/usr/bin/env bash
# JOTP Release Preparation Script
# Prepares a release: version bump, changelog generation, tag creation
#
# Usage:
#   ./scripts/release/prepare.sh [version] [options]
#
# Options:
#   --dry-run        Show what would be done without making changes
#   --skip-tests     Skip test validation
#   --beta           Mark as beta/prerelease
#   --force          Bypass safety checks
#
# Examples:
#   ./scripts/release/prepare.sh 1.0.0
#   ./scripts/release/prepare.sh 1.1.0 --beta
#   ./scripts/release/prepare.sh 2.0.0 --dry-run

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# Configuration
DRY_RUN=false
SKIP_TESTS=false
BETA_RELEASE=false
FORCE=false
VERSION=""
CURRENT_VERSION=""
RELEASE_BRANCH=""
COMMIT_HASH=""

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Parse command line arguments
parse_args() {
    if [[ $# -eq 0 ]]; then
        log_error "Version argument is required"
        echo "Usage: $0 [version] [options]"
        echo ""
        echo "Examples:"
        echo "  $0 1.0.0"
        echo "  $0 1.1.0-beta --beta"
        echo "  $0 2.0.0 --dry-run"
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
            --skip-tests)
                SKIP_TESTS=true
                shift
                ;;
            --beta)
                BETA_RELEASE=true
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

# Validate version format
validate_version() {
    local version="$1"

    log_info "Validating version format: $version"

    # Semantic versioning regex
    if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
        log_error "Invalid version format: $version"
        log_error "Expected format: X.Y.Z or X.Y.Z-PRERELEASE (e.g., 1.0.0, 1.0.0-beta)"
        exit 1
    fi

    # Check if beta release
    if [[ $version == *-* ]]; then
        BETA_RELEASE=true
        log_info "Prerelease version detected"
    fi

    log_success "Version format is valid"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check if we're on a git repo
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        log_error "Not a git repository"
        exit 1
    fi

    # Check for uncommitted changes
    if [[ -n $(git status --porcelain) ]]; then
        log_error "Working directory has uncommitted changes"
        log_error "Please commit or stash them first:"
        git status --short
        exit 1
    fi

    # Check if on main branch or create release branch
    CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
    log_info "Current branch: $CURRENT_BRANCH"

    # Determine release branch name
    if [[ $BETA_RELEASE == true ]]; then
        RELEASE_BRANCH="release/${VERSION}-beta"
    else
        RELEASE_BRANCH="release/${VERSION}"
    fi

    # Check Java version
    if ! command -v java &> /dev/null; then
        log_error "Java not found in PATH"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [[ $JAVA_VERSION -lt 26 ]]; then
        log_error "Java 26 or higher required. Found: $JAVA_VERSION"
        exit 1
    fi

    # Check for mvnd or mvnw
    if command -v mvnd &> /dev/null; then
        MAVEN_CMD="mvnd"
    elif [[ -f "$PROJECT_ROOT/mvnw" ]]; then
        MAVEN_CMD="./mvnw"
    else
        log_error "Neither mvnd nor mvnw found"
        exit 1
    fi

    log_info "Maven command: $MAVEN_CMD"

    # Check for GPG
    if ! command -v gpg &> /dev/null; then
        log_warning "GPG not found. Artifacts will not be signed in dry-run mode"
    fi

    # Check Maven Central credentials (only in non-dry-run)
    if [[ $DRY_RUN == false ]]; then
        if [[ -z "${OSSRH_TOKEN:-}" ]] && [[ -z "${CENTRAL_TOKEN:-}" ]]; then
            log_warning "OSSRH_TOKEN or CENTRAL_TOKEN not set"
            log_warning "Set it with: export OSSRH_TOKEN=your_token"
        fi

        if [[ -z "${GPG_KEYNAME:-}" ]]; then
            log_warning "GPG_KEYNAME not set"
            log_warning "Set it with: export GPG_KEYNAME=your_key_id"
        fi
    fi

    log_success "Prerequisites check passed"
}

# Get current version from pom.xml
get_current_version() {
    CURRENT_VERSION=$(grep -A 1 "<artifactId>jotp</artifactId>" "$PROJECT_ROOT/pom.xml" | grep "<version>" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')
    log_info "Current version in pom.xml: $CURRENT_VERSION"
}

# Backup pom.xml
backup_pom() {
    log_info "Backing up pom.xml..."
    cp "$PROJECT_ROOT/pom.xml" "$PROJECT_ROOT/pom.xml.backup"
    log_success "Backup created: pom.xml.backup"
}

# Update version in pom.xml
update_version() {
    local new_version="$1"

    log_info "Updating version to: $new_version"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would update pom.xml version from $CURRENT_VERSION to $new_version"
        return
    fi

    # Update version in pom.xml
    sed -i.bak "s/<version>$CURRENT_VERSION<\/version>/<version>$new_version<\/version>/g" "$PROJECT_ROOT/pom.xml"
    rm -f "$PROJECT_ROOT/pom.xml.bak"

    log_success "Version updated in pom.xml"
}

# Create or update CHANGELOG
update_changelog() {
    local version="$1"
    local changelog_file="$PROJECT_ROOT/CHANGELOG.md"

    log_info "Updating CHANGELOG.md..."

    if [[ ! -f "$changelog_file" ]]; then
        log_info "Creating new CHANGELOG.md"
    fi

    # Generate changelog entries from git commits
    local prev_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    local commits=""

    if [[ -n $prev_tag ]]; then
        commits=$(git log --pretty=format:"- %s (%h)" ${prev_tag}..HEAD 2>/dev/null || echo "")
    else
        commits=$(git log --pretty=format:"- %s (%h)" -10 2>/dev/null || echo "")
    fi

    if [[ -z $commits ]]; then
        commits="- Initial release or no commits since last tag"
    fi

    # Create new changelog entry
    local new_entry="## [$version] - $(date +%Y-%m-%d)

### Added
$commits

### Changed
- Version bumped to $version

### Fixed

### Deprecated

### Removed

### Security

"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would update CHANGELOG.md with:"
        echo "$new_entry" | head -20
        return
    fi

    # Prepend new entry to changelog
    if [[ -f "$changelog_file" ]]; then
        echo "$new_entry" | cat - "$changelog_file" > "${changelog_file}.tmp"
        mv "${changelog_file}.tmp" "$changelog_file"
    else
        echo "# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

" > "$changelog_file"
        echo "$new_entry" >> "$changelog_file"
    fi

    log_success "CHANGELOG.md updated"
}

# Create release branch
create_release_branch() {
    local branch="$1"

    log_info "Creating release branch: $branch"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would create branch: $branch"
        return
    fi

    if git rev-parse --verify "$branch" >/dev/null 2>&1; then
        log_warning "Branch $branch already exists"
        read -p "Delete and recreate? (y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            git branch -D "$branch"
            git checkout -b "$branch"
        else
            log_info "Using existing branch"
            git checkout "$branch"
        fi
    else
        git checkout -b "$branch"
    fi

    log_success "Release branch created: $branch"
}

# Run tests
run_tests() {
    if [[ $SKIP_TESTS == true ]]; then
        log_warning "Skipping tests due to --skip-tests flag"
        return
    fi

    log_info "Running tests..."

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would run tests"
        return
    fi

    # Run full test suite
    "$MAVEN_CMD" clean verify -T1C -DskipTests=false

    log_success "All tests passed"
}

# Commit changes
commit_changes() {
    local version="$1"

    log_info "Committing release changes..."

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would commit changes"
        return
    fi

    # Commit version and changelog changes
    git add "$PROJECT_ROOT/pom.xml"
    git add "$PROJECT_ROOT/CHANGELOG.md"

    git commit -m "chore(release): Prepare release $version

- Update version to $version
- Update CHANGELOG.md

[skip ci]"

    log_success "Changes committed"
}

# Create git tag
create_tag() {
    local version="$1"

    log_info "Creating git tag: v$version"

    if [[ $DRY_RUN == true ]]; then
        log_warning "DRY RUN: Would create tag: v$version"
        return
    fi

    # Create annotated tag
    git tag -a "v$version" -m "Release $version

- See CHANGELOG.md for details
- Published to Maven Central"

    log_success "Tag created: v$version"
}

# Display next steps
show_next_steps() {
    local version="$1"

    echo ""
    echo "======================================================================"
    log_success "Release preparation complete!"
    echo "======================================================================"
    echo ""
    echo "Version: $version"
    echo "Branch: $RELEASE_BRANCH"
    echo "Tag: v$version"
    echo ""
    echo "Next steps:"
    echo ""
    echo "1. Review the changes:"
    echo "   git diff $CURRENT_BRANCH..$RELEASE_BRANCH"
    echo ""
    echo "2. Run the perform script to deploy to Maven Central:"
    echo "   ./scripts/release/perform.sh $version"
    echo ""
    echo "3. Or push and trigger GitHub Actions:"
    echo "   git push origin $RELEASE_BRANCH"
    echo "   git push origin v$version"
    echo ""
    echo "4. Monitor the deployment at:"
    echo "   https://central.sonatype.com"
    echo ""
    echo "To rollback if needed:"
    echo "   ./scripts/release/rollback.sh $version"
    echo ""
}

# Main execution
main() {
    echo "======================================"
    echo "JOTP Release Preparation"
    echo "======================================"
    echo ""

    parse_args "$@"
    validate_version "$VERSION"
    check_prerequisites
    get_current_version

    # Safety confirmation for non-beta releases
    if [[ $BETA_RELEASE == false ]] && [[ $FORCE == false ]] && [[ $DRY_RUN == false ]]; then
        echo ""
        log_warning "You are preparing a PRODUCTION release: $VERSION"
        echo ""
        read -p "Continue? (yes/NO): " -r
        echo
        if [[ ! $REPLY == "yes" ]]; then
            log_info "Release preparation cancelled"
            exit 0
        fi
    fi

    backup_pom
    update_version "$VERSION"
    update_changelog "$VERSION"
    create_release_branch "$RELEASE_BRANCH"
    run_tests
    commit_changes "$VERSION"
    create_tag "$VERSION"

    show_next_steps "$VERSION"
}

# Run main function
main "$@"
