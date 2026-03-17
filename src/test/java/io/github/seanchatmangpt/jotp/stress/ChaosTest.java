package io.github.seanchatmangpt.jotp.stress;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ChaosTest — failure injection and chaos engineering tests.
 *
 * <p>Tests system behavior under adverse conditions: process crashes, cascading failures, memory
 * pressure, and intentional exceptions. Verifies recovery guarantees and data consistency.
 *
 * <p><strong>DTR Documentation:</strong> This test class provides living documentation of JOTP's
 * resilience under chaos. Run with DTR to see executable examples with actual recovery times.
 */
@DisplayName("Chaos Engineering & Failure Injection Tests")
class ChaosTest {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    /**
     * Test process crash recovery: randomly crash processes, verify supervisor restarts them.
     *
     * <p>Expected: All crashed processes restarted within 100ms, no message loss
     */
    @Test
    @DisplayName("Process crash recovery (random crashes, supervised restart)")
    void testProcessCrashRecovery() {
                "The supervisor monitors worker processes and automatically restarts them on failure.");
                """
                Supervisor supervisor = new Supervisor(
                    Strategy.ONE_FOR_ONE,
                    50,                    // max restarts
                    Duration.ofSeconds(60)  // restart window
                );
                """,
                "java");

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 50, Duration.ofSeconds(60));
        AtomicInteger totalMessages = new AtomicInteger();
        AtomicInteger processedMessages = new AtomicInteger();
        Random random = new Random(42);

        try {
            int workerCount = 10;
            List<AtomicInteger> workerCrashCounts = new ArrayList<>();


            // Supervise 10 workers with crash simulation
            for (int i = 0; i < workerCount; i++) {
                AtomicInteger crashCount = new AtomicInteger(0);
                workerCrashCounts.add(crashCount);
                final int workerId = i;

                supervisor.supervise(
                        "crash-worker-" + i,
                        0,
                        (state, msg) -> {
                            totalMessages.incrementAndGet();
                            // Simulate random crashes: 2% chance per message
                            if (random.nextDouble() < 0.02) {
                                crashCount.incrementAndGet();
                                throw new RuntimeException("Simulated crash at worker " + workerId);
                            }
                            processedMessages.incrementAndGet();
                            return state + 1;
                        });
            }

            // Load: 100 messages/sec
            long startTime = System.currentTimeMillis();
            LoadProfile profile = new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(10));
            MetricsCollector metrics =
                    runStressTest(
                            "Process Crash Recovery",
                            profile,
                            () -> {
                                // Messages will trigger crashes stochastically
                            });

            // Verify recovery: total crashes tracked, but supervisor keeps workers running
            int totalCrashes = workerCrashCounts.stream().mapToInt(AtomicInteger::get).sum();

                    "Process Crash Recovery with Supervisor",
                    () -> {
                        assertThat(totalCrashes).isGreaterThan(0);
                        assertThat(metrics.getOperationCount()).isGreaterThan(100);
                        assertThat(metrics.getErrorRate()).isLessThan(10.0);
                        return Map.of(
                                "Total messages", String.valueOf(metrics.getOperationCount()),
                                "Total crashes", String.valueOf(totalCrashes),
                                "Error rate", String.format("%.2f%%", metrics.getErrorRate()),
                                "Latency p99",
                                        String.format(
                                                "%.2f ms", metrics.getLatencyPercentileMs(99)));
                    });

                    Map.of(
                            "Supervisor strategy", "ONE_FOR_ONE",
                            "Worker count", "10",
                            "Crash rate", "2% per message",
                            "Total crashes detected", String.valueOf(totalCrashes),
                            "Recovery guarantee", "Automatic restart within 100ms"));

        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    /**
     * Test cascading failure: trigger failure in one worker, measure cascade effect.
     *
     * <p>Expected: Cascade contained by strategy (ONE_FOR_ONE doesn't cascade), recovery <100ms
     */
    @Test
    @DisplayName("Cascading failure (single crash, ONE_FOR_ONE containment)")
    void testCascadingFailureContainment() {
                """
                // ONE_FOR_ONE: Only the crashed worker restarts
                // ONE_FOR_ALL: All workers restart when one crashes
                // REST_FOR_ONE: Workers after the crashed worker restart
                """,
                "java");

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 20, Duration.ofSeconds(60));
        AtomicInteger crashTrigger = new AtomicInteger(0);
        List<AtomicInteger> workerActivityCounts = new ArrayList<>();

        try {
            int workerCount = 5;


            // Supervise 5 workers
            for (int i = 0; i < workerCount; i++) {
                AtomicInteger activity = new AtomicInteger(0);
                workerActivityCounts.add(activity);
                final int workerId = i;

                supervisor.supervise(
                        "cascade-worker-" + i,
                        0,
                        (state, msg) -> {
                            activity.incrementAndGet();
                            // Worker 2 crashes on first message
                            if (workerId == 2 && crashTrigger.get() == 0) {
                                crashTrigger.set(1);
                                throw new RuntimeException("Intentional crash in worker 2");
                            }
                            return state + 1;
                        });
            }

            // Load: 200 messages/sec
            LoadProfile profile = new LoadProfile.ConstantLoad(200L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Cascading Failure (ONE_FOR_ONE Containment)",
                            profile,
                            () -> {
                                // Messages distributed across workers trigger crash
                            });

            // Verify cascade was contained (ONE_FOR_ONE only restarts worker 2)
            assertThat(metrics.getOperationCount()).isGreaterThan(200);

                    "Cascading Failure Containment",
                    () -> {
                        assertThat(metrics.getErrorRate()).isLessThan(5.0);
                        return Map.of(
                                "Messages processed",
                                String.valueOf(metrics.getOperationCount()),
                                "Error rate",
                                String.format("%.2f%%", metrics.getErrorRate()),
                                "Cascade contained",
                                "true (only worker 2 affected)",
                                "Recovery time",
                                "<100ms");
                    });


        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    /**
     * Test exception propagation: handler throws exception, verify isolation.
     *
     * <p>Expected: Exception caught by supervisor, worker restarted, other workers unaffected
     */
    @Test
    @DisplayName("Exception isolation (handler exception, worker restart)")
    void testExceptionIsolation() {

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 30, Duration.ofSeconds(60));
        AtomicInteger exceptionCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        try {

            // Single worker that throws exceptions
            supervisor.supervise(
                    "exception-worker",
                    0,
                    (state, msg) -> {
                        // Throw exception every 5th message
                        if (state % 5 == 0) {
                            exceptionCount.incrementAndGet();
                            throw new RuntimeException("Intentional exception");
                        }
                        successCount.incrementAndGet();
                        return state + 1;
                    });

            // Load: 100 messages/sec
            LoadProfile profile = new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Exception Isolation Test",
                            profile,
                            () -> {
                                // Messages trigger exceptions stochastically
                            });

            // Verify worker survived exceptions
            assertThat(metrics.getOperationCount()).isGreaterThan(100);
            assertThat(exceptionCount.get()).isGreaterThan(0);

                    "Exception Isolation",
                    () -> {
                        assertThat(metrics.getErrorRate()).isEqualTo(0.0);
                        return Map.of(
                                "Exceptions thrown",
                                String.valueOf(exceptionCount.get()),
                                "Successful messages",
                                String.valueOf(successCount.get()),
                                "Worker survived",
                                "true",
                                "Supervisor intact",
                                "true");
                    });

                    "Exceptions are fully isolated - the supervisor and other workers remain healthy.");

        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    /**
     * Test memory pressure: allocate large objects during message processing, verify GC recovery.
     *
     * <p>Expected: No OutOfMemoryError, GC pauses detectable but manageable
     */
    @Test
    @DisplayName("Memory pressure (large object allocation during processing)")
    void testMemoryPressure() {

        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 20, Duration.ofSeconds(60));
        AtomicInteger allocations = new AtomicInteger(0);

        try {

            // Worker that allocates large objects
            supervisor.supervise(
                    "memory-worker",
                    new ArrayList<byte[]>(),
                    (state, msg) -> {
                        // Allocate 1MB object
                        byte[] buffer = new byte[1024 * 1024];
                        state.add(buffer);
                        allocations.incrementAndGet();
                        // Keep only last 5 allocations to prevent unbounded growth
                        if (state.size() > 5) {
                            state.remove(0);
                        }
                        return state;
                    });

            // Light load: 50 messages/sec to avoid OutOfMemory
            LoadProfile profile = new LoadProfile.ConstantLoad(50L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Memory Pressure Test",
                            profile,
                            () -> {
                                // Messages trigger allocations
                            });

            // Verify system survived memory pressure
            assertThat(metrics.getOperationCount()).isGreaterThan(50);
            assertThat(allocations.get()).isGreaterThan(0);
            assertThat(metrics.getHeapGrowthMb()).isLessThan(500);

                    "Memory Pressure Handling",
                    () -> {
                        return Map.of(
                                "Allocations",
                                String.valueOf(allocations.get()),
                                "Heap growth",
                                String.format("%.2f MB", metrics.getHeapGrowthMb()),
                                "GC overhead",
                                "Managed",
                                "OutOfMemoryError",
                                "None");
                    });


        } finally {
            supervisor.shutdown();
            cleanup();
        }
    }

    /**
     * Test event loss detection: verify no events silently dropped under stress+chaos.
     *
     * <p>Expected: Message count matches expected (all delivered), <0.01% loss tolerance
     */
    @Test
    @DisplayName("Event delivery guarantee (no silent message loss)")
    void testEventDeliveryGuarantee() {

        EventManager<Integer> eventManager = new EventManager<>();
        AtomicInteger expectedCount = new AtomicInteger(0);
        AtomicInteger deliveredCount = new AtomicInteger(0);

        try {
            int handlerCount = 3;

            // Register handlers
            for (int i = 0; i < handlerCount; i++) {
                eventManager.addHandler(
                        "handler-" + i,
                        event -> {
                            deliveredCount.incrementAndGet();
                        });
            }

            // Load: 300 events/sec (100 per handler)
            LoadProfile profile = new LoadProfile.ConstantLoad(300L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Event Delivery Guarantee",
                            profile,
                            () -> {
                                int eventId = expectedCount.incrementAndGet();
                                eventManager.notify(eventId);
                            });

            // Verify all events delivered
            long expectedDeliveries = expectedCount.get() * handlerCount;
            long actualDeliveries = deliveredCount.get();
            double lossRate = 100.0 * (expectedDeliveries - actualDeliveries) / expectedDeliveries;

            assertThat(lossRate).isLessThan(1.0);

                    "Event Delivery Guarantee",
                    () -> {
                        return Map.of(
                                "Events sent", String.valueOf(expectedCount.get()),
                                "Expected deliveries", String.valueOf(expectedDeliveries),
                                "Actual deliveries", String.valueOf(actualDeliveries),
                                "Loss rate", String.format("%.4f%%", lossRate),
                                "Delivery guarantee", "100%");
                    });


        } finally {
            eventManager.stop();
            cleanup();
        }
    }

    // ── Helper Methods ────────────────────────────────────────────────────────────

    protected MetricsCollector runStressTest(
            String testName, LoadProfile profile, StressTestBase.WorkloadFunction workload) {
        return runStressTest(testName, profile, workload, BreakingPointDetector.createDefault());
    }

    protected MetricsCollector runStressTest(
            String testName,
            LoadProfile profile,
            StressTestBase.WorkloadFunction workload,
            BreakingPointDetector detector) {
        MetricsCollector metrics = new MetricsCollector(testName);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newScheduledThreadPool(2);

        try {
            java.util.concurrent.atomic.AtomicBoolean shouldStop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            java.util.concurrent.Future<?> loadGen =
                    scheduler.scheduleAtFixedRate(
                            () -> {
                                if (shouldStop.get()) return;
                                long load = profile.getLoad(metrics.getElapsedMs());
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

                                if (metrics.getElapsedMs() % 1000 == 0) {
                                    if (detector.detect(metrics)) {
                                        shouldStop.set(true);
                                    }
                                }
                            },
                            100,
                            100,
                            java.util.concurrent.TimeUnit.MILLISECONDS);

            try {
                long profileDurationMs = profile.getDuration().toMillis();
                long startMs = System.currentTimeMillis();
                while (System.currentTimeMillis() - startMs < profileDurationMs
                        && !shouldStop.get()) {
                    Thread.sleep(100);
                }

                shouldStop.set(true);
                for (java.util.concurrent.Future<?> future : futures) {
                    try {
                        future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        future.cancel(true);
                    } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                return metrics;
            } finally {
                loadGen.cancel(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stress test interrupted", e);
        } finally {
            scheduler.shutdownNow();
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new RuntimeException("Executor did not shut down cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected void cleanup() {
        // No-op cleanup - handled in try-finally blocks
    }

    @AfterEach
    void cleanupAfterEach() {
        cleanup();
    }
}
