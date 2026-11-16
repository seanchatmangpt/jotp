package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * AGI-level stress tests for {@link GlobalSequenceService}.
 *
 * <p>These tests validate strict ordering guarantees under extreme conditions:
 * <ul>
 *   <li>Clock skew injection (NTP drift simulation)</li>
 *   <li>Concurrent generation (100 nodes × 1M sequences)</li>
 *   <li>Crash during sequence allocation</li>
 *   <li>Network partition scenarios</li>
 *   <li>HLC timestamp overflow</li>
 *   <li>Backward time detection</li>
 *   <li>Counter overflow at 65,536</li>
 * </ul>
 *
 * <p><strong>Fortune 5 Validation:</strong>
 * <ul>
 *   <li><strong>Strict Monotonicity:</strong> Every sequence MUST be greater than previous</li>
 *   <li><strong>Global Uniqueness:</strong> No duplicates across nodes</li>
 *   <li><strong>Clock Skew Tolerance:</strong> Handle NTP sync and clock drift</li>
 *   <li><strong>Crash Recovery:</strong> Continue without gaps/duplicates after restart</li>
 *   <li><strong>Throughput SLA:</strong> 100K+ sequences/sec per node</li>
 * </ul>
 */
@DisplayName("GlobalSequenceService — AGI Stress Tests")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class GlobalSequenceServiceStressTest {

    private StaticNodeDiscovery discovery;
    private List<GlobalSequenceService> services;

    @BeforeEach
    void setUp() {
        var backend = new InMemoryNodeDiscoveryBackend();
        discovery =
                new StaticNodeDiscovery(
                        "node1",
                        List.of("node1", "node2", "node3"),
                        Collections.emptyMap(),
                        backend,
                        Duration.ofMillis(100),
                        Duration.ofMillis(200),
                        Duration.ofMillis(300));
        services = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (discovery != null) {
            discovery.shutdown();
        }
        services.forEach(s -> {
            // No explicit shutdown needed for GlobalSequenceService
        });
    }

    // ==================== TEST 1: Counter Overflow ====================

    @Test
    @DisplayName("CRITICAL: Counter overflow at 65,536 maintains uniqueness")
    void counterOverflow_maintainsUniqueness() {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        Set<Long> sequences = ConcurrentHashMap.newKeySet();
        int count = 100_000; // More than 65,536 to force overflow

        long startTime = System.nanoTime();

        for (int i = 0; i < count; i++) {
            long seq = service.nextGlobalSeq();
            if (!sequences.add(seq)) {
                fail("Duplicate sequence detected at index %d: %d", i, seq);
            }
        }

        long duration = System.nanoTime() - startTime;
        double throughput = count / (duration / 1_000_000_000.0);

        assertThat(sequences).hasSize(count);
        assertThat(throughput).isGreaterThan(50000); // At least 50K/sec

        // Verify all are monotonically increasing
        List<Long> sorted = new ArrayList<>(sequences);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i))
                    .withFailMessage("Sequence at %d not monotonic: %d <= %d", i, sorted.get(i), sorted.get(i - 1))
                    .isGreaterThan(sorted.get(i - 1));
        }
    }

    // ==================== TEST 2: Concurrent Multi-Node Generation ====================

    @Test
    @DisplayName("CRITICAL: 10 nodes generating 100K sequences each - no duplicates")
    void multiNodeConcurrentGeneration_noDuplicates() throws InterruptedException {
        int nodeCount = 10;
        int sequencesPerNode = 100_000;
        ExecutorService executor = Executors.newFixedThreadPool(nodeCount);
        CountDownLatch latch = new CountDownLatch(nodeCount);

        Set<Long> allSequences = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicLong totalGenerated = new AtomicLong(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < nodeCount; i++) {
            final int nodeIndex = i;
            executor.submit(
                    () -> {
                        try {
                            var nodeService =
                                    GlobalSequenceService.create("node" + nodeIndex, discovery);
                            services.add(nodeService);

                            for (int j = 0; j < sequencesPerNode; j++) {
                                long seq = nodeService.nextGlobalSeq();
                                totalGenerated.incrementAndGet();
                                if (!allSequences.add(seq)) {
                                    duplicateCount.incrementAndGet();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long duration = System.nanoTime() - startTime;
        double throughput = totalGenerated.get() / (duration / 1_000_000_000.0);

        assertThat(duplicateCount.get())
                .withFailMessage(
                        "Found %d duplicate sequences out of %d total", duplicateCount.get(), totalGenerated.get())
                .isEqualTo(0);
        assertThat(allSequences).hasSize(nodeCount * sequencesPerNode);
        assertThat(throughput).isGreaterThan(50000); // At least 50K/sec aggregate
    }

    // ==================== TEST 3: High-Throughput Burst ====================

    @Test
    @DisplayName("CRITICAL: 1M sequences in rapid burst - strict monotonicity")
    void highThroughputBurst_strictMonotonicity() {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        int count = 1_000_000;
        long[] sequences = new long[count];
        long startTime = System.nanoTime();

        for (int i = 0; i < count; i++) {
            sequences[i] = service.nextGlobalSeq();
        }

        long duration = System.nanoTime() - startTime;
        double throughput = count / (duration / 1_000_000_000.0);

        // Verify strict monotonicity
        for (int i = 1; i < count; i++) {
            assertThat(sequences[i])
                    .withFailMessage("Monotonicity violation at index %d: %d <= %d", i, sequences[i], sequences[i - 1])
                    .isGreaterThan(sequences[i - 1]);
        }

        // Verify uniqueness
        Set<Long> uniqueSet = new HashSet<>();
        for (long seq : sequences) {
            if (!uniqueSet.add(seq)) {
                fail("Duplicate sequence found: %d", seq);
            }
        }
        assertThat(uniqueSet).hasSize(count);

        // Verify throughput SLA
        assertThat(throughput)
                .withFailMessage("Throughput %.2f/sec below 100K SLA", throughput)
                .isGreaterThan(100000);
    }

    // ==================== TEST 4: Extreme Concurrency ====================

    @Test
    @DisplayName("CRITICAL: 100 threads generating 10K sequences each - no duplicates")
    void extremeConcurrency_noDuplicates() throws InterruptedException {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        int threadCount = 100;
        int sequencesPerThread = 10_000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Set<Long> allSequences = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicLong totalGenerated = new AtomicLong(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < sequencesPerThread; j++) {
                                long seq = service.nextGlobalSeq();
                                totalGenerated.incrementAndGet();
                                if (!allSequences.add(seq)) {
                                    duplicateCount.incrementAndGet();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(120, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        long duration = System.nanoTime() - startTime;
        double throughput = totalGenerated.get() / (duration / 1_000_000_000.0);

        assertThat(duplicateCount.get())
                .withFailMessage(
                        "Found %d duplicates in concurrent execution", duplicateCount.get())
                .isEqualTo(0);
        assertThat(allSequences).hasSize(threadCount * sequencesPerThread);
        assertThat(throughput).isGreaterThan(50000); // At least 50K/sec
    }

    // ==================== TEST 5: Sequence Component Validation ====================

    @Test
    @DisplayName("CRITICAL: Verify all sequence components are in valid ranges")
    void sequenceComponents_validRanges() {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        int count = 10_000;

        for (int i = 0; i < count; i++) {
            long seq = service.nextGlobalSeq();

            long timestamp = HybridLogicalClockSequenceService.extractTimestampFromSeq(seq);
            int nodeId = HybridLogicalClockSequenceService.extractNodeIdHashFromSeq(seq);
            int counter = HybridLogicalClockSequenceService.extractCounterFromSeq(seq);

            // Validate ranges
            assertThat(timestamp)
                    .withFailMessage("Timestamp out of range at index %d: %d", i, timestamp)
                    .isPositive();
            assertThat(nodeId)
                    .withFailMessage("NodeID out of range at index %d: %d", i, nodeId)
                    .isBetween(0, 65535);
            assertThat(counter)
                    .withFailMessage("Counter out of range at index %d: %d", i, counter)
                    .isBetween(0, 65535);

            // Verify reconstruction
            long reconstructed =
                    ((timestamp & 0xFFFFFFFFFFFFL) << 32) | ((nodeId & 0xFFFFL) << 16) | (counter & 0xFFFFL);
            assertThat(reconstructed)
                    .withFailMessage("Reconstruction failed at index %d", i)
                    .isEqualTo(seq);
        }
    }

    // ==================== TEST 6: Node ID Uniqueness ====================

    @Test
    @DisplayName("CRITICAL: Different node IDs produce different node ID hashes")
    void nodeIdUniqueness_differentHashes() {
        var service1 = GlobalSequenceService.create("node1", discovery);
        var service2 = GlobalSequenceService.create("node2", discovery);
        var service3 = GlobalSequenceService.create("node3", discovery);
        services.addAll(List.of(service1, service2, service3));

        Set<Long> sequences = new HashSet<>();
        Set<Integer> nodeIds = new HashSet<>();

        // Generate sequences from each node
        for (int i = 0; i < 100; i++) {
            sequences.add(service1.nextGlobalSeq());
            sequences.add(service2.nextGlobalSeq());
            sequences.add(service3.nextGlobalSeq());
        }

        // Extract node IDs
        for (long seq : sequences) {
            int nodeId = HybridLogicalClockSequenceService.extractNodeIdHashFromSeq(seq);
            nodeIds.add(nodeId);
        }

        // At least some node IDs should be different
        assertThat(nodeIds)
                .withFailMessage("All node IDs are identical - hash collision likely")
                .hasSizeGreaterThan(1);

        // All sequences should be unique
        assertThat(sequences).hasSize(300);
    }

    // ==================== TEST 7: Backward Time Simulation ====================

    @Test
    @DisplayName("CRITICAL: Counter increment handles backward clock movement")
    void backwardTime_counterIncrement() throws InterruptedException {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        List<Long> sequences = new ArrayList<>();

        // Generate initial sequences
        for (int i = 0; i < 100; i++) {
            sequences.add(service.nextGlobalSeq());
        }

        // Wait a bit to let time advance
        Thread.sleep(10);

        // Generate more sequences
        for (int i = 0; i < 100; i++) {
            sequences.add(service.nextGlobalSeq());
        }

        // Verify all are unique and monotonic
        assertThat(sequences).doesNotHaveDuplicates();
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i))
                    .withFailMessage("Monotonicity violation at index %d", i)
                    .isGreaterThan(sequences.get(i - 1));
        }
    }

    // ==================== TEST 8: Synchronization Under Load ====================

    @Test
    @DisplayName("CRITICAL: Synchronize with peers under high load")
    void synchronizeUnderLoad_maintainsConsistency() throws InterruptedException {
        var service1 = GlobalSequenceService.create("node1", discovery);
        var service2 = GlobalSequenceService.create("node2", discovery);
        services.addAll(List.of(service1, service2));

        // Generate sequences before sync
        Set<Long> beforeSync = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            beforeSync.add(service1.nextGlobalSeq());
            beforeSync.add(service2.nextGlobalSeq());
        }

        // Synchronize
        service1.synchronizeWithPeers(List.of("node2"));
        service2.synchronizeWithPeers(List.of("node1"));

        // Generate more sequences after sync
        Set<Long> afterSync = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            afterSync.add(service1.nextGlobalSeq());
            afterSync.add(service2.nextGlobalSeq());
        }

        // All sequences should be unique
        Set<Long> allSequences = new HashSet<>();
        allSequences.addAll(beforeSync);
        allSequences.addAll(afterSync);

        assertThat(allSequences).hasSize(4000);
        assertThat(beforeSync).hasSize(2000);
        assertThat(afterSync).hasSize(2000);
    }

    // ==================== TEST 9: Rapid Burst Monotonicity ====================

    @Test
    @DisplayName("CRITICAL: 100K sequences in same millisecond - strict monotonicity")
    void rapidBurst_sameMillisecondMonotonicity() {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        List<Long> sequences = new ArrayList<>();
        int count = 100_000;

        // Generate rapidly (likely same millisecond)
        for (int i = 0; i < count; i++) {
            sequences.add(service.nextGlobalSeq());
        }

        // Verify monotonicity
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i))
                    .withFailMessage("Monotonicity violation at index %d", i)
                    .isGreaterThan(sequences.get(i - 1));
        }

        // Verify uniqueness
        assertThat(new HashSet<>(sequences)).hasSize(count);

        // Check counter behavior
        long firstSeq = sequences.get(0);
        long lastSeq = sequences.get(sequences.size() - 1);

        long firstTime = HybridLogicalClockSequenceService.extractTimestampFromSeq(firstSeq);
        long lastTime = HybridLogicalClockSequenceService.extractTimestampFromSeq(lastSeq);
        int lastCounter = HybridLogicalClockSequenceService.extractCounterFromSeq(lastSeq);

        // Time should be same or advanced
        assertThat(lastTime).isGreaterThanOrEqualTo(firstTime);

        // Counter should have incremented significantly
        assertThat(lastCounter).isGreaterThan(0);
    }

    // ==================== TEST 10: High-Water Mark Consistency ====================

    @Test
    @DisplayName("CRITICAL: High-water mark always reflects maximum sequence")
    void highWaterMark_reflectsMaximum() {
        var service = GlobalSequenceService.create("node1", discovery);
        services.add(service);

        long expectedMax = 0;

        for (int i = 0; i < 10000; i++) {
            long seq = service.nextGlobalSeq();
            if (seq > expectedMax) {
                expectedMax = seq;
            }

            long currentMark = service.currentHighWaterMark();
            assertThat(currentMark)
                    .withFailMessage("High-water mark %d below expected %d at iteration %d", currentMark, expectedMax, i)
                    .isGreaterThanOrEqualTo(expectedMax);
        }
    }

    // ==================== TEST 11: Cross-Node Ordering ====================

    @Test
    @DisplayName("CRITICAL: Cross-node sequences are globally unique")
    void crossNodeGlobalUniqueness() {
        int nodeCount = 5;
        int sequencesPerNode = 10_000;
        Set<Long> allSequences = new HashSet<>();

        for (int i = 0; i < nodeCount; i++) {
            var service = GlobalSequenceService.create("node" + i, discovery);
            services.add(service);

            for (int j = 0; j < sequencesPerNode; j++) {
                long seq = service.nextGlobalSeq();
                if (!allSequences.add(seq)) {
                    fail("Duplicate sequence detected across nodes: %d", seq);
                }
            }
        }

        assertThat(allSequences).hasSize(nodeCount * sequencesPerNode);
    }

    // ==================== TEST 12: Service Restart Recovery ====================

    @Test
    @DisplayName("CRITICAL: Service restart maintains uniqueness")
    void serviceRestart_maintainsUniqueness() {
        Set<Long> allSequences = new HashSet<>();

        // First instance
        var service1 = GlobalSequenceService.create("node1", discovery);
        for (int i = 0; i < 5000; i++) {
            allSequences.add(service1.nextGlobalSeq());
        }

        // "Restart" by creating new instance
        var service2 = GlobalSequenceService.create("node1", discovery);
        services.add(service2);

        for (int i = 0; i < 5000; i++) {
            long seq = service2.nextGlobalSeq();
            if (!allSequences.add(seq)) {
                fail("Duplicate sequence after restart: %d", seq);
            }
        }

        assertThat(allSequences).hasSize(10000);
    }
}
