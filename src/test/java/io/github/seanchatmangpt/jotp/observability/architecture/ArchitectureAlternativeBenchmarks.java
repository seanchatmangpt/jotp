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

package io.github.seanchatmangpt.jotp.observability.architecture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Comprehensive JMH benchmark comparing five alternative architectures for framework observability.
 *
 * <p><strong>Objective:</strong> Identify which architectural approach can achieve the <100ns
 * target for zero-cost observability when disabled.
 *
 * <p><strong>Architectures Evaluated:</strong>
 *
 * <ol>
 *   <li><b>Current Baseline:</b> Boolean check + async executor (current implementation)
 *   <li><b>Compile-Time Elimination:</b> @ConditionalOnProperty style code generation
 *   <li><b>Method Handle Indirection:</b> MethodHandle with mutable target switching
 *   <li><b>Static Final Delegation:</b> Interface-based delegation with compile-time constant
 *   <li><b>Unsafe Memory Operations:</b> sun.misc.Unsafe for direct memory writes
 * </ol>
 *
 * <p><strong>Test Event:</strong> ProcessCreated event (most frequent framework event)
 *
 * <p><strong>Metrics:</strong> Average latency (ns/op), throughput (ops/ms), allocation rate
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
public class ArchitectureAlternativeBenchmarks {

    // ── Test Event ─────────────────────────────────────────────────────────────

    /**
     * Test event representing ProcessCreated (most frequent framework event).
     * Record for minimal allocation overhead.
     */
    record TestEvent(long timestamp, String processId, String processType) {
        static TestEvent create() {
            return new TestEvent(System.nanoTime(), "proc-" + System.nanoTime(), "test-worker");
        }
    }

    // ── Architecture 1: Current Baseline ───────────────────────────────────────

    /**
     * Current implementation: Boolean check + async executor.
     *
     * <p>Baseline from existing FrameworkEventBus implementation.
     */
    @Benchmark
    public void baseline_currentImplementation(Blackhole bh) {
        var event = TestEvent.create();
        boolean enabled = Boolean.getBoolean("jotp.observability.enabled");
        if (enabled) {
            // Simulate async executor submission
            bh.consume(event);
        }
    }

    // ── Architecture 2: Compile-Time Elimination ──────────────────────────────

    /**
     * Compile-time elimination using two separate implementations selected at build time.
     *
     * <p><strong>Strategy:</strong> Generate two classes:
     * <ul>
     *   <li>EnabledEventBus - full implementation
     *   <li>NoOpEventBus - empty methods (JIT elides call entirely)
     * </ul>
     *
     * <p><strong>Benefit:</strong> No runtime branch, JIT can inline and eliminate entirely
     */
    static final class CompileTimeEliminationEventBus {
        private static final boolean ENABLED =
                Boolean.getBoolean("jotp.observability.enabled");

        private static final EventBusDelegate DELEGATE =
                ENABLED ? new EnabledEventBus() : new NoOpEventBus();

        interface EventBusDelegate {
            void publish(TestEvent event);
        }

        static final class EnabledEventBus implements EventBusDelegate {
            @Override
            public void publish(TestEvent event) {
                // Full implementation with async dispatch
                // In production: executor.submit(() -> notifySubscribers(event));
            }
        }

        static final class NoOpEventBus implements EventBusDelegate {
            @Override
            public void publish(TestEvent event) {
                // Empty method - JIT should eliminate entirely
            }
        }

        static void publish(TestEvent event) {
            DELEGATE.publish(event);
        }
    }

    @Benchmark
    public void compileTimeElimination(Blackhole bh) {
        var event = TestEvent.create();
        CompileTimeEliminationEventBus.publish(event);
    }

    // ── Architecture 3: Method Handle Indirection ────────────────────────────

    /**
     * MethodHandle indirection with mutable target switching.
     *
     * <p><strong>Strategy:</strong> Use MethodHandle to switch between implementations
     * at runtime without branch checks.
     *
     * <p><strong>Benefit:</strong> Direct invocation via invokeExact, no conditional branching
     */
    static final class MethodHandleEventBus {
        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
        private static final MethodType PUBLISH_TYPE =
                MethodType.methodType(void.class, TestEvent.class);

        private static volatile MethodHandle publishHandle;

        static {
            try {
                boolean enabled = Boolean.getBoolean("jotp.observability.enabled");
                if (enabled) {
                    publishHandle =
                            LOOKUP.findStatic(
                                    EnabledPublisher.class, "publish", PUBLISH_TYPE);
                } else {
                    publishHandle =
                            LOOKUP.findStatic(
                                    NoOpPublisher.class, "publish", PUBLISH_TYPE);
                }
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final class EnabledPublisher {
            static void publish(TestEvent event) {
                // Full implementation
            }
        }

        static final class NoOpPublisher {
            static void publish(TestEvent event) {
                // No-op
            }
        }

        static void publish(TestEvent event) {
            try {
                publishHandle.invokeExact(event);
            } catch (Throwable e) {
                // Fall through
            }
        }
    }

    @Benchmark
    public void methodHandleIndirection(Blackhole bh) {
        var event = TestEvent.create();
        MethodHandleEventBus.publish(event);
    }

    // ── Architecture 4: Static Final Delegation ───────────────────────────────

    /**
     * Static final delegation with interface-based constant resolution.
     *
     * <p><strong>Strategy:</strong> Use static final field with interface type,
     * resolved at class initialization time.
     *
     * <p><strong>Benefit:</strong> JVM can inline interface calls when target is constant
     */
    static final class StaticFinalDelegationEventBus {
        interface Publisher {
            void publish(TestEvent event);
        }

        private static final Publisher PUBLISHER;

        static {
            boolean enabled = Boolean.getBoolean("jotp.observability.enabled");
            PUBLISHER = enabled ? StaticFinalDelegationEventBus::publishEnabled
                                : StaticFinalDelegationEventBus::publishNoOp;
        }

        private static void publishEnabled(TestEvent event) {
            // Full implementation
        }

        private static void publishNoOp(TestEvent event) {
            // No-op
        }

        static void publish(TestEvent event) {
            PUBLISHER.publish(event);
        }
    }

    @Benchmark
    public void staticFinalDelegation(Blackhole bh) {
        var event = TestEvent.create();
        StaticFinalDelegationEventBus.publish(event);
    }

    // ── Architecture 5: Unsafe Memory Operations ──────────────────────────────

    /**
     * Unsafe memory operations using sun.misc.Unsafe.
     *
     * <p><strong>Strategy:</strong> Direct memory writes without safety checks.
     *
     * <p><strong>Benefit:</strong> Eliminates all bounds checking, null checks, and safety
     * validations in the hottest path.
     *
     * <p><strong>Warning:</strong> Use only in validated production code - can crash JVM if misused
     */
    static final class UnsafeEventBus {
        private static final sun.misc.Unsafe UNSAFE;
        private static final boolean ENABLED;

        static {
            try {
                Field unsafeField =
                        sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) unsafeField.get(null);
                ENABLED = Boolean.getBoolean("jotp.observability.enabled");
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static volatile long eventCount;

        static void publish(TestEvent event) {
            if (ENABLED) {
                // Direct atomic increment without safety checks
                UNSAFE.getAndAddLong(UnsafeEventBus.class,
                        UNSAFE.objectFieldOffset(eventCountField()), 1L);
            }
        }

        private static Field eventCountField() {
            try {
                return UnsafeEventBus.class.getDeclaredField("eventCount");
            } catch (NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    @Benchmark
    public void unsafeMemoryOperations(Blackhole bh) {
        var event = TestEvent.create();
        UnsafeEventBus.publish(event);
    }

    // ── Control: No Overhead Baseline ────────────────────────────────────────

    /**
     * Pure baseline with zero overhead - just creates event and discards it.
     * This represents the theoretical minimum overhead.
     */
    @Benchmark
    public void control_noOverhead(Blackhole bh) {
        var event = TestEvent.create();
        bh.consume(event);
    }

    // ── Control: Branch Prediction Test ──────────────────────────────────────

    /**
     * Test branch prediction impact with always-false condition.
     * Simulates the worst-case scenario for branch misprediction.
     */
    @Benchmark
    public void control_branchPrediction(Blackhole bh) {
        var event = TestEvent.create();
        if (false) { // Always false - perfect branch prediction
            bh.consume(event);
        }
    }
}
