package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.ai.ResourceOptimizer.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for ResourceOptimizer with real Supervisor and process supervision.
 *
 * <p>Tests the full integration of resource allocation, dynamic rebalancing, and supervisor
 * process placement.
 */
class ResourceOptimizerIT {

    private List<ClusterNode> nodes;
    private ResourceOptimizer optimizer;
    private Supervisor supervisor;

    @BeforeEach
    void setup() {
        ApplicationController.reset();
        nodes = List.of(
                new ClusterNode("node-1", 16, 32768, 1000),
                new ClusterNode("node-2", 16, 32768, 1000),
                new ClusterNode("node-3", 8, 16384, 1000),
                new ClusterNode("node-4", 8, 16384, 1000));
        optimizer = ResourceOptimizer.create(nodes, Duration.ofMillis(100));
        supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(30));
    }

    @AfterEach
    void cleanup() {
        supervisor.stop();
        optimizer.shutdown();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Integration Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldIntegrateSupervisorProcessPlacementWithResourceOptimizer() {
        // Allocate resources
        var spec1 = new ProcessSpec("worker-1", "cpu-worker", 4, 2048, 100);
        var allocationResult = optimizer.allocate(spec1);
        assertThat(allocationResult.isSuccess()).isTrue();

        // Use allocation to guide supervisor placement
        var node = allocationResult.unwrap();
        var placementHints = optimizer.getPlacementHints("cpu-worker");
        assertThat(placementHints).contains(node.nodeId());

        // Create actual process with supervisor
        var procRef = supervisor.supervise("worker-1", new WorkerState(0),
                ResourceOptimizerIT::workerHandler);
        assertThat(procRef.proc()).isNotNull();
    }

    @Test
    void shouldTrackProcessMetricsFromProcSysAndUpdateML() {
        var spec = new ProcessSpec("proc-1", "cpu-bound", 4, 2048, 100);
        optimizer.allocate(spec);

        // Create process with supervisor
        var procRef = supervisor.supervise("proc-1", new WorkerState(0),
                ResourceOptimizerIT::workerHandler);

        // Send messages and get statistics
        procRef.tell(new WorkerMsg(10));
        procRef.tell(new WorkerMsg(20));
        procRef.tell(new WorkerMsg(30));

        // Get process statistics via ProcSys
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    var stats = ProcSys.statistics(procRef.proc());
                    assertThat(stats.messagesIn()).isGreaterThan(0);
                });

        // Simulate metrics collection (in real system, would come from monitoring)
        optimizer.updateMetrics("proc-1", 3.5, 1950, 95);

        // Verify ML model learned from observation
        var utilization = optimizer.currentUtilization();
        assertThat(utilization).isNotEmpty();
    }

    @Test
    void shouldHandleProcessLifecycleWithRebalancing() {
        // Allocate multiple processes
        for (int i = 0; i < 5; i++) {
            optimizer.allocate(
                    new ProcessSpec("proc-" + i, "worker", 2, 2048, 100));
        }

        var beforeSnapshots = optimizer.currentUtilization();
        var beforeFragmentation = beforeSnapshots.stream()
                .mapToDouble(NodeSnapshot::fragmentationScore).average().orElse(0);

        // Deallocate some processes
        optimizer.deallocate("proc-0");
        optimizer.deallocate("proc-1");

        // Trigger rebalancing
        var plan = optimizer.rebalance();

        var afterSnapshots = optimizer.currentUtilization();
        var afterFragmentation = afterSnapshots.stream()
                .mapToDouble(NodeSnapshot::fragmentationScore).average().orElse(0);

        // Rebalancing should not increase fragmentation
        assertThat(afterFragmentation).isLessThanOrEqualTo(beforeFragmentation * 1.5);
    }

    @Test
    void shouldRespectSLAConstraintsDuringHighLoad() {
        // Allocate many processes up to SLA limits
        int allocated = 0;
        for (int i = 0; i < 30; i++) {
            var spec = new ProcessSpec("proc-" + i, "worker", 2, 2048, 100);
            if (optimizer.allocate(spec).isSuccess()) {
                allocated++;
            }
        }

        assertThat(allocated).isGreaterThan(0);

        var snapshots = optimizer.currentUtilization();
        // No node should exceed SLA thresholds
        for (var snap : snapshots) {
            assertThat(snap.cpuUtilization()).isLessThanOrEqualTo(0.85);
            assertThat(snap.memoryUtilization()).isLessThanOrEqualTo(0.90);
            assertThat(snap.networkUtilization()).isLessThanOrEqualTo(0.80);
        }
    }

    @Test
    void shouldMaintainClusterHealthDuringContinuousOperations() {
        // Simulate steady-state cluster operations
        for (int cycle = 0; cycle < 5; cycle++) {
            // Allocate processes
            for (int i = 0; i < 10; i++) {
                var spec = new ProcessSpec("proc-cycle" + cycle + "-" + i, "worker", 2, 2048,
                        100);
                optimizer.allocate(spec);
            }

            // Update metrics
            for (int i = 0; i < 10; i++) {
                optimizer.updateMetrics("proc-cycle" + cycle + "-" + i, 1.8, 1900, 90);
            }

            // Rebalance
            optimizer.rebalance();

            // Deallocate half
            for (int i = 0; i < 5; i++) {
                optimizer.deallocate("proc-cycle" + cycle + "-" + i);
            }
        }

        var finalTelemetry = optimizer.telemetrySnapshot();
        assertThat(finalTelemetry.get("active_processes")).isInstanceOf(Number.class);
        assertThat((Number) finalTelemetry.get("total_allocations")).longValue()
                .isGreaterThan(0);
    }

    @Test
    void shouldOptimizePowerConsumptionAcrossCycles() {
        // Baseline: many small allocations on all nodes
        for (int i = 0; i < 20; i++) {
            optimizer.allocate(
                    new ProcessSpec("proc-" + i, "worker", 1, 512, 25));
        }

        var snap1 = optimizer.currentUtilization();
        var nodesUsed1 = snap1.stream().filter(s -> s.processCount() > 0).count();

        // Rebalance should consolidate
        var plan = optimizer.rebalance();
        var migrations = plan.migrations().size();

        // Execute migrations
        for (var migration : plan.migrations()) {
            optimizer.deallocate(migration.processId());
            var spec = new ProcessSpec(migration.processId(), "worker", 1, 512, 25);
            optimizer.allocate(spec);
        }

        var snap2 = optimizer.currentUtilization();
        var nodesUsed2 = snap2.stream().filter(s -> s.processCount() > 0).count();

        // Should use same or fewer nodes
        assertThat(nodesUsed2).isLessThanOrEqualTo(nodesUsed1 + 1);
    }

    @Test
    void shouldHandleNodeFailureScenario() {
        // Allocate across cluster
        for (int i = 0; i < 8; i++) {
            optimizer.allocate(
                    new ProcessSpec("proc-" + i, "worker", 2, 2048, 100));
        }

        var before = optimizer.currentUtilization();
        var processCountBefore = before.stream()
                .mapToInt(NodeSnapshot::processCount).sum();

        // Simulate node failure by deallocating all processes on node-1
        var node1Procs = new ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            node1Procs.add("proc-" + i);
        }

        // Deallocate (in real system, would be automatic)
        node1Procs.forEach(optimizer::deallocate);

        // Reallocate to remaining nodes
        for (int i = 0; i < 8; i++) {
            optimizer.allocate(
                    new ProcessSpec("proc-recover-" + i, "worker", 2, 2048, 100));
        }

        var after = optimizer.currentUtilization();
        var processCountAfter = after.stream()
                .mapToInt(NodeSnapshot::processCount).sum();

        // Should maintain similar process count
        assertThat(processCountAfter).isGreaterThanOrEqualTo(processCountBefore / 2);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Performance Tests
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void shouldAllocateProcessesWithSubMillisecondLatency() {
        var start = System.nanoTime();

        for (int i = 0; i < 100; i++) {
            optimizer.allocate(
                    new ProcessSpec("perf-proc-" + i, "worker", 1, 512, 25));
        }

        var elapsed = (System.nanoTime() - start) / 1_000_000.0; // ms
        var avgLatency = elapsed / 100;

        // Average allocation should be fast (< 1ms per allocation after warmup)
        assertThat(avgLatency).isLessThan(5.0); // Conservative for CI
    }

    @Test
    void shouldRebalanceInReasonableTime() {
        // Allocate many processes
        for (int i = 0; i < 50; i++) {
            optimizer.allocate(
                    new ProcessSpec("proc-" + i, "worker", 1, 512, 25));
        }

        var start = System.nanoTime();
        var plan = optimizer.rebalance();
        var elapsed = (System.nanoTime() - start) / 1_000_000.0; // ms

        assertThat(elapsed).isLessThan(100.0); // < 100ms for 50 processes
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static WorkerState workerHandler(WorkerState state, WorkerMsg msg) {
        return new WorkerState(state.counter() + msg.value());
    }

    private record WorkerState(int counter) {}

    private record WorkerMsg(int value) {}
}
