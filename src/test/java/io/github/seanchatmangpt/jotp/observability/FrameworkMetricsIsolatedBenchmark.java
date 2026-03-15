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

/**
 * Isolated microbenchmark for FrameworkMetrics.accept() overhead.
 *
 * <p>This benchmark uses @Setup and @TearDown to isolate ONLY the accept() call,
 * eliminating overhead from object allocation, blackhole consumption, and cleanup.
 *
 * <p><strong>Key Improvements over Original Benchmark:</strong>
 *
 * <ul>
 *   <li>Uses @State to hold pre-allocated objects
 *   <li>Measures ONLY the accept() call in @Benchmark methods
 *   <li>Separate benchmarks for disabled vs enabled paths
 *   <li>Separate benchmarks for direct call vs event bus publish
 * </ul>
 *
 * <p><strong>Expected Results:</strong>
 *
 * <ul>
 *   <li>Disabled path (direct accept): <10ns
 *   <li>Enabled path (direct accept): 500-2000ns depending on event type
 *   <li>Event bus publish (no subscribers): <10ns
 *   <li>Event bus publish (disabled subscriber): ~400-500ns (executor.submit() overhead)
 *   <li>Event bus publish (enabled subscriber): ~500-700ns
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(10)
public class FrameworkMetricsIsolatedBenchmark {

    // ── Test Fixtures ─────────────────────────────────────────────────────────────

    @State(Scope.Thread)
    public static class DisabledMetricsState {
        FrameworkMetrics metrics;
        FrameworkEventBus.FrameworkEvent event;

        @Setup(Level.Trial)
        public void setup() {
            // Ensure observability is disabled
            System.clearProperty("jotp.observability.enabled");

            metrics = FrameworkMetrics.create();
            event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    "test-pid", "Proc", 0);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            metrics.close();
        }
    }

    @State(Scope.Thread)
    public static class EnabledMetricsState {
        FrameworkMetrics metrics;
        FrameworkEventBus.FrameworkEvent event;

        @Setup(Level.Trial)
        public void setup() {
            // Enable observability
            System.setProperty("jotp.observability.enabled", "true");

            metrics = FrameworkMetrics.create();
            event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    "test-pid", "Proc", 0);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            metrics.close();
            System.clearProperty("jotp.observability.enabled");
        }
    }

    @State(Scope.Thread)
    public static class EventBusNoSubscribersState {
        FrameworkEventBus eventBus;
        FrameworkEventBus.FrameworkEvent event;

        @Setup(Level.Trial)
        public void setup() {
            System.clearProperty("jotp.observability.enabled");
            eventBus = FrameworkEventBus.create();
            event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    "test-pid", "Proc", 0);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            eventBus.shutdown();
        }
    }

    @State(Scope.Thread)
    public static class EventBusDisabledSubscriberState {
        FrameworkEventBus eventBus;
        FrameworkMetrics metrics;
        FrameworkEventBus.FrameworkEvent event;

        @Setup(Level.Trial)
        public void setup() {
            System.clearProperty("jotp.observability.enabled");

            eventBus = FrameworkEventBus.create();
            metrics = FrameworkMetrics.create();

            event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    "test-pid", "Proc", 0);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            metrics.close();
            eventBus.shutdown();
        }
    }

    @State(Scope.Thread)
    public static class EventBusEnabledSubscriberState {
        FrameworkEventBus eventBus;
        FrameworkMetrics metrics;
        FrameworkEventBus.FrameworkEvent event;

        @Setup(Level.Trial)
        public void setup() {
            System.setProperty("jotp.observability.enabled", "true");

            eventBus = FrameworkEventBus.create();
            metrics = FrameworkMetrics.create();

            event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    "test-pid", "Proc", 0);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            metrics.close();
            eventBus.shutdown();
            System.clearProperty("jotp.observability.enabled");
        }
    }

    // ── Isolated FrameworkMetrics.accept() Benchmarks ─────────────────────────────

    /**
     * Baseline: Direct call to FrameworkMetrics.accept() when disabled.
     *
     * <p>Expected: <10ns. This is the pure overhead of the disabled path:
     * static final read + branch + return.
     */
    @Benchmark
    public void accept_disabled_direct(DisabledMetricsState state) {
        state.metrics.accept(state.event);
    }

    /**
     * Direct call to FrameworkMetrics.accept() when enabled.
     *
     * <p>Expected: 500-2000ns. Full event handling with metrics collection.
     */
    @Benchmark
    public void accept_enabled_direct(EnabledMetricsState state) {
        state.metrics.accept(state.event);
    }

    // ── Event Bus Publish() Benchmarks ─────────────────────────────────────────────

    /**
     * Event bus publish with no subscribers.
     *
     * <p>Expected: <10ns. Early return from subscribers.isEmpty() check.
     */
    @Benchmark
    public void publish_noSubscribers(EventBusNoSubscribersState state) {
        state.eventBus.publish(state.event);
    }

    /**
     * Event bus publish with disabled FrameworkMetrics subscriber.
     *
     * <p>Expected: ~400-500ns. The executor.submit() overhead even though the
     * subscriber's accept() method returns immediately.
     */
    @Benchmark
    public void publish_disabledSubscriber(EventBusDisabledSubscriberState state) {
        state.eventBus.publish(state.event);
    }

    /**
     * Event bus publish with enabled FrameworkMetrics subscriber.
     *
     * <p>Expected: ~500-700ns. executor.submit() + actual event handling.
     */
    @Benchmark
    public void publish_enabledSubscriber(EventBusEnabledSubscriberState state) {
        state.eventBus.publish(state.event);
    }

    // ── Component Breakdown Benchmarks ────────────────────────────────────────────

    /**
     * Measure just the static final field read.
     *
     * <p>Expected: <1ns. L1 cache hit.
     */
    @Benchmark
    public void component_staticFieldRead() {
        boolean enabled = FrameworkMetrics.isEnabled();
        // Consume to prevent dead code elimination
        if (enabled) {
            throw new RuntimeException("Should not reach here");
        }
    }

    /**
     * Measure the branch prediction overhead.
     *
     * <p>Expected: <1ns after warmup (correctly predicted).
     */
    @Benchmark
    public void component_branchPrediction() {
        boolean enabled = false;
        if (!enabled) {
            // Branch taken
        }
    }

    /**
     * Measure empty method call overhead.
     *
     * <p>Expected: <1ns (inlined).
     */
    @Benchmark
    public void component_emptyMethodCall() {
        emptyMethod();
    }

    private static void emptyMethod() {
        // Empty
    }

    /**
     * Measure interface method call overhead.
     *
     * <p>Expected: 5-10ns (virtual dispatch).
     */
    @Benchmark
    public void component_interfaceCall() {
        Consumer<String> consumer = s -> {};
        consumer.accept("test");
    }

    // ── Comparison: Original Benchmark ────────────────────────────────────────────

    /**
     * Original benchmark pattern (including allocation).
     *
     * <p>This should measure ~456ns like the original B16 benchmark.
     * Use this to verify our hypothesis about the benchmark design flaw.
     */
    @Benchmark
    public void accept_originalPattern() {
        FrameworkMetrics metrics = FrameworkMetrics.create();
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        "test-pid", "Proc", 0);

        metrics.accept(event);

        metrics.close();
    }
}
