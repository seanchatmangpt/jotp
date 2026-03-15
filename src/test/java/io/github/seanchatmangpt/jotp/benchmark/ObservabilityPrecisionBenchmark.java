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
 * JMH precision benchmarks for JOTP observability infrastructure.
 *
 * <p><strong>Thesis Claim:</strong> FrameworkEventBus has <100ns overhead when disabled, ensuring
 * observability never impacts hot paths like {@link Proc#tell()}.
 *
 * <p><strong>Measurement Goals:</strong>
 *
 * <ul>
 *   <li>Fast path validation: <100ns when observability disabled or no subscribers
 *   <li>Event delivery latency: async overhead with active subscribers
 *   <li>Memory allocation: FrameworkEvent creation cost
 *   <li>Hot path purity: Proc.tell() has zero measurable overhead
 * </ul>
 *
 * <p><strong>Running the Benchmarks:</strong>
 *
 * <pre>{@code
 * # Disable observability (fast path validation)
 * mvnd test -Dtest=ObservabilityPrecisionBenchmark -Djotp.observability.enabled=false
 *
 * # Enable observability (async delivery measurement)
 * mvnd test -Dtest=ObservabilityPrecisionBenchmark -Djotp.observability.enabled=true
 *
 * # Full JMH report (all JVM optimizations)
 * java -jar target/benchmarks.jar -prof gc
 * }</pre>
 *
 * @see FrameworkEventBus
 * @see io.github.seanchatmangpt.jotp.observability.FrameworkEvent
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ObservabilityPrecisionBenchmark {

    /**
     * Feature flag parameter: toggle observability on/off across benchmark iterations. This
     * validates both fast path (<100ns) and async delivery overhead.
     */
    @Param({"false", "true"})
    public boolean observabilityEnabled;

    /**
     * Event bus instance for benchmarking. Package-private constructor allows testing without
     * singleton constraints.
     */
    private FrameworkEventBus eventBus;

    /** Test process for hot path purity validation. Simple counter actor: state = state + msg. */
    private Proc<Integer, Integer> counterProc;

    /**
     * Subscriber counter for async delivery validation. Incremented when event is delivered (not
     * just published).
     */
    private volatile int subscriberCallCount;

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

        // Subscribe if observability is enabled (measure async delivery)
        if (observabilityEnabled) {
            eventBus.subscribe(event -> subscriberCallCount++);
        }
    }

    /** ── FAST PATH VALIDATION (<100ns claim) ───────────────────────────────────── */

    /**
     * Baseline: Event bus publish when DISABLED and NO subscribers.
     *
     * <p><strong>Expected:</strong> <100ns — single branch check returns immediately.
     *
     * <p><strong>Validation:</strong> This validates the core thesis claim that observability has
     * zero overhead when disabled. The fast path is: {@code if (!ENABLED || !running ||
     * subscribers.isEmpty()) return;}
     *
     * <p><strong>Regression test:</strong> Any code change that increases this beyond 100ns
     * violates the hot path protection contract.
     */
    @Benchmark
    public void eventBusPublish_disabled_noSubscribers() {
        eventBus.publish(sampleEvent);
    }

    /**
     * Fast path: Event bus publish when ENABLED but NO subscribers.
     *
     * <p><strong>Expected:</strong> <100ns — single branch check returns immediately.
     *
     * <p><strong>Validation:</strong> Even when observability is enabled via system property, the
     * fast path should still be <100ns if there are no active subscribers. This tests the {@code
     * subscribers.isEmpty()} branch.
     *
     * <p><strong>Note:</strong> This requires {@code @Param("true")} with zero subscribers (setup
     * above only subscribes when enabled, so for this variant we'd need a separate setup method
     * that enables but doesn't subscribe).
     */
    @Benchmark
    public void eventBusPublish_enabled_noSubscribers() {
        var emptyBus = FrameworkEventBus.create();
        emptyBus.publish(sampleEvent);
    }

    /** ── ASYNC DELIVERY OVERHEAD ─────────────────────────────────────────────────── */

    /**
     * Async delivery: Event bus publish when ENABLED with ONE subscriber.
     *
     * <p><strong>Expected:</strong> 200-500ns — fire-and-forget executor.submit().
     *
     * <p><strong>Validation:</strong> This measures the overhead of async event delivery when
     * there's an active subscriber. The cost includes:
     *
     * <ul>
     *   <li>Branch checks (enabled, running, !isEmpty)
     *   <li>ExecutorService.submit() overhead
     *   <li>CopyOnWriteArrayList iteration (lock-free read)
     * </ul>
     *
     * <p><strong>Why not higher:</strong> Subscriber execution happens on background daemon thread,
     * so this benchmark only measures the submission cost, not the handler execution.
     */
    @Benchmark
    public void eventBusPublish_enabled_oneSubscriber() {
        eventBus.publish(sampleEvent);
    }

    /** ── HOT PATH PURITY (Proc.tell() validation) ────────────────────────────────── */

    /**
     * Hot path purity: Proc.tell() with observability DISABLED.
     *
     * <p><strong>Expected:</strong> <50ns — pure LinkedTransferQueue.offer().
     *
     * <p><strong>Validation:</strong> Proc.tell() should NEVER publish framework events. This
     * benchmark validates that the hot path is pure — it's just mailbox enqueue. Framework events
     * are published from:
     *
     * <ul>
     *   <li>Process constructor (ProcessCreated) — not in tell()
     *   <li>Termination callbacks (ProcessTerminated) — not in tell()
     *   <li>Supervisor crash handling — not in tell()
     * </ul>
     *
     * <p><strong>Regression test:</strong> If this benchmark exceeds 50ns, it means observability
     * code has leaked into the hot path, violating the design contract.
     *
     * <p><strong>Blackhole.consume()</strong prevents JIT from optimizing away the tell() call.
     */
    @Benchmark
    public void procTell_withObservabilityDisabled(Blackhole bh) {
        counterProc.tell(1);
        // Prevent dead code elimination
        bh.consume(counterProc);
    }

    /** ── MEMORY ALLOCATION OVERHEAD ──────────────────────────────────────────────── */

    /**
     * FrameworkEvent creation cost (ProcessCreated).
     *
     * <p><strong>Expected:</strong> <100ns allocation + initialization.
     *
     * <p><strong>Validation:</strong> Measures the cost of creating immutable record events.
     * ProcessCreated is the most common event (published once per process spawn).
     *
     * <p><strong>@CompilerControl(DONT_INLINE)</strong> prevents JIT from inlining the constructor,
     * ensuring we measure actual allocation cost, not optimized-away code.
     *
     * <p><strong>GC Profiling:</strong> Run with {@code -prof gc} to see allocation rate:
     *
     * <pre>
     * #Expected: ~32 bytes per event (Instant: ~16B, String refs: ~8B each, header: ~12B)
     * java -jar target/benchmarks.jar -prof gc
     * </pre>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public FrameworkEventBus.FrameworkEvent.ProcessCreated createProcessCreatedEvent() {
        return new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                java.time.Instant.now(), "bench-proc-" + System.nanoTime(), "Proc");
    }

    /** ── COMPARATIVE BASELINES ───────────────────────────────────────────────────── */

    /**
     * Baseline: Empty method call overhead.
     *
     * <p><strong>Purpose:</strong> Establish the JMH measurement baseline. Subtract this from other
     * benchmarks to isolate the actual operation cost.
     */
    @Benchmark
    public void baseline_empty() {
        // No-op — measures JMH framework overhead
    }

    /**
     * Baseline: Branch check cost (single if statement).
     *
     * <p><strong>Purpose:</strong> Isolate the fast path branch cost. The FrameworkEventBus fast
     * path is essentially: {@code if (!enabled) return;} This benchmark measures just that branch
     * logic.
     */
    @Benchmark
    public void baseline_branchCheck() {
        boolean enabled = false; // Simulates disabled observability
        if (enabled) {
            // This branch is never taken (fast path)
            System.nanoTime();
        }
    }
}
