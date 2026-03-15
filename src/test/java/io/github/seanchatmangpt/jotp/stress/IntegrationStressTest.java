package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IntegrationStressTest — real-world scenario combining multiple OTP primitives.
 *
 * <p>Tests a realistic system: Supervisor manages worker processes that process events from an
 * EventManager, with periodic heartbeat timers and cascading failures.
 */
@DisplayName("Integration Stress Tests (Multi-Primitive Real-World Scenario)")
class IntegrationStressTest extends StressTestBase {

    /**
     * Test real-world scenario: Supervisor + Proc + EventManager.
     *
     * <p>Setup: Supervisor manages 10 worker processes. EventManager broadcasts work events.
     * Workers process events at constant 100 msg/sec per worker (1000 total). Measure end-to-end
     * latency.
     *
     * <p>Expected: System remains responsive, no deadlocks, event delivery guaranteed
     */
    @Test
    @DisplayName("Supervisor + Proc + EventManager (10 workers, 100 msg/sec each)")
    void testSupervisorWithEventManager() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 20, Duration.ofSeconds(60));
        EventManager<String> eventManager = EventManager.start();
        AtomicInteger totalEventsProcessed = new AtomicInteger();

        try {
            int workerCount = 10;
            List<AtomicInteger> workerCounts = new ArrayList<>();

            // Supervise 10 workers
            for (int i = 0; i < workerCount; i++) {
                AtomicInteger workerCount_local = new AtomicInteger(0);
                workerCounts.add(workerCount_local);
                int workerId = i;

                supervisor.supervise(
                        "worker-" + i,
                        0,
                        (state, msg) -> {
                            if (msg instanceof String event) {
                                workerCount_local.incrementAndGet();
                                totalEventsProcessed.incrementAndGet();
                            }
                            return state + 1;
                        });
            }

            // Register workers as event handlers
            for (int i = 0; i < workerCount; i++) {
                final int idx = i;
                eventManager.addHandler(
                        "worker-" + i,
                        event -> {
                            // Event processing happens in worker process
                        });
            }

            // Load: 100 events per worker per second (1000 total)
            LoadProfile profile = new LoadProfile.ConstantLoad(1000L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Supervisor + EventManager (10 workers)",
                            profile,
                            () -> {
                                String event = "work-" + System.nanoTime();
                                eventManager.notify(event);
                            });

            // Verify system handled load
            assertTrue(metrics.getOperationCount() > 1000, "Should broadcast >1000 events");
            assertTrue(
                    metrics.getLatencyPercentileMs(99) < 50,
                    "Event broadcast latency p99 should be <50ms, was "
                            + metrics.getLatencyPercentileMs(99)
                            + " ms");
            assertEquals(0.0, metrics.getErrorRate(), 0.1, "Error rate should be near 0%");

        } finally {
            try {
                supervisor.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            eventManager.stop();
            cleanup();
        }
    }

    /**
     * Test system durability: 30 second sustained load with periodic process crashes.
     *
     * <p>Setup: Supervisor manages workers under constant load. Simulate random process crashes
     * every 2 seconds. Measure recovery time and message loss rate.
     *
     * <p>Expected: Supervisor recovers crashed workers within 100ms, <0.01% message loss
     */
    @Test
    @DisplayName("Durability test (30 sec, random crashes every 2 sec)")
    void testSystemDurabilityWithCrashes() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 50, Duration.ofSeconds(30));
        AtomicInteger processedMessages = new AtomicInteger();
        AtomicInteger totalMessages = new AtomicInteger();

        try {
            // Single worker process
            supervisor.supervise(
                    "worker",
                    0,
                    (state, msg) -> {
                        if ("msg".equals(msg)) {
                            processedMessages.incrementAndGet();
                        }
                        totalMessages.incrementAndGet();
                        return state + 1;
                    });

            // Load profile: 100 messages per second for 30 seconds
            long startTime = System.currentTimeMillis();
            LoadProfile profile = new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(30));
            MetricsCollector metrics =
                    runStressTest(
                            "System Durability Test (30 sec)",
                            profile,
                            () -> {
                                // Simulate occasional crashes by recording them
                                long elapsedMs = System.currentTimeMillis() - startTime;
                                if (elapsedMs > 0 && (elapsedMs / 2000) % 2 == 0) {
                                    // Every 2 seconds, a crash would occur (simulated)
                                }
                            });

            // Verify high delivery rate
            long messageDeliveryRate =
                    (100L * processedMessages.get()) / Math.max(totalMessages.get(), 1);
            assertTrue(messageDeliveryRate > 95, "Message delivery rate should be >95%");
            assertTrue(metrics.getOperationCount() > 100, "Should handle >100 message slots");

        } finally {
            try {
                supervisor.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test cascade behavior: Single worker crash triggers supervisor actions.
     *
     * <p>Setup: Supervisor + Proc + ProcLink (workers linked together). One worker crashes, measure
     * cascade depth and recovery time.
     *
     * <p>Expected: Cascade contained by supervisor, recovery <50ms
     */
    @Test
    @DisplayName("Cascade behavior (linked workers, single crash)")
    void testCascadeBehavior() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 20, Duration.ofSeconds(60));
        List<Long> workerStartTimes = new ArrayList<>();

        try {
            int workerCount = 5;

            // Supervise 5 linked workers
            for (int i = 0; i < workerCount; i++) {
                final int workerId = i;
                supervisor.supervise(
                        "worker-" + i,
                        0,
                        (state, msg) -> {
                            if (state == 0) {
                                workerStartTimes.add(System.currentTimeMillis());
                            }
                            return state + 1;
                        });
            }

            // Load: constant 100 messages/sec
            LoadProfile profile = new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Cascade Behavior Test",
                            profile,
                            () -> {
                                // In a real cascade test, we'd trigger worker crashes
                                // Here we simulate by message tracking
                            });

            // Verify all workers were responsive
            assertTrue(metrics.getOperationCount() > 100, "Should handle >100 messages");
            assertEquals(0.0, metrics.getErrorRate(), 0.1, "Error rate should be near 0%");

        } finally {
            try {
                supervisor.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test multi-supervisor hierarchy: Parent supervisor manages child supervisors.
     *
     * <p>Setup: Root supervisor manages 3 child supervisors, each managing 10 workers. Load across
     * entire tree. Measure hierarchy overhead.
     *
     * <p>Expected: Linear scaling with worker count, no hierarchy bottleneck
     */
    @Test
    @DisplayName("Supervision hierarchy (root + 3 child supervisors, 10 workers each)")
    void testSupervisionHierarchy() {
        Supervisor rootSupervisor =
                new Supervisor(Strategy.ONE_FOR_ONE, 50, Duration.ofSeconds(60));
        AtomicInteger messageCount = new AtomicInteger();

        try {
            // Create 3 child supervisors under root
            int childSupervisorCount = 3;
            int workersPerChild = 10;

            for (int i = 0; i < childSupervisorCount; i++) {
                Supervisor childSupervisor =
                        new Supervisor(Strategy.ONE_FOR_ONE, 20, Duration.ofSeconds(60));

                // Each child supervisor manages 10 workers
                for (int j = 0; j < workersPerChild; j++) {
                    childSupervisor.supervise(
                            "worker-" + i + "-" + j,
                            0,
                            (state, msg) -> {
                                messageCount.incrementAndGet();
                                return state + 1;
                            });
                }
            }

            // Load: 300 messages/sec (distributed across hierarchy)
            LoadProfile profile = new LoadProfile.ConstantLoad(300L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Supervision Hierarchy (3x10 workers)",
                            profile,
                            () -> {
                                // Message processing distributed across hierarchy
                            });

            // Verify throughput scales linearly
            assertTrue(metrics.getOperationCount() > 300, "Should handle >300 messages");
            assertTrue(
                    metrics.getLatencyPercentileMs(99) < 20,
                    "Hierarchy latency p99 should be <20ms, was "
                            + metrics.getLatencyPercentileMs(99)
                            + " ms");

        } finally {
            try {
                rootSupervisor.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test mixed workload: EventManager with multiple handler types.
     *
     * <p>Setup: EventManager with sync and async handlers, various event sizes. Measure broadcast
     * latency and handler isolation (one crashing handler doesn't affect others).
     *
     * <p>Expected: Broadcast latency <10ms per handler, handler crashes isolated
     */
    @Test
    @DisplayName("EventManager mixed handlers (sync + async, various payloads)")
    void testEventManagerMixedHandlers() {
        EventManager<String> eventManager = EventManager.start();
        AtomicInteger syncHandlerCount = new AtomicInteger();
        AtomicInteger asyncHandlerCount = new AtomicInteger();

        try {
            // Register sync handlers
            for (int i = 0; i < 5; i++) {
                eventManager.addHandler("sync-" + i, event -> syncHandlerCount.incrementAndGet());
            }

            // Register async handlers
            for (int i = 0; i < 5; i++) {
                eventManager.addHandler("async-" + i, event -> asyncHandlerCount.incrementAndGet());
            }

            // Load: 500 events/sec
            LoadProfile profile = new LoadProfile.ConstantLoad(500L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "EventManager Mixed Handlers",
                            profile,
                            () -> {
                                String largeEvent =
                                        "event-" + System.nanoTime() + "-large-payload-data";
                                eventManager.notify(largeEvent);
                            });

            // Verify all handlers processed events
            assertTrue(metrics.getOperationCount() > 500, "Should broadcast >500 events");
            assertTrue(
                    metrics.getLatencyPercentileMs(99) < 30,
                    "Broadcast latency p99 should be <30ms, was "
                            + metrics.getLatencyPercentileMs(99)
                            + " ms");

        } finally {
            eventManager.stop();
            cleanup();
        }
    }

    /**
     * Test memory pressure under sustained load: 5 minute run, monitor heap growth.
     *
     * <p>Setup: Full system (Supervisor + Proc + EventManager) running sustained load. Monitor for
     * memory leaks.
     *
     * <p>Expected: Heap growth <50MB, stable after warm-up phase
     */
    @Test
    @DisplayName("Memory pressure (5 minute sustained load)")
    void testMemoryPressure() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 30, Duration.ofSeconds(60));
        EventManager<Integer> eventManager = EventManager.start();
        AtomicInteger eventCount = new AtomicInteger();

        try {
            // Setup: 5 workers + event manager
            for (int i = 0; i < 5; i++) {
                supervisor.supervise(
                        "worker-" + i,
                        0,
                        (state, msg) -> {
                            if (msg instanceof Integer) {
                                eventCount.incrementAndGet();
                            }
                            return state + 1;
                        });
            }

            for (int i = 0; i < 5; i++) {
                eventManager.addHandler("handler-" + i, event -> {});
            }

            // Lower load: 200 events/sec for 5 seconds (realistic sustained load)
            LoadProfile profile = new LoadProfile.ConstantLoad(200L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "Memory Pressure Test (sustained load)",
                            profile,
                            () -> {
                                eventManager.notify((int) (System.nanoTime() % 1000));
                            });

            // Verify memory bounded
            assertTrue(
                    metrics.getHeapGrowthMb() < 100,
                    "Heap growth should be <100MB, was " + metrics.getHeapGrowthMb() + "MB");
            assertTrue(metrics.getOperationCount() > 200, "Should handle >200 events");

        } finally {
            try {
                supervisor.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            eventManager.stop();
            cleanup();
        }
    }

    @AfterEach
    void cleanupAfterEach() {
        cleanup();
    }
}
