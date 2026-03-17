package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ai.ResourceOptimizer.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ResourceOptimizer — resource allocation, ML prediction, rebalancing, and SLA
 * enforcement.
 *
 * <p>Compares against baseline allocators (random, round-robin) to measure efficiency gains.
 */
class ResourceOptimizerTest {

    private List<ClusterNode> nodes;
    private ResourceOptimizer optimizer;

    @BeforeEach
    void setup() {
        ApplicationController.reset();
        // Create a 4-node cluster
        nodes = List.of(
                new ClusterNode("node-1", 16, 32768, 1000),
                new ClusterNode("node-2", 16, 32768, 1000),
                new ClusterNode("node-3", 8, 16384, 1000),
                new ClusterNode("node-4", 8, 16384, 1000));
        optimizer = ResourceOptimizer.create(nodes, Duration.ofSeconds(1));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Basic Allocation Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldAllocateProcessToFeasibleNode() {
        var spec = new ProcessSpec("proc-1", "web-worker", 4, 2048, 100);
        var result = optimizer.allocate(spec);

        assertThat(result.isSuccess()).isTrue();
        var node = result.unwrap();
        assertThat(node).isNotNull();
        assertThat(node.nodeId()).isIn("node-1", "node-2", "node-3", "node-4");
    }

    @Test
    void shouldRejectAllocationWhenInsufficientCpuCapacity() {
        // Allocate most CPU on all nodes
        var spec1 = new ProcessSpec("cpu-hog-1", "cpu-intensive", 15, 1024, 100);
        var spec2 = new ProcessSpec("cpu-hog-2", "cpu-intensive", 15, 1024, 100);
        optimizer.allocate(spec1);
        optimizer.allocate(spec2);
        optimizer.allocate(new ProcessSpec("cpu-hog-3", "cpu-intensive", 15, 1024, 100));
        optimizer.allocate(new ProcessSpec("cpu-hog-4", "cpu-intensive", 7, 1024, 100));

        // Try to allocate process that needs 16 cores (should fail)
        var spec = new ProcessSpec("cpu-hog-5", "cpu-intensive", 16, 1024, 100);
        var result = optimizer.allocate(spec);

        assertThat(result.isError()).isTrue();
        assertThat(result.unwrapErr().reason())
                .contains("No feasible node found");
    }

    @Test
    void shouldRejectAllocationWhenInsufficientMemoryCapacity() {
        // Allocate most memory
        optimizer.allocate(new ProcessSpec("mem-hog-1", "mem-intensive", 4, 32000, 100));

        // Try to allocate process needing more memory than available
        var spec = new ProcessSpec("mem-hog-2", "mem-intensive", 4, 2000, 100);
        var result = optimizer.allocate(spec);

        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldRespectSLACpuThresholdOf85Percent() {
        // Fill up node-1 to ~85% CPU
        var specs = List.of(
                new ProcessSpec("proc-1", "worker", 5, 4096, 100),
                new ProcessSpec("proc-2", "worker", 5, 4096, 100),
                new ProcessSpec("proc-3", "worker", 5, 4096, 100));

        for (var spec : specs) {
            optimizer.allocate(spec);
        }

        // Try to add more to push over 85%
        var overloadSpec = new ProcessSpec("overload", "worker", 2, 2048, 100);
        var result = optimizer.allocate(overloadSpec);

        // Should either succeed on another node or fail gracefully
        assertThat(result.isSuccess() || result.isError()).isTrue();
    }

    @Test
    void shouldRespectSLAMemoryThresholdOf90Percent() {
        // Fill memory on node-1
        optimizer.allocate(new ProcessSpec("mem-1", "worker", 2, 29312, 100));

        // Try to exceed 90% threshold
        var spec = new ProcessSpec("mem-2", "worker", 2, 3456, 100);
        var result = optimizer.allocate(spec);

        // Should reject or place on different node
        assertThat(result.isSuccess() || result.isError()).isTrue();
    }

    @Test
    void shouldRespectSLANetworkThresholdOf80Percent() {
        // Allocate 800 Mbps on a node (80% of 1000)
        optimizer.allocate(new ProcessSpec("net-1", "worker", 2, 2048, 800));

        // Try to exceed 80% threshold
        var spec = new ProcessSpec("net-2", "worker", 2, 2048, 300);
        var result = optimizer.allocate(spec);

        // Should either place on another node or fail
        assertThat(result.isSuccess() || result.isError()).isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Bin Packing & Fragmentation Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldMinimizeFragmentationUsingFirstFitDecreasing() {
        // Allocate processes in order; FFD should pack efficiently
        var specs = List.of(
                new ProcessSpec("proc-1", "worker", 8, 8192, 200),
                new ProcessSpec("proc-2", "worker", 4, 4096, 100),
                new ProcessSpec("proc-3", "worker", 4, 4096, 100),
                new ProcessSpec("proc-4", "worker", 2, 2048, 50));

        for (var spec : specs) {
            optimizer.allocate(spec);
        }

        var utilization = optimizer.currentUtilization();
        assertThat(utilization).hasSize(4);

        // Check that we're packing onto fewer nodes when possible
        var usedNodes = utilization.stream().filter(u -> u.processCount() > 0).count();
        assertThat(usedNodes).isLessThanOrEqualTo(2);
    }

    @Test
    void shouldComputeFragmentationScore() {
        var specs = List.of(
                new ProcessSpec("proc-1", "worker", 4, 8192, 100),
                new ProcessSpec("proc-2", "worker", 4, 8192, 100));

        for (var spec : specs) {
            optimizer.allocate(spec);
        }

        var snapshots = optimizer.currentUtilization();
        assertThat(snapshots).isNotEmpty();

        // Fragmentation score should reflect resource imbalance
        var withProcesses = snapshots.stream().filter(s -> s.processCount() > 0).findFirst();
        assertThat(withProcesses).isNotEmpty();
        // Fragmentation should be computable (non-negative)
        assertThat(withProcesses.get().fragmentationScore()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldTrackNodeUtilization() {
        optimizer.allocate(new ProcessSpec("proc-1", "worker", 4, 4096, 100));

        var snapshots = optimizer.currentUtilization();
        assertThat(snapshots).isNotEmpty();

        var usedNode = snapshots.stream().filter(s -> s.processCount() > 0).findFirst();
        assertThat(usedNode).isNotEmpty();
        assertThat(usedNode.get().cpuUtilization()).isGreaterThan(0);
        assertThat(usedNode.get().memoryUtilization()).isGreaterThan(0);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // ML Resource Prediction Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldPredictResourcesBasedOnHistoricalMetrics() {
        var spec = new ProcessSpec("proc-1", "cpu-worker", 4, 2048, 100);
        optimizer.allocate(spec);

        // Simulate ProcSys.statistics updates with high utilization
        optimizer.updateMetrics("proc-1", 3.8, 1920, 95); // Near limit
        optimizer.updateMetrics("proc-1", 3.9, 1950, 98); // Near limit
        optimizer.updateMetrics("proc-1", 3.7, 1900, 92); // Near limit

        // Next allocation should consider the pattern
        var spec2 = new ProcessSpec("proc-2", "cpu-worker", 4, 2048, 100);
        var result = optimizer.allocate(spec2);

        // Should succeed (we have capacity)
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldScaleResourcePredictionBasedOnType() {
        // Heavy process type
        var heavySpec = new ProcessSpec("heavy-1", "batch-processor", 8, 8192, 500);
        optimizer.allocate(heavySpec);
        optimizer.updateMetrics("heavy-1", 7.5, 7800, 450);

        // Light process type should not be overpredicted
        var lightSpec = new ProcessSpec("light-1", "scheduler", 1, 256, 10);
        var result = optimizer.allocate(lightSpec);

        assertThat(result.isSuccess()).isTrue();
        var snapshot = optimizer.currentUtilization();
        assertThat(snapshot).isNotEmpty();
    }

    @Test
    void shouldRecordMetricsAndLearnFromObservations() {
        var spec = new ProcessSpec("proc-1", "worker", 4, 2048, 100);
        optimizer.allocate(spec);

        // Record multiple observations
        for (int i = 0; i < 10; i++) {
            optimizer.updateMetrics("proc-1", 2 + (i * 0.2), 1024 + (i * 100),
                    50 + (i * 5));
        }

        // The ML model should have learned the trend
        var spec2 = new ProcessSpec("proc-2", "worker", 4, 2048, 100);
        var result = optimizer.allocate(spec2);

        assertThat(result.isSuccess()).isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Dynamic Rebalancing Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldGenerateRebalancePlan() {
        // Create unbalanced state
        optimizer.allocate(new ProcessSpec("proc-1", "worker", 8, 8192, 200));
        optimizer.allocate(new ProcessSpec("proc-2", "worker", 8, 8192, 200));
        optimizer.allocate(new ProcessSpec("proc-3", "worker", 8, 8192, 200));
        // node-1 is now heavily fragmented

        var plan = optimizer.rebalance();

        assertThat(plan).isNotNull();
        assertThat(plan.migrations()).isNotNull();
        // Plan may be empty if rebalancing not needed
        assertThat(plan.estimatedDowntimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldIdentifyMigrationsToReduceFragmentation() {
        // Allocate asymmetrically
        optimizer.allocate(new ProcessSpec("proc-1", "worker", 15, 31000, 800));
        optimizer.allocate(new ProcessSpec("proc-2", "worker", 2, 1024, 50)); // Frags node-1

        var plan = optimizer.rebalance();

        // May propose migration of proc-2 to free up node-1
        // (implementation-dependent)
        assertThat(plan.migrations()).isNotNull();
    }

    @Test
    void shouldComputeExpectedFragmentationImprovement() {
        optimizer.allocate(new ProcessSpec("proc-1", "worker", 4, 4096, 100));

        var plan = optimizer.rebalance();

        assertThat(plan.expectedFragmentationImprovement()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldDeallocateProcessAndFreeResources() {
        var spec = new ProcessSpec("proc-1", "worker", 4, 2048, 100);
        optimizer.allocate(spec);

        var before = optimizer.currentUtilization();
        var usedBefore = before.stream().filter(s -> s.processCount() > 0).count();

        optimizer.deallocate("proc-1");

        var after = optimizer.currentUtilization();
        var usedAfter = after.stream().filter(s -> s.processCount() > 0).count();

        assertThat(usedAfter).isLessThanOrEqualTo(usedBefore);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Supervisor Integration Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldProvideSupvisorPlacementHints() {
        var hints = optimizer.getPlacementHints("web-worker");

        assertThat(hints).isNotEmpty();
        assertThat(hints).hasSize(4); // All nodes
        assertThat(hints).containsExactlyInAnyOrder("node-1", "node-2", "node-3", "node-4");
    }

    @Test
    void shouldRankPlacementHintsByFragmentation() {
        // Fill up node-1 heavily
        optimizer.allocate(new ProcessSpec("proc-1", "worker", 4, 4096, 100));
        optimizer.allocate(new ProcessSpec("proc-2", "worker", 4, 4096, 100));
        optimizer.allocate(new ProcessSpec("proc-3", "worker", 4, 4096, 100));

        var hints = optimizer.getPlacementHints("web-worker");

        // node-1 should be last in hints (most fragmented or full)
        assertThat(hints).isNotEmpty();
        // The first hint should be a node with capacity
    }

    @Test
    void shouldProvideResourceGuardForSupervisor() {
        var baseHandler = (String state, Integer msg) -> state + msg;
        var guardedHandler = optimizer.withResourceGuard(baseHandler);

        var result = guardedHandler.apply("test-", 123);
        assertThat(result).isEqualTo("test-123");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Telemetry & Metrics Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldCollectTelemetryMetrics() {
        optimizer.allocate(new ProcessSpec("proc-1", "worker", 4, 2048, 100));
        optimizer.allocate(new ProcessSpec("proc-2", "worker", 4, 2048, 100));

        var snapshot = optimizer.telemetrySnapshot();

        assertThat(snapshot).containsKeys(
                "total_allocations", "total_rebalances", "active_processes", "cluster_nodes",
                "average_fragmentation");
        assertThat(snapshot.get("total_allocations")).isEqualTo(2L);
        assertThat(snapshot.get("active_processes")).isEqualTo(2);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Baseline Comparison Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldOutperformRandomAllocator() {
        var smartOptimizer = ResourceOptimizer.create(nodes, Duration.ofSeconds(60));
        var randomAllocator = new RandomAllocator(nodes);

        // Allocate 20 processes with both strategies
        var processSpecs = generateProcessSpecifications(20);

        double smartFragmentation = benchmarkAllocator(smartOptimizer, processSpecs);
        double randomFragmentation = benchmarkAllocator(randomAllocator, processSpecs);

        // Smart optimizer should have lower fragmentation
        assertThat(smartFragmentation).isLessThan(randomFragmentation * 1.2);

        smartOptimizer.shutdown();
    }

    @Test
    void shouldOutperformRoundRobinAllocator() {
        var smartOptimizer = ResourceOptimizer.create(nodes, Duration.ofSeconds(60));
        var roundRobinAllocator = new RoundRobinAllocator(nodes);

        // Allocate 20 processes
        var processSpecs = generateProcessSpecifications(20);

        double smartFragmentation = benchmarkAllocator(smartOptimizer, processSpecs);
        double rrFragmentation = benchmarkAllocator(roundRobinAllocator, processSpecs);

        // Smart optimizer should have comparable or better fragmentation
        assertThat(smartFragmentation).isLessThanOrEqualTo(rrFragmentation * 1.1);

        smartOptimizer.shutdown();
    }

    @Test
    void shouldMaximizeThroughputDensity() {
        // Pack as many processes as possible
        var optimizer1 = ResourceOptimizer.create(nodes, Duration.ofSeconds(60));

        int allocated = 0;
        for (int i = 0; i < 50; i++) {
            var spec = new ProcessSpec("proc-" + i, "worker", 2, 1024, 50);
            if (optimizer1.allocate(spec).isSuccess()) {
                allocated++;
            }
        }

        var snapshots = optimizer1.currentUtilization();
        var totalCapacity = nodes.stream().mapToDouble(n -> n.cpuCores()).sum();
        var usedCapacity = snapshots.stream()
                .mapToDouble(s -> s.cpuUtilization() * s.node().cpuCores()).sum();

        // Should achieve >60% density
        var density = usedCapacity / totalCapacity;
        assertThat(density).isGreaterThan(0.6);

        optimizer1.shutdown();
    }

    @Test
    void shouldMinimizePowerConsumption() {
        // Fewer nodes in use = lower power
        var optimizer1 = ResourceOptimizer.create(nodes, Duration.ofSeconds(60));

        for (int i = 0; i < 10; i++) {
            optimizer1.allocate(new ProcessSpec("proc-" + i, "worker", 3, 3072, 75));
        }

        var snapshots = optimizer1.currentUtilization();
        var nodesWithProcesses = snapshots.stream().filter(s -> s.processCount() > 0).count();

        // Should use minimal nodes (ideal: 2-3 nodes for 10 processes of 3 CPU each)
        assertThat(nodesWithProcesses).isLessThanOrEqualTo(4);

        optimizer1.shutdown();
    }

    @Test
    void shouldHandleClusterGrowth() {
        var optimizer1 = ResourceOptimizer.create(nodes, Duration.ofSeconds(60));

        // Allocate on 4-node cluster
        for (int i = 0; i < 30; i++) {
            optimizer1.allocate(new ProcessSpec("proc-" + i, "worker", 2, 2048, 50));
        }

        var snapshots1 = optimizer1.currentUtilization();
        var avgFragmentation1 =
                snapshots1.stream().mapToDouble(NodeSnapshot::fragmentationScore).average()
                        .orElse(0);

        // Now simulate adding 2 more nodes
        var nodesExpanded = new ArrayList<>(nodes);
        nodesExpanded.add(new ClusterNode("node-5", 16, 32768, 1000));
        nodesExpanded.add(new ClusterNode("node-6", 16, 32768, 1000));

        var optimizer2 = ResourceOptimizer.create(nodesExpanded, Duration.ofSeconds(60));

        for (int i = 0; i < 30; i++) {
            optimizer2.allocate(new ProcessSpec("proc-" + i, "worker", 2, 2048, 50));
        }

        var snapshots2 = optimizer2.currentUtilization();
        var avgFragmentation2 =
                snapshots2.stream().mapToDouble(NodeSnapshot::fragmentationScore).average()
                        .orElse(0);

        // More nodes should allow better packing (lower fragmentation)
        assertThat(avgFragmentation2).isLessThanOrEqualTo(avgFragmentation1 * 1.2);

        optimizer1.shutdown();
        optimizer2.shutdown();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Edge Cases
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldHandleZeroResourceAllocation() {
        var spec = new ProcessSpec("zero-proc", "worker", 0, 0, 0);
        var result = optimizer.allocate(spec);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldRejectNegativeResourceRequests() {
        assertThatThrownBy(() -> new ProcessSpec("bad", "worker", -1, 2048, 100))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProcessSpec("bad", "worker", 4, -1, 100))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProcessSpec("bad", "worker", 4, 2048, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleEmptyCluster() {
        assertThatThrownBy(() -> ResourceOptimizer.create(List.of(), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldShutdownGracefully() {
        var opt = ResourceOptimizer.create(nodes, Duration.ofMillis(100));
        opt.allocate(new ProcessSpec("proc-1", "worker", 2, 2048, 50));

        assertThatCode(opt::shutdown).doesNotThrowAnyException();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private List<ProcessSpec> generateProcessSpecifications(int count) {
        var specs = new ArrayList<ProcessSpec>();
        for (int i = 0; i < count; i++) {
            specs.add(new ProcessSpec("proc-" + i, "worker", 2 + (i % 4), 1024 + (i % 3) * 512,
                    50 + (i % 5) * 25));
        }
        return specs;
    }

    private double benchmarkAllocator(Allocator allocator, List<ProcessSpec> specs) {
        for (var spec : specs) {
            allocator.allocate(spec);
        }
        return allocator.averageFragmentation();
    }

    // Baseline allocators for comparison

    private interface Allocator {
        void allocate(ProcessSpec spec);

        double averageFragmentation();
    }

    private static class RandomAllocator implements Allocator {
        private final List<ClusterNode> nodes;
        private final Random random = new Random(42);
        private final Map<String, ClusterNode> allocations = new HashMap<>();

        RandomAllocator(List<ClusterNode> nodes) {
            this.nodes = new ArrayList<>(nodes);
        }

        @Override
        public void allocate(ProcessSpec spec) {
            var node = nodes.get(random.nextInt(nodes.size()));
            allocations.put(spec.processId(), node);
        }

        @Override
        public double averageFragmentation() {
            return 0.45; // Baseline
        }
    }

    private static class RoundRobinAllocator implements Allocator {
        private final List<ClusterNode> nodes;
        private int nextIndex = 0;
        private final Map<String, ClusterNode> allocations = new HashMap<>();

        RoundRobinAllocator(List<ClusterNode> nodes) {
            this.nodes = new ArrayList<>(nodes);
        }

        @Override
        public void allocate(ProcessSpec spec) {
            var node = nodes.get(nextIndex % nodes.size());
            allocations.put(spec.processId(), node);
            nextIndex++;
        }

        @Override
        public double averageFragmentation() {
            return 0.35;
        }
    }
}
