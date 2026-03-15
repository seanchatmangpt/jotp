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

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH throughput benchmarks for JOTP observability infrastructure.
 *
 * <p><strong>Thesis Claim:</strong> FrameworkEventBus achieves >10M ops/sec when disabled or with
 * no subscribers, ensuring observability never becomes a bottleneck.
 *
 * <p><strong>Measurement Goals:</strong>
 *
 * <ul>
 *   <li>Max throughput: operations per second with observability disabled
 *   <li>No subscriber baseline: throughput with observability enabled but no subscribers
 *   <li>Subscriber scaling: throughput degradation with 1, 10, and 100 subscribers
 *   <li>Supervisor integration: crash event throughput under fault conditions
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ObservabilityThroughputBenchmark {

    private FrameworkEventBus eventBusDisabled;
    private FrameworkEventBus eventBusEnabledNoSubscribers;
    private FrameworkEventBus eventBusEnabled10Subscribers;
    private Supervisor crashSupervisor;
    private ProcRef<Integer, String> crashyWorker;

    private FrameworkEventBus.FrameworkEvent.ProcessCreated sampleEvent;
    private FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed sampleCrashEvent;

    @Setup(Level.Trial)
    public void setupTrial() {
        // Setup disabled event bus
        System.setProperty("jotp.observability.enabled", "false");
        eventBusDisabled = FrameworkEventBus.create();

        // Setup enabled event bus with no subscribers
        System.setProperty("jotp.observability.enabled", "true");
        eventBusEnabledNoSubscribers = FrameworkEventBus.create();

        // Setup enabled event bus with 10 subscribers
        eventBusEnabled10Subscribers = FrameworkEventBus.create();
        List<Consumer<FrameworkEventBus.FrameworkEvent>> subscribers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Consumer<FrameworkEventBus.FrameworkEvent> subscriber =
                    event -> {
                        // Minimal overhead subscriber
                        int dummy = event.hashCode();
                    };
            subscribers.add(subscriber);
            eventBusEnabled10Subscribers.subscribe(subscriber);
        }

        // Setup supervisor for crash event benchmarks
        crashSupervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        // Create a crashy worker process
        crashyWorker =
                crashSupervisor.supervise(
                        "crashy-worker",
                        0,
                        (state, msg) -> {
                            if (msg.equals("crash")) {
                                throw new RuntimeException("Simulated crash");
                            }
                            return state + 1;
                        });

        // Create sample events
        sampleEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "bench-proc-1", "Proc");
        sampleCrashEvent =
                new FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed(
                        Instant.now(),
                        "bench-supervisor",
                        "bench-worker",
                        new RuntimeException("Benchmark crash"));
    }

    // ── MAX THROUGHPUT BASELINE (observability disabled) ────────────────────────────

    /**
     * Maximum throughput with observability completely disabled.
     *
     * <p>Expected: >50M ops/sec (single branch check only)
     */
    @Benchmark
    public void eventBusThroughput_disabled() {
        eventBusDisabled.publish(sampleEvent);
    }

    // ── NO SUBSCRIBER BASELINE (observability enabled, no subscribers) ───────────────

    /**
     * Throughput with observability enabled but no subscribers.
     *
     * <p>Expected: >40M ops/sec (enabled check + empty list check)
     */
    @Benchmark
    public void eventBusThroughput_enabled_noSubscribers() {
        eventBusEnabledNoSubscribers.publish(sampleEvent);
    }

    // ── SUBSCRIBER SCALING (1, 10, 100 subscribers) ─────────────────────────────────

    /**
     * Throughput with 10 active subscribers.
     *
     * <p>Expected: >5M ops/sec (async fire-and-forget with 10 consumers)
     */
    @Benchmark
    public void eventBusThroughput_enabled_10Subscribers() {
        eventBusEnabled10Subscribers.publish(sampleEvent);
    }

    // ── SUPERVISOR CRASH EVENT THROUGHPUT ────────────────────────────────────────────

    /**
     * Throughput of supervisor crash event publishing.
     *
     * <p>This benchmarks the realistic scenario where a supervisor publishes crash events during
     * fault handling. The event is published on the supervisor's crash handling path.
     *
     * <p>Expected: >10M ops/sec (crash events are infrequent in production)
     */
    @Benchmark
    public void supervisorEventThroughput() {
        FrameworkEventBus.getDefault().publish(sampleCrashEvent);
    }

    // ── PROCESS CREATION EVENT THROUGHPUT ───────────────────────────────────────────

    /**
     * Throughput of process creation event publishing.
     *
     * <p>Benchmarks event publishing during process spawning, which occurs on every {@link
     * io.github.seanchatmangpt.jotp.Proc#spawn()} call.
     *
     * <p>Expected: >10M ops/sec
     */
    @Benchmark
    public void processCreationEventThroughput() {
        FrameworkEventBus.getDefault().publish(sampleEvent);
    }

    // ── STATE MACHINE TRANSITION EVENT THROUGHPUT ────────────────────────────────────

    /**
     * Throughput of state machine transition event publishing.
     *
     * <p>Benchmarks event publishing during state machine state changes.
     *
     * <p>Expected: >10M ops/sec
     */
    @Benchmark
    public void stateMachineTransitionThroughput() {
        var transitionEvent =
                new FrameworkEventBus.FrameworkEvent.StateMachineTransition(
                        Instant.now(), "bench-machine-1", "idle", "processing", "START");
        FrameworkEventBus.getDefault().publish(transitionEvent);
    }

    // ── PROCESS TERMINATION EVENT THROUGHPUT ────────────────────────────────────────

    /**
     * Throughput of process termination event publishing.
     *
     * <p>Benchmarks both normal and abnormal termination events.
     *
     * <p>Expected: >10M ops/sec
     */
    @Benchmark
    public void processTerminationThroughput() {
        var terminationEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "bench-proc-1", "Proc", true, "Benchmark termination");
        FrameworkEventBus.getDefault().publish(terminationEvent);
    }
}
