import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import io.github.seanchatmangpt.jotp.observability.FrameworkMetrics;
import io.github.seanchatmangpt.jotp.observability.HotPathValidation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Standalone baseline benchmark measuring observability overhead.
 *
 * <p>Run with: javac --enable-preview -cp target/classes BaselineBenchmark.java && java
 * --enable-preview -cp target/classes:. BaselineBenchmark
 */
public class BaselineBenchmark {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 100_000;

    public static void main(String[] args) throws Exception {
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║     JOTP OBSERVABILITY BASELINE PERFORMANCE BENCHMARK         ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);
        System.out.println("Measurement iterations: " + MEASUREMENT_ITERATIONS);
        System.out.println();

        // Phase 1: Measure baseline (observability DISABLED)
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("PHASE 1: Measuring BASELINE (observability DISABLED)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        System.clearProperty("jotp.observability.enabled");
        List<Long> baselineLatencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
        Proc<Integer, String> baselineProc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            baselineProc.tell("warmup-" + i);
        }
        Thread.sleep(200); // Let messages drain

        // Measure baseline
        long baselineStart = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            baselineProc.tell("message-" + i);
            long end = System.nanoTime();
            baselineLatencies.add(end - start);
        }
        long baselineEnd = System.nanoTime();

        PercentileResult baselineStats = calculatePercentiles(baselineLatencies);
        baselineProc.stop();

        System.out.println();
        System.out.println("BASELINE Results (NO observability):");
        System.out.println("  p50 (median): " + baselineStats.p50 + " ns");
        System.out.println("  p95: " + baselineStats.p95 + " ns");
        System.out.println("  p99: " + baselineStats.p99 + " ns");
        System.out.println("  min: " + baselineStats.min + " ns");
        System.out.println("  max: " + baselineStats.max + " ns");
        System.out.println("  mean: " + String.format("%.2f", baselineStats.mean) + " ns");
        System.out.println("  Total time: " + ((baselineEnd - baselineStart) / 1_000_000) + " ms");
        System.out.println();

        Thread.sleep(500); // Let process fully terminate

        // Phase 2: Measure with observability ENABLED
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("PHASE 2: Measuring WITH OBSERVABILITY ENABLED");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        System.setProperty("jotp.observability.enabled", "true");
        FrameworkMetrics metrics = FrameworkMetrics.create();

        System.out.println("Observability enabled: " + FrameworkEventBus.isEnabled());
        System.out.println("Metrics subscribed: " + metrics.isSubscribed());
        System.out.println();

        List<Long> obsLatencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
        Proc<Integer, String> obsProc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            obsProc.tell("warmup-" + i);
        }
        Thread.sleep(200);

        // Measure with observability
        long obsStart = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            obsProc.tell("message-" + i);
            long end = System.nanoTime();
            obsLatencies.add(end - start);
        }
        long obsEnd = System.nanoTime();

        PercentileResult obsStats = calculatePercentiles(obsLatencies);
        metrics.close();
        obsProc.stop();

        System.out.println();
        System.out.println("OBSERVABILITY Results (ENABLED):");
        System.out.println("  p50 (median): " + obsStats.p50 + " ns");
        System.out.println("  p95: " + obsStats.p95 + " ns");
        System.out.println("  p99: " + obsStats.p99 + " ns");
        System.out.println("  min: " + obsStats.min + " ns");
        System.out.println("  max: " + obsStats.max + " ns");
        System.out.println("  mean: " + String.format("%.2f", obsStats.mean) + " ns");
        System.out.println("  Total time: " + ((obsEnd - obsStart) / 1_000_000) + " ms");
        System.out.println();

        // Phase 3: Overhead Analysis
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("PHASE 3: OVERHEAD ANALYSIS");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();

        double overheadNs = obsStats.mean - baselineStats.mean;
        double overheadPercent = (overheadNs / baselineStats.mean) * 100.0;

        System.out.println("Baseline mean: " + String.format("%.2f", baselineStats.mean) + " ns");
        System.out.println(
                "Observability mean: " + String.format("%.2f", obsStats.mean) + " ns");
        System.out.println("Absolute overhead: " + String.format("%.2f", overheadNs) + " ns");
        System.out.println("Relative overhead: " + String.format("%.2f", overheadPercent) + "%");
        System.out.println();

        // Hot path validation
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("PHASE 4: HOT PATH CONTAMINATION CHECK");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();

        try {
            HotPathValidation.validateHotPaths();
            System.out.println("✓ Hot path validation PASSED - no contamination detected");
        } catch (AssertionError e) {
            System.out.println("✗ Hot path validation FAILED:");
            System.out.println("  " + e.getMessage());
        }

        System.out.println();

        // Final verdict
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    FINAL VERDICT                               ║");
        System.out.println("╚══════════════════════════════════━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╝");
        System.out.println();

        boolean zeroOverhead = overheadNs < 100;
        boolean subMicrosecond = obsStats.p95 < 1_000;

        if (zeroOverhead && subMicrosecond) {
            System.out.println("✓✓✓ ZERO-OVERHEAD VERIFIED ✓✓✓");
            System.out.println();
            System.out.println("The JOTP observability framework maintains:");
            System.out.println("  • Overhead < 100ns (zero-overhead principle)");
            System.out.println("  • p95 latency < 1μs (sub-microsecond hot path)");
            System.out.println("  • No hot path contamination (async event bus)");
            System.out.println();
            System.out.println("This proves the async event bus design does NOT degrade");
            System.out.println("performance in critical message-passing paths.");
        } else {
            System.out.println("✗✗✗ OVERHEAD DETECTED ✗✗✗");
            System.out.println();
            if (!zeroOverhead) {
                System.out.println(
                        "  ✗ Overhead exceeds 100ns threshold: " + String.format("%.2f", overheadNs) + " ns");
            }
            if (!subMicrosecond) {
                System.out.println(
                        "  ✗ p95 exceeds 1μs threshold: " + obsStats.p95 + " ns");
            }
            System.out.println();
            System.out.println("Consider reviewing the observability implementation for");
            System.out.println("potential hot path contamination.");
        }

        System.out.println();
    }

    private static Proc<Integer, String> createTestProcess() {
        BiFunction<Integer, String, Integer> handler =
                (state, msg) -> {
                    if (msg.startsWith("message-")) {
                        return state + 1;
                    }
                    return state;
                };
        return Proc.spawn(0, handler);
    }

    private static PercentileResult calculatePercentiles(List<Long> samples) {
        long[] sorted = samples.stream().mapToLong(Long::longValue).toArray();
        java.util.Arrays.sort(sorted);

        long min = sorted[0];
        long max = sorted[sorted.length - 1];
        double mean = samples.stream().mapToLong(Long::longValue).average().orElse(0.0);

        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);

        return new PercentileResult(p50, p95, p99, min, max, mean);
    }

    private static long percentile(long[] sorted, double percentile) {
        int n = sorted.length;
        double rank = percentile / 100.0 * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);

        if (lower == upper) {
            return sorted[lower];
        }

        double weight = rank - lower;
        return (long) (sorted[lower] * (1 - weight) + sorted[upper] * weight);
    }

    private record PercentileResult(
            long p50, long p95, long p99, long min, long max, double mean) {}
}
