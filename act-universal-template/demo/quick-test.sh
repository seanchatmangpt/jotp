#!/bin/bash

# Quick Test Script - Universal Act Template
# ==========================================
# Quickly test Act with the universal template

set -e

echo "🧪 Quick Act Test - Universal Template"
echo "====================================="

# Check if we're in a Maven project
if [ ! -f "pom.xml" ]; then
    echo "❌ Error: Not in a Maven project (pom.xml not found)"
    echo ""
    echo "📝 To set up a demo project:"
    echo "   cd act-universal-template"
    echo "   ./demo/setup-demo.sh"
    echo "   cd demo-app"
    echo "   source ../test-act.sh"
    exit 1
fi

# Check prerequisites
echo "🔍 Checking prerequisites..."

if ! command -v act &> /dev/null; then
    echo "❌ Act not found. Installing..."
    # Try to install Act if not found
    if command -v curl &> /dev/null; then
        curl -s https://raw.githubusercontent.com/nektos/act/master/install.sh | bash
    else
        echo "❌ Please install Act first: https://github.com/nektos/act"
        exit 1
    fi
fi

if ! command -v mvn &> /dev/null; then
    echo "❌ Maven not found. Please install Java and Maven."
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "1\.\d+' | cut -d'"' -f2 | cut -d'.' -f2)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "⚠️  Warning: Java version may be too old. Java 17+ recommended."
fi

echo "✅ Prerequisites OK"
echo ""

# Build the project
echo "🏗️  Building project..."
mvn clean compile -q
echo "✅ Build successful"
echo ""

# Run Act with dry run
echo "🔄 Running Act dry run..."
act --dryrun -W .github/workflows/ci.yml -v
echo "✅ Act dry run completed"
echo ""

# Test workflow if it exists
if [ -f ".github/workflows/ci.yml" ]; then
    echo "📋 Testing specific jobs..."

    # Test build job
    if act --list -W .github/workflows/ci.yml | grep -q "build"; then
        echo "🏗️  Testing build job..."
        act -W .github/workflows/ci.yml -j build --dryrun -v
    fi

    # Test test job
    if act --list -W .github/workflows/ci.yml | grep -q "test"; then
        echo "🧪 Testing test job..."
        act -W .github/workflows/ci.yml -j test --dryrun -v
    fi
fi

echo ""
echo "🎉 Quick test completed!"
echo ""
echo "📝 Next steps:"
echo "   1. Update .act.config.yaml for your project type"
echo "   2. Set up secrets in .secrets"
echo "   3. Run full test: source test-act.sh"
echo "   4. For CI testing: act -W .github/workflows/ci.yml"