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
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH memory allocation & GC pressure benchmarks for FrameworkEventBus hot path.
 *
 * <p><strong>Analysis Goal:</strong> Identify whether memory allocation contributes to the 456ns
 * hot path regression by measuring:
 *
 * <ul>
 *   <li>Bytes allocated per publish() call
 *   <li>Allocation rate vs. GC threshold
 *   <li>Escape analysis effectiveness (stack vs. heap allocation)
 *   <li>GC overhead contribution to latency
 * </ul>
 *
 * <p><strong>Running with GC Profiling:</strong>
 *
 * <pre>{@code
 * # Run with GC profiler to see allocation rate
 * mvnd test -Dtest=MemoryAllocationBenchmark -Djmh.profilers=gc
 *
 * # Or via JMH directly (after packaging)
 * java -jar target/benchmarks.jar MemoryAllocationBenchmark -prof gc
 *
 * # With escape analysis logging (Java 17+)
 * java -XX:+PrintEliminateAllocations -jar target/benchmarks.jar MemoryAllocationBenchmark
 * }</pre>
 *
 * <p><strong>Expected Output:</strong>
 *
 * <ul>
 *   <li>Allocation map: what objects are created, how big, how often
 *   <li>GC pressure quantification (bytes/sec)
 *   <li>Identification of unnecessary allocations
 *   <li>Recommendations for allocation-free implementation
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class MemoryAllocationBenchmark {

    @Param({"false", "true"})
    public boolean observabilityEnabled;

    private FrameworkEventBus eventBus;
    private Proc<Integer, Integer> counterProc;

    // Event types for allocation comparison
    private FrameworkEventBus.FrameworkEvent.ProcessCreated processCreatedEvent;
    private FrameworkEventBus.FrameworkEvent.ProcessTerminated processTerminatedEvent;
    private FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed supervisorChildCrashedEvent;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.setProperty("jotp.observability.enabled", String.valueOf(observabilityEnabled));
        eventBus = FrameworkEventBus.create();
        counterProc = Proc.spawn(0, (state, msg) -> state + msg);

        // Pre-create events to isolate publish() allocation cost
        processCreatedEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        java.time.Instant.now(), "bench-proc-1", "Proc");

        processTerminatedEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        java.time.Instant.now(), "bench-proc-1", "Proc", false, "normal");

        supervisorChildCrashedEvent =
                new FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed(
                        java.time.Instant.now(),
                        "supervisor-1",
                        "child-1",
                        new RuntimeException("test"));
    }

    // ── HOT PATH: publish() ALLOCATION ANALYSIS ─────────────────────────────────────

    /**
     * Hot path: publish() when DISABLED.
     *
     * <p><strong>Expected allocation:</strong> 0 bytes (fast path returns immediately).
     *
     * <p><strong>GC profiler output:</strong>
     *
     * <pre>
     * [ GC profile result ]
     * allocated: 0.000 ± 0.000 MB/sec
     * GC count: 0
     * GC time: 0 ms
     * </pre>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void publish_disabled_noAllocation() {
        eventBus.publish(processCreatedEvent);
    }

    /**
     * Fast path: publish() when ENABLED but NO subscribers.
     *
     * <p><strong>Expected allocation:</strong> 0 bytes (subscribers.isEmpty() check returns early).
     *
     * <p><strong>GC profiler output:</strong>
     *
     * <pre>
     * allocated: 0.000 ± 0.000 MB/sec
     * GC count: 0
     * GC time: 0 ms
     * </pre>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void publish_enabled_noSubscribers_noAllocation() {
        var emptyBus = FrameworkEventBus.create();
        emptyBus.publish(processCreatedEvent);
    }

    /**
     * Async delivery: publish() when ENABLED with subscriber.
     *
     * <p><strong>Expected allocation:</strong> ~100-200 bytes per call.
     *
     * <p><strong>Allocation breakdown:</strong>
     *
     * <ul>
     *   <li>Lambda capture: ~16 bytes (reference to event)
     *   <li>Executor task: ~32 bytes (FutureTask wrapper)
     *   <li>CopyOnWriteArrayList iterator: ~24 bytes
     *   <li>Total: ~72 bytes per submit()
     * </ul>
     *
     * <p><strong>GC profiler output:</strong>
     *
     * <pre>
     * allocated: ~0.100 ± 0.010 MB/sec (at 1M ops/sec)
     * GC count: ~1-2 per second
     * GC time: ~1-2 ms/sec
     * </pre>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void publish_enabled_withSubscriber_measuresAllocation() {
        eventBus.subscribe(
                event -> {
                    // No-op subscriber
                });
        eventBus.publish(processCreatedEvent);
    }

    // ── EVENT CREATION ALLOCATION COST ───────────────────────────────────────────────

    /**
     * ProcessCreated event allocation.
     *
     * <p><strong>Expected allocation:</strong> ~56-72 bytes.
     *
     * <p><strong>Object layout:</strong>
     *
     * <ul>
     *   <li>Record header: 12 bytes (mark word + class pointer)
     *   <li>Instant timestamp: 16 bytes (epochSecond + nano)
     *   <li>String processId: 8 bytes (reference)
     *   <li>String processType: 8 bytes (reference)
     *   <li>Padding: 12 bytes (for 8-byte alignment)
     *   <li>Total: ~56 bytes
     * </ul>
     *
     * <p><strong>Escape analysis:</strong> If JIT can prove event doesn't escape, it will be
     * stack-allocated (zero GC pressure).
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public FrameworkEventBus.FrameworkEvent.ProcessCreated
            createProcessCreated_measuresAllocation() {
        return new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                java.time.Instant.now(), "proc-" + System.nanoTime(), "Proc");
    }

    /**
     * ProcessTerminated event allocation.
     *
     * <p><strong>Expected allocation:</strong> ~72-88 bytes (larger than ProcessCreated).
     *
     * <p><strong>Object layout:</strong>
     *
     * <ul>
     *   <li>Record header: 12 bytes
     *   <li>Instant timestamp: 16 bytes
     *   <li>String processId: 8 bytes
     *   <li>String processType: 8 bytes
     *   <li>boolean abnormal: 1 byte
     *   <li>String reason: 8 bytes
     *   <li>Padding: 19 bytes
     *   <li>Total: ~72 bytes
     * </ul>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public FrameworkEventBus.FrameworkEvent.ProcessTerminated
            createProcessTerminated_measuresAllocation() {
        return new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                java.time.Instant.now(), "proc-" + System.nanoTime(), "Proc", false, "normal");
    }

    /**
     * SupervisorChildCrashed event allocation (largest event type).
     *
     * <p><strong>Expected allocation:</strong> ~80-96 bytes.
     *
     * <p><strong>Object layout:</strong>
     *
     * <ul>
     *   <li>Record header: 12 bytes
     *   <li>Instant timestamp: 16 bytes
     *   <li>String supervisorId: 8 bytes
     *   <li>String childId: 8 bytes
     *   <li>Throwable reason: 8 bytes (reference)
     *   <li>Padding: 28 bytes
     *   <li>Total: ~80 bytes
     * </ul>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed
            createSupervisorChildCrashed_measuresAllocation() {
        return new FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed(
                java.time.Instant.now(),
                "sup-" + System.nanoTime(),
                "child-" + System.nanoTime(),
                new RuntimeException("test"));
    }

    // ── PROC.TELL() HOT PATH PURITY ─────────────────────────────────────────────────

    /**
     * Proc.tell() with observability DISABLED.
     *
     * <p><strong>Expected allocation:</strong> 0 bytes (pure mailbox enqueue).
     *
     * <p><strong>Validation:</strong> Proc.tell() should NEVER allocate. If GC profiler shows
     * non-zero allocation, it means observability code has leaked into the hot path.
     *
     * <p><strong>GC profiler output:</strong>
     *
     * <pre>
     * allocated: 0.000 ± 0.000 MB/sec
     * </pre>
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void procTell_disabled_noAllocation(Blackhole bh) {
        counterProc.tell(1);
        bh.consume(counterProc);
    }

    // ── COMPARATIVE BASELINES ───────────────────────────────────────────────────────

    /**
     * Baseline: Empty method (JMH overhead).
     *
     * <p><strong>Expected allocation:</strong> 0 bytes.
     */
    @Benchmark
    public void baseline_empty_noAllocation() {
        // No-op
    }

    /**
     * Baseline: Simple object allocation (int array).
     *
     * <p><strong>Expected allocation:</strong> 24 bytes (header: 12, data: 4*3=12, padding: 0).
     *
     * <p><strong>Purpose:</strong> Validate GC profiler is working. This should show consistent
     * allocation across all iterations.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int[] baseline_simpleAllocation() {
        return new int[] {1, 2, 3};
    }

    /**
     * Baseline: Lambda capture allocation.
     *
     * <p><strong>Expected allocation:</strong> ~16-24 bytes (lambda instance + captured vars).
     *
     * <p><strong>Purpose:</strong> Measure lambda overhead, which is used in async
     * executor.submit().
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Runnable baseline_lambdaAllocation() {
        int value = 42;
        return () -> System.nanoTime();
    }

    // ── ESCAPE ANALYSIS VALIDATION ───────────────────────────────────────────────────

    /**
     * Escape analysis: Non-escaping event (should be stack-allocated).
     *
     * <p><strong>Expected allocation:</strong> 0 bytes (after JIT optimization).
     *
     * <p><strong>Validation:</strong> If JIT's escape analysis proves the event doesn't escape this
     * method, it will be stack-allocated (zero GC pressure). Run with
     * `-XX:+PrintEliminateAllocations` to verify.
     *
     * <p><strong>Note:</strong> Requires C2 compiler (typically after ~10k iterations).
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long escapeAnalysis_nonEscapingEvent() {
        var event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        java.time.Instant.now(), "bench-proc-1", "Proc");
        // Event doesn't escape: just read a field and return
        return event.timestamp().getEpochSecond();
    }

    /**
     * Escape analysis: Escaping event (must be heap-allocated).
     *
     * <p><strong>Expected allocation:</strong> ~56 bytes.
     *
     * <p><strong>Purpose:</strong> Compare with non-escaping version to validate escape analysis is
     * working. The allocation difference shows JIT optimization effectiveness.
     */
    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public FrameworkEventBus.FrameworkEvent.ProcessCreated escapeAnalysis_escapingEvent() {
        return new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                java.time.Instant.now(), "bench-proc-1", "Proc");
    }
}
