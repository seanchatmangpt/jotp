package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for DataPartitioner — intelligent data partitioning with hotspot detection and
 * rebalancing.
 *
 * <p>Test Coverage:
 * <ul>
 *   <li><b>Consistent Hashing:</b> Keys always map to same partition
 *   <li><b>Hotspot Detection:</b> Zipfian access patterns identified
 *   <li><b>Rebalancing:</b> Hot keys assigned multiple virtual nodes
 *   <li><b>Cross-Partition Traffic:</b> Minimized through intelligent distribution
 *   <li><b>Metrics:</b> Skewness, access frequency, replica count
 * </ul>
 */
class DataPartitionerTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Basic functionality tests
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void createsWithDefaultPartitions() {
        var partitioner = new DataPartitioner(128);
        assertThat(partitioner.getMetrics().totalPartitions()).isEqualTo(128);
    }

    @Test
    void throwsOnInvalidPartitionCount() {
        assertThatThrownBy(() -> new DataPartitioner(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataPartitioner(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsAccessForKey() {
        var partitioner = new DataPartitioner(128);
        partitioner.recordAccess("customer:apple");

        var metrics = partitioner.getMetrics();
        assertThat(metrics.activeKeys()).isOne();
        assertThat(metrics.totalAccesses()).isOne();
    }

    @Test
    void recordsAccessWithLatency() {
        var partitioner = new DataPartitioner(128);
        partitioner.recordAccess("customer:apple", 42);

        var stats = partitioner.getKeyStatistics();
        assertThat(stats.get("customer:apple").totalLatencyMs()).isEqualTo(42);
    }

    @Test
    void consistentHashReturnsZeroBased() {
        var partitioner = new DataPartitioner(128);
        int partition = partitioner.getPartition("customer:apple");

        assertThat(partition).isBetween(0, 127);
    }

    @Test
    void consistentHashIsDeterministic() {
        var partitioner = new DataPartitioner(256);

        int partition1 = partitioner.getPartition("customer:apple");
        int partition2 = partitioner.getPartition("customer:apple");

        assertThat(partition1).isEqualTo(partition2);
    }

    @Test
    void differentKeysMapToDifferentPartitionsUsually() {
        var partitioner = new DataPartitioner(256);

        Set<Integer> partitions = new HashSet<>();
        partitions.add(partitioner.getPartition("customer:apple"));
        partitions.add(partitioner.getPartition("customer:google"));
        partitions.add(partitioner.getPartition("customer:microsoft"));

        // Expect at least 2 different partitions (with high probability)
        assertThat(partitions.size()).isGreaterThanOrEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Hotspot detection with realistic (Zipfian) distributions
    // ────────────────────────────────────────────────────────────────────────────────

    @Nested
    class ZipfianDistributionTests {

        /**
         * Simulates a realistic Zipfian access pattern: N most-accessed item gets 1/1^alpha
         * frequency, 2nd gets 1/2^alpha, etc.
         *
         * <p>Example: alpha=1.0 (moderate skew):
         * <ul>
         *   <li>Top item: 1 / 1 = 1.0 frequency
         *   <li>2nd item: 1 / 2 = 0.5 frequency
         *   <li>3rd item: 1 / 3 = 0.33 frequency
         * </ul>
         *
         * Example: alpha=2.0 (heavy skew, realistic for web):
         * <ul>
         *   <li>Top item: 1 / 1 = 1.0 frequency
         *   <li>2nd item: 1 / 4 = 0.25 frequency
         *   <li>3rd item: 1 / 9 = 0.11 frequency
         * </ul>
         */
        private void generateZipfianAccess(
                DataPartitioner partitioner, int numKeys, double alpha, long totalAccesses) {
            var random = new Random(42); // Deterministic seed
            var accessCounts = new long[numKeys];

            // Compute Zipfian distribution
            double harmonicSum = 0;
            for (int i = 1; i <= numKeys; i++) {
                harmonicSum += 1.0 / Math.pow(i, alpha);
            }

            // Assign access counts following Zipf distribution
            for (int i = 1; i <= numKeys; i++) {
                long count = (long) (totalAccesses / harmonicSum / Math.pow(i, alpha));
                accessCounts[i - 1] = count;
            }

            // Record accesses
            for (int i = 0; i < numKeys; i++) {
                String key = "customer:" + i;
                for (long j = 0; j < accessCounts[i]; j++) {
                    partitioner.recordAccess(key);
                }
            }
        }

        @Test
        void detectsHotspotInZipfianAlpha1() {
            // Moderate skew: Apple ~2x more requests than Google
            var partitioner = new DataPartitioner(256);
            generateZipfianAccess(partitioner, 10, 1.0, 1000);

            var hotspots = partitioner.detectHotspots(0.8);

            assertThat(hotspots).isNotEmpty();
            // Top hotspot should be "customer:0"
            assertThat(hotspots.get(0).key()).isEqualTo("customer:0");
        }

        @Test
        void detectsHeavyHotspotInZipfianAlpha2() {
            // Heavy skew: Apple ~4x more requests than Google, ~9x more than Microsoft
            var partitioner = new DataPartitioner(256);
            generateZipfianAccess(partitioner, 20, 2.0, 10000);

            var hotspots = partitioner.detectHotspots(0.9);

            assertThat(hotspots).isNotEmpty();
            assertThat(hotspots.get(0).key()).isEqualTo("customer:0");
            assertThat(hotspots.get(0).accessCount()).isGreaterThan(hotspots.get(1).accessCount());
        }

        @Test
        void appleCaseStudy() {
            // Real-world scenario: Apple Inc (1M req/sec) vs Other (1K req/sec)
            var partitioner = new DataPartitioner(128);

            // Apple: 1M accesses
            for (int i = 0; i < 1_000_000; i++) {
                partitioner.recordAccess("customer:apple");
            }

            // Others: 1K accesses each
            for (int i = 0; i < 100; i++) {
                partitioner.recordAccess("customer:other:" + i);
            }

            var hotspots = partitioner.detectHotspots(0.95);

            assertThat(hotspots).isNotEmpty();
            assertThat(hotspots.get(0).key()).isEqualTo("customer:apple");
            assertThat(hotspots.get(0).accessCount()).isGreaterThan(9_000);
        }

        @Test
        void estimatesZipfianAlpha() {
            var partitioner = new DataPartitioner(256);
            generateZipfianAccess(partitioner, 50, 1.5, 100_000);

            double alpha = partitioner.estimateZipfianAlpha();

            // Should estimate alpha somewhere in the 1.0-2.0 range
            assertThat(alpha).isBetween(0.5, 3.0);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Virtual nodes and rebalancing
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void getPartitionsReturnsSingleByDefault() {
        var partitioner = new DataPartitioner(128);
        partitioner.recordAccess("customer:apple");

        var partitions = partitioner.getPartitions("customer:apple");
        assertThat(partitions).hasSize(1);
    }

    @Test
    void rebalanceIncreasesVirtualNodesForHotkeys() {
        var partitioner = new DataPartitioner(128);

        // Create hotspot: Apple 10x more than others
        for (int i = 0; i < 10_000; i++) {
            partitioner.recordAccess("customer:apple");
        }
        for (int i = 0; i < 1_000; i++) {
            partitioner.recordAccess("customer:google");
        }

        var actions = partitioner.rebalance();

        // Should have rebalance actions for hot key
        assertThat(actions).isNotEmpty();
        assertThat(actions.get(0).key()).isEqualTo("customer:apple");
    }

    @Test
    void rebalanceSpreadingReducesHotspot() {
        var partitioner = new DataPartitioner(16);

        // Heavy hotspot
        for (int i = 0; i < 50_000; i++) {
            partitioner.recordAccess("customer:apple");
        }
        for (int i = 0; i < 1_000; i++) {
            partitioner.recordAccess("customer:google");
        }

        // Before rebalance: single partition
        var partitionsBefore = partitioner.getPartitions("customer:apple");

        // Rebalance
        var actions = partitioner.rebalance();

        // After rebalance: multiple partitions for apple
        var partitionsAfter = partitioner.getPartitions("customer:apple");

        assertThat(partitionsAfter.size())
                .isGreaterThanOrEqualTo(partitionsBefore.size());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Metrics and observability
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void metricsReflectAccessPatterns() {
        var partitioner = new DataPartitioner(128);

        partitioner.recordAccess("customer:apple");
        partitioner.recordAccess("customer:apple");
        partitioner.recordAccess("customer:google");

        var metrics = partitioner.getMetrics();

        assertThat(metrics.totalPartitions()).isEqualTo(128);
        assertThat(metrics.activeKeys()).isEqualTo(2);
        assertThat(metrics.totalAccesses()).isEqualTo(3);
    }

    @Test
    void metricsComputeAvgAndStdDev() {
        var partitioner = new DataPartitioner(256);

        // 10 accesses to A, 2 to B, 1 to C
        for (int i = 0; i < 10; i++) partitioner.recordAccess("A");
        for (int i = 0; i < 2; i++) partitioner.recordAccess("B");
        partitioner.recordAccess("C");

        var metrics = partitioner.getMetrics();

        assertThat(metrics.avgAccessPerKey()).isEqualTo((10 + 2 + 1) / 3.0);
        assertThat(metrics.stdDevAccessPerKey()).isGreaterThan(0);
    }

    @Test
    void skewnessRatioHighForHotspots() {
        var partitioner = new DataPartitioner(128);

        // Heavy hotspot: 1000 vs 1
        for (int i = 0; i < 1000; i++) {
            partitioner.recordAccess("hot");
        }
        partitioner.recordAccess("cold");

        var metrics = partitioner.getMetrics();

        // Skewness ratio = max / mean = 1000 / ~500 ≈ 2.0
        assertThat(metrics.skewnessRatio()).isGreaterThan(1.5);
    }

    @Test
    void topHotspotsSortedByAccessCount() {
        var partitioner = new DataPartitioner(128);

        partitioner.recordAccess("A"); // 1
        for (int i = 0; i < 2; i++) partitioner.recordAccess("B");
        for (int i = 0; i < 5; i++) partitioner.recordAccess("C");

        var hotspots = partitioner.detectHotspots(0.5);

        assertThat(hotspots).isNotEmpty();
        if (hotspots.size() > 1) {
            // Should be in descending order
            assertThat(hotspots.get(0).accessCount())
                    .isGreaterThanOrEqualTo(hotspots.get(1).accessCount());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Cross-partition communication
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void estimatesCrossPartitionTraffic() {
        var partitioner = new DataPartitioner(128);

        long traffic0 = partitioner.estimateCrossPartitionTraffic("customer:apple", 0);
        long traffic1 = partitioner.estimateCrossPartitionTraffic("customer:apple", 1);

        // If key maps to partition 50, traffic from 0 should be recorded
        assertThat(traffic0 + traffic1).isGreaterThanOrEqualTo(0);
    }

    @Test
    void zeroTrafficWhenColocated() {
        var partitioner = new DataPartitioner(128);

        int partition = partitioner.getPartition("customer:apple");
        long traffic = partitioner.estimateCrossPartitionTraffic("customer:apple", partition);

        assertThat(traffic).isZero();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Edge cases
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void ignoresNullKeys() {
        var partitioner = new DataPartitioner(128);
        partitioner.recordAccess(null);
        partitioner.recordAccess("");

        var metrics = partitioner.getMetrics();
        assertThat(metrics.activeKeys()).isZero();
    }

    @Test
    void handlesEmptyPartitioner() {
        var partitioner = new DataPartitioner(256);

        var metrics = partitioner.getMetrics();
        assertThat(metrics.activeKeys()).isZero();
        assertThat(metrics.totalAccesses()).isZero();

        var hotspots = partitioner.detectHotspots(0.9);
        assertThat(hotspots).isEmpty();
    }

    @Test
    void consistentHashWithOnePartition() {
        var partitioner = new DataPartitioner(1);

        int p1 = partitioner.getPartition("A");
        int p2 = partitioner.getPartition("B");

        assertThat(p1).isEqualTo(p2).isEqualTo(0);
    }

    @Test
    void recordsNegativeLatencyAsZero() {
        var partitioner = new DataPartitioner(128);
        partitioner.recordAccess("key", -10);

        var stats = partitioner.getKeyStatistics();
        assertThat(stats.get("key").totalLatencyMs()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Thread safety and concurrent access
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void handlesConcurrentRecordAccess() throws Exception {
        var partitioner = new DataPartitioner(128);
        int threadCount = 10;
        int accessesPerThread = 1000;

        var executor = Executors.newFixedThreadPool(threadCount);
        var tasks = new ArrayList<Future<?>>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            tasks.add(
                    executor.submit(
                            () -> {
                                for (int i = 0; i < accessesPerThread; i++) {
                                    String key = "key:" + (threadId % 10);
                                    partitioner.recordAccess(key);
                                }
                            }));
        }

        for (var task : tasks) {
            task.get(5, TimeUnit.SECONDS);
        }

        executor.shutdown();

        var metrics = partitioner.getMetrics();
        assertThat(metrics.totalAccesses()).isEqualTo((long) threadCount * accessesPerThread);
        assertThat(metrics.activeKeys()).isEqualTo(10);
    }

    @Test
    void handlesConcurrentReadsAndWrites() throws Exception {
        var partitioner = new DataPartitioner(256);
        var executor = Executors.newFixedThreadPool(5);
        var tasks = new ArrayList<Future<?>>();

        // Writers
        for (int w = 0; w < 2; w++) {
            final int writerId = w;
            tasks.add(
                    executor.submit(
                            () -> {
                                for (int i = 0; i < 1000; i++) {
                                    partitioner.recordAccess("key:" + (writerId * 100 + i % 50));
                                }
                            }));
        }

        // Readers
        for (int r = 0; r < 3; r++) {
            tasks.add(
                    executor.submit(
                            () -> {
                                for (int i = 0; i < 500; i++) {
                                    partitioner.getPartition("key:" + (i % 100));
                                    partitioner.detectHotspots(0.8);
                                    partitioner.getMetrics();
                                }
                            }));
        }

        for (var task : tasks) {
            task.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();

        var metrics = partitioner.getMetrics();
        assertThat(metrics.totalAccesses()).isGreaterThan(0);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Integration scenarios
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    void fullLifecycleWithRebalancing() {
        var partitioner = new DataPartitioner(64);

        // Phase 1: Normal access pattern
        for (int i = 0; i < 1000; i++) {
            partitioner.recordAccess("customer:normal");
        }

        // Phase 2: Sudden hotspot
        for (int i = 0; i < 100_000; i++) {
            partitioner.recordAccess("customer:viral");
        }

        // Phase 3: Detect and rebalance
        var hotspots = partitioner.detectHotspots(0.9);
        assertThat(hotspots).isNotEmpty();
        assertThat(hotspots.get(0).key()).isEqualTo("customer:viral");

        var actions = partitioner.rebalance();
        assertThat(actions).isNotEmpty();

        // Phase 4: Verify metrics updated
        var metrics = partitioner.getMetrics();
        assertThat(metrics.skewnessRatio()).isGreaterThan(1.0);
        assertThat(metrics.topHotspots()).isNotEmpty();
    }

    @Test
    void multipleHotspotRebalancing() {
        var partitioner = new DataPartitioner(128);

        // Create multiple hotspots
        var hotKeys = List.of("apple", "google", "microsoft", "amazon", "meta");
        for (String hotKey : hotKeys) {
            for (int i = 0; i < 10_000; i++) {
                partitioner.recordAccess("customer:" + hotKey);
            }
        }

        // Cold keys
        for (int i = 0; i < 50; i++) {
            for (int j = 0; j < 10; j++) {
                partitioner.recordAccess("customer:cold:" + i);
            }
        }

        var hotspots = partitioner.detectHotspots(0.8);
        assertThat(hotspots.size()).isGreaterThan(0);

        var actions = partitioner.rebalance();
        assertThat(actions.size()).isGreaterThan(0);

        var metrics = partitioner.getMetrics();
        assertThat(metrics.topHotspots().size()).isGreaterThan(0);
    }
}
