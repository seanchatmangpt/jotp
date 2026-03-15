#!/bin/bash
# Workflow Validation Script for JOTP CI/CD
# This script validates that all required workflows and configurations are in place

set -e

echo "🔍 JOTP CI/CD Workflow Validation"
echo "=================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if we're in the right directory
if [ ! -f "pom.xml" ]; then
    echo -e "${RED}❌ Error: pom.xml not found. Please run from project root.${NC}"
    exit 1
fi

PASSED=0
FAILED=0
WARNINGS=0

# Function to check file exists
check_file() {
    local file=$1
    local description=$2

    if [ -f "$file" ]; then
        echo -e "${GREEN}✅ $description: $file${NC}"
        ((PASSED++))
        return 0
    else
        echo -e "${RED}❌ Missing $description: $file${NC}"
        ((FAILED++))
        return 1
    fi
}

# Function to check workflow syntax
check_workflow_syntax() {
    local file=$1
    local description=$2

    echo -n "Checking $description syntax... "

    # Check if it's valid YAML
    if command -v yamllint &> /dev/null; then
        if yamllint "$file" -d "{extends: default, rules: {line-length: disable}}" &> /dev/null; then
            echo -e "${GREEN}✅ VALID${NC}"
            ((PASSED++))
        else
            echo -e "${YELLOW}⚠️  WARN${NC}"
            ((WARNINGS++))
        fi
    else
        echo -e "${YELLOW}⚠️  yamllint not installed, skipping syntax check${NC}"
        ((WARNINGS++))
    fi
}

# Validate workflow files
echo "📋 Validating Workflow Files"
echo "----------------------------"

check_file ".github/workflows/ci.yml" "CI Pipeline"
check_workflow_syntax ".github/workflows/ci.yml" "CI Pipeline"

check_file ".github/workflows/quality.yml" "Quality Checks"
check_workflow_syntax ".github/workflows/quality.yml" "Quality Checks"

check_file ".github/workflows/release.yml" "Release Pipeline"
check_workflow_syntax ".github/workflows/release.yml" "Release Pipeline"

echo ""

# Validate documentation
echo "📚 Validating Documentation"
echo "--------------------------"

check_file ".github/workflows/WORKFLOWS-GUIDE.md" "Workflows Guide"
check_file ".github/workflows/QUICK-REFERENCE.md" "Quick Reference"

echo ""

# Validate required project files
echo "📦 Validating Project Files"
echo "--------------------------"

check_file "pom.xml" "Maven POM"
check_file "LICENSE" "License file"
check_file "README.md" "README"
check_file ".github/workflows/ci.yml" "CI workflow"

echo ""

# Validate Maven configuration
echo "🔧 Validating Maven Configuration"
echo "--------------------------------"

if grep -q "maven-compiler-plugin" pom.xml; then
    echo -e "${GREEN}✅ Compiler plugin configured${NC}"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  Compiler plugin not found${NC}"
    ((WARNINGS++))
fi

if grep -q "maven-surefire-plugin" pom.xml; then
    echo -e "${GREEN}✅ Surefire plugin configured${NC}"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  Surefire plugin not found${NC}"
    ((WARNINGS++))
fi

if grep -q "maven-failsafe-plugin" pom.xml; then
    echo -e "${GREEN}✅ Failsafe plugin configured${NC}"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  Failsafe plugin not found${NC}"
    ((WARNINGS++))
fi

if grep -q "spotless-maven-plugin" pom.xml; then
    echo -e "${GREEN}✅ Spotless plugin configured${NC}"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  Spotless plugin not found${NC}"
    ((WARNINGS++))
fi

if grep -q "central-publishing-maven-plugin" pom.xml; then
    echo -e "${GREEN}✅ Central publishing plugin configured${NC}"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  Central publishing plugin not found${NC}"
    ((WARNINGS++))
fi

if grep -q "maven-gpg-plugin" pom.xml; then
    echo -e "${GREEN}✅ GPG plugin configured${NC}"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  GPG plugin not found${NC}"
    ((WARNINGS++))
fi

echo ""

# Check for required secrets (placeholder check)
echo "🔐 Validating Secrets Configuration"
echo "-----------------------------------"

# We can't check actual secret values, but we can check if the workflows reference them
if grep -q "CENTRAL_TOKEN" .github/workflows/release.yml; then
    echo -e "${GREEN}✅ Release workflow references CENTRAL_TOKEN${NC}"
    ((PASSED++))
else
    echo -e "${RED}❌ Release workflow missing CENTRAL_TOKEN reference${NC}"
    ((FAILED++))
fi

if grep -q "GPG_PRIVATE_KEY" .github/workflows/release.yml; then
    echo -e "${GREEN}✅ Release workflow references GPG_PRIVATE_KEY${NC}"
    ((PASSED++))
else
    echo -e "${RED}❌ Release workflow missing GPG_PRIVATE_KEY reference${NC}"
    ((FAILED++))
fi

echo ""

# Validate workflow structure
echo "🏗️  Validating Workflow Structure"
echo "---------------------------------"

# Check CI workflow jobs
if grep -q "jobs:" .github/workflows/ci.yml; then
    CI_JOBS=$(grep -E "^\s+[a-z-]+:" .github/workflows/ci.yml | grep -v "if\|env\|steps\|uses\|run\|with\|name" | wc -l | tr -d ' ')
    echo -e "${GREEN}✅ CI workflow has $CI_JOBS jobs${NC}"
    ((PASSED++))
else
    echo -e "${RED}❌ CI workflow missing jobs${NC}"
    ((FAILED++))
fi

# Check quality workflow jobs
if grep -q "jobs:" .github/workflows/quality.yml; then
    QUALITY_JOBS=$(grep -E "^\s+[a-z-]+:" .github/workflows/quality.yml | grep -v "if\|env\|steps\|uses\|run\|with\|name" | wc -l | tr -d ' ')
    echo -e "${GREEN}✅ Quality workflow has $QUALITY_JOBS jobs${NC}"
    ((PASSED++))
else
    echo -e "${RED}❌ Quality workflow missing jobs${NC}"
    ((FAILED++))
fi

# Check release workflow jobs
if grep -q "jobs:" .github/workflows/release.yml; then
    RELEASE_JOBS=$(grep -E "^\s+[a-z-]+:" .github/workflows/release.yml | grep -v "if\|env\|steps\|uses\|run\|with\|name" | wc -l | tr -d ' ')
    echo -e "${GREEN}✅ Release workflow has $RELEASE_JOBS jobs${NC}"
    ((PASSED++))
else
    echo -e "${RED}❌ Release workflow missing jobs${NC}"
    ((FAILED++))
fi

echo ""

# Summary
echo "📊 Validation Summary"
echo "===================="
echo -e "${GREEN}Passed: $PASSED${NC}"
echo -e "${YELLOW}Warnings: $WARNINGS${NC}"
echo -e "${RED}Failed: $FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✅ All critical validations passed!${NC}"
    echo ""
    echo "🎉 Your JOTP CI/CD workflows are ready to use!"
    echo ""
    echo "📖 Next steps:"
    echo "1. Configure required secrets in GitHub repository settings"
    echo "2. Review WORKFLOWS-GUIDE.md for detailed documentation"
    echo "3. Test workflows with Act or GitHub Actions"
    echo "4. Push changes to trigger CI pipeline"
    echo ""
    echo "🔑 Required secrets:"
    echo "- CENTRAL_USERNAME"
    echo "- CENTRAL_TOKEN"
    echo "- GPG_PRIVATE_KEY"
    echo "- GPG_PASSPHRASE"
    echo "- GPG_KEY_ID"
    echo ""
    exit 0
else
    echo -e "${RED}❌ Validation failed! Please fix the errors above.${NC}"
    echo ""
    echo "📖 Troubleshooting:"
    echo "1. Ensure all workflow files are present"
    echo "2. Check YAML syntax in workflow files"
    echo "3. Verify Maven plugins are configured in pom.xml"
    echo "4. Review workflow structure and job definitions"
    echo ""
    exit 1
fi
