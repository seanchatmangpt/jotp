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

package io.github.seanchatmangpt.jotp.observability;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Comprehensive microbenchmark profiling FrameworkMetrics overhead components.
 *
 * <p>This benchmark isolates each potential source of overhead in the disabled path to identify
 * exactly where the 456ns is being spent. Each benchmark measures a single component in isolation
 * using JMH's @CompilerControl to prevent inlining and ensure accurate measurements.
 *
 * <p><strong>Components Profiled:</strong>
 *
 * <ul>
 *   <li><b>B01_VolatileRead:</b> Overhead of reading a volatile boolean field
 *   <li><b>B02_StaticFieldRead:</b> Overhead of reading a static boolean field
 *   <li><b>B03_SystemPropertyCheck:</b> Overhead of Boolean.getBoolean()
 *   <li><b>B04_BooleanGetBooleanCached:</b> One-time cached Boolean.getBoolean()
 *   <li><b>B05_BranchPredictionMiss:</b> Overhead of mispredicted branch (always false)
 *   <li><b>B06_BranchPredictionHit:</b> Overhead of correctly predicted branch (always true)
 *   <li><b>B07_EmptyReturn:</b> Overhead of empty method with return
 *   <li><b>B08_InterfaceCall:</b> Overhead of Consumer.accept() interface call
 *   <li><b>B09_StaticMethodCall:</b> Overhead of static method call
 *   <li><b>B10_FullDisabledPath:</b> Complete disabled path with all components
 *   <li><b>B11_SwitchStatement:</b> Overhead of switch statement without pattern matching
 *   <li><b>B12_SwitchPatternMatching:</b> Overhead of sealed type pattern matching switch
 *   <li><b>B13_ComboBox:</b> Combined: volatile read + branch + empty return
 *   <li><b>B14_ComboTriple:</b> Combined: volatile + branch + static method call
 *   <li><b>B15_MethodReference:</b> Overhead of method reference vs lambda
 * </ul>
 *
 * <p><strong>Expected Results:</strong>
 *
 * <ul>
 *   <li>Volatile reads: ~2-5ns on modern CPUs
 *   <li>Static field reads: ~0.5-1ns (CPU cache hit)
 *   <li>Boolean.getBoolean(): ~50-150ns (system property lookup)
 *   <li>Branch prediction miss: ~10-20ns pipeline flush
 *   <li>Interface call: ~5-10ns (virtual dispatch)
 *   <li>Full disabled path: Should be < 50ns if optimized correctly
 * </ul>
 *
 * <p><strong>Running the Benchmark:</strong>
 *
 * <pre>{@code
 * # Quick run (3 forks, 5 iterations each)
 * mvnd test -Dtest=FrameworkMetricsProfilingBenchmark -Dbenchmark.forks=3 -Dbenchmark.warmup=3 -Dbenchmark.iterations=5
 *
 * # Full production run (recommended)
 * mvnd test -Dtest=FrameworkMetricsProfilingBenchmark -Dbenchmark.forks=10 -Dbenchmark.warmup=5 -Dbenchmark.iterations=10
 *
 * # With JVM diagnostics
 * mvnd test -Dtest=FrameworkMetricsProfilingBenchmark -Djvm.args="-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions"
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(10)
@State(Scope.Thread)
public class FrameworkMetricsProfilingBenchmark {

    // ── Test Data ───────────────────────────────────────────────────────────────

    private static final boolean ENABLED = false; // Simulate disabled path

    private volatile boolean volatileField = false;
    private static boolean staticField = false;

    private static final String DUMMY_PROPERTY = "jotp.observability.enabled.dummy";
    private static final String PROPERTY_NAME = "jotp.observability.enabled";

    // ── B01: Volatile Read ───────────────────────────────────────────────────────

    /**
     * Baseline: volatile boolean read.
     *
     * <p>Expected: 2-5ns on modern CPUs. Volatile reads prevent CPU reordering and require memory
     * barrier synchronization.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B01_volatileRead(Blackhole bh) {
        boolean value = volatileField;
        bh.consume(value);
    }

    // ── B02: Static Field Read ───────────────────────────────────────────────────

    /**
     * Baseline: static boolean field read (non-volatile).
     *
     * <p>Expected: 0.5-1ns (CPU cache hit). Much faster than volatile since no memory barrier
     * required.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B02_staticFieldRead(Blackhole bh) {
        boolean value = staticField;
        bh.consume(value);
    }

    // ── B03: System Property Check (Uncached) ────────────────────────────────────

    /**
     * System property check on every call (uncached).
     *
     * <p>Expected: 50-150ns. This is the WORST case - reading system properties requires traversing
     * a ConcurrentHashMap and has significant overhead.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B03_systemPropertyCheck(Blackhole bh) {
        boolean enabled = Boolean.getBoolean(PROPERTY_NAME);
        bh.consume(enabled);
    }

    // ── B04: System Property Check (Cached) ──────────────────────────────────────

    /**
     * System property check cached in static final field.
     *
     * <p>Expected: 1-2ns. This is the BEST case - property is read once at class initialization,
     * then cached. This is what FrameworkMetrics actually does.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B04_systemPropertyCheckCached(Blackhole bh) {
        bh.consume(ENABLED);
    }

    // ── B05: Branch Prediction Miss ───────────────────────────────────────────────

    /**
     * Branch prediction miss (condition is always false).
     *
     * <p>Expected: 10-20ns. CPU mispredicts the branch, causing pipeline flush. This simulates the
     * disabled path where the branch is never taken.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B05_branchPredictionMiss(Blackhole bh) {
        if (ENABLED) {
            // This branch is never taken
            bh.consume(42);
        } else {
            bh.consume(0);
        }
    }

    // ── B06: Branch Prediction Hit ────────────────────────────────────────────────

    /**
     * Branch prediction hit (condition is always true).
     *
     * <p>Expected: <1ns. CPU correctly predicts the branch, no pipeline flush. This is the best
     * case for branch prediction.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B06_branchPredictionHit(Blackhole bh) {
        if (!ENABLED) {
            // This branch is always taken
            bh.consume(0);
        } else {
            bh.consume(42);
        }
    }

    // ── B07: Empty Return ────────────────────────────────────────────────────────

    /**
     * Empty method with early return.
     *
     * <p>Expected: <1ns. Just a return statement.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B07_emptyReturn() {
        if (ENABLED) {
            return;
        }
        // Fall through
    }

    // ── B08: Interface Call ───────────────────────────────────────────────────────

    /**
     * Consumer.accept() interface call overhead.
     *
     * <p>Expected: 5-10ns. Virtual dispatch through interface method table.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B08_interfaceCall(Blackhole bh) {
        Consumer<String> consumer = s -> {};
        consumer.accept("test");
        bh.consume(true);
    }

    // ── B09: Static Method Call ───────────────────────────────────────────────────

    /**
     * Static method call overhead.
     *
     * <p>Expected: 1-3ns. Static calls are faster than interface calls since no virtual dispatch
     * required.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B09_staticMethodCall(Blackhole bh) {
        boolean result = staticMethod();
        bh.consume(result);
    }

    private static boolean staticMethod() {
        return false;
    }

    // ── B10: Full Disabled Path (Current Implementation) ─────────────────────────

    /**
     * Full disabled path: volatile read + branch + return.
     *
     * <p>This is what FrameworkMetrics.accept() does when disabled. Expected: 5-10ns. If this is
     * significantly higher (>100ns), there's a problem with the implementation.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B10_fullDisabledPath(Blackhole bh) {
        if (!ENABLED) {
            return; // Early return from disabled path
        }
        // This code is never executed
        bh.consume(true);
    }

    // ── B11: Switch Statement ────────────────────────────────────────────────────

    /**
     * Traditional switch statement without pattern matching.
     *
     * <p>Expected: 1-3ns. Switch on integer with few cases is optimized to tableswitch.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B11_switchStatement(Blackhole bh) {
        int value = 1;
        switch (value) {
            case 1:
                bh.consume("one");
                break;
            case 2:
                bh.consume("two");
                break;
            default:
                bh.consume("other");
        }
    }

    // ── B12: Switch Pattern Matching ─────────────────────────────────────────────

    /**
     * Pattern matching switch on sealed type.
     *
     * <p>Expected: 3-5ns. Pattern matching has additional overhead for type checking but compiler
     * can optimize for sealed hierarchies.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B12_switchPatternMatching(Blackhole bh) {
        Object obj = "test";
        String result =
                switch (obj) {
                    case String s -> "string: " + s;
                    case Integer i -> "integer: " + i;
                    default -> "unknown";
                };
        bh.consume(result);
    }

    // ── B13: Combo: Volatile + Branch + Return ────────────────────────────────────

    /**
     * Combined: volatile read + branch check + empty return.
     *
     * <p>This is the actual disabled path pattern. Expected: 5-10ns total.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B13_comboVolatileBranchReturn(Blackhole bh) {
        if (volatileField) {
            bh.consume(true);
            return;
        }
        bh.consume(false);
    }

    // ── B14: Combo: Static + Branch + Method Call ─────────────────────────────────

    /**
     * Combined: static field read + branch + static method call.
     *
     * <p>Expected: 3-7ns. This is the optimized version using static final instead of volatile.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B14_comboStaticBranchMethod(Blackhole bh) {
        if (!staticField) {
            bh.consume(staticMethod());
        }
    }

    // ── B15: Method Reference vs Lambda ───────────────────────────────────────────

    /**
     * Method reference overhead.
     *
     * <p>Expected: 1-2ns. Method references are slightly faster than lambdas.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B15_methodReference(Blackhole bh) {
        Runnable r = this::emptyMethod;
        r.run();
        bh.consume(true);
    }

    private void emptyMethod() {
        // Empty
    }

    // ── B16: Real FrameworkMetrics Disabled Path ─────────────────────────────────

    /**
     * Actual FrameworkMetrics.accept() disabled path.
     *
     * <p>This creates a real FrameworkMetrics instance and calls accept() with it disabled.
     * Expected: <50ns if implementation is correct.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B16_realFrameworkMetricsDisabled(Blackhole bh) {
        FrameworkMetrics metrics = FrameworkMetrics.create();
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated("test-pid", "Proc", 0);

        metrics.accept(event);
        bh.consume(true);

        metrics.close();
    }

    // ── B17: Real FrameworkMetrics Enabled Path ──────────────────────────────────

    /**
     * Actual FrameworkMetrics.accept() enabled path.
     *
     * <p>This enables observability and measures the full path. Expected: 500-2000ns depending on
     * which event type is handled.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B17_realFrameworkMetricsEnabled(Blackhole bh) {
        // Note: This will only work if -Djotp.observability.enabled=true is set
        // Otherwise it will measure the disabled path
        FrameworkMetrics metrics = FrameworkMetrics.create();
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated("test-pid", "Proc", 0);

        metrics.accept(event);
        bh.consume(true);

        metrics.close();
    }

    // ── B18: Null Check ───────────────────────────────────────────────────────────

    /**
     * Null check overhead.
     *
     * <p>Expected: <1ns. Null checks are extremely cheap on modern JVMs.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B18_nullCheck(Blackhole bh) {
        Object obj = null;
        if (obj != null) {
            bh.consume(obj.toString());
        }
        bh.consume(false);
    }

    // ── B19: InstanceOf Check ────────────────────────────────────────────────────

    /**
     * instanceof check overhead.
     *
     * <p>Expected: 1-2ns. Type checks are fast but not as fast as null checks.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B19_instanceofCheck(Blackhole bh) {
        Object obj = "test";
        if (obj instanceof String) {
            bh.consume(true);
        } else {
            bh.consume(false);
        }
    }

    // ── B20: Array Length Check ───────────────────────────────────────────────────

    /**
     * Array length check overhead.
     *
     * <p>Expected: <1ns. Array length is stored in the object header.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void B20_arrayLengthCheck(Blackhole bh) {
        String[] array = new String[] {"a", "b", "c"};
        int length = array.length;
        bh.consume(length);
    }
}
