/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.benchmark;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * CORRECTED JMH benchmark for hot path validation.
 *
 * <p><strong>Fixes applied to original HotPathValidationBenchmark:</strong>
 *
 * <ul>
 *   <li>✅ Added {@link State(Scope.Benchmark)} - proper lifecycle management
 *   <li>✅ Added {@link Blackhole} parameter - prevents dead code elimination
 *   <li>✅ Changed to {@link BenchmarkMode#AverageTime} - correct for mean latency
 *   <li>✅ Explicit {@link OutputTimeUnit#NANOSECONDS} - prevents unit confusion
 *   <li>✅ Increased warmup to 15 iterations - sufficient for GraalVM JIT
 *   <li>✅ Increased measurement to 20 iterations - better statistical significance
 *   <li>✅ Added {@link Setup(Level.Trial)} - proper initialization
 *   <li>✅ Added {@link CompilerControl#DONT_INLINE} variant - validates inlining effects
 * </ul>
 *
 * <p><strong>Expected Result (Corrected):</strong> 200-300ns (not 456ns from original flawed
 * benchmark)
 *
 * <p><strong>Running the Benchmark:</strong>
 *
 * <pre>{@code
 * # Standard run (recommended)
 * mvnd test -Dtest=HotPathValidationBenchmarkFixed
 *
 * # With JIT compilation logging
 * java -XX:+PrintCompilation \
 *      -XX:+PrintGCDetails \
 *      -jar target/benchmarks.jar
 *
 * # Variant: Compare with different fork counts
 * java -jar target/benchmarks.jar -f 1  # Fastest, least reliable
 * java -jar target/benchmarks.jar -f 3  # Standard (recommended)
 * java -jar target/benchmarks.jar -f 5  # Most reliable
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 15, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class HotPathValidationBenchmarkFixed {

    /**
     * Feature flag parameter: toggle observability on/off across benchmark iterations. This
     * validates both fast path (<100ns) and async delivery overhead.
     */
    @Param({"false", "true"})
    public boolean observabilityEnabled;

    /** Event bus instance for benchmarking. Package-private constructor allows testing. */
    private FrameworkEventBus eventBus;

    /** Test process for hot path purity validation. Simple counter actor: state = state + msg. */
    private Proc<Integer, Integer> counterProc;

    /**
     * Sample event for memory allocation benchmarking. ProcessCreated is the most common framework
     * event.
     */
    private FrameworkEventBus.FrameworkEvent.ProcessCreated sampleEvent;

    @Setup(Level.Trial)
    public void setupTrial() {
        // Set system property before creating event bus
        System.setProperty("jotp.observability.enabled", String.valueOf(observabilityEnabled));

        // Create fresh event bus instance (package-private constructor)
        eventBus = FrameworkEventBus.create();

        // Create simple counter process for hot path testing
        counterProc = Proc.spawn(0, (state, msg) -> state + msg);

        // Create sample event for allocation testing
        sampleEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        java.time.Instant.now(), "bench-proc-1", "Proc");
    }

    /** ── CRITICAL PATH VALIDATION (CORRECTED) ─────────────────────────────────── */

    /**
     * CRITICAL FIX: Original benchmark used {@code Mode.SampleTime} (wrong) and no Blackhole (dead
     * code elimination risk).
     *
     * <p><strong>Corrected:</strong>
     *
     * <ul>
     *   <li>Uses {@link Mode#AverageTime} for mean latency measurement
     *   <li>Uses {@link Blackhole} to prevent dead code elimination
     *   <li>Explicit {@link OutputTimeUnit#NANOSECONDS} (not ms/op)
     *   <li>Proper {@link State} lifecycle management
     * </ul>
     *
     * <p><strong>Expected Result:</strong> 200-300ns (not 456ns from original flawed benchmark)
     *
     * <p><strong>Validation:</strong> This measures the complete critical path:
     *
     * <ol>
     *   <li>Event bus publish() ~30ns (validated in ObservabilityPrecisionBenchmark)
     *   <li>Proc.tell() overhead ~60ns (validated in ACTUAL-precision-results.md)
     *   <li>State machine transition ~50-100ns (estimated)
     *   <li>Mailbox operations ~60ns (validated)
     * </ol>
     *
     * <p><strong>Total expected:</strong> 200-250ns
     *
     * @param bh Blackhole to prevent JIT from optimizing away the benchmark
     */
    @Benchmark
    public void benchmarkLatencyCriticalPath(Blackhole bh) {
        // Critical path: event publish + process tell + state transition
        eventBus.publish(sampleEvent);
        counterProc.tell(1);

        // Prevent dead code elimination (CRITICAL FIX)
        bh.consume(counterProc);
        bh.consume(eventBus);
    }

    /**
     * VARIANT: Prevent JIT inlining to measure actual call overhead.
     *
     * <p><strong>Purpose:</strong> By preventing inlining, we measure the true method call cost
     * including stack frame allocation and parameter passing.
     *
     * <p><strong>Expected Result:</strong> 10-20ns higher than inlined version
     *
     * <p><strong>Interpretation:</strong>
     *
     * <ul>
     *   <li>If difference <10ns: JIT inlining is effective (good)
     *   <li>If difference 10-20ns: Normal inlining benefit (expected)
     *   <li>If difference >50ns: Critical path is too small to benchmark accurately
     * </ul>
     *
     * @param bh Blackhole to prevent dead code elimination
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void benchmarkLatencyCriticalPathNoInline(Blackhole bh) {
        eventBus.publish(sampleEvent);
        counterProc.tell(1);
        bh.consume(counterProc);
        bh.consume(eventBus);
    }

    /** ─── BASELINE COMPARISONS ─────────────────────────────────────────────────── */

    /**
     * BASELINE: Empty method call overhead.
     *
     * <p><strong>Purpose:</strong> Establish JMH measurement baseline. Subtract this from other
     * benchmarks to isolate actual operation cost.
     *
     * <p><strong>Expected:</strong> <5ns (pure JMH framework overhead)
     *
     * @param bh Blackhole to prevent dead code elimination
     */
    @Benchmark
    public void baseline_empty(Blackhole bh) {
        // No-op — measures JMH framework overhead
        bh.consume(this);
    }

    /**
     * BASELINE: FrameworkEventBus fast path only.
     *
     * <p><strong>Expected:</strong> 10-30ns (validated in ObservabilityPrecisionBenchmark)
     *
     * @param bh Blackhole to prevent dead code elimination
     */
    @Benchmark
    public void baseline_eventBusOnly(Blackhole bh) {
        eventBus.publish(sampleEvent);
        bh.consume(eventBus);
    }

    /**
     * BASELINE: Proc.tell() only.
     *
     * <p><strong>Expected:</strong> 50-70ns (validated in ACTUAL-precision-results.md)
     *
     * @param bh Blackhole to prevent dead code elimination
     */
    @Benchmark
    public void baseline_procTellOnly(Blackhole bh) {
        counterProc.tell(1);
        bh.consume(counterProc);
    }

    /** ─── VALIDATION HELPERS ──────────────────────────────────────────────────── */

    /**
     * VALIDATION: Check if JIT is optimizing away the benchmark.
     *
     * <p><strong>Purpose:</strong> If this returns >100ns, JIT is NOT optimizing (good). If this
     * returns <10ns, JIT optimized away the loop (bad - need Blackhole).
     *
     * <p><strong>Expected:</strong> 100-200ns (dummy work should not be optimized away)
     *
     * @param bh Blackhole to prevent dead code elimination
     */
    @Benchmark
    public void validation_noJitOptimization(Blackhole bh) {
        // Dummy work that should not be optimized away
        long sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += i;
        }
        bh.consume(sum);
    }
}
