# JOTP Benchmark Reproduction Guide

**Version:** 1.0
**Last Updated:** 2026-03-16
**Purpose:** Step-by-step instructions to reproduce all published JOTP performance benchmarks

---

## Quick Start (5 Minutes)

```bash
# 1. Clone repository
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp

# 2. Install Java 26 (macOS)
brew install --cask graalvm-jdk-26

# 3. Run quick benchmark
make benchmark-quick

# 4. View results
cat target/benchmark-results.json | jq '.[0].primaryMetric.score'
```

**Expected Results (Quick Run):**
- Actor tell() throughput: > 1M ops/s
- Process creation: < 100 µs
- Supervisor restart: < 1 ms

---

## Part 1: Environment Validation

### Minimum Requirements Checklist

Before running benchmarks, verify your environment meets these minimums:

```bash
#!/bin/bash
# scripts/validate-environment.sh

echo "=== JOTP Benchmark Environment Validation ==="

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
echo "Java Version: $JAVA_VERSION"
if [[ "$JAVA_VERSION" < "26" ]]; then
    echo "❌ FAIL: Java 26+ required"
    exit 1
fi
echo "✅ PASS: Java $JAVA_VERSION"

# Check preview support
if java --help 2>&1 | grep -q -- "--enable-preview"; then
    echo "✅ PASS: Preview features supported"
else
    echo "❌ FAIL: --enable-preview not available"
    exit 1
fi

# Check available RAM (macOS)
if [[ "$OSTYPE" == "darwin"* ]]; then
    RAM_GB=$(echo "$(sysctl -n hw.memsize) / 1024 / 1024 / 1024" | bc)
    echo "Available RAM: ${RAM_GB}GB"
    if (( $(echo "$RAM_GB < 8" | bc -l) )); then
        echo "❌ FAIL: Minimum 8GB RAM required"
        exit 1
    fi
    echo "✅ PASS: Sufficient RAM"
fi

# Check CPU cores
CORES=$(sysctl -n hw.physicalcpu)
echo "Physical Cores: $CORES"
if [ "$CORES" -lt 4 ]; then
    echo "⚠️  WARNING: Minimum 4 cores recommended for full suite"
fi

# Check Maven
if command -v mvnd &> /dev/null; then
    MVN_VERSION=$(mvnd --version | head -1)
    echo "✅ Maven Daemon: $MVN_VERSION"
elif command -v mvn &> /dev/null; then
    MVN_VERSION=$(mvn --version | head -1)
    echo "✅ Maven: $MVN_VERSION"
else
    echo "❌ FAIL: Maven not installed"
    exit 1
fi

echo "=== Environment Validation Complete ==="
```

**Run validation:**
```bash
chmod +x scripts/validate-environment.sh
./scripts/validate-environment.sh
```

### Hardware Tier Comparison

| Benchmark Category | Minimum | Recommended | Optimal (Published) |
|--------------------|---------|-------------|---------------------|
| **Core Primitives** | M1 / 8GB / 4 cores | M2 / 16GB / 8 cores | **M3 Max / 48GB / 16 cores** |
| **Enterprise Patterns** | M2 Pro / 16GB / 8 cores | M3 / 32GB / 12 cores | **M3 Max / 48GB / 16 cores** |
| **Stress Tests (1M)** | M3 / 32GB / 12 cores | M3 Max / 48GB / 16 cores | **M3 Max / 64GB / 16 cores** |

---

## Part 2: Build and Setup

### Initial Project Setup

```bash
# 1. Clone repository (specific commit for reproducibility)
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp
git checkout <commit-hash>  # Optional: Pin to specific version

# 2. Install Java 26
# macOS
brew install --cask graalvm-jdk-26
sudo ln -s /Library/Java/JavaVirtualMachines/graalvm-jdk-26.jdk /Library/Java/JavaVirtualMachines/default

# Linux
wget https://download.oracle.com/java/26/latest/jdk-26_linux-x64_bin.tar.gz
tar -xzf jdk-26_linux-x64_bin.tar.gz
export JAVA_HOME=$(pwd)/jdk-26
export PATH=$JAVA_HOME/bin:$PATH

# 3. Install Maven Daemon (optional but recommended)
# macOS
brew install mvnd

# Linux
wget https://github.com/apache/maven-mvnd/releases/download/1.0-m2/mvnd-1.0-m2-linux-amd64.tar.gz
tar -xzf mvnd-1.0-m2-linux-amd64.tar.gz
export PATH=$PATH:$(pwd)/mvnd-1.0-m2-linux-amd64/bin

# 4. Verify installations
java -version    # Should be 26+
mvnd --version   # Should be 4.x
```

### Build Project

```bash
# Clean build (first time)
./mvnw clean compile -T1C --enable-preview

# Quick test run (verify environment)
./mvnw test -T1C -Dtest=ProcTest

# Full test suite (optional, 5-10 minutes)
./mvnw test -T1C
```

---

## Part 3: Core Primitive Benchmarks

### Reproduce Core Primitives

**Target Results:**
- Proc.tell() p95: < 1 µs (published: 458 ns)
- Proc.ask() p99: < 100 µs
- Supervisor restart: < 1 ms (published: 500 µs)
- EventManager.notify() p95: < 1 µs (published: 583 ns)

#### Option A: Quick Run (2 minutes)

```bash
make benchmark-quick

# Equivalent to:
./mvnw verify -Dbenchmark \
  -Dexec.args="-f 1 -wi 1 -i 2 -rff target/benchmark-results.json -rf json"
```

**Configuration:**
- Forks: 1
- Warmup iterations: 1
- Measurement iterations: 2
- Total time: ~2 minutes

#### Option B: Production Run (8 minutes)

```bash
make benchmark

# Equivalent to:
./mvnw verify -Dbenchmark \
  -Dexec.args="-f 1 -wi 3 -i 5 -rff target/benchmark-results.json -rf json"
```

**Configuration:**
- Forks: 1
- Warmup iterations: 3
- Measurement iterations: 5
- Total time: ~8 minutes

#### Option C: Full Run (15 minutes)

```bash
make benchmark-full

# Equivalent to:
./mvnw verify -Dbenchmark \
  -Dexec.args="-f 3 -wi 5 -i 10 -rff target/benchmark-results.json -rf json"
```

**Configuration:**
- Forks: 3 (3 separate JVM runs)
- Warmup iterations: 5
- Measurement iterations: 10
- Total time: ~15 minutes

### View Core Primitive Results

```bash
# View raw JSON
cat target/benchmark-results.json | jq '.[] | select(.benchmark | contains("tell")) | .primaryMetric'

# Extract specific metrics
cat target/benchmark-results.json | jq '.[] | select(.benchmark | contains("ActorBenchmark")) | {
  benchmark: .benchmark,
  score: .primaryMetric.score,
  unit: .primaryMetric.scoreUnit,
  mode: .mode
}'

# Generate human-readable report
cat target/benchmark-results.json | jq -r '
  "=== JOTP Benchmark Results ===\n",
  "Generated: \(now | localtime | todate)",
  "",
  "## Core Primitives\n",
  .[] | select(.benchmark | contains("ActorBenchmark") or contains("Supervisor")) |
    "\n### \(.benchmark | split(".") | .[1])\n",
    "Score: \(.primaryMetric.score) ± \(.primaryMetric.scoreError) \(.primaryMetric.scoreUnit)\n",
    "Mode: \(.mode)\n",
    "Forks: \(.forks)\n"
'
```

---

## Part 4: Enterprise Pattern Benchmarks

### Reproduce Enterprise Patterns

**Target Results:**
- Message Channel: > 1M msg/s (published: 30.1M msg/s)
- Event Fanout: > 1M deliveries/s (published: 1.1B deliveries/s)
- Request-Reply: > 10K rt/s (published: 78K rt/s)

#### Run Pattern Benchmarks

```bash
# Run all pattern benchmarks
./mvnw test -Dtest=PatternBenchmarkSuite -Dbenchmark

# Run specific pattern benchmarks
./mvnw test -Dtest=ActorBenchmark -Dbenchmark
./mvnw test -Dtest=ResultBenchmark -Dbenchmark
./mvnw test -Dtest=ParallelBenchmark -Dbenchmark
```

### Custom JMH Options

```bash
# Increase warmup for more stable results
./mvnw test -Dtest=PatternBenchmarkSuite -Dbenchmark \
  -Dexec.args="-f 1 -wi 10 -i 5 -rff target/pattern-results.json"

# Run multiple forks for better statistics
./mvnw test -Dtest=PatternBenchmarkSuite -Dbenchmark \
  -Dexec.args="-f 5 -wi 3 -i 3 -rff target/pattern-results.json"

# Longer measurement iterations (reduce variance)
./mvnw test -Dtest=PatternBenchmarkSuite -Dbenchmark \
  -Dexec.args="-f 1 -wi 5 -i 20 -rff target/pattern-results.json"
```

---

## Part 5: Stress Test Benchmarks

### Reproduce 1M Process Tests

**Target Results:**
- Zero message loss
- Supervisor survives 100K crashes
- Memory footprint: ~1.2KB/process

#### Run Stress Tests

```bash
# Acquisitions stress test
./mvnw test -Dtest=AcquisitionSupervisorStressTest

# Registry race test
./mvnw test -Dtest=RegistryRaceStressTest

# Link cascade test
./mvnw test -Dtest=LinkCascadeStressTest

# Pattern stress test
./mvnw test -Dtest=PatternStressTest

# Process memory analysis
./mvnw test -Dtest=ProcessMemoryAnalysisTest
```

#### Increase JVM Heap for Stress Tests

```bash
# Run with 32GB heap
export MAVEN_OPTS="-Xmx32g -Xms16g"
./mvnw test -Dtest=ProcessMemoryAnalysisTest

# Run with GC logging
export MAVEN_OPTS="-Xmx32g -Xms16g -Xlog:gc*:file=gc.log:time,uptime,level,tags"
./mvnw test -Dtest=ProcessMemoryAnalysisTest
```

---

## Part 6: Automated Validation

### Benchmark Validation Script

```bash
#!/bin/bash
# scripts/validate-benchmarks.sh

set -e

echo "=== JOTP Benchmark Validation ==="

# Define expected ranges
declare -A EXPECTED=(
    ["tell_latency_p95"]="0.000001"    # < 1 µs
    ["ask_latency_p99"]="0.0001"       # < 100 µs
    ["supervisor_restart"]="0.001"     # < 1 ms
    ["event_notify_p95"]="0.000001"    # < 1 µs
)

# Run benchmarks
echo "Running benchmarks..."
make benchmark > /dev/null 2>&1

# Extract results
RESULTS_FILE="target/benchmark-results.json"

if [ ! -f "$RESULTS_FILE" ]; then
    echo "❌ FAIL: Benchmark results not found"
    exit 1
fi

# Validate results (simplified)
echo "Validating results against expectations..."

# Example: Check tell() latency
TELL_SCORE=$(jq -r '.[] | select(.benchmark | contains("tell_throughput")) | .primaryMetric.score' "$RESULTS_FILE")
echo "tell() throughput: $TELL_SCORE ops/s"

if (( $(echo "$TELL_SCORE > 1000000" | bc -l) )); then
    echo "✅ PASS: tell() throughput > 1M ops/s"
else
    echo "❌ FAIL: tell() throughput below 1M ops/s"
    exit 1
fi

echo "=== Validation Complete ==="
```

**Run validation:**
```bash
chmod +x scripts/validate-benchmarks.sh
./scripts/validate-benchmarks.sh
```

---

## Part 7: Performance Normalization

### Cross-Platform Comparison

**When comparing results across different hardware:**

```python
# scripts/normalize-benchmark-results.py

import json
import sys

# Baseline: M3 Max (16 cores, 48GB RAM)
BASELINE_SCORES_PER_GHZ = {
    "tell_throughput": 2000000,  # ops/sec per GHz
    "ask_latency": 50,            # ns per GHz
    "supervisor_restart": 100,    # µs per GHz
}

def get_cpu_freq_ghz():
    """Detect CPU frequency in GHz (platform-specific)"""
    import subprocess
    if sys.platform == "darwin":
        result = subprocess.run(["sysctl", "-n", "hw.cpufrequency"], capture_output=True)
        return int(result.stdout) / 1e9
    elif sys.platform.startswith("linux"):
        with open("/proc/cpuinfo") as f:
            for line in f:
                if "MHz" in line:
                    return float(line.split(":")[1].strip()) / 1000
    return None

def normalize_results(results_file, target_freq):
    """Normalize benchmark results to target CPU frequency"""
    with open(results_file) as f:
        results = json.load(f)

    current_freq = get_cpu_freq_ghz()
    if not current_freq:
        print("Cannot detect CPU frequency")
        return

    ratio = target_freq / current_freq

    print(f"CPU Frequency Normalization:")
    print(f"  Current: {current_freq:.2f} GHz")
    print(f"  Target: {target_freq:.2f} GHz")
    print(f"  Ratio: {ratio:.2f}x")

    for benchmark in results:
        name = benchmark["benchmark"]
        if "tell" in name.lower():
            original = benchmark["primaryMetric"]["score"]
            normalized = original * ratio
            print(f"  {name}: {original:.0f} → {normalized:.0f} ops/s")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python normalize-benchmark-results.py <results.json> [target_freq_ghz]")
        sys.exit(1)

    results_file = sys.argv[1]
    target_freq = float(sys.argv[2]) if len(sys.argv) > 2 else 4.05  # M3 Max base freq
    normalize_results(results_file, target_freq)
```

**Normalize results:**
```bash
# Normalize to 4.05 GHz (M3 Max base frequency)
python scripts/normalize-benchmark-results.py target/benchmark-results.json 4.05
```

---

## Part 8: Troubleshooting

### Common Issues and Solutions

#### Issue 1: Java 26 Not Found

**Error:**
```
java: invalid target release: 26
```

**Solution:**
```bash
# Verify Java installation
java -version

# Set JAVA_HOME explicitly
export JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-jdk-26.jdk/Contents/Home

# Add to PATH
export PATH=$JAVA_HOME/bin:$PATH

# Verify again
java -version  # Should show 26+
```

#### Issue 2: --enable-preview Not Supported

**Error:**
```
error: preview feature is not supported
```

**Solution:**
```bash
# Ensure Java 26+ (preview features mandatory in 26)
java -version  # Must be 26 or higher

# Add --enable-preview to Maven config
export MAVEN_OPTS="--enable-preview"

# Or update pom.xml (already configured)
<argLine>--enable-preview</argLine>
```

#### Issue 3: High Variance in Results

**Symptom:** CV > 10% across multiple runs

**Solutions:**
```bash
# 1. Close all other applications
# 2. Prevent sleep during benchmarks
caffeinate -dims make benchmark-full

# 3. Increase warmup and measurement iterations
make benchmark-full  # Uses 5 warmup, 10 measurement iterations

# 4. Disable CPU scaling (macOS)
sudo pmset -c disablesleep 1
sudo powermetrics --samplers cpu_power -i 1000

# 5. Run during off-peak hours
# 6. Check for thermal throttling
sudo powermetrics --samplers thermal -n 1
```

#### Issue 4: OutOfMemoryError

**Error:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size
export MAVEN_OPTS="-Xmx32g -Xms16g"

# Run with GC logging to diagnose
export MAVEN_OPTS="-Xmx32g -Xms16g -Xlog:gc*:file=gc.log"
./mvnw test -Dtest=ProcessMemoryAnalysisTest

# Analyze GC log
cat gc.log | grep "Full GC"
```

#### Issue 5: Slower Performance on Intel Mac

**Symptom:** Benchmarks run 2-3× slower than published results

**Cause:** ARM64-specific optimizations in Java 26

**Solution:**
```bash
# Results are not directly comparable across architectures
# Use normalized comparison:

# Get CPU frequency
sysctl -n hw.cpufrequency  # macOS
cat /proc/cpuinfo | grep "MHz"  # Linux

# Normalize by CPU frequency when comparing
python scripts/normalize-benchmark-results.py target/benchmark-results.json
```

---

## Part 9: Advanced Profiling

### Profile JVM During Benchmarks

```bash
# Run with Java Flight Recorder
export MAVEN_OPTS="-XX:StartFlightRecording=filename=benchmark-recording.jfr,duration=60s"
./mvnw test -Dtest=PatternBenchmarkSuite -Dbenchmark

# Analyze recording
jfr2summary benchmark-recording.jfr

# Run with async-profiler (Linux)
git clone https://github.com/jvm-profiling-tools/async-profiler.git
cd async-profiler
make
./profiler.sh -d 30 -f profile.svg -i 1ms $(pgrep -f PatternBenchmarkSuite)
```

### CPU Profiling

```bash
# macOS Instruments
# 1. Open Instruments
# 2. Select "Time Profiler"
# 3. Target: Java process
# 4. Record during benchmark
# 5. Analyze hotspots

# Linux perf
perf record -F 99 -p $(pgrep -f PatternBenchmarkSuite) -g -- sleep 60
perf report
```

### Memory Profiling

```bash
# Heap dump after benchmark
jmap -dump:format=b,file=heap.hprof $(pgrep -f PatternBenchmarkSuite)

# Analyze with VisualVM
visualvm --openfile heap.hprof

# Or with Eclipse MAT
MemoryAnalyzer -vmargs -Xmx8g -heap heap.hprof
```

---

## Part 10: CI/CD Integration

### GitHub Actions Benchmark Workflow

```yaml
# .github/workflows/benchmark.yml
name: Performance Benchmarks

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:

jobs:
  benchmark:
    runs-on: macos-latest  # Requires Apple Silicon for comparable results

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 26
        uses: actions/setup-java@v4
        with:
          distribution: 'oracle'
          java-version: '26'

      - name: Cache Maven packages
        uses: actions/cache@v3
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}

      - name: Run benchmarks
        run: |
          make benchmark-full
          cp target/benchmark-results.json benchmark-results-${{ github.sha }}.json

      - name: Upload benchmark results
        uses: actions/upload-artifact@v3
        with:
          name: benchmark-results
          path: benchmark-results-${{ github.sha }}.json

      - name: Comment PR with results
        if: github.event_name == 'pull_request'
        uses: actions/github-script@v7
        with:
          script: |
            const fs = require('fs');
            const results = JSON.parse(fs.readFileSync('benchmark-results-${{ github.sha }}.json', 'utf8'));
            const tellScore = results.find(r => r.benchmark.includes('tell_throughput'));
            if (tellScore) {
              github.rest.issues.createComment({
                issue_number: context.issue.number,
                owner: context.repo.owner,
                repo: context.repo.repo,
                body: `## Benchmark Results\n\n**tell() throughput:** ${tellScore.primaryMetric.score.toFixed(0)} ops/s`
              });
            }
```

---

## Appendix: Quick Reference

### Benchmark Commands

```bash
# Quick (2 min)
make benchmark-quick

# Production (8 min)
make benchmark

# Full (15 min)
make benchmark-full

# Specific test
./mvnw test -Dtest=ActorBenchmark -Dbenchmark

# With custom options
./mvnw verify -Dbenchmark \
  -Dexec.args="-f 3 -wi 5 -i 10 -rff results.json -rf json"
```

### Result Analysis

```bash
# View results
cat target/benchmark-results.json | jq '.'

# Extract scores
cat target/benchmark-results.json | jq '.[] | .primaryMetric.score'

# Compare runs
diff results-run1.json results-run2.json

# Generate report
python scripts/benchmark-report.py --input=target/benchmark-results.json
```

### Environment Check

```bash
# Validate environment
./scripts/validate-environment.sh

# Check Java version
java -version

# Check Maven version
mvnd --version

# Check system specs
sysctl -n hw.memsize hw.physicalcpu hw.logicalcpu
```

---

**Document Status:** ✅ COMPLETE
**Next Update:** After new benchmark categories added
**Related Documents:**
- `/docs/validation/performance/test-environment-specs.md` (environment documentation)
- `/docs/JOTP-PERFORMANCE-REPORT.md` (published results)
- `/Makefile` (benchmark execution)

---

**END OF DOCUMENT**
