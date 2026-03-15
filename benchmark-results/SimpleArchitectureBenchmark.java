import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Simple architecture comparison benchmark for framework observability.
 *
 * <p>Measures latency of 5 alternative architectures targeting <100ns overhead:
 * 1. Current baseline (Boolean check)
 * 2. Compile-time elimination (Interface delegation)
 * 3. Method handle indirection
 * 4. Static final delegation
 * 5. Unsafe memory operations
 *
 * <p>Usage: java SimpleArchitectureBenchmark [iterations]
 */
public class SimpleArchitectureBenchmark {

    // Test event record
    record TestEvent(long timestamp, String processId, String processType) {
        static TestEvent create() {
            return new TestEvent(
                    System.nanoTime(),
                    "proc-" + System.nanoTime(),
                    "test-worker");
        }
    }

    // ── Architecture 1: Current Baseline ───────────────────────────────────────

    static class BaselineEventBus {
        private static final boolean ENABLED = false; // Disabled for test

        static void publish(TestEvent event) {
            if (!ENABLED) {
                return; // Fast path
            }
            // Async dispatch would go here
        }
    }

    // ── Architecture 2: Compile-Time Elimination ──────────────────────────────

    static class CompileTimeEliminationEventBus {
        interface EventBusDelegate {
            void publish(TestEvent event);
        }

        static final class EnabledEventBus implements EventBusDelegate {
            @Override
            public void publish(TestEvent event) {
                // Full implementation
            }
        }

        static final class NoOpEventBus implements EventBusDelegate {
            @Override
            public void publish(TestEvent event) {
                // Empty - JIT will eliminate
            }
        }

        private static final EventBusDelegate DELEGATE = new NoOpEventBus();

        static void publish(TestEvent event) {
            DELEGATE.publish(event);
        }
    }

    // ── Architecture 3: Method Handle Indirection ────────────────────────────

    static class MethodHandleEventBus {
        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
        private static final MethodType PUBLISH_TYPE =
                MethodType.methodType(void.class, TestEvent.class);

        private static volatile MethodHandle publishHandle;

        static {
            try {
                publishHandle =
                        LOOKUP.findStatic(NoOpPublisher.class, "publish", PUBLISH_TYPE);
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

    // ── Architecture 4: Static Final Delegation ───────────────────────────────

    static class StaticFinalDelegationEventBus {
        interface Publisher {
            void publish(TestEvent event);
        }

        private static final Publisher PUBLISHER =
                StaticFinalDelegationEventBus::publishNoOp;

        private static void publishEnabled(TestEvent event) {
            // Full implementation
        }

        private static void publishNoOp(TestEvent event) {
            // Empty - JIT will eliminate
        }

        static void publish(TestEvent event) {
            PUBLISHER.publish(event);
        }
    }

    // ── Architecture 5: Unsafe Memory Operations ──────────────────────────────

    static class UnsafeEventBus {
        private static final sun.misc.Unsafe UNSAFE;
        private static final long EVENT_COUNT_OFFSET;
        private static final boolean ENABLED = false;

        static {
            try {
                Field unsafeField =
                        sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                UNSAFE = (sun.misc.Unsafe) unsafeField.get(null);
                EVENT_COUNT_OFFSET =
                        UNSAFE.objectFieldOffset(
                                UnsafeEventBus.class.getDeclaredField("eventCount"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static volatile long eventCount;

        static void publish(TestEvent event) {
            if (ENABLED) {
                UNSAFE.getAndAddLong(UnsafeEventBus.class, EVENT_COUNT_OFFSET, 1L);
            }
        }
    }

    // ── Benchmark Harness ─────────────────────────────────────────────────────

    static class BenchmarkResult {
        final String name;
        final double avgNanos;
        final double minNanos;
        final double maxNanos;
        final double throughputOpsPerMs;

        BenchmarkResult(String name, List<Long> nanos) {
            this.name = name;
            long total = nanos.stream().mapToLong(Long::longValue).sum();
            this.avgNanos = (double) total / nanos.size();
            this.minNanos = nanos.stream().mapToLong(Long::longValue).min().orElse(0);
            this.maxNanos = nanos.stream().mapToLong(Long::longValue).max().orElse(0);
            this.throughputOpsPerMs = 1_000_000.0 / avgNanos;
        }

        @Override
        public String toString() {
            return String.format(
                    "%-40s %8.1f ns/op  (min: %8.1f, max: %8.1f)  %10.0f ops/ms",
                    name, avgNanos, minNanos, maxNanos, throughputOpsPerMs);
        }
    }

    static BenchmarkResult benchmark(String name, Consumer<TestEvent> publisher, int iterations) {
        // Warmup
        for (int i = 0; i < 10_000; i++) {
            publisher.accept(TestEvent.create());
        }

        // Measurement
        List<Long> nanos = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            var event = TestEvent.create();
            long start = System.nanoTime();
            publisher.accept(event);
            long end = System.nanoTime();
            nanos.add(end - start);
        }

        return new BenchmarkResult(name, nanos);
    }

    // ── Main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;

        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Architecture Alternative Benchmarks                           ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Iterations:    " + String.format("%,d", iterations));
        System.out.println("  Test Event:    ProcessCreated (most frequent)");
        System.out.println("  Target:        <100ns per operation");
        System.out.println();
        System.out.println("Running benchmarks...");
        System.out.println();

        long start = System.nanoTime();

        List<BenchmarkResult> results = List.of(
                benchmark(
                        "Control: No Overhead (baseline)",
                        event -> {},
                        iterations),
                benchmark(
                        "Architecture 1: Current Baseline (Boolean check)",
                        BaselineEventBus::publish,
                        iterations),
                benchmark(
                        "Architecture 2: Compile-Time Elimination",
                        CompileTimeEliminationEventBus::publish,
                        iterations),
                benchmark(
                        "Architecture 3: Method Handle Indirection",
                        MethodHandleEventBus::publish,
                        iterations),
                benchmark(
                        "Architecture 4: Static Final Delegation",
                        StaticFinalDelegationEventBus::publish,
                        iterations),
                benchmark(
                        "Architecture 5: Unsafe Memory Operations",
                        UnsafeEventBus::publish,
                        iterations));

        long end = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(end - start);

        // Print results
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Results                                                       ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Header
        System.out.printf(
                "%-40s %12s  %12s  %12s  %12s%n",
                "Architecture", "Avg (ns)", "Min (ns)", "Max (ns)", "Ops/ms");
        System.out.println(
                "─────────────────────────────────────────────────────────────────────────────");

        // Results
        for (BenchmarkResult result : results) {
            System.out.println(result);
        }

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Analysis                                                      ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Calculate improvements
        BenchmarkResult baseline = results.get(1); // Current baseline
        BenchmarkResult control = results.get(0); // No overhead

        System.out.println("Current Baseline: " + String.format("%.1f", baseline.avgNanos) + " ns/op");
        System.out.println("Control (no overhead): " + String.format("%.1f", control.avgNanos) + " ns/op");
        System.out.println();

        System.out.println("Improvements vs. Baseline:");
        System.out.println();

        for (int i = 2; i < results.size(); i++) {
            BenchmarkResult result = results.get(i);
            double improvement =
                    ((baseline.avgNanos - result.avgNanos) / baseline.avgNanos) * 100.0;
            boolean meetsTarget = result.avgNanos < 100.0;
            String status = meetsTarget ? "✅ YES" : "❌ NO";

            System.out.printf(
                    "  %-40s %6.1f%%  Target met: %s%n",
                    result.name, improvement, status);
        }

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Recommendation                                               ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Find best performer
        BenchmarkResult best =
                results.stream()
                        .skip(2) // Skip control and baseline
                        .min((a, b) -> Double.compare(a.avgNanos, b.avgNanos))
                        .orElse(results.get(2));

        System.out.println("Best Performer: " + best.name);
        System.out.println(
                "Performance:   "
                        + String.format("%.1f", best.avgNanos)
                        + " ns/op ("
                        + String.format("%.0f", best.throughputOpsPerMs)
                        + " ops/ms)");
        System.out.println(
                "Improvement:   "
                        + String.format(
                                "%.1f",
                                ((baseline.avgNanos - best.avgNanos) / baseline.avgNanos) * 100.0)
                        + "% vs. baseline");
        System.out.println(
                "Target Status: "
                        + (best.avgNanos < 100.0 ? "✅ ACHIEVED (<100ns)" : "❌ MISSED (>100ns)"));
        System.out.println();
        System.out.printf("Benchmark completed in %d ms%n", durationMs);
    }
}
