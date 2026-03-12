package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StressTestBase — common utilities for all stress tests.
 *
 * <p>Provides thread pool management, load generation harness, timeout handling, and result
 * aggregation.
 */
public abstract class StressTestBase {

    protected static final int DEFAULT_TIMEOUT_SECONDS = 120;
    protected final ExecutorService executor = Executors.newCachedThreadPool();
    protected final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * Run a load profile against a workload lambda, collecting metrics.
     *
     * <p>Executes the workload under the specified load profile and returns metrics. Automatically
     * detects breaking points and terminates early if thresholds are exceeded.
     *
     * @param testName descriptive name for this test
     * @param profile load profile (constant, ramp, spike, etc.)
     * @param workload lambda that executes one operation; returns latency in ms
     * @return metrics collected during test
     */
    protected MetricsCollector runStressTest(
            String testName, LoadProfile profile, WorkloadFunction workload) {
        return runStressTest(testName, profile, workload, BreakingPointDetector.createDefault());
    }

    /** Run with custom breaking point detector. */
    protected MetricsCollector runStressTest(
            String testName,
            LoadProfile profile,
            WorkloadFunction workload,
            BreakingPointDetector detector) {

        MetricsCollector metrics = new MetricsCollector(testName);
        AtomicBoolean shouldStop = new AtomicBoolean(false);
        List<Future<?>> futures = new ArrayList<>();

        // Start load generator
        Future<?> loadGen =
                scheduler.scheduleAtFixedRate(
                        () -> {
                            if (shouldStop.get()) return;
                            long load = profile.getLoad(metrics.getElapsedMs());
                            // Submit 'load' operations (approximated per scheduling interval)
                            for (int i = 0; i < Math.min(load / 100, 1000); i++) {
                                futures.add(
                                        executor.submit(
                                                () -> {
                                                    try {
                                                        long startNs = System.nanoTime();
                                                        workload.execute();
                                                        long latencyMs =
                                                                (System.nanoTime() - startNs)
                                                                        / 1_000_000L;
                                                        metrics.recordOperation(latencyMs);
                                                    } catch (Exception e) {
                                                        metrics.recordError();
                                                    }
                                                }));
                            }

                            // Periodically check for breaking point
                            if (metrics.getElapsedMs() % 1000 == 0) {
                                if (detector.detect(metrics)) {
                                    shouldStop.set(true);
                                }
                            }
                        },
                        100,
                        100,
                        TimeUnit.MILLISECONDS);

        try {
            // Run until profile completes or breaking point detected
            long profileDurationMs = profile.getDuration().toMillis();
            long startMs = System.currentTimeMillis();
            while (System.currentTimeMillis() - startMs < profileDurationMs && !shouldStop.get()) {
                Thread.sleep(100);
            }

            // Wait for outstanding operations to complete
            shouldStop.set(true);
            for (Future<?> future : futures) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Report results
            System.out.println(metrics.getSummary());
            if (detector.isBreakingPointDetected()) {
                System.out.println("Breaking point: " + detector.getBreakingPointReason());
            }

            return metrics;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Stress test interrupted: " + e.getMessage());
            return metrics;
        } finally {
            loadGen.cancel(true);
        }
    }

    /** Workload function — executes one operation and returns latency in ms. */
    @FunctionalInterface
    protected interface WorkloadFunction {
        /**
         * Execute one operation.
         *
         * @throws Exception if operation fails
         */
        void execute() throws Exception;
    }

    /**
     * Await a condition with timeout and message.
     *
     * @param condition condition to wait for
     * @param timeoutMs timeout in milliseconds
     * @param message error message if timeout
     */
    protected void awaitCondition(
            java.util.function.BooleanSupplier condition, long timeoutMs, String message) {
        long startMs = System.currentTimeMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startMs > timeoutMs) {
                fail(message + " (timeout after " + timeoutMs + " ms)");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message);
            }
        }
    }

    /** Clean up executor services after test. */
    protected void cleanup() {
        scheduler.shutdownNow();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                fail("Executor did not shut down cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
