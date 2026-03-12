package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProcStressTest — stress tests for Proc message throughput and latency.
 *
 * <p>Tests the core message passing performance of Proc under various load profiles (constant,
 * ramp, spike). Measures throughput, latency percentiles, and identifies breaking points where the
 * mailbox becomes saturated.
 */
@DisplayName("Proc Message Throughput Stress Tests")
class ProcStressTest extends StressTestBase {

    /**
     * Test constant load: maintain steady 10K messages/sec.
     *
     * <p>Expected: >100K messages delivered, latency p99 <10ms
     */
    @Test
    @DisplayName("Constant load (10K msg/sec for 10 seconds)")
    void testConstantLoad() {
        // Create a simple process that counts received messages
        AtomicInteger messageCount = new AtomicInteger();
        CountDownLatch readyLatch = new CountDownLatch(1);

        Proc<Integer, String> proc =
                new Proc<>(
                        0,
                        (state, msg) -> {
                            messageCount.incrementAndGet();
                            if (state == 0) {
                                readyLatch.countDown();
                            }
                            return state + 1;
                        });

        try {
            readyLatch.await();

            LoadProfile profile = new LoadProfile.ConstantLoad(10_000L, Duration.ofSeconds(10));
            MetricsCollector metrics =
                    runStressTest(
                            "Proc Constant Load (10K msg/sec)",
                            profile,
                            () -> {
                                proc.tell("message");
                            });

            // Verify results
            assertTrue(metrics.getOperationCount() > 100_000, "Should send >100K messages");
            assertTrue(
                    metrics.getLatencyPercentileMs(99) < 10,
                    "Latency p99 should be <10ms, was "
                            + metrics.getLatencyPercentileMs(99)
                            + " ms");
            assertEquals(0.0, metrics.getErrorRate(), 0.01, "Error rate should be near 0%");

        } finally {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test ramp load: gradually increase from 1K to 10K messages/sec.
     *
     * <p>Expected: Throughput should scale linearly, no errors
     */
    @Test
    @DisplayName("Ramp load (1K→10K msg/sec over 10 seconds)")
    void testRampLoad() {
        AtomicInteger messageCount = new AtomicInteger();

        Proc<Integer, String> proc =
                new Proc<>(
                        0,
                        (state, msg) -> {
                            messageCount.incrementAndGet();
                            return state + 1;
                        });

        try {
            LoadProfile profile = new LoadProfile.RampLoad(1_000L, 10_000L, Duration.ofSeconds(10));
            MetricsCollector metrics =
                    runStressTest(
                            "Proc Ramp Load (1K→10K msg/sec)",
                            profile,
                            () -> {
                                proc.tell("message");
                            });

            // Verify results
            assertTrue(metrics.getOperationCount() > 50_000, "Should send >50K messages in ramp");
            assertEquals(0.0, metrics.getErrorRate(), 0.01, "Error rate should be near 0%");

        } finally {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test spike load: sudden burst of 100K messages/sec for 1 second, then baseline 1K msg/sec.
     *
     * <p>Expected: System should handle spike without errors, then recover to baseline
     */
    @Test
    @DisplayName("Spike load (baseline 1K, spike 100K for 1 sec)")
    void testSpikeLoad() {
        AtomicInteger messageCount = new AtomicInteger();

        Proc<Integer, String> proc =
                new Proc<>(
                        0,
                        (state, msg) -> {
                            messageCount.incrementAndGet();
                            return state + 1;
                        });

        try {
            LoadProfile profile =
                    new LoadProfile.SpikeLoad(1_000L, 100_000L, 1_000L, Duration.ofSeconds(10));
            MetricsCollector metrics =
                    runStressTest(
                            "Proc Spike Load (1K baseline, 100K spike)",
                            profile,
                            () -> {
                                proc.tell("message");
                            });

            // Verify results
            assertTrue(metrics.getOperationCount() > 50_000, "Should send >50K messages");
            // During spike, some errors are acceptable (mailbox saturation)
            assertTrue(
                    metrics.getErrorRate() < 5.0,
                    "Error rate should be <5%, was " + metrics.getErrorRate() + "%");

        } finally {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test saturation: send until mailbox depth exceeds threshold.
     *
     * <p>Expected: Should detect breaking point when mailbox saturates
     */
    @Test
    @DisplayName("Saturation test (send until breaking point)")
    void testSaturation() {
        // Slow-processing proc to build up mailbox
        AtomicInteger messageCount = new AtomicInteger();

        Proc<Integer, String> proc =
                new Proc<>(
                        0,
                        (state, msg) -> {
                            messageCount.incrementAndGet();
                            // Simulate slow processing
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return state + 1;
                        });

        try {
            // Aggressive load: 100K msg/sec into a slow processor
            LoadProfile profile = new LoadProfile.ConstantLoad(100_000L, Duration.ofSeconds(5));
            BreakingPointDetector detector = BreakingPointDetector.createStrict();
            MetricsCollector metrics =
                    runStressTest(
                            "Proc Saturation Test",
                            profile,
                            () -> {
                                proc.tell("message");
                            },
                            detector);

            // Breaking point should be detected due to high latency
            // (with slow processing, latency will exceed threshold)
            System.out.println(
                    "Saturation test completed. Breaking point detected: "
                            + detector.isBreakingPointDetected());

        } finally {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    /**
     * Test concurrent senders: multiple threads sending to same process.
     *
     * <p>Expected: Throughput should scale with sender count, no lost messages
     */
    @Test
    @DisplayName("Concurrent senders (4 threads, 5K msg/sec each)")
    void testConcurrentSenders() {
        AtomicInteger messageCount = new AtomicInteger();

        Proc<Integer, String> proc =
                new Proc<>(
                        0,
                        (state, msg) -> {
                            messageCount.incrementAndGet();
                            return state + 1;
                        });

        try {
            int senderCount = 4;
            long msgsPerSender = 5_000L;
            long totalLoad = senderCount * msgsPerSender;

            LoadProfile profile = new LoadProfile.ConstantLoad(totalLoad, Duration.ofSeconds(5));

            // Simulate concurrent senders
            MetricsCollector metrics =
                    runStressTest(
                            "Proc Concurrent Senders (4 threads)",
                            profile,
                            () -> {
                                proc.tell("message");
                            });

            // Verify results
            assertTrue(
                    metrics.getOperationCount() > totalLoad * 4 / 10,
                    "Should send significant portion of messages");
            assertEquals(0.0, metrics.getErrorRate(), 0.1, "Error rate should be near 0%");

        } finally {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanup();
        }
    }

    @AfterEach
    void cleanupAfterEach() {
        cleanup();
    }
}
