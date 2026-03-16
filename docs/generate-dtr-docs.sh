#!/bin/bash
# DTR Documentation Generation Script for JOTP
# Generates living documentation from tests in multiple formats

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Project root
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Output directories
DOC_OUTPUT_DIR="$PROJECT_ROOT/target/site/doctester"
DTR_OUTPUT_DIR="$PROJECT_ROOT/target/dtr-docs"
MARKDOWN_DIR="$DTR_OUTPUT_DIR/markdown"
HTML_DIR="$DTR_OUTPUT_DIR/html"
LATEX_DIR="$DTR_OUTPUT_DIR/latex"
REVEALJS_DIR="$DTR_OUTPUT_DIR/revealjs"
JSON_DIR="$DTR_OUTPUT_DIR/json"

echo -e "${GREEN}=== JOTP DTR Documentation Generation ===${NC}"
echo ""
echo "This script generates living documentation from DTR-annotated tests."
echo ""

# Create output directories
echo -e "${YELLOW}Creating output directories...${NC}"
mkdir -p "$DOC_OUTPUT_DIR"
mkdir -p "$MARKDOWN_DIR"
mkdir -p "$HTML_DIR"
mkdir -p "$LATEX_DIR"
mkdir -p "$REVEALJS_DIR"
mkdir -p "$JSON_DIR"

# Check if we need to compile first
echo -e "${YELLOW}Checking if compilation is needed...${NC}"
if [ ! -d "$PROJECT_ROOT/target/test-classes" ]; then
    echo -e "${YELLOW}Compiling test classes...${NC}"
    ./mvnw test-compile -Dspotless.check.skip=true -q
fi

# Run DocTestExtension-based tests (currently working ones)
echo -e "${YELLOW}Running DocTestExtension-based documentation tests...${NC}"

# List of working DocTest classes
DOCTEST_CLASSES=(
    "io.github.seanchatmangpt.jotp.doctest.ProcDocIT"
    "io.github.seanchatmangpt.jotp.doctest.SupervisorDocIT"
    "io.github.seanchatmangpt.jotp.doctest.MessageBusDocIT"
    "io.github.seanchatmangpt.jotp.doctest.ReactiveChannelDocIT"
)

# Run each doctest class
for test_class in "${DOCTEST_CLASSES[@]}"; do
    class_name=$(basename "$test_class")
    echo -e "  Running $class_name..."

    if ./mvnw test -Dtest="$class_name" -Dspotless.check.skip=true -q; then
        echo -e "    ${GREEN}✓${NC} $class_name documentation generated"
    else
        echo -e "    ${RED}✗${NC} $class_name failed (may have compilation issues)"
    fi
done

echo ""
echo -e "${YELLOW}DocTestExtension HTML output location:${NC}"
echo "  $DOC_OUTPUT_DIR"
echo ""

# Check if any HTML was generated
if [ -f "$DOC_OUTPUT_DIR/index.html" ]; then
    echo -e "${GREEN}✓ Documentation index generated: $DOC_OUTPUT_DIR/index.html${NC}"

    # Count generated HTML files
    html_count=$(find "$DOC_OUTPUT_DIR" -name "*.html" -type f | wc -l)
    echo -e "${GREEN}✓ Total HTML files generated: $html_count${NC}"
else
    echo -e "${YELLOW}⚠ No documentation index found. Some tests may have failed.${NC}"
fi

# Future: Multi-format output support
echo ""
echo -e "${YELLOW}=== Future Multi-Format Support ===${NC}"
echo "The following output formats are planned for DTR integration:"
echo ""
echo "  • Markdown: $MARKDOWN_DIR"
echo "  • HTML:     $HTML_DIR"
echo "  • LaTeX:    $LATEX_DIR"
echo "  • Reveal.js: $REVEALJS_DIR"
echo "  • JSON:     $JSON_DIR"
echo ""
echo "Note: Multi-format output requires DTR RenderMachine configuration."
echo "Currently, DocTestExtension generates HTML documentation."
echo ""

# Copy generated docs to user-guide if requested
if [ "$1" == "--publish" ]; then
    echo -e "${YELLOW}Publishing documentation to user-guide...${NC}"
    USER_GUIDE_DIR="$PROJECT_ROOT/docs/user-guide/output"

    mkdir -p "$USER_GUIDE_DIR"
    if [ -d "$DOC_OUTPUT_DIR" ]; then
        cp -r "$DOC_OUTPUT_DIR" "$USER_GUIDE_DIR/html"
        echo -e "${GREEN}✓ Published to: $USER_GUIDE_DIR/html${NC}"
    fi
fi

echo ""
echo -e "${GREEN}=== Documentation Generation Complete ===${NC}"
echo ""
echo "To view the documentation:"
echo "  1. Open: file://$DOC_OUTPUT_DIR/index.html"
echo "  2. Or run: make docs-serve (if configured)"
echo ""
echo "To generate CI/CD documentation:"
echo "  ./docs/generate-dtr-docs.sh --publish"
echo ""
