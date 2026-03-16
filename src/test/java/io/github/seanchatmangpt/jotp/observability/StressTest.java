package io.github.seanchatmangpt.jotp.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Stress test that captures ACTUAL performance metrics under load. Uses System.nanoTime() for
 * microsecond precision timing.
 */
public class StressTest {

    /** Simple message types for testing. */
    sealed interface TestMsg {
        record Increment() implements TestMsg {}

        record Get() implements TestMsg {}

        record Envelope(long timestamp, String payload) implements TestMsg {}
    }

    /**
     * Message Tsunami: Send 1M messages through Proc mailboxes and measure latency distribution.
     */
    @Test
    public void testMessageTsunami() throws Exception {
        System.out.println("\n=== MESSAGE TSUNAMI TEST (1M MESSAGES) ===");

        LongAdder messagesReceived = new LongAdder();
        LongAdder totalLatency = new LongAdder();

        // Handler for echo process
        BiFunction<Integer, TestMsg, Integer> handler =
                (Integer state, TestMsg msg) ->
                        switch (msg) {
                            case TestMsg.Envelope env -> {
                                messagesReceived.increment();
                                totalLatency.add(System.nanoTime() - env.timestamp());
                                yield state;
                            }
                            default -> state;
                        };

        Proc<Integer, TestMsg> proc = Proc.spawn(0, handler);

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            proc.tell(new TestMsg.Envelope(System.nanoTime(), "warmup-" + i));
        }
        Thread.sleep(100);

        // Measure baseline
        messagesReceived.reset();
        totalLatency.reset();
        long startTime = System.nanoTime();

        // Send 1M messages
        for (int i = 0; i < 1_000_000; i++) {
            proc.tell(new TestMsg.Envelope(System.nanoTime(), "msg-" + i));
        }

        // Wait for processing
        Thread.sleep(5000);

        long endTime = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        double avgLatencyNanos =
                messagesReceived.sum() > 0 ? totalLatency.sum() / messagesReceived.sum() : 0;
        double avgLatencyMicros = avgLatencyNanos / 1000.0;
        double throughput = messagesReceived.sum() * 1000.0 / elapsedMs;

        System.out.println("Messages Received: " + messagesReceived.sum());
        System.out.println("Elapsed Time: " + elapsedMs + " ms");
        System.out.println("Average Latency: " + String.format("%.2f", avgLatencyMicros) + " µs");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " msg/sec");

        assertTrue(messagesReceived.sum() >= 999_900, "Should process 99.99% of messages");
        assertTrue(avgLatencyMicros < 100, "Average latency should be < 100µs");
    }

    /** Process Storm: Spawn 1K processes and measure spawn overhead. */
    @Test
    public void testProcessStorm() throws Exception {
        System.out.println("\n=== PROCESS STORM TEST (1K PROCESSES) ===");

        int processCount = 1000;
        List<Proc<Integer, TestMsg>> procs = new CopyOnWriteArrayList<>();

        // Simple handler
        BiFunction<Integer, TestMsg, Integer> handler = (state, msg) -> state + 1;

        // Measure spawn time
        long startTime = System.nanoTime();

        // Spawn 1K processes
        for (int i = 0; i < processCount; i++) {
            Proc<Integer, TestMsg> proc = Proc.spawn(0, handler);
            procs.add(proc);
        }

        long spawnTime = System.nanoTime() - startTime;
        double avgSpawnMicros = spawnTime / (double) procs.size() / 1000.0;

        // Send one message to each
        long sendStart = System.nanoTime();
        for (var proc : procs) {
            proc.tell(new TestMsg.Increment());
        }
        long sendTime = System.nanoTime() - sendStart;
        double avgSendMicros = sendTime / (double) procs.size() / 1000.0;

        System.out.println("Processes Spawned: " + procs.size());
        System.out.println("Spawn Time: " + String.format("%.2f", avgSpawnMicros) + " µs/process");
        System.out.println("Send Time: " + String.format("%.2f", avgSendMicros) + " µs/call");

        assertTrue(procs.size() >= 999, "Should spawn all processes");
        assertTrue(avgSpawnMicros < 1000, "Spawn should be < 1ms per process");
    }

    /** Sustained Load: Run for 10 seconds monitoring memory and GC. */
    @Test
    public void testSustainedLoad() throws Exception {
        System.out.println("\n=== SUSTAINED LOAD TEST (10 SECONDS) ===");

        AtomicInteger messageCount = new AtomicInteger(0);

        // Handler for counting process
        BiFunction<Integer, TestMsg, Integer> handler =
                (state, msg) -> {
                    messageCount.incrementAndGet();
                    return state;
                };

        Proc<Integer, TestMsg> proc = Proc.spawn(0, handler);

        Runtime runtime = Runtime.getRuntime();
        long testDurationMs = 10_000;
        long startTime = System.currentTimeMillis();

        // Memory tracking
        List<Long> memorySamples = new CopyOnWriteArrayList<>();

        // Publisher thread
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        Future<?> publishing =
                publisher.submit(
                        () -> {
                            int id = 0;
                            while (System.currentTimeMillis() - startTime < testDurationMs) {
                                proc.tell(new TestMsg.Envelope(System.nanoTime(), "msg-" + id++));
                                try {
                                    Thread.sleep(1); // 1K messages/sec
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        });

        // Monitor thread
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(
                () -> {
                    long used = runtime.totalMemory() - runtime.freeMemory();
                    memorySamples.add(used);
                },
                0,
                1,
                TimeUnit.SECONDS);

        // Wait for completion
        publishing.get(testDurationMs + 5000, TimeUnit.MILLISECONDS);
        publisher.shutdown();
        monitor.shutdown();

        Thread.sleep(2000); // Allow final messages to process

        // Calculate metrics
        int totalMessages = messageCount.get();
        double durationSec = (System.currentTimeMillis() - startTime) / 1000.0;
        double throughput = totalMessages / durationSec;

        // Memory analysis
        long initialMemory = memorySamples.get(0);
        long peakMemory = memorySamples.stream().max(Long::compare).orElse(initialMemory);
        long finalMemory = memorySamples.get(memorySamples.size() - 1);
        long memoryGrowth = finalMemory - initialMemory;

        System.out.println("Test Duration: " + String.format("%.2f", durationSec) + " seconds");
        System.out.println("Total Messages: " + totalMessages);
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " msg/sec");
        System.out.println("Memory Samples: " + memorySamples.size());
        System.out.println("Initial Memory: " + (initialMemory / 1024 / 1024) + " MB");
        System.out.println("Peak Memory: " + (peakMemory / 1024 / 1024) + " MB");
        System.out.println("Final Memory: " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("Memory Growth: " + (memoryGrowth / 1024 / 1024) + " MB");

        assertTrue(totalMessages > 5_000, "Should process > 5K messages in 10 seconds");
        assertTrue(memoryGrowth < 100 * 1024 * 1024, "Memory growth should be < 100MB (no leak)");
    }

    /** Memory Leak Detection: Repeatedly create/destroy processes. */
    @Test
    public void testMemoryLeakDetection() throws Exception {
        System.out.println("\n=== MEMORY LEAK DETECTION TEST ===");

        Runtime runtime = Runtime.getRuntime();
        int iterations = 100;
        List<Long> memorySnapshots = new ArrayList<>();

        BiFunction<Integer, TestMsg, Integer> handler = (state, msg) -> state + 1;

        for (int i = 0; i < iterations; i++) {
            // Create processes
            List<Proc<Integer, TestMsg>> procs = new ArrayList<>();
            for (int j = 0; j < 100; j++) {
                procs.add(Proc.spawn(0, handler));
            }

            // Send messages
            for (var proc : procs) {
                proc.tell(new TestMsg.Increment());
            }

            // Cleanup
            procs.clear();

            // Force GC every 10 iterations
            if (i % 10 == 0) {
                System.gc();
                Thread.sleep(100);
            }

            // Snapshot memory
            long used = runtime.totalMemory() - runtime.freeMemory();
            memorySnapshots.add(used);

            if (i % 10 == 0) {
                System.out.println("Iteration " + i + ": " + (used / 1024 / 1024) + " MB");
            }
        }

        // Final GC
        System.gc();
        Thread.sleep(500);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long initialMemory = memorySnapshots.get(0);
        long growth = finalMemory - initialMemory;

        // Calculate trend
        double avgGrowthPerIter = growth / (double) iterations;

        System.out.println("Initial Memory: " + (initialMemory / 1024 / 1024) + " MB");
        System.out.println("Final Memory: " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("Total Growth: " + (growth / 1024 / 1024) + " MB");
        System.out.println("Growth Per Iteration: " + (avgGrowthPerIter / 1024) + " KB");

        // Check for leak: growth should be minimal
        assertTrue(growth < 50 * 1024 * 1024, "Memory growth should be < 50MB over 100 iterations");
    }

    /** Percentile Latency Test: Capture P50, P95, P99 latencies for message passing. */
    @Test
    public void testPercentileLatency() throws Exception {
        System.out.println("\n=== PERCENTILE LATENCY TEST ===");

        int messageCount = 100_000;
        List<Long> latencies = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(messageCount);

        BiFunction<Integer, TestMsg, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMsg.Envelope env) {
                        long latency = System.nanoTime() - env.timestamp();
                        latencies.add(latency);
                        latch.countDown();
                    }
                    return state;
                };

        Proc<Integer, TestMsg> proc = Proc.spawn(0, handler);

        // Send messages
        for (int i = 0; i < messageCount; i++) {
            proc.tell(new TestMsg.Envelope(System.nanoTime(), "msg-" + i));
        }

        // Wait for processing
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should process all messages within 10s");

        // Calculate percentiles
        List<Long> sorted = latencies.stream().sorted().toList();

        if (sorted.isEmpty()) {
            fail("No latencies captured");
            return;
        }

        long p50 = sorted.get((int) (sorted.size() * 0.50));
        long p95 = sorted.get((int) (sorted.size() * 0.95));
        long p99 = sorted.get((int) (sorted.size() * 0.99));
        long max = sorted.get(sorted.size() - 1);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("Messages Processed: " + sorted.size());
        System.out.println("P50 Latency: " + String.format("%.2f", p50 / 1000.0) + " µs");
        System.out.println("P95 Latency: " + String.format("%.2f", p95 / 1000.0) + " µs");
        System.out.println("P99 Latency: " + String.format("%.2f", p99 / 1000.0) + " µs");
        System.out.println("Max Latency: " + String.format("%.2f", max / 1000.0) + " µs");
        System.out.println("Avg Latency: " + String.format("%.2f", avg / 1000.0) + " µs");

        assertTrue(p99 < 1_000_000, "P99 latency should be < 1ms");
        assertTrue(avg < 100_000, "Average latency should be < 100µs");
    }

    /** Concurrent Process Stress: Measure contention with many concurrent processes. */
    @Test
    public void testConcurrentProcessStress() throws Exception {
        System.out.println("\n=== CONCURRENT PROCESS STRESS TEST ===");

        int processCount = 500;
        int messagesPerProcess = 500;
        LongAdder totalMessages = new LongAdder();

        BiFunction<Integer, TestMsg, Integer> handler =
                (state, msg) -> {
                    totalMessages.increment();
                    return state;
                };

        // Spawn 500 processes
        List<Proc<Integer, TestMsg>> procs = new ArrayList<>();
        for (int i = 0; i < processCount; i++) {
            procs.add(Proc.spawn(0, handler));
        }

        // Warmup
        for (var proc : procs) {
            proc.tell(new TestMsg.Increment());
        }
        Thread.sleep(100);

        // Measure concurrent message passing
        totalMessages.reset();
        long startTime = System.nanoTime();

        // Send 500 messages to each of 500 processes = 250K total messages
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();

        for (var proc : procs) {
            futures.add(
                    executor.submit(
                            () -> {
                                for (int i = 0; i < messagesPerProcess; i++) {
                                    proc.tell(new TestMsg.Envelope(System.nanoTime(), "msg-" + i));
                                }
                            }));
        }

        // Wait for all sends to complete
        for (var future : futures) {
            future.get();
        }
        executor.shutdown();

        long sendTime = System.nanoTime() - startTime;
        double sendThroughput =
                (processCount * messagesPerProcess * 1000.0) / (sendTime / 1_000_000.0);

        // Wait for processing
        Thread.sleep(5000);

        System.out.println("Processes: " + processCount);
        System.out.println("Messages Per Process: " + messagesPerProcess);
        System.out.println("Total Messages: " + totalMessages.sum());
        System.out.println(
                "Send Throughput: " + String.format("%.2f", sendThroughput) + " msg/sec");
        System.out.println("Send Time: " + (sendTime / 1_000_000) + " ms");

        assertTrue(
                totalMessages.sum() >= (processCount * messagesPerProcess * 0.99),
                "Should process 99% of messages");
    }
}
