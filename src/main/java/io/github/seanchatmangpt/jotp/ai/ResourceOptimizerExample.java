package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.ai.ResourceOptimizer.*;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;

/**
 * Example demonstrating ResourceOptimizer in action.
 *
 * <p>Shows:
 * <ul>
 *   <li>Cluster setup with heterogeneous nodes
 *   <li>Process allocation with resource constraints
 *   <li>ML-based resource prediction from historical metrics
 *   <li>Dynamic rebalancing to reduce fragmentation
 *   <li>SLA enforcement (CPU, memory, network thresholds)
 *   <li>Integration with Supervisor for process placement
 *   <li>Telemetry and observability
 * </ul>
 */
public final class ResourceOptimizerExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║         JOTP Resource Optimizer — Cluster Allocation          ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ──────────────────────────────────────────────────────────────────────
        // 1. SETUP: Define cluster topology
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► CLUSTER TOPOLOGY");
        System.out.println("  Setting up a 6-node heterogeneous cluster:");

        var nodes = List.of(
                new ClusterNode("us-west-2a", 32, 65536, 10000),  // Large node: 32 CPU, 64GB
                new ClusterNode("us-west-2b", 32, 65536, 10000),  // Large node
                new ClusterNode("us-west-2c", 16, 32768, 5000),   // Medium node
                new ClusterNode("eu-central-1a", 16, 32768, 5000), // Medium node
                new ClusterNode("eu-central-1b", 8, 16384, 2000),  // Small node
                new ClusterNode("ap-southeast-1a", 8, 16384, 2000) // Small node
        );

        for (var node : nodes) {
            System.out.printf(
                    "  • %-20s CPU: %2d cores | Memory: %6d MB | Network: %5d Mbps%n",
                    node.nodeId(), node.cpuCores(), node.memoryMB(), node.networkMbps());
        }
        System.out.println();

        var optimizer = ResourceOptimizer.create(nodes, Duration.ofSeconds(30));

        // ──────────────────────────────────────────────────────────────────────
        // 2. ALLOCATION: Place processes intelligently
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► ALLOCATING PROCESSES");
        System.out.println();

        // Workload 1: Web frontend tier (many light processes)
        System.out.println("  [Web Tier] 10 web-server instances:");
        for (int i = 1; i <= 10; i++) {
            var spec = new ProcessSpec("web-server-" + i, "web-worker", 2, 512, 100);
            var result = optimizer.allocate(spec);
            if (result.isSuccess()) {
                System.out.printf("    ✓ web-server-%2d → %s%n", i, result.unwrap().nodeId());
            } else {
                System.out.printf("    ✗ web-server-%2d FAILED: %s%n", i,
                        result.unwrapErr().reason());
            }
        }
        System.out.println();

        // Workload 2: Data processing tier (medium CPU/memory)
        System.out.println("  [Data Processing Tier] 6 analytics-engine instances:");
        for (int i = 1; i <= 6; i++) {
            var spec = new ProcessSpec("analytics-" + i, "analytics-engine", 8, 8192, 500);
            var result = optimizer.allocate(spec);
            if (result.isSuccess()) {
                System.out.printf("    ✓ analytics-%d → %s%n", i, result.unwrap().nodeId());
            } else {
                System.out.printf("    ✗ analytics-%d FAILED: %s%n", i,
                        result.unwrapErr().reason());
            }
        }
        System.out.println();

        // Workload 3: ML batch jobs (heavy compute)
        System.out.println("  [ML Batch Tier] 4 gpu-simulator instances:");
        for (int i = 1; i <= 4; i++) {
            var spec = new ProcessSpec("gpu-sim-" + i, "batch-processor", 16, 16384, 1000);
            var result = optimizer.allocate(spec);
            if (result.isSuccess()) {
                System.out.printf("    ✓ gpu-sim-%d → %s%n", i, result.unwrap().nodeId());
            } else {
                System.out.printf("    ✗ gpu-sim-%d FAILED: %s%n", i,
                        result.unwrapErr().reason());
            }
        }
        System.out.println();

        // ──────────────────────────────────────────────────────────────────────
        // 3. UTILIZATION SNAPSHOT
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► CLUSTER UTILIZATION SNAPSHOT");
        System.out.println();

        var snapshots = optimizer.currentUtilization();
        for (var snap : snapshots) {
            var cpuBar = buildBar(snap.cpuUtilization(), 20);
            var memBar = buildBar(snap.memoryUtilization(), 20);
            var netBar = buildBar(snap.networkUtilization(), 20);

            System.out.printf("  %s%n", snap.node().nodeId());
            System.out.printf("    CPU:     [%-20s] %5.1f%% | Processes: %2d%n", cpuBar,
                    snap.cpuUtilization() * 100, snap.processCount());
            System.out.printf("    Memory:  [%-20s] %5.1f%%%n", memBar,
                    snap.memoryUtilization() * 100);
            System.out.printf("    Network: [%-20s] %5.1f%%%n", netBar,
                    snap.networkUtilization() * 100);
            System.out.printf("    Fragmentation Score: %.4f%n", snap.fragmentationScore());
            System.out.println();
        }

        // ──────────────────────────────────────────────────────────────────────
        // 4. ML PREDICTION: Learn from metrics
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► MACHINE LEARNING: RESOURCE PREDICTION");
        System.out.println();
        System.out.println("  Recording observed metrics (simulated ProcSys.statistics):");
        System.out.println();

        // Simulate ProcSys metrics collection
        for (int cycle = 1; cycle <= 3; cycle++) {
            System.out.printf("  Observation Cycle %d:%n", cycle);
            for (int i = 1; i <= 5; i++) {
                var cpuUsage = 1.5 + (cycle * 0.3); // Growing trend
                var memUsage = 400 + (cycle * 100);
                var netUsage = 80 + (cycle * 20);
                optimizer.updateMetrics("web-server-" + i, cpuUsage, memUsage, netUsage);
                if (i == 1 || i == 5) {
                    System.out.printf("    web-server-%d: CPU=%.1f %%, Mem=%d MB, Net=%d Mbps%n",
                            i, cpuUsage, memUsage, netUsage);
                }
            }
            System.out.println("    ...");
            System.out.println();
        }

        // ──────────────────────────────────────────────────────────────────────
        // 5. DYNAMIC REBALANCING
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► DYNAMIC REBALANCING");
        System.out.println();

        var plan = optimizer.rebalance();
        System.out.printf(
                "  Rebalancing Plan: %d migrations, "
                        + "%.2f%% fragmentation improvement, "
                        + "%d ms downtime estimate%n",
                plan.migrations().size(), plan.expectedFragmentationImprovement() * 100,
                plan.estimatedDowntimeMs());

        if (!plan.migrations().isEmpty()) {
            System.out.println();
            System.out.println("  Proposed Migrations:");
            for (var migration : plan.migrations()) {
                System.out.printf("    • %s: %s → %s%n", migration.processId(),
                        migration.fromNode(), migration.toNode());
            }
        } else {
            System.out.println("  No rebalancing needed — cluster is well-packed.");
        }
        System.out.println();

        // ──────────────────────────────────────────────────────────────────────
        // 6. SLA CONSTRAINTS
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► SLA CONSTRAINT ENFORCEMENT");
        System.out.println();

        var finalSnapshots = optimizer.currentUtilization();
        boolean slaCompliant = true;

        for (var snap : finalSnapshots) {
            var cpuOk = snap.cpuUtilization() <= 0.85;
            var memOk = snap.memoryUtilization() <= 0.90;
            var netOk = snap.networkUtilization() <= 0.80;

            System.out.printf("  %s: %s CPU ✓ | %s Memory ✓ | %s Network ✓%n",
                    snap.node().nodeId(),
                    cpuOk ? "✓" : "✗", memOk ? "✓" : "✗", netOk ? "✓" : "✗");

            slaCompliant = slaCompliant && cpuOk && memOk && netOk;
        }
        System.out.println();
        System.out.printf("  Overall SLA Compliance: %s%n", slaCompliant ? "✓ PASS" : "✗ FAIL");
        System.out.println();

        // ──────────────────────────────────────────────────────────────────────
        // 7. SUPERVISOR INTEGRATION
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► SUPERVISOR INTEGRATION");
        System.out.println();

        var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5,
                Duration.ofSeconds(30));

        var hints = optimizer.getPlacementHints("web-worker");
        System.out.println("  Placement hints for web-worker processes (priority order):");
        for (int i = 0; i < Math.min(3, hints.size()); i++) {
            System.out.printf("    %d. %s%n", i + 1, hints.get(i));
        }
        System.out.println();

        // Create actual supervised process
        var procRef = supervisor.supervise("example-proc", new ExampleState(0),
                ResourceOptimizerExample::exampleHandler);
        System.out.printf("  Created supervised process: %s%n", procRef);
        System.out.println();

        // ──────────────────────────────────────────────────────────────────────
        // 8. TELEMETRY & METRICS
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► TELEMETRY & OBSERVABILITY");
        System.out.println();

        var telemetry = optimizer.telemetrySnapshot();
        System.out.printf("  Total Allocations:    %s%n", telemetry.get("total_allocations"));
        System.out.printf("  Total Rebalances:     %s%n", telemetry.get("total_rebalances"));
        System.out.printf("  Active Processes:     %s%n", telemetry.get("active_processes"));
        System.out.printf("  Cluster Nodes:        %s%n", telemetry.get("cluster_nodes"));
        System.out.printf("  Avg Fragmentation:    %.4f%n",
                telemetry.get("average_fragmentation"));
        System.out.println();

        // ──────────────────────────────────────────────────────────────────────
        // 9. INTERACTIVE MODE (Optional)
        // ──────────────────────────────────────────────────────────────────────

        System.out.println("► INTERACTIVE COMMANDS (Optional)");
        System.out.println("  Commands: status, deallocate <proc-id>, allocate <name> <type> "
                + "<cpu> <mem> <net>, rebalance, exit");
        System.out.println();

        var interactive = false;
        if (interactive) {
            runInteractiveMode(optimizer, supervisor);
        }

        // ──────────────────────────────────────────────────────────────────────
        // CLEANUP
        // ──────────────────────────────────────────────────────────────────────

        supervisor.stop();
        optimizer.shutdown();

        System.out.println();
        System.out.println("✓ Example completed successfully");
    }

    private static void runInteractiveMode(ResourceOptimizer optimizer, Supervisor supervisor) {
        var scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            var input = scanner.nextLine().trim();

            if (input.equals("exit")) {
                break;
            } else if (input.equals("status")) {
                var snaps = optimizer.currentUtilization();
                for (var snap : snaps) {
                    System.out.printf("  %s: %d processes%n", snap.node().nodeId(),
                            snap.processCount());
                }
            } else if (input.startsWith("deallocate ")) {
                var procId = input.substring(11);
                optimizer.deallocate(procId);
                System.out.println("Deallocated " + procId);
            } else if (input.equals("rebalance")) {
                var plan = optimizer.rebalance();
                System.out.printf("  %d migrations proposed%n", plan.migrations().size());
            }
        }
        scanner.close();
    }

    private static String buildBar(double utilization, int width) {
        var filled = (int) (utilization * width);
        var sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '█' : '░');
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Example Proc Handler
    // ──────────────────────────────────────────────────────────────────────────

    private record ExampleState(long counter) {}

    private record ExampleMsg(long value) {}

    private static ExampleState exampleHandler(ExampleState state, ExampleMsg msg) {
        return new ExampleState(state.counter() + msg.value());
    }
}
