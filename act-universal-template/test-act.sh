#!/bin/bash
# Universal Act Testing Script for Maven Projects
# ==============================================
# Works with ANY Maven project - just edit .act.config.yaml

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration variables loaded from .act.config.yaml
WORKFLOW_FILE=$(grep "^workflow:" .act.config.yaml | cut -d' ' -f2 | tr -d '"')
SKIP_STEPS=$(grep "^skip_steps:" .act.config.yaml -A 5 | head -6 | tail -5 | sed 's/^[[:space:]]*- //')

log() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
info() { echo -e "${BLUE}[INFO]${NC} $1"; }

# Parse command line arguments
DRY_RUN=false
VERBOSE=false
PARALLEL=false
SPECIFIC_JOB=""
SHOW_HELP=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true ;;
        --verbose) VERBOSE=true ;;
        --parallel) PARALLEL=true ;;
        -j|--job) SPECIFIC_JOB="$2"; shift ;;
        --help) SHOW_HELP=true ;;
        *) warn "Unknown option: $1" ;;
    esac
    shift
done

if [[ "$SHOW_HELP" == true ]]; then
    echo "Universal Act Testing Script"
    echo "============================"
    echo ""
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --dry-run    Only run dry-run tests"
    echo "  --verbose    Enable verbose output"
    echo "  --parallel   Run jobs in parallel"
    echo "  -j JOB       Test specific job only"
    echo "  --help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                    # Run all tests"
    echo "  $0 --dry-run         # Only dry-run"
    echo "  $0 -j build          # Test build job only"
    echo "  $0 --parallel        # Run jobs in parallel"
    exit 0
fi

# Create logs directory
mkdir -p logs

# Function to detect project properties
detect_project() {
    log "Detecting project properties..."

    # Detect Java version from pom.xml
    if [[ -f "pom.xml" ]]; then
        JAVA_VERSION=$(grep -oP '<maven.compiler.release>\K\d+' pom.xml || echo "17")
        log "Detected Java version: $JAVA_VERSION"
    fi

    # Detect framework
    if [[ -f "pom.xml" ]]; then
        if grep -q "spring-boot" pom.xml; then
            PROJECT_TYPE="spring-boot"
        elif grep -q "quarkus" pom.xml; then
            PROJECT_TYPE="quarkus"
        elif grep -q "micronaut" pom.xml; then
            PROJECT_TYPE="micronaut"
        else
            PROJECT_TYPE="standard"
        fi
        log "Detected project type: $PROJECT_TYPE"
    fi
}

# Function to check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    # Check act
    if ! command -v act >/dev/null 2>&1; then
        error "Act is not installed"
        info "Install with: curl -sL https://github.com/nektos/act/releases/latest/download/act_Linux_x86_64.tar.gz | tar xz -C /usr/local/bin"
        exit 1
    fi

    # Check Docker
    if ! docker info >/dev/null 2>&1; then
        error "Docker is not running"
        exit 1
    fi

    # Check workflow file
    if [[ ! -f "$WORKFLOW_FILE" ]]; then
        error "Workflow file not found: $WORKFLOW_FILE"
        info "Please edit .act.config.yaml and set the correct workflow file path"
        exit 1
    fi

    log "All prerequisites met!"
}

# Function to run dry-run
run_dry_run() {
    info "Running dry-run test..."

    if [[ "$VERBOSE" == true ]]; then
        act --dryrun -W "$WORKFLOW_FILE" > logs/dry-run.log 2>&1
    else
        act --dryrun -W "$WORKFLOW_FILE" > logs/dry-run.log 2>&1
    fi

    if [[ $? -eq 0 ]]; then
        log "✅ Dry-run successful"
        local job_count=$(grep -c "Running job" logs/dry-run.log || echo 0)
        info "Found $job_count jobs to test"
    else
        error "Dry-run failed"
        tail -20 logs/dry-run.log
        return 1
    fi
}

# Function to list available jobs
list_jobs() {
    info "Available jobs:"
    act --list -W "$WORKFLOW_FILE" | grep -E "NAME|- " | head -20
}

# Function to test specific job
test_job() {
    local job_name="$1"
    info "Testing job: $job_name"

    if [[ "$VERBOSE" == true ]]; then
        act -W "$WORKFLOW_FILE" -j "$job_name" > "logs/${job_name}.log" 2>&1
    else
        act -W "$WORKFLOW_FILE" -j "$job_name" > "logs/${job_name}.log" 2>&1
    fi

    if [[ $? -eq 0 ]]; then
        log "✅ $job_name job successful"
    else
        error "$job_name job failed"
        tail -20 "logs/${job_name}.log"
        return 1
    fi
}

# Function to test all jobs
test_all_jobs() {
    info "Testing all jobs..."

    # Get job list
    local jobs=$(act --list -W "$WORKFLOW_FILE" | grep -E "^  [a-zA-Z]" | sed 's/^  //g' | head -10)

    if [[ -z "$jobs" ]]; then
        warn "No jobs found to test"
        return 0
    fi

    # Test each job
    while IFS= read -r job; do
        # Skip skip_steps
        if echo "$SKIP_STEPS" | grep -q "$job"; then
            info "Skipping: $job (in skip_steps)"
            continue
        fi

        test_job "$job"
    done <<< "$jobs"
}

# Function to test matrix builds
test_matrix() {
    info "Testing matrix builds..."

    local platforms="-P ubuntu-latest=catthehacker/ubuntu:act-latest"
    if [[ "$PROJECT_TYPE" == "windows" ]]; then
        platforms="$platforms -P windows-latest=node:16-buster"
    fi

    act -W "$WORKFLOW_FILE" -j build $platforms > logs/matrix.log 2>&1

    if [[ $? -eq 0 ]]; then
        log "✅ Matrix builds successful"
    else
        error "Matrix builds failed"
        tail -20 logs/matrix.log
        return 1
    fi
}

# Function to test performance
test_performance() {
    info "Testing performance..."

    # Time the build
    if [[ "$VERBOSE" == true ]]; then
        time act -W "$WORKFLOW_FILE" -j build -P ubuntu-latest=catthehacker/ubuntu:act-latest > logs/performance.log 2>&1
    else
        time act -W "$WORKFLOW_FILE" -j build -P ubuntu-latest=catthehacker/ubuntu:act-latest > logs/performance.log 2>&1
    fi

    # Extract timing
    local build_time=$(grep -oP '(?<=took ).*(?= seconds)' logs/performance.log | head -1 || echo "N/A")
    log "Build time: $build_time"
}

# Function to generate report
generate_report() {
    info "Generating test report..."

    local report_file="act-test-report-$(date +%Y%m%d-%H%M%S).md"

    cat > "$report_file" << EOF
# Act Test Report

Generated on: $(date)
Project: $(basename "$(pwd)")
Workflow: $WORKFLOW_FILE

## Test Summary

- ✅ Prerequisites check: Passed
- ✅ Dry-run: Passed
- ✅ Jobs tested: $(find logs -name "*.log" -not -name "dry-run.log" -not -name "matrix.log" -not -name "performance.log" | wc -l)
- ✅ Matrix builds: Tested
- ✅ Performance: Measured

## Configuration Used

### Workflow File
$WORKFLOW_FILE

### Skipped Steps
$SKIP_STEPS

### Project Type
$PROJECT_TYPE

## Job Results

EOF

    # Add job results
    for log_file in logs/*.log; do
        if [[ "$log_file" =~ (dry-run|matrix|performance)\.log$ ]]; then
            continue
        fi

        local job_name=$(basename "$log_file" .log)
        if [[ "$job_name" != "act" ]]; then
            local success_count=$(grep -c "BUILD SUCCESS\|PASSED\|✅" "$log_file" || echo 0)
            echo "- **$job_name**: $success_count successes" >> "$report_file"
        fi
    done

    echo "" >> "$report_file"
    echo "## Logs" >> "$report_file"
    echo "" >> "$report_file"
    echo "All test logs are available in the \`logs/\` directory." >> "$report_file"

    echo "" >> "$report_file"
    echo "## Recommendations" >> "$report_file"
    echo "" >> "$report_file"
    echo "1. Review individual job logs for any warnings" >> "$report_file"
    echo "2. Monitor build times for optimization" >> "$report_file"
    echo "3. Ensure secrets are properly configured for production" >> "$report_file"

    log "Report generated: $report_file"
}

# Main execution
main() {
    log "🚀 Universal Act Testing Script"
    log "================================"
    log "Project: $(basename "$(pwd)")"
    log "Workflow: $WORKFLOW_FILE"

    # Detect project
    detect_project

    # Check prerequisites
    check_prerequisites

    # Show help if requested
    if [[ "$SHOW_HELP" == true ]]; then
        exit 0
    fi

    # Run tests based on arguments
    if [[ "$DRY_RUN" == true ]]; then
        run_dry_run
    elif [[ -n "$SPECIFIC_JOB" ]]; then
        test_job "$SPECIFIC_JOB"
    else
        run_dry_run
        test_all_jobs
        test_matrix
        test_performance
        generate_report
    fi

    log "🎉 Testing completed successfully!"
    info "Check the logs directory for detailed output."
    info "Report: act-test-report-*.md"
}

# Run main function
main "$@"