#!/bin/bash
# Quick start script for Act testing JOTP

echo "🚀 Act Quick Start for JOTP"
echo "=========================="

# The error you got: "Could not find any stages to run"
# This means Act can't read the workflow file properly

echo "Troubleshooting steps:"

echo "1. Check if workflow file exists:"
if [ -f ".github/workflows/publish.yml" ]; then
    echo "   ✅ .github/workflows/publish.yml exists"
else
    echo "   ❌ .github/workflows/publish.yml not found"
    exit 1
fi

echo ""
echo "2. Try running Act with explicit workflow:"
act --list -W .github/workflows/publish.yml

echo ""
echo "3. If that fails, try with specific event:"
act --list -W .github/workflows/publish.yml --event push

echo ""
echo "4. Run with dry-run (recommended first):"
act --dryrun -W .github/workflows/publish.yml

echo ""
echo "5. If still issues, check workflow syntax:"
echo "   - Make sure YAML is properly formatted"
echo "   - Check for missing indents"
echo "   - Verify job names are correct"

echo ""
echo "6. Quick test commands:"
echo "   # Test build job"
echo "   act -W .github/workflows/publish.yml -j build"
echo ""
echo "   # Test test job"
echo "   act -W .github/workflows/publish.yml -j test"
echo ""
echo "   # Full test (if everything works)"
echo "   act -W .github/workflows/publish.yml"

echo ""
echo "7. For Apple M1/M2 chips, you might need:"
echo "   act --container-architecture linux/amd64 -W .github/workflows/publish.yml"