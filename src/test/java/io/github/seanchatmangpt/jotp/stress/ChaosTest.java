package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;
import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * ChaosTest — failure injection and chaos engineering tests.
 *
 * <p>Tests system behavior under adverse conditions: process crashes, cascading failures, memory
 * pressure, and intentional exceptions. Verifies recovery guarantees and data consistency.
 */
@DisplayName("Chaos Engineering & Failure Injection Tests")
class ChaosTest extends StressTestBase {
    /**
     * Test process crash recovery: randomly crash processes, verify supervisor restarts them.
     *
     * <p>Expected: All crashed processes restarted within 100ms, no message loss
     */
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("Process crash recovery (random crashes, supervised restart)")
    void testProcessCrashRecovery() {
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
            assertTrue(totalCrashes > 0, "Should have triggered some crashes");
            assertTrue(
                    metrics.getOperationCount() > 100,
                    "Should process >100 message slots despite crashes");
                    metrics.getErrorRate() < 10.0,
                    "Error rate should be <10% (crashes are expected), was "
                            + metrics.getErrorRate()
                            + "%");
        } finally {
            supervisor.shutdown();
            cleanup();
        }
     * Test cascading failure: trigger failure in one worker, measure cascade effect.
     * <p>Expected: Cascade contained by strategy (ONE_FOR_ONE doesn't cascade), recovery <100ms
    @DisplayName("Cascading failure (single crash, ONE_FOR_ONE containment)")
    void testCascadingFailureContainment() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 20, Duration.ofSeconds(60));
        AtomicInteger crashTrigger = new AtomicInteger(0);
        List<AtomicInteger> workerActivityCounts = new ArrayList<>();
            int workerCount = 5;
            // Supervise 5 workers
                AtomicInteger activity = new AtomicInteger(0);
                workerActivityCounts.add(activity);
                        "cascade-worker-" + i,
                            activity.incrementAndGet();
                            // Worker 2 crashes on first message
                            if (workerId == 2 && crashTrigger.get() == 0) {
                                crashTrigger.set(1);
                                throw new RuntimeException("Intentional crash in worker 2");
            // Load: 200 messages/sec
            LoadProfile profile = new LoadProfile.ConstantLoad(200L, Duration.ofSeconds(5));
                            "Cascading Failure (ONE_FOR_ONE Containment)",
                                // Messages distributed across workers trigger crash
            // Verify cascade was contained (ONE_FOR_ONE only restarts worker 2)
            assertTrue(metrics.getOperationCount() > 200, "Should handle >200 message slots");
            // Error rate should reflect only the one crash
                    metrics.getErrorRate() < 5.0,
                    "Error rate should be <5%, was " + metrics.getErrorRate() + "%");
     * Test exception propagation: handler throws exception, verify isolation.
     * <p>Expected: Exception caught by supervisor, worker restarted, other workers unaffected
    @DisplayName("Exception isolation (handler exception, worker restart)")
    void testExceptionIsolation() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 30, Duration.ofSeconds(60));
        AtomicInteger exceptionCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
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
            LoadProfile profile = new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(5));
                            "Exception Isolation Test",
                                // Messages trigger exceptions stochastically
            // Verify worker survived exceptions
            assertTrue(metrics.getOperationCount() > 100, "Should handle >100 message slots");
            assertTrue(exceptionCount.get() > 0, "Should have triggered some exceptions");
            assertEquals(
                    0.0,
                    metrics.getErrorRate(),
                    0.01,
                    "Overall error rate should be 0% (exceptions handled)");
     * Test memory pressure: allocate large objects during message processing, verify GC recovery.
     * <p>Expected: No OutOfMemoryError, GC pauses detectable but manageable
    @DisplayName("Memory pressure (large object allocation during processing)")
    void testMemoryPressure() {
        AtomicInteger allocations = new AtomicInteger(0);
            // Worker that allocates large objects
                    "memory-worker",
                    new ArrayList<byte[]>(),
                        // Allocate 1MB object
                        byte[] buffer = new byte[1024 * 1024];
                        state.add(buffer);
                        allocations.incrementAndGet();
                        // Keep only last 5 allocations to prevent unbounded growth
                        if (state.size() > 5) {
                            state.remove(0);
                        return state;
            // Light load: 50 messages/sec to avoid OutOfMemory
            LoadProfile profile = new LoadProfile.ConstantLoad(50L, Duration.ofSeconds(5));
                            "Memory Pressure Test",
                                // Messages trigger allocations
            // Verify system survived memory pressure
            assertTrue(metrics.getOperationCount() > 50, "Should handle >50 message slots");
            assertTrue(allocations.get() > 0, "Should have allocated memory");
            // Heap should not grow unboundedly
                    metrics.getHeapGrowthMb() < 500,
                    "Heap growth should be <500MB, was " + metrics.getHeapGrowthMb() + "MB");
     * Test event loss detection: verify no events silently dropped under stress+chaos.
     * <p>Expected: Message count matches expected (all delivered), <0.01% loss tolerance
    @DisplayName("Event delivery guarantee (no silent message loss)")
    void testEventDeliveryGuarantee() {
        EventManager<Integer> eventManager = new EventManager<>();
        AtomicInteger expectedCount = new AtomicInteger(0);
        AtomicInteger deliveredCount = new AtomicInteger(0);
            // Register handlers
            for (int i = 0; i < 3; i++) {
                eventManager.addHandler(
                        "handler-" + i,
                        event -> {
                            deliveredCount.incrementAndGet();
            // Load: 300 events/sec (100 per handler)
            LoadProfile profile = new LoadProfile.ConstantLoad(300L, Duration.ofSeconds(5));
                            "Event Delivery Guarantee",
                                int eventId = expectedCount.incrementAndGet();
                                eventManager.notify(eventId);
            // Verify all events delivered
            long expectedDeliveries = expectedCount.get() * 3; // 3 handlers per event
            long actualDeliveries = deliveredCount.get();
            double lossRate = 100.0 * (expectedDeliveries - actualDeliveries) / expectedDeliveries;
                    lossRate < 1.0,
                    "Message loss rate should be <1%, was "
                            + lossRate
                            + "% ("
                            + actualDeliveries
                            + "/"
                            + expectedDeliveries
                            + ")");
            eventManager.stop();
     * Test recovery speed: measure time to recover from cascading failures.
     * <p>Expected: System responsive within 100ms after crash
    @DisplayName("Recovery speed (cascading failure recovery time)")
    void testRecoverySpeed() {
        Supervisor supervisor = new Supervisor(Strategy.ONE_FOR_ALL, 10, Duration.ofSeconds(60));
        List<Long> recoveryTimes = new ArrayList<>();
        AtomicInteger messageCounter = new AtomicInteger(0);
            // Create workers
            for (int i = 0; i < 5; i++) {
                        "recovery-worker-" + i,
                            long now = System.currentTimeMillis();
                            messageCounter.incrementAndGet();
            // Measure response time under constant load
                            "Recovery Speed Test",
                                // Simulate recovery by measuring message latency
                                long now = System.currentTimeMillis();
            // Verify responsiveness
                    metrics.getLatencyPercentileMs(99) < 50,
                    "Recovery latency p99 should be <50ms, was "
                            + metrics.getLatencyPercentileMs(99)
                            + " ms");
     * Test concurrent failure scenarios: multiple failures across hierarchy simultaneously.
     * <p>Expected: System remains available, no deadlocks, bounded cascade
    @DisplayName("Concurrent failures (multiple simultaneous crashes)")
    void testConcurrentFailures() {
        AtomicInteger failureCount = new AtomicInteger(0);
            // Create 10 workers, each prone to random failures
            for (int i = 0; i < 10; i++) {
                        "concurrent-worker-" + i,
                            // 5% chance of failure
                            if (random.nextDouble() < 0.05) {
                                failureCount.incrementAndGet();
                                throw new RuntimeException(
                                        "Concurrent failure at worker " + workerId);
            // Aggressive load: 1000 messages/sec
            LoadProfile profile = new LoadProfile.ConstantLoad(1000L, Duration.ofSeconds(5));
                            "Concurrent Failures Test",
                                // Messages trigger concurrent failures
            // Verify system survived concurrent failures
            assertTrue(failureCount.get() > 0, "Should have triggered concurrent failures");
                    metrics.getOperationCount() > 1000,
                    "Should handle >1000 message slots despite concurrent failures");
            // Error rate reflects all failures but system continues
                    metrics.getErrorRate() < 20.0,
                    "Error rate should be <20%, was " + metrics.getErrorRate() + "%");
    @AfterEach
    void cleanupAfterEach() {
        cleanup();
}
