import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import io.github.seanchatmangpt.jotp.observability.FrameworkMetrics;

import java.time.Duration;

/**
 * Quick performance test runner for observability infrastructure.
 */
public class ObservabilityPerformanceRunner {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 50_000;

    public static void main(String[] args) {
        System.out.println("=== JOTP Observability Performance Test ===");
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println();

        // Test 1: Baseline Proc.tell() performance
        System.out.println("--- Test 1: Baseline Proc.tell() Performance ---");
        var baselineNs = benchmarkProcTell();
        System.out.println("Baseline Proc.tell(): " + baselineNs + " ns/op");
        System.out.println();

        // Test 2: Event bus publish overhead
        System.out.println("--- Test 2: Event Bus Publish Overhead ---");
        var eventBusNs = benchmarkEventBus();
        System.out.println("EventBus.publish(): " + eventBusNs + " ns/op");
        System.out.println();

        // Test 3: With observability enabled
        System.out.println("--- Test 3: With Observability Enabled ---");
        System.setProperty("jotp.observability.enabled", "true");
        var withObservabilityNs = benchmarkProcTell();
        System.out.println("Proc.tell() with observability: " + withObservabilityNs + " ns/op");
        double overhead = ((withObservabilityNs - baselineNs) * 100.0) / baselineNs;
        System.out.println("Overhead: " + String.format("%.2f", overhead) + "%");
        System.out.println();

        System.out.println("=== Test Complete ===");
    }

    private static double benchmarkProcTell() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var proc = Proc.spawn(() -> "initial", (state, msg) -> state);
            proc.tell("test");
            proc.ref().shutdown();
        }

        // Measurement
        long totalNs = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var proc = Proc.spawn(() -> "initial", (state, msg) -> state);

            long start = System.nanoTime();
            proc.tell("test");
            long end = System.nanoTime();

            totalNs += (end - start);
            proc.ref().shutdown();
        }

        double avgNs = (double) totalNs / MEASUREMENT_ITERATIONS;
        System.out.println("  Operations: " + MEASUREMENT_ITERATIONS);
        System.out.println("  Total time: " + (totalNs / 1_000_000) + " ms");
        System.out.println("  Average latency: " + String.format("%.2f", avgNs) + " ns");

        return avgNs;
    }

    private static double benchmarkEventBus() {
        var eventBus = FrameworkEventBus.create();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                "test-process", "proc-1", "TEST"
            );
            eventBus.publish(event);
        }

        // Measurement
        long totalNs = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            var event = new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                "test-process", "proc-" + i, "TEST"
            );

            long start = System.nanoTime();
            eventBus.publish(event);
            long end = System.nanoTime();

            totalNs += (end - start);
        }

        eventBus.shutdown();

        double avgNs = (double) totalNs / MEASUREMENT_ITERATIONS;
        System.out.println("  Operations: " + MEASUREMENT_ITERATIONS);
        System.out.println("  Total time: " + (totalNs / 1_000_000) + " ms");
        System.out.println("  Average latency: " + String.format("%.2f", avgNs) + " ns");

        return avgNs;
    }
}
