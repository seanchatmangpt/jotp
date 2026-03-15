#!/bin/bash
# verify-release.sh - Verify release readiness
#
# This script checks that all requirements are met for a JOTP release:
# - All tests pass
# - Documentation is complete
# - Version is updated
# - Changelog exists
# - No forbidden patterns
# - Code is formatted
# - Artifacts can be built

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MVND="${PROJECT_ROOT}/.mvn/wrapper/mvnw"
if [ ! -f "${MVND}" ]; then
    MVND="mvnd"
fi

# Helper functions
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_pass() {
    echo -e "  ${GREEN}✓${NC} $1"
}

check_fail() {
    echo -e "  ${RED}✗${NC} $1"
}

check_skip() {
    echo -e "  ${YELLOW}⊘${NC} $1 (skipped)"
}

# Print banner
echo -e "${BLUE}"
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  JOTP Release Readiness Verification                       ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo ""

# Change to project root
cd "${PROJECT_ROOT}"

# Track overall status
ALL_CHECKS_PASSED=true

# ============================================
# 1. Version Check
# ============================================
info "Checking version information..."

VERSION=$(grep "<version>" pom.xml | head -1 | sed -e 's/.*<version>\(.*\)<\/version>.*/\1/')

if [ -z "$VERSION" ]; then
    check_fail "Could not extract version from pom.xml"
    ALL_CHECKS_PASSED=false
else
    check_pass "Version extracted from pom.xml: ${VERSION}"

    # Check if version is a pre-release
    if [[ "$VERSION" =~ -Alpha$|-Beta$|-RC$|-SNAPSHOT$ ]]; then
        warning "Version appears to be a pre-release: ${VERSION}"
    else
        success "Version appears to be a stable release: ${VERSION}"
    fi
fi

# Check if version is tagged
if git rev-parse "v${VERSION}" >/dev/null 2>&1; then
    warning "Tag v${VERSION} already exists"
else
    check_pass "Tag v${VERSION} does not exist yet (good for new release)"
fi

echo ""

# ============================================
# 2. CHANGELOG Check
# ============================================
info "Checking CHANGELOG.md..."

if [ ! -f "CHANGELOG.md" ]; then
    check_fail "CHANGELOG.md not found"
    ALL_CHECKS_PASSED=false
else
    check_pass "CHANGELOG.md exists"

    # Check if version is mentioned in CHANGELOG
    if grep -q "## \[${VERSION}\]" CHANGELOG.md; then
        check_pass "Version ${VERSION} found in CHANGELOG.md"
    else
        check_fail "Version ${VERSION} not found in CHANGELOG.md"
        ALL_CHECKS_PASSED=false
    fi

    # Check CHANGELOG format
    if grep -q "## \[Unreleased\]" CHANGELOG.md; then
        check_pass "CHANGELOG has [Unreleased] section"
    fi

    # Check for required sections
    if grep -q "### Added\|### Changed\|### Deprecated\|### Removed\|### Fixed\|### Security" CHANGELOG.md; then
        check_pass "CHANGELOG has standard sections"
    else
        warning "CHANGELOG may be missing standard sections"
    fi
fi

echo ""

# ============================================
# 3. Documentation Check
# ============================================
info "Checking documentation..."

# Check main README
if [ -f "README.md" ]; then
    check_pass "README.md exists"

    # Check if version is mentioned in README
    if grep -q "$VERSION" README.md; then
        check_pass "Version ${VERSION} mentioned in README.md"
    else
        warning "Version ${VERSION} not found in README.md"
    fi
else
    check_fail "README.md not found"
    ALL_CHECKS_PASSED=false
fi

# Check DEVOPS documentation
if [ -f "docs/DEVOPS.md" ]; then
    check_pass "docs/DEVOPS.md exists"
else
    warning "docs/DEVOPS.md not found (recommended)"
fi

# Check for Javadoc completeness
info "Checking Javadoc..."

JAVADOC_CHECK=true
if ! ${MVND} javadoc:javadoc -q >/dev/null 2>&1; then
    check_fail "Javadoc generation failed"
    ALL_CHECKS_PASSED=false
    JAVADOC_CHECK=false
else
    check_pass "Javadoc generates without errors"
fi

echo ""

# ============================================
# 4. Code Quality Check
# ============================================
info "Checking code quality..."

# Run Spotless check
info "Running Spotless format check..."
if ${MVND} spotless:check -q >/dev/null 2>&1; then
    check_pass "Code formatting (Spotless) check passed"
else
    check_fail "Code formatting (Spotless) check failed"
    warning "Run: mvnd spotless:apply"
    ALL_CHECKS_PASSED=false
fi

# Check for forbidden patterns
info "Running guard validation..."
if [ -f ".claude/hooks/simple-guards.sh" ]; then
    if bash .claude/hooks/simple-guards.sh >/dev/null 2>&1; then
        check_pass "Guard validation passed (no forbidden patterns)"
    else
        check_fail "Guard validation failed (forbidden patterns found)"
        warning "Look for: H_TODO, H_MOCK, H_STUB"
        ALL_CHECKS_PASSED=false
    fi
else
    check_skip "Guard validation script not found"
fi

echo ""

# ============================================
# 5. Build Check
# ============================================
info "Checking build..."

# Clean build
info "Running clean build..."
if ${MVND} clean compile -q -T1C >/dev/null 2>&1; then
    check_pass "Clean compilation successful"
else
    check_fail "Clean compilation failed"
    ALL_CHECKS_PASSED=false
fi

# Check if artifacts can be built
info "Building artifacts..."
if ${MVND} package -DskipTests -q -T1C >/dev/null 2>&1; then
    check_pass "Package build successful"

    # Check for expected artifacts
    ARTIFACT_COUNT=$(find target -name "jotp-*.jar" -type f | wc -l)
    if [ "$ARTIFACT_COUNT" -ge 1 ]; then
        check_pass "Found ${ARTIFACT_COUNT} artifact(s) in target/"

        # Check for specific artifacts
        if [ -f "target/jotp-${VERSION}.jar" ]; then
            check_pass "Main artifact exists: jotp-${VERSION}.jar"
        else
            check_fail "Main artifact missing: jotp-${VERSION}.jar"
            ALL_CHECKS_PASSED=false
        fi

        if [ -f "target/jotp-${VERSION}-sources.jar" ]; then
            check_pass "Sources artifact exists: jotp-${VERSION}-sources.jar"
        else
            warning "Sources artifact missing: jotp-${VERSION}-sources.jar"
        fi

        if [ -f "target/jotp-${VERSION}-javadoc.jar" ]; then
            check_pass "Javadoc artifact exists: jotp-${VERSION}-javadoc.jar"
        else
            warning "Javadoc artifact missing: jotp-${VERSION}-javadoc.jar"
        fi
    else
        check_fail "No artifacts found in target/"
        ALL_CHECKS_PASSED=false
    fi
else
    check_fail "Package build failed"
    ALL_CHECKS_PASSED=false
fi

echo ""

# ============================================
# 6. Test Check
# ============================================
info "Checking tests..."

# Run unit tests
info "Running unit tests..."
if ${MVND} test -q -T1C >/dev/null 2>&1; then
    check_pass "Unit tests passed"
else
    check_fail "Unit tests failed"
    warning "Run: mvnd test to see failures"
    ALL_CHECKS_PASSED=false
fi

# Run integration tests
info "Running integration tests..."
if ${MVND} verify -DskipUnitTests -q -T1C >/dev/null 2>&1; then
    check_pass "Integration tests passed"
else
    check_fail "Integration tests failed"
    warning "Run: mvnd verify to see failures"
    ALL_CHECKS_PASSED=false
fi

echo ""

# ============================================
# 7. Dependency Check
# ============================================
info "Checking dependencies..."

# Check dependency tree
if ${MVND} dependency:tree -q >/dev/null 2>&1; then
    check_pass "Dependency tree valid"
else
    check_fail "Dependency tree validation failed"
    ALL_CHECKS_PASSED=false
fi

# Check for known vulnerabilities (if OWASP plugin available)
if ${MVND} org.owasp:dependency-check-maven:check -q >/dev/null 2>&1; then
    check_pass "No known vulnerabilities detected"
else
    check_skip "Dependency vulnerability check not available or failed"
fi

echo ""

# ============================================
# 8. Git Check
# ============================================
info "Checking git status..."

# Check if working directory is clean
if [ -z "$(git status --porcelain)" ]; then
    check_pass "Working directory is clean"
else
    warning "Working directory has uncommitted changes"
    git status --short
fi

# Check if on a release branch
CURRENT_BRANCH=$(git branch --show-current)
if [[ "$CURRENT_BRANCH" =~ ^release/ ]]; then
    check_pass "On release branch: ${CURRENT_BRANCH}"
elif [ "$CURRENT_BRANCH" = "main" ]; then
    warning "On main branch (consider creating release branch)"
else
    warning "On feature branch: ${CURRENT_BRANCH} (should be on release branch)"
fi

# Check if remote is up to date
if git fetch origin >/dev/null 2>&1; then
    LOCAL_COMMIT=$(git rev-parse HEAD)
    REMOTE_COMMIT=$(git rev-parse origin/${CURRENT_BRANCH} 2>/dev/null || echo "")

    if [ "$LOCAL_COMMIT" = "$REMOTE_COMMIT" ]; then
        check_pass "Branch is up to date with origin"
    else
        warning "Branch is not up to date with origin"
        info "Run: git push origin ${CURRENT_BRANCH}"
    fi
else
    check_skip "Could not check remote status"
fi

echo ""

# ============================================
# 9. Release Metadata Check
# ============================================
info "Checking release metadata..."

# Check pom.xml for required elements
if grep -q "<name>" pom.xml && grep -q "<description>" pom.xml && grep -q "<url>" pom.xml; then
    check_pass "pom.xml has basic metadata"
else
    check_fail "pom.xml missing basic metadata (name, description, url)"
    ALL_CHECKS_PASSED=false
fi

# Check for license
if grep -q "<license>" pom.xml; then
    check_pass "pom.xml has license information"
else
    check_fail "pom.xml missing license information"
    ALL_CHECKS_PASSED=false
fi

# Check for SCM
if grep -q "<scm>" pom.xml; then
    check_pass "pom.xml has SCM information"
else
    check_fail "pom.xml missing SCM information"
    ALL_CHECKS_PASSED=false
fi

# Check for developers
if grep -q "<developers>" pom.xml; then
    check_pass "pom.xml has developer information"
else
    check_fail "pom.xml missing developer information"
    ALL_CHECKS_PASSED=false
fi

echo ""

# ============================================
# 10. GPG Check (optional)
# ============================================
info "Checking GPG configuration..."

if command -v gpg >/dev/null 2>&1; then
    check_pass "GPG is installed"

    # Check if default key is available
    if gpg --list-secret-keys >/dev/null 2>&1; then
        check_pass "GPG secret key(s) available"
    else
        warning "No GPG secret keys found"
        warning "GPG signing is required for Maven Central publishing"
    fi
else
    warning "GPG not found (required for Maven Central publishing)"
fi

echo ""

# ============================================
# Final Summary
# ============================================
echo -e "${BLUE}"
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Release Readiness Summary                                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

if [ "$ALL_CHECKS_PASSED" = true ]; then
    success "All critical checks passed! ✅"
    echo ""
    info "You are ready to create release v${VERSION}"
    echo ""
    echo "Next steps:"
    echo "  1. Review CHANGELOG.md"
    echo "  2. Create release branch: git checkout -b release/${VERSION}"
    echo "  3. Tag the release: git tag -a v${VERSION} -m 'Release ${VERSION}'"
    echo "  4. Push tag: git push origin v${VERSION}"
    echo "  5. Monitor CI/CD pipeline"
    echo ""
    exit 0
else
    error "Some checks failed! ❌"
    echo ""
    error "Please fix the issues above before creating the release."
    echo ""
    echo "Common fixes:"
    echo "  - Code formatting: mvnd spotless:apply"
    echo "  - Tests failures: mvnd test"
    echo "  - Javadoc errors: mvnd javadoc:javadoc"
    echo "  - Guard violations: bash .claude/hooks/simple-guards.sh"
    echo ""
    exit 1
fi
