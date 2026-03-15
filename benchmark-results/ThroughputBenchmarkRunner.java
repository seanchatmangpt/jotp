import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Realistic throughput benchmark that measures actual ops/sec with proper timing.
 */
public class ThroughputBenchmarkRunner {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 10;
    private static final int MEASUREMENT_DURATION_MS = 2000; // 2 seconds per iteration

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("JOTP Framework Throughput Benchmarks");
        System.out.println("=".repeat(80));
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);
        System.out.println("Measurement iterations: " + MEASUREMENT_ITERATIONS);
        System.out.println("Measurement duration: " + MEASUREMENT_DURATION_MS + " ms per iteration");
        System.out.println();

        // Run all benchmarks
        benchmarkEventBusDisabled();
        benchmarkEventBusEnabled();
        benchmarkProcTell();
        benchmarkSubscriberScaling();
        benchmarkLatency();

        System.out.println("=".repeat(80));
        System.out.println("All benchmarks completed successfully!");
        System.out.println("=".repeat(80));
    }

    private static void benchmarkEventBusDisabled() throws Exception {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("Benchmark: EventBus.publish() with observability DISABLED");
        System.out.println("─".repeat(80));

        System.setProperty("jotp.observability.enabled", "false");
        FrameworkEventBus eventBus = FrameworkEventBus.create();
        FrameworkEventBus.FrameworkEvent.ProcessCreated sampleEvent =
            new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                Instant.now(), "bench-proc-1", "Proc");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            long end = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < end) {
                for (int j = 0; j < 10000; j++) {
                    eventBus.publish(sampleEvent);
                }
            }
        }

        Thread.sleep(100);

        // Measurement
        double[] throughputs = new double[MEASUREMENT_ITERATIONS];
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            long end = start + MEASUREMENT_DURATION_MS;
            long operations = 0;

            while (System.currentTimeMillis() < end) {
                for (int j = 0; j < 10000; j++) {
                    eventBus.publish(sampleEvent);
                    operations++;
                }
            }

            long actualDuration = System.currentTimeMillis() - start;
            double opsPerSec = (operations * 1000.0) / actualDuration;
            throughputs[i] = opsPerSec;
            System.out.printf("  Iteration %2d: %,.2f ops/sec%n", i + 1, opsPerSec);
        }

        printStatistics("EventBus Disabled", throughputs);
    }

    private static void benchmarkEventBusEnabled() throws Exception {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("Benchmark: EventBus.publish() with observability ENABLED (1 subscriber)");
        System.out.println("─".repeat(80));

        System.setProperty("jotp.observability.enabled", "true");
        FrameworkEventBus eventBus = FrameworkEventBus.create();
        FrameworkEventBus.FrameworkEvent.ProcessCreated sampleEvent =
            new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                Instant.now(), "bench-proc-1", "Proc");

        AtomicInteger counter = new AtomicInteger(0);
        eventBus.subscribe(event -> counter.incrementAndGet());

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            long end = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < end) {
                for (int j = 0; j < 10000; j++) {
                    eventBus.publish(sampleEvent);
                }
            }
        }

        Thread.sleep(100);
        counter.set(0);

        // Measurement
        double[] throughputs = new double[MEASUREMENT_ITERATIONS];
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            counter.set(0);
            long start = System.currentTimeMillis();
            long end = start + MEASUREMENT_DURATION_MS;
            long operations = 0;

            while (System.currentTimeMillis() < end) {
                for (int j = 0; j < 10000; j++) {
                    eventBus.publish(sampleEvent);
                    operations++;
                }
            }

            long actualDuration = System.currentTimeMillis() - start;
            double opsPerSec = (operations * 1000.0) / actualDuration;
            throughputs[i] = opsPerSec;
            System.out.printf("  Iteration %2d: %,.2f ops/sec (delivered: %d)%n",
                i + 1, opsPerSec, counter.get());

            // Wait for async delivery to complete
            Thread.sleep(100);
        }

        printStatistics("EventBus Enabled (1 subscriber)", throughputs);
    }

    private static void benchmarkProcTell() throws Exception {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("Benchmark: Proc.tell() throughput");
        System.out.println("─".repeat(80));

        System.setProperty("jotp.observability.enabled", "false");
        Proc<Integer, Integer> counterProc = Proc.spawn(0, (state, msg) -> state + msg);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            long end = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < end) {
                for (int j = 0; j < 10000; j++) {
                    counterProc.tell(1);
                }
            }
        }

        Thread.sleep(100);

        // Measurement
        double[] throughputs = new double[MEASUREMENT_ITERATIONS];
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            long end = start + MEASUREMENT_DURATION_MS;
            long operations = 0;

            while (System.currentTimeMillis() < end) {
                for (int j = 0; j < 10000; j++) {
                    counterProc.tell(1);
                    operations++;
                }
            }

            long actualDuration = System.currentTimeMillis() - start;
            double opsPerSec = (operations * 1000.0) / actualDuration;
            throughputs[i] = opsPerSec;
            System.out.printf("  Iteration %2d: %,.2f ops/sec%n", i + 1, opsPerSec);
        }

        printStatistics("Proc.tell()", throughputs);

        counterProc.stop();
    }

    private static void benchmarkSubscriberScaling() throws Exception {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("Benchmark: EventBus subscriber scaling (1, 5, 10, 50 subscribers)");
        System.out.println("─".repeat(80));

        System.setProperty("jotp.observability.enabled", "true");
        int[] subscriberCounts = {1, 5, 10, 50};

        for (int subscriberCount : subscriberCounts) {
            FrameworkEventBus eventBus = FrameworkEventBus.create();
            FrameworkEventBus.FrameworkEvent.ProcessCreated sampleEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    Instant.now(), "bench-proc-1", "Proc");

            final AtomicInteger[] counters = new AtomicInteger[subscriberCount];
            for (int i = 0; i < subscriberCount; i++) {
                counters[i] = new AtomicInteger(0);
                final int idx = i;
                eventBus.subscribe(event -> counters[idx].incrementAndGet());
            }

            // Warmup
            for (int i = 0; i < 3; i++) {
                long end = System.currentTimeMillis() + 200;
                while (System.currentTimeMillis() < end) {
                    for (int j = 0; j < 10000; j++) {
                        eventBus.publish(sampleEvent);
                    }
                }
            }

            Thread.sleep(100);

            // Measurement
            double[] throughputs = new double[MEASUREMENT_ITERATIONS];
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.currentTimeMillis();
                long end = start + MEASUREMENT_DURATION_MS;
                long operations = 0;

                while (System.currentTimeMillis() < end) {
                    for (int j = 0; j < 10000; j++) {
                        eventBus.publish(sampleEvent);
                        operations++;
                    }
                }

                long actualDuration = System.currentTimeMillis() - start;
                double opsPerSec = (operations * 1000.0) / actualDuration;
                throughputs[i] = opsPerSec;

                // Wait for async delivery
                Thread.sleep(100);
            }

            printStatistics("EventBus with " + subscriberCount + " subscriber(s)", throughputs);
        }
    }

    private static void benchmarkLatency() throws Exception {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("Benchmark: Single operation latency (nanoseconds)");
        System.out.println("─".repeat(80));

        System.setProperty("jotp.observability.enabled", "false");
        FrameworkEventBus eventBus = FrameworkEventBus.create();
        FrameworkEventBus.FrameworkEvent.ProcessCreated sampleEvent =
            new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                Instant.now(), "bench-proc-1", "Proc");

        Proc<Integer, Integer> proc = Proc.spawn(0, (state, msg) -> state + msg);

        // Warmup
        for (int i = 0; i < 100000; i++) {
            eventBus.publish(sampleEvent);
            proc.tell(1);
        }

        Thread.sleep(100);

        // Measure EventBus.publish() latency
        long[] eventBusLatencies = new long[100000];
        for (int i = 0; i < 100000; i++) {
            long start = System.nanoTime();
            eventBus.publish(sampleEvent);
            long elapsed = System.nanoTime() - start;
            eventBusLatencies[i] = elapsed;
        }

        // Measure Proc.tell() latency
        long[] procTellLatencies = new long[100000];
        for (int i = 0; i < 100000; i++) {
            long start = System.nanoTime();
            proc.tell(1);
            long elapsed = System.nanoTime() - start;
            procTellLatencies[i] = elapsed;
        }

        // Calculate percentiles
        java.util.Arrays.sort(eventBusLatencies);
        java.util.Arrays.sort(procTellLatencies);

        System.out.println("\n  EventBus.publish() latency:");
        System.out.printf("    p50:  %d ns%n", eventBusLatencies[eventBusLatencies.length / 2]);
        System.out.printf("    p90:  %d ns%n", eventBusLatencies[(int)(eventBusLatencies.length * 0.9)]);
        System.out.printf("    p99:  %d ns%n", eventBusLatencies[(int)(eventBusLatencies.length * 0.99)]);
        System.out.printf("    p999: %d ns%n", eventBusLatencies[(int)(eventBusLatencies.length * 0.999)]);
        System.out.printf("    min:  %d ns%n", eventBusLatencies[0]);
        System.out.printf("    max:  %d ns%n", eventBusLatencies[eventBusLatencies.length - 1]);

        System.out.println("\n  Proc.tell() latency:");
        System.out.printf("    p50:  %d ns%n", procTellLatencies[procTellLatencies.length / 2]);
        System.out.printf("    p90:  %d ns%n", procTellLatencies[(int)(procTellLatencies.length * 0.9)]);
        System.out.printf("    p99:  %d ns%n", procTellLatencies[(int)(procTellLatencies.length * 0.99)]);
        System.out.printf("    p999: %d ns%n", procTellLatencies[(int)(procTellLatencies.length * 0.999)]);
        System.out.printf("    min:  %d ns%n", procTellLatencies[0]);
        System.out.printf("    max:  %d ns%n", procTellLatencies[procTellLatencies.length - 1]);

        proc.stop();
    }

    private static void printStatistics(String label, double[] values) {
        // Calculate statistics
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double v : values) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / values.length;

        // Standard deviation
        double variance = 0;
        for (double v : values) {
            variance += Math.pow(v - mean, 2);
        }
        variance /= values.length;
        double stdDev = Math.sqrt(variance);

        // 99.9% confidence interval (3.291 * stdDev / sqrt(n))
        double confidenceInterval = 3.291 * stdDev / Math.sqrt(values.length);

        System.out.println("\n  " + "─".repeat(76));
        System.out.printf("  %s Results:%n", label);
        System.out.printf("    Mean:      %,.2f ops/sec%n", mean);
        System.out.printf("    Min:       %,.2f ops/sec%n", min);
        System.out.printf("    Max:       %,.2f ops/sec%n", max);
        System.out.printf("    Std Dev:   %,.2f ops/sec%n", stdDev);
        System.out.printf("    99.9%% CI: ±%,.2f ops/sec%n", confidenceInterval);
        System.out.println("  " + "─".repeat(76));
    }
}
