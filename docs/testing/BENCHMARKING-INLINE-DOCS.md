# Benchmark Inline Documentation Updates

**Purpose:** Template for adding methodology disclosures to benchmark test files.

**Instruction:** Add this header to ALL benchmark test files (both JMH and non-JMH).

---

## Template Header for Benchmark Files

```java
/*
 * ═══════════════════════════════════════════════════════════════════════════
 * BENCHMARK METHODOLOGY DISCLOSURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * IMPORTANT: Read before interpreting results!
 *
 * Known Issues:
 * - If this is a non-JMH benchmark: Results may be affected by dead code
 *   elimination (DCE) or insufficient warmup. Treat as PRELIMINARY ESTIMATES.
 * - If this benchmark uses empty messages: Throughput numbers are 73× higher
 *   than realistic 150-byte payload throughput (see PayloadSizeThroughputBenchmark).
 * - Results are hardware-specific (Apple M3 Max). Re-benchmark on target hardware.
 *
 * Variance:
 * - Results vary by ±30-50% depending on JIT state (warm vs. cold).
 * - Run with @Fork(3) or higher to capture inter-run variance.
 * - Report P95/P99 percentiles, not just mean.
 *
 * Reproduction:
 * - Hardware: Apple M3 Max (16-core CPU, 36GB RAM)
 * - JVM: OpenJDK 26 with --enable-preview
 * - Command: ./mvnw test -Dtest=<ThisBenchmark> -Djmh.format=json
 *
 * For full methodology details, see: docs/testing/BENCHMARKING.md
 * For improvement plan, see: docs/testing/BENCHMARKING-IMPROVEMENT-PLAN.md
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */
```

---

## Example Updates

### Example 1: Update `ActorBenchmark.java`

**Add after license header:**

```java
/*
 * Copyright 2026 Sean Chat Mangpt
 * ...
 * limitations under the License.
 */

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * BENCHMARK METHODOLOGY DISCLOSURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Benchmark Type: JMH (well-designed)
 * Status: ⚠️ CONCERNS - Missing Blackhole, unstable state setup
 *
 * Known Issues:
 * - tell_throughput() doesn't consume result → DCE risk
 * - Setup at Level.Iteration → prevents JIT warmup
 *
 * Expected Results (on M3 Max):
 * - tell_throughput: 50-100ns (mean), P95 <150ns
 * - ask_latency: 5-20μs (mean), P99 <50μs
 *
 * For methodology details, see: docs/testing/BENCHMARKING.md
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */

package io.github.seanchatmangpt.jotp.benchmark;
// ...
```

---

### Example 2: Update `SimpleObservabilityBenchmark.java`

**Add after license header:**

```java
/*
 * Copyright 2026 Sean Chat Mangpt
 * ...
 * limitations under the License.
 */

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * BENCHMARK METHODOLOGY DISCLOSURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Benchmark Type: NON-JMH (ad-hoc, for CI/CD quick validation only)
 * Status: ❌ FLAWED - Do NOT use for published performance claims
 *
 * Critical Issues:
 * - Only 5,000 warmup iterations → insufficient for C2 compilation
 * - No Blackhole → dead code elimination risk
 * - Manual timing with System.nanoTime() → includes timer overhead
 * - No variance reporting → single data point
 *
 * Action Required:
 * - Convert to JMH benchmark (see BENCHMARKING-IMPROVEMENT-PLAN.md Phase 1.1)
 * - Use results only as PRELIMINARY ESTIMATES
 * - Re-validate with proper JMH before publication
 *
 * For methodology details, see: docs/testing/BENCHMARKING.md
 * For improvement plan, see: docs/testing/BENCHMARKING-IMPROVEMENT-PLAN.md
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */

package io.github.seanchatmangpt.jotp.benchmark;
// ...
```

---

### Example 3: Update `FrameworkMetricsProfilingBenchmark.java`

**Add after license header:**

```java
/*
 * Copyright 2026 Sean Chat Mangpt
 * ...
 * limitations under the License.
 */

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * BENCHMARK METHODOLOGY DISCLOSURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Benchmark Type: JMH (exemplary design)
 * Status: ✅ SOUND - Results are trustworthy
 *
 * Strengths:
 * - Proper warmup/fork configuration (@Fork(10), @Warmup(iterations=5))
 * - Blackhole consumption of all results
 * - @CompilerControl(DONT_INLINE) prevents inlining artifacts
 * - Component isolation (each benchmark measures one operation)
 *
 * Expected Results (on M3 Max):
 * - B01_volatileRead: 2-5ns
 * - B02_staticFieldRead: 0.5-1ns
 * - B03_systemPropertyCheck: 50-150ns
 *
 * These results are REPRODUCIBLE and can be used for published claims.
 *
 * For methodology details, see: docs/testing/BENCHMARKING.md
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */

package io.github.seanchatmangpt.jotp.benchmark;
// ...
```

---

## Inline Method Documentation

### Add Expected Results to @Benchmark Methods

**Before:**
```java
@Benchmark
public void tell_throughput(Blackhole bh) {
    countingActor.tell(1);
}
```

**After:**
```java
/**
 * Benchmarks Actor.tell() throughput (fire-and-forget).
 *
 * <p><strong>Expected Results (on Apple M3 Max):</strong>
 * <ul>
 *   <li>Mean: 50-100ns</li>
 *   <li>P95: <150ns</li>
 *   <li>P99: <300ns</li>
 * </ul>
 *
 * <p><strong>Rationale:</strong>
 * LinkedTransferQueue.offer() is ~30-50ns. Actor pattern adds virtual thread
 * overhead (~10-20ns) and handler dispatch (~10-30ns). Total: 50-100ns.
 *
 * <p><strong>Known Issues:</strong>
 * - ⚠️ Missing Blackhole consumption (FIXED in improvement plan)
 * - ⚠️ Setup at Level.Iteration prevents warmup (FIXED in improvement plan)
 *
 * @param bh Blackhole for consuming results
 */
@Benchmark
public void tell_throughput(Blackhole bh) {
    countingActor.tell(1);
    bh.consume(countingActor);  // Added in improvement plan
}
```

---

## Batch Update Script

**File:** `scripts/add-benchmark-disclaimers.sh`

```bash
#!/bin/bash
# Add methodology disclosure headers to benchmark files

BENCHMARK_DIR="src/test/java/io/github/seanchatmangpt/jotp/benchmark"

# Find all Java files in benchmark directory
find "$BENCHMARK_DIR" -name "*Benchmark.java" -type f | while read -r file; do
    echo "Processing: $file"

    # Check if header already exists
    if grep -q "BENCHMARK METHODOLOGY DISCLOSURE" "$file"; then
        echo "  → Already has disclosure header, skipping"
        continue
    fi

    # Determine benchmark type
    if grep -q "@Benchmark" "$file"; then
        if grep -q "import org.openjdk.jmh" "$file"; then
            TYPE="JMH"
            # Check for issues
            if grep -q "Blackhole bh" "$file" && grep -q "@Setup(Level.Trial)" "$file"; then
                STATUS="✅ SOUND - Results are trustworthy"
            elif grep -q "Blackhole bh" "$file"; then
                STATUS="⚠️ CONCERNS - Unstable state setup"
            else
                STATUS="⚠️ CONCERNS - Missing Blackhole or unstable state"
            fi
        else
            TYPE="NON-JMH"
            STATUS="❌ FLAWED - Do NOT use for published claims"
        fi
    else
        TYPE="UNKNOWN"
        STATUS="⚠️ UNKNOWN - Not a benchmark file"
    fi

    # Create temporary header
    cat > /tmp/benchmark_header.txt <<EOF

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * BENCHMARK METHODOLOGY DISCLOSURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Benchmark Type: $TYPE
 * Status: $STATUS
 *
 * For full methodology details, see: docs/testing/BENCHMARKING.md
 * For improvement plan, see: docs/testing/BENCHMARKING-IMPROVEMENT-PLAN.md
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */
EOF

    # Insert header after license block (before package declaration)
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        sed -i '' '/^package /r /tmp/benchmark_header.txt' "$file"
    else
        # Linux
        sed -i '/^package /r /tmp/benchmark_header.txt' "$file"
    fi

    echo "  → Added disclosure header"
done

echo "✅ Benchmark disclosure headers added"
```

**Usage:**
```bash
chmod +x scripts/add-benchmark-disclaimers.sh
./scripts/add-benchmark-disclaimers.sh
```

---

## Validation Checklist

After adding disclosures, validate each benchmark file:

- [ ] Header added with benchmark type (JMH vs. NON-JMH)
- [ ] Status clearly indicated (✅ SOUND, ⚠️ CONCERNS, ❌ FLAWED)
- [ ] Known issues documented (DCE, warmup, variance)
- [ ] Expected results included (with hardware specifics)
- [ ] Reference to methodology docs included
- [ ] @Benchmark methods have javadoc with expected results

---

## Example: Complete Updated File

```java
/*
 * Copyright 2026 Sean Chat Mangpt
 * ...
 * limitations under the License.
 */

/*
 * ═══════════════════════════════════════════════════════════════════════════
 * BENCHMARK METHODOLOGY DISCLOSURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Benchmark Type: JMH (well-designed)
 * Status: ✅ SOUND - Results are trustworthy
 *
 * Strengths:
 * - Proper warmup/fork configuration
 * - Blackhole consumption of all results
 * - Component isolation
 *
 * Expected Results (on M3 Max):
 * - Volatile read: 2-5ns
 * - Static field read: 0.5-1ns
 *
 * For methodology details, see: docs/testing/BENCHMARKING.md
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */

package io.github.seanchatmangpt.jotp.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

/**
 * Framework metrics profiling benchmark.
 *
 * <p>Measures component-level overhead of observability primitives.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(10)
@State(Scope.Benchmark)
public class FrameworkMetricsProfilingBenchmark {

    private volatile boolean volatileField = false;

    /**
     * Benchmarks volatile read operation.
     *
     * <p><strong>Expected Results (on Apple M3 Max):</strong>
     * <ul>
     *   <li>Mean: 2-5ns</li>
     *   <li>P95: <10ns</li>
     *   <li>P99: <20ns</li>
     * </ul>
     *
     * <p><strong>Rationale:</strong>
     * Volatile read requires memory barrier, which on Apple Silicon is ~2-5ns.
     *
     * @param bh Blackhole for consuming results
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B01_volatileRead(Blackhole bh) {
        boolean value = volatileField;
        bh.consume(value);
    }
}
```

---

**End of Template**

**Next Steps:**
1. Run `scripts/add-benchmark-disclaimers.sh` to add headers
2. Manually review and customize each header
3. Add expected results to @Benchmark methods
4. Validate with checklist above
