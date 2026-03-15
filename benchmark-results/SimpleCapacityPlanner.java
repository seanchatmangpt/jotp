import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SimpleCapacityPlanner {
    
    private static final long MEMORY_PER_1K_EVENTS = 10 * 1024 * 1024; // 10MB
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== JOTP Capacity Planning Tests - Java 26 ===\n");
        
        // Run all instance profiles
        testSmallInstance();
        testMediumInstance();
        testLargeInstance();
        testEnterpriseInstance();
        testMemoryOverhead();
        testHotPathContamination();
    }
    
    static void testSmallInstance() throws Exception {
        System.out.println("Testing SMALL instance (1K msg/sec, 10 processes)...");
        CapacityReport report = runCapacityTest("small", 1_000, 10, 1.0, 1.0);
        System.out.println(report.toJson());
        System.out.println("SLA Compliance: " + (report.slaCompliant() ? "PASS ✓" : "FAIL ✗"));
        System.out.println();
    }
    
    static void testMediumInstance() throws Exception {
        System.out.println("Testing MEDIUM instance (10K msg/sec, 100 processes)...");
        CapacityReport report = runCapacityTest("medium", 10_000, 100, 3.0, 2.0);
        System.out.println(report.toJson());
        System.out.println("SLA Compliance: " + (report.slaCompliant() ? "PASS ✓" : "FAIL ✗"));
        System.out.println();
    }
    
    static void testLargeInstance() throws Exception {
        System.out.println("Testing LARGE instance (100K msg/sec, 1K processes)...");
        CapacityReport report = runCapacityTest("large", 100_000, 1_000, 5.0, 5.0);
        System.out.println(report.toJson());
        System.out.println("SLA Compliance: " + (report.slaCompliant() ? "PASS ✓" : "FAIL ✗"));
        System.out.println();
    }
    
    static void testEnterpriseInstance() throws Exception {
        System.out.println("Testing ENTERPRISE instance (1M msg/sec, 10K processes)...");
        CapacityReport report = runCapacityTest("enterprise", 1_000_000, 10_000, 10.0, 10.0);
        System.out.println(report.toJson());
        System.out.println("SLA Compliance: " + (report.slaCompliant() ? "PASS ✓" : "FAIL ✗"));
        System.out.println();
    }
    
    static void testMemoryOverhead() throws Exception {
        System.out.println("Testing MEMORY OVERHEAD (<10MB per 1000 events)...");
        int eventCount = 1_000;
        
        System.gc();
        Thread.sleep(100);
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Simulate event processing
        Map<String, Set<String>> eventStore = new ConcurrentHashMap<>();
        for (int i = 0; i < eventCount; i++) {
            eventStore.put("test_event_" + i, ConcurrentHashMap.newKeySet());
        }
        
        System.gc();
        Thread.sleep(100);
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        long memoryUsed = memoryAfter - memoryBefore;
        long memoryOverhead = memoryUsed / (eventCount / 1000);
        
        System.out.printf("Events: %d%n", eventCount);
        System.out.printf("Memory before: %,d bytes%n", memoryBefore);
        System.out.printf("Memory after: %,d bytes%n", memoryAfter);
        System.out.printf("Memory used: %,d bytes%n", memoryUsed);
        System.out.printf("Overhead per 1K events: %,d bytes (%.2f MB)%n", 
            memoryOverhead, memoryOverhead / (1024.0 * 1024.0));
        System.out.printf("SLA target: %,d bytes (%.2f MB)%n", 
            MEMORY_PER_1K_EVENTS, MEMORY_PER_1K_EVENTS / (1024.0 * 1024.0));
        System.out.println("SLA Compliance: " + (memoryOverhead < MEMORY_PER_1K_EVENTS ? "PASS ✓" : "FAIL ✗"));
        System.out.println();
    }
    
    static void testHotPathContamination() {
        System.out.println("Testing HOT PATH CONTAMINATION (<1% overhead)...");
        int iterations = 10_000;
        
        // Baseline
        long baselineTime = runHotPathBaseline(iterations);
        
        // With observability simulation
        long withObservabilityTime = runHotPathWithObservability(iterations);
        
        double overhead = ((double)(withObservabilityTime - baselineTime) / baselineTime) * 100.0;
        
        System.out.printf("Iterations: %,d%n", iterations);
        System.out.printf("Baseline time: %,d ns%n", baselineTime);
        System.out.printf("With observability: %,d ns%n", withObservabilityTime);
        System.out.printf("Overhead: %.3f%%%n", overhead);
        System.out.printf("SLA target: <1.000%%%n");
        System.out.println("SLA Compliance: " + (overhead < 1.0 ? "PASS ✓" : "FAIL ✗"));
        System.out.println();
    }
    
    static CapacityReport runCapacityTest(
        String instanceType,
        int targetThroughput,
        int processCount,
        double cpuTargetPercent,
        double p99TargetMs
    ) throws Exception {
        
        List<long[]> samples = Collections.synchronizedList(new ArrayList<>(targetThroughput));
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(processCount);
        AtomicInteger publishedCount = new AtomicInteger(0);
        Semaphore concurrencyLimit = new Semaphore(Math.min(processCount, 1000));
        
        // Simulate event bus
        Map<String, Set<String>> eventBus = new ConcurrentHashMap<>();
        
        // Baseline CPU time
        var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
        long baselineCpuTime = threadBean.getCurrentThreadCpuTime();
        
        // Spawn producer processes
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < processCount; i++) {
                final int processId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        while (publishedCount.get() < targetThroughput) {
                            concurrencyLimit.acquire();
                            try {
                                if (publishedCount.get() >= targetThroughput) break;
                                
                                int currentCount = publishedCount.incrementAndGet();
                                if (currentCount > targetThroughput) break;
                                
                                Instant start = Instant.now();
                                
                                // Simulate event publishing and metrics collection
                                eventBus.put("capacity_test_event_" + processId + "_" + currentCount, 
                                    ConcurrentHashMap.newKeySet());
                                
                                Instant end = Instant.now();
                                long latencyNs = Duration.between(start, end).toNanos();
                                samples.add(new long[]{currentCount, latencyNs});
                            } finally {
                                concurrencyLimit.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeLatch.countDown();
                    }
                });
            }
            
            // Start all producers simultaneously
            Instant testStart = Instant.now();
            startLatch.countDown();
            completeLatch.await();
            Instant testEnd = Instant.now();
            
            long testDurationMs = Duration.between(testStart, testEnd).toMillis();
            long actualCpuTime = threadBean.getCurrentThreadCpuTime() - baselineCpuTime;
            
            // Calculate percentiles
            List<Long> latencies = samples.stream()
                .map(s -> s[1])
                .sorted()
                .toList();
            
            long p50 = percentile(latencies, 50);
            long p95 = percentile(latencies, 95);
            long p99 = percentile(latencies, 99);
            
            double actualThroughput = (targetThroughput * 1000.0) / testDurationMs;
            double cpuOverhead = (actualCpuTime / 1_000_000.0) / testDurationMs * 100.0;
            
            // Memory metrics
            long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            System.gc();
            Thread.sleep(50);
            long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryUsed = memoryAfter - memoryBefore;
            
            return new CapacityReport(
                instanceType,
                targetThroughput,
                actualThroughput,
                processCount,
                p50 / 1_000_000.0,
                p95 / 1_000_000.0,
                p99 / 1_000_000.0,
                cpuOverhead,
                memoryUsed,
                (memoryUsed / (targetThroughput / 1000)),
                cpuTargetPercent,
                p99TargetMs
            );
        }
    }
    
    static long runHotPathBaseline(int iterations) {
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            int result = i * 2 + 1;
            result = result ^ (result >> 1);
        }
        return System.nanoTime() - startTime;
    }
    
    static long runHotPathWithObservability(int iterations) {
        Map<String, Set<String>> eventBus = new ConcurrentHashMap<>();
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            int result = i * 2 + 1;
            result = result ^ (result >> 1);
            
            // Minimal observability
            eventBus.put("hot_path_event_" + i, ConcurrentHashMap.newKeySet());
        }
        
        return System.nanoTime() - startTime;
    }
    
    static long percentile(List<Long> sortedData, int percentile) {
        if (sortedData.isEmpty()) return 0;
        int index = (int) Math.ceil((percentile / 100.0) * sortedData.size()) - 1;
        return sortedData.get(Math.max(0, Math.min(index, sortedData.size() - 1)));
    }
    
    static class CapacityReport {
        String instanceType;
        int targetThroughput;
        double actualThroughput;
        int processCount;
        double p50LatencyMs;
        double p95LatencyMs;
        double p99LatencyMs;
        double cpuOverheadPercent;
        long totalMemoryBytes;
        long memoryPer1KEvents;
        double cpuTargetPercent;
        double p99TargetMs;
        
        CapacityReport(
            String instanceType,
            int targetThroughput,
            double actualThroughput,
            int processCount,
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            double cpuOverheadPercent,
            long totalMemoryBytes,
            long memoryPer1KEvents,
            double cpuTargetPercent,
            double p99TargetMs
        ) {
            this.instanceType = instanceType;
            this.targetThroughput = targetThroughput;
            this.actualThroughput = actualThroughput;
            this.processCount = processCount;
            this.p50LatencyMs = p50LatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.cpuOverheadPercent = cpuOverheadPercent;
            this.totalMemoryBytes = totalMemoryBytes;
            this.memoryPer1KEvents = memoryPer1KEvents;
            this.cpuTargetPercent = cpuTargetPercent;
            this.p99TargetMs = p99TargetMs;
        }
        
        boolean slaCompliant() {
            return p99LatencyMs <= p99TargetMs && cpuOverheadPercent <= cpuTargetPercent;
        }
        
        List<String> violations() {
            List<String> violations = new ArrayList<>();
            if (p99LatencyMs > p99TargetMs) {
                violations.add(String.format("P99 latency %.2fms exceeds target %.2fms",
                    p99LatencyMs, p99TargetMs));
            }
            if (cpuOverheadPercent > cpuTargetPercent) {
                violations.add(String.format("CPU overhead %.2f%% exceeds target %.2f%%",
                    cpuOverheadPercent, cpuTargetPercent));
            }
            return violations;
        }
        
        String toJson() {
            return String.format("""
                {
                  "instance_type": "%s",
                  "target_throughput": %d,
                  "actual_throughput": "%.2f",
                  "process_count": %d,
                  "latency": {
                    "p50_ms": "%.3f",
                    "p95_ms": "%.3f",
                    "p99_ms": "%.3f"
                  },
                  "cpu_overhead_percent": "%.2f",
                  "cpu_target_percent": "%.2f",
                  "memory": {
                    "total_bytes": %d,
                    "per_1k_events_bytes": %d,
                    "target_bytes": %d
                  },
                  "sla_compliance": "%s",
                  "violations": [%s]
                }
                """.trim(),
                instanceType,
                targetThroughput,
                actualThroughput,
                processCount,
                p50LatencyMs,
                p95LatencyMs,
                p99LatencyMs,
                cpuOverheadPercent,
                cpuTargetPercent,
                totalMemoryBytes,
                memoryPer1KEvents,
                MEMORY_PER_1K_EVENTS,
                slaCompliant() ? "PASS" : "FAIL",
                violations().isEmpty() ? "" : String.join(", ", violations())
            );
        }
    }
}
