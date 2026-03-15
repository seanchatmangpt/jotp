/*
 * JOTP - Java One-Time Password and Fault Tolerance Framework
 * Copyright (c) 2025, JOTP Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.seanchatmangpt.jotp.benchmark;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.RestartStrategy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Baseline performance benchmarks WITHOUT observability.
 *
 * <p>Establishes reference metrics for validating observability overhead claims:
 *
 * <ul>
 *   <li>&lt;1% hot path overhead
 *   <li>&lt;100ns fast path overhead
 *   <li>Zero contamination of hot code paths
 * </ul>
 *
 * <p>Run with:
 *
 * <pre>
 * mvnd test -Dtest=BaselinePerformanceBenchmark -Djmh.includes=.* -Djmh.output=results/baseline.json
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Thread)
public class BaselinePerformanceBenchmark {

    private ProcRef<String, String> procRef;
    private Proc<String, String> stateMachineProc;
    private Supervisor supervisor;

    @Setup
    public void setup() throws TimeoutException, InterruptedException {
        // Initialize test processes WITHOUT observability
        procRef =
                Proc.spawn(
                                () -> "initialized",
                                (state, msg) -> {
                                    // Simple echo handler
                                    return state;
                                })
                        .getRef();

        // State machine for framework overhead
        stateMachineProc =
                Proc.spawn(
                                () -> "idle",
                                StateMachine.<String, String, String>builder()
                                        .state("idle")
                                        .onEvent("tick")
                                        .transitionTo("active")
                                        .state("active")
                                        .onEvent("tock")
                                        .transitionTo("idle")
                                        .build())
                        .getRef();

        // Supervisor for framework overhead
        supervisor =
                Supervisor.create(
                        RestartStrategy.ONE_FOR_ONE,
                        Supervisor.ChildSpec.create(
                                "test-worker",
                                () -> Proc.spawn(() -> "worker-state", (state, msg) -> state),
                                Supervisor.RestartType.PERMANENT,
                                Supervisor.Shutdown.timeout(1000)));
    }

    @TearDown
    public void teardown() throws InterruptedException, TimeoutException {
        if (procRef != null) {
            procRef.shutdown().join();
        }
        if (stateMachineProc != null) {
            stateMachineProc.shutdown().join();
        }
        if (supervisor != null) {
            supervisor.shutdown().join();
        }
    }

    /**
     * Baseline 1: Raw Proc.tell() performance without observability.
     *
     * <p>This is the HOT PATH for message passing. Any observability overhead will be visible as
     * increased latency here.
     *
     * <p>Target: &lt;100ns per tell() operation
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void procTellBaseline(Blackhole bh) {
        // Synchronous tell to avoid async latency
        bh.consume(procRef.tell("baseline-message"));
    }

    /**
     * Baseline 2: Proc.tell() with high contention (simulates concurrent load).
     *
     * <p>Tests mailbox contention without observability.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Threads(10)
    public void procTellConcurrentBaseline(Blackhole bh) {
        bh.consume(procRef.tell("concurrent-baseline"));
    }

    /**
     * Baseline 3: Core framework overhead - Proc.spawn().
     *
     * <p>Measures process creation overhead without observability.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public ProcRef<String, String> frameworkOverheadSpawn(Blackhole bh) throws Exception {
        return Proc.spawn(() -> "spawned", (state, msg) -> state).getRef();
    }

    /**
     * Baseline 4: StateMachine transition overhead.
     *
     * <p>Measures state transition cost without observability. This is a critical hot path for
     * gen_statem users.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void frameworkOverheadStateMachine(Blackhole bh) {
        bh.consume(stateMachineProc.tell("tick"));
        bh.consume(stateMachineProc.tell("tock"));
    }

    /**
     * Baseline 5: Supervisor child management overhead.
     *
     * <p>Measures supervisor operations without observability. Tests startChild/terminateChild
     * performance.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void frameworkOverheadSupervisor(Blackhole bh) throws Exception {
        var child =
                Supervisor.ChildSpec.create(
                        "temp-worker",
                        () -> Proc.spawn(() -> "temp", (state, msg) -> state),
                        Supervisor.RestartType.TEMPORARY,
                        Supervisor.Shutdown.infinity());

        bh.consume(supervisor.startChild(child));
        bh.consume(supervisor.terminateChild("temp-worker"));
    }

    /**
     * Baseline 6: Memory allocation for message passing.
     *
     * <p>Measures baseline allocation rate without observability.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(1000)
    public void memoryAllocationBaseline(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume(procRef.tell("msg-" + i));
        }
    }

    /**
     * Baseline 7: Message object allocation without sending.
     *
     * <p>Isolates allocation cost from mailbox operations.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(1000)
    public void memoryAllocationMessageOnly(Blackhole bh) {
        for (int i = 0; i < 1000; i++) {
            bh.consume("msg-" + i);
        }
    }

    /**
     * Baseline 8: Process creation and teardown lifecycle.
     *
     * <p>Measures full lifecycle cost without observability.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void memoryAllocationProcessLifecycle(Blackhole bh) throws Exception {
        try (var proc = Proc.spawn(() -> "temp-lifecycle", (state, msg) -> state)) {
            bh.consume(proc.tell("test"));
        }
    }

    /**
     * Baseline 9: Batch message throughput (simulates production load).
     *
     * <p>Tests sustained throughput without observability.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(100)
    public void throughputBatchMessaging(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            bh.consume(procRef.tell("batch-" + i));
        }
    }

    /**
     * Baseline 10: Empty control loop overhead.
     *
     * <p>Measures JVM/JMH baseline overhead (empty method). Used to normalize other measurements.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void controlLoopOverhead(Blackhole bh) {
        // Empty baseline for JMH overhead
        bh.consume(1);
    }
}
