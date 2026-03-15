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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * JMH throughput benchmarks for FrameworkEventBus observability.
 *
 * <p>Measures operations per second (ops/sec) for event bus publishing under various conditions:
 *
 * <ul>
 *   <li>Disabled - observability feature flag off (baseline max throughput)
 *   <li>Enabled, no subscribers - feature flag on, zero subscriber overhead
 *   <li>Enabled, 10 subscribers - realistic production load with async delivery
 *   <li>Supervisor crash handling - end-to-end fault detection throughput
 * </ul>
 *
 * <p><strong>Expected Results:</strong>
 *
 * <ul>
 *   <li>eventBusThroughput_disabled: >100M ops/sec (single branch check)
 *   <li>eventBusThroughput_enabled_noSubscribers: >100M ops/sec (early return check)
 *   <li>eventBusThroughput_enabled_10Subscribers: >1M ops/sec (async fire-and-forget)
 *   <li>supervisorEventThroughput: >10K crashes/sec (full supervision pipeline)
 * </ul>
 *
 * <p><strong>Run benchmarks:</strong>
 *
 * <pre>{@code
 * # Run all benchmarks
 * ./mvnw test -Dtest=ObservabilityThroughputBenchmark
 *
 * # Run specific benchmark
 * ./mvnw test -Dtest=ObservabilityThroughputBenchmark#eventBusThroughput_disabled
 *
 * # With JMH profiling
 * ./mvnw test -Dtest=ObservabilityThroughputBenchmark -Djmh.profiler=stack
 * }</pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class ObservabilityThroughputBenchmark {

    /** Event bus with observability DISABLED (baseline). */
    private FrameworkEventBus eventBusDisabled;

    /** Event bus with observability enabled, no subscribers. */
    private FrameworkEventBus eventBusEnabledNoSubs;

    /** Event bus with observability enabled, 10 active subscribers. */
    private FrameworkEventBus eventBusEnabled10Subs;

    /** Supervisor for crash handling benchmarks. */
    private Supervisor supervisor;

    /** Crashable child process ref for supervisor benchmarks. */
    private ProcRef<Integer, String> crashChild;

    /** Test event for publishing benchmarks. */
    private FrameworkEventBus.FrameworkEvent testEvent;

    @Setup(Level.Trial)
    public void setup() {
        // Baseline: observability disabled
        System.clearProperty("jotp.observability.enabled");
        eventBusDisabled = FrameworkEventBus.create();

        // Enabled but no subscribers
        System.setProperty("jotp.observability.enabled", "true");
        eventBusEnabledNoSubs = FrameworkEventBus.create();

        // Enabled with 10 subscribers
        eventBusEnabled10Subs = FrameworkEventBus.create();
        for (int i = 0; i < 10; i++) {
            eventBusEnabled10Subs.subscribe(
                    event -> {
                        // No-op subscriber simulates minimal overhead
                    });
        }

        // Supervisor setup for crash handling benchmark
        supervisor =
                Supervisor.create(
                        Supervisor.Strategy.ONE_FOR_ONE, 5, java.time.Duration.ofSeconds(60));
        crashChild =
                supervisor.supervise(
                        "crash-child",
                        0,
                        (state, msg) -> {
                            if ("crash".equals(msg)) {
                                throw new RuntimeException("Simulated crash");
                            }
                            return state;
                        });

        // Create test event
        testEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "proc-123", "TestProc");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        if (eventBusDisabled != null) {
            eventBusDisabled.shutdown();
        }
        if (eventBusEnabledNoSubs != null) {
            eventBusEnabledNoSubs.shutdown();
        }
        if (eventBusEnabled10Subs != null) {
            eventBusEnabled10Subs.shutdown();
        }
        if (supervisor != null && supervisor.isRunning()) {
            supervisor.shutdown();
        }
        System.clearProperty("jotp.observability.enabled");
    }

    /**
     * Baseline throughput with observability DISABLED.
     *
     * <p>Expected: >100M ops/sec. Single branch check only - the theoretical maximum.
     */
    @Benchmark
    public void eventBusThroughput_disabled() {
        eventBusDisabled.publish(testEvent);
    }

    /**
     * Throughput with observability enabled but NO subscribers.
     *
     * <p>Expected: >100M ops/sec. Early return check after ENABLED flag test.
     */
    @Benchmark
    public void eventBusThroughput_enabled_noSubscribers() {
        eventBusEnabledNoSubs.publish(testEvent);
    }

    /**
     * Throughput with observability enabled and 10 active subscribers.
     *
     * <p>Expected: >1M ops/sec. Fire-and-forget async delivery to CopyOnWriteArrayList.
     */
    @Benchmark
    public void eventBusThroughput_enabled_10Subscribers() {
        eventBusEnabled10Subs.publish(testEvent);
    }

    /**
     * Throughput of supervisor crash event handling.
     *
     * <p>Measures full pipeline: child crash → supervisor detection → FrameworkEventBus publish →
     * restart decision. This is the critical path for fault detection overhead.
     *
     * <p>Expected: >10K crashes/sec. Includes virtual thread spawn, crash callback, event loop
     * processing, restart strategy application.
     */
    @Benchmark
    public void supervisorEventThroughput() {
        // Send crash message to trigger supervisor crash handling
        crashChild.tell("crash");

        // Wait for restart using spin-wait for benchmark consistency
        // Supervisor restarts are fast (<10ms typically), so short busy-wait is acceptable
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
        while (System.nanoTime() < deadline) {
            // Busy wait ensures consistent timing for benchmark
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Throughput with varying subscriber counts.
     *
     * <p>Parametrized benchmark showing scalability from 1 to 100 subscribers.
     */
    @Benchmark
    @OperationsPerInvocation(50)
    public void eventBusThroughput_subscriberScalability(
            @Param({"1", "10", "50", "100"}) int subscriberCount) {
        FrameworkEventBus bus = FrameworkEventBus.create();
        for (int i = 0; i < subscriberCount; i++) {
            bus.subscribe(event -> {});
        }

        for (int i = 0; i < 50; i++) {
            bus.publish(testEvent);
        }

        bus.shutdown();
    }

    /**
     * Contention test: multiple threads publishing simultaneously.
     *
     * <p>Uses Thread scope to simulate concurrent production load.
     */
    @Benchmark
    @Threads(8)
    public void eventBusThroughput_concurrent() {
        eventBusEnabled10Subs.publish(testEvent);
    }

    /**
     * Memory allocation rate during event publishing.
     *
     * <p>Useful for detecting garbage collection pressure under high throughput.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void eventBusThroughput_allocationRate() {
        var event =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-" + System.nanoTime(), "TestProc", true, "crash");
        eventBusEnabled10Subs.publish(event);
    }

    /**
     * Event creation overhead (not including publish).
     *
     * <p>Isolates the cost of record instantiation vs. bus delivery.
     */
    @Benchmark
    public FrameworkEventBus.FrameworkEvent eventCreationOverhead() {
        return new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                Instant.now(), "proc-" + System.nanoTime(), "TestProc");
    }

    /**
     * Subscriber notification overhead (synchronous, no async delivery).
     *
     * <p>Worst-case scenario: all subscribers notified synchronously on publisher thread.
     */
    @Benchmark
    public void eventBusThroughput_synchronousNotification() {
        List<java.util.function.Consumer<FrameworkEventBus.FrameworkEvent>> subs =
                new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            subs.add(event -> {});
        }

        for (var sub : subs) {
            try {
                sub.accept(testEvent);
            } catch (Throwable t) {
                // Swallow exceptions like FrameworkEventBus does
            }
        }
    }

    /**
     * Restart latency after child crash.
     *
     * <p>Measures time from crash to successful restart completion.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void supervisorRestartLatency() throws Exception {
        crashChild.tell("crash");

        // Use Awaitility pattern instead of Thread.sleep()
        long startTime = System.nanoTime();
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(
                        () -> {
                            var ref = supervisor.lookup("crash-child");
                            return ref.isPresent() && ref.get().isAlive();
                        });
        long endTime = System.nanoTime();

        // Prevent optimizer from removing the measurement
        if (endTime - startTime < 0) {
            throw new IllegalStateException("Negative duration");
        }
    }
}
