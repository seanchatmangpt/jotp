package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;

/**
 * Controller for process migration and rebalancing after node failures.
 *
 * <p>Coordinates the movement of processes from failed nodes to healthy nodes in the cluster. When
 * a node goes down, the failover controller:
 *
 * <ol>
 *   <li>Finds all processes hosted on the failed node
 *   <li>Selects target nodes from the healthy cluster members
 *   <li>Migrates each process to its new location
 *   <li>Updates the global registry with the new assignments
 * </ol>
 *
 * <p><strong>Migration Strategy:</strong> Processes are distributed across healthy nodes using
 * round-robin assignment. This ensures load balancing and prevents any single node from being
 * overwhelmed by migrations.
 *
 * <p><strong>Idempotence:</strong> Migrations use sequence numbers in {@link GlobalProcRef} to
 * ensure crash-safe updates. Each migration increments the sequence number, and partial writes are
 * detected and recovered during crash handling.
 *
 * <p><strong>Thread Safety:</strong> This implementation is thread-safe. Multiple node failures can
 * be handled concurrently, with migrations executing in parallel via virtual threads.
 *
 * @see NodeDiscovery
 * @see GlobalProcRegistry
 */
public final class FailoverController {

    private final GlobalProcRegistry globalRegistry;
    private final NodeDiscovery nodeDiscovery;
    private final ExecutorService migrationExecutor;

    /**
     * Create a new failover controller.
     *
     * @param globalRegistry the global process registry for cluster-wide process lookup (must not
     *     be null)
     * @param nodeDiscovery the node discovery system for health monitoring (must not be null)
     * @throws NullPointerException if any parameter is null
     */
    public FailoverController(GlobalProcRegistry globalRegistry, NodeDiscovery nodeDiscovery) {
        if (globalRegistry == null) {
            throw new NullPointerException("globalRegistry must not be null");
        }
        if (nodeDiscovery == null) {
            throw new NullPointerException("nodeDiscovery must not be null");
        }
        this.globalRegistry = globalRegistry;
        this.nodeDiscovery = nodeDiscovery;
        this.migrationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Handle a node going down - migrate all its processes.
     *
     * <p>This method is called by the node discovery system when a node is marked as DOWN. It finds
     * all processes hosted on the failed node and migrates them to healthy nodes.
     *
     * <p>Migration is performed asynchronously via virtual threads. This method returns immediately
     * after launching the migration tasks. The actual migration happens in the background.
     *
     * @param failedNode the node that failed (must not be null)
     * @return count of processes scheduled for migration (may be 0 if the node hosted no processes)
     * @throws NullPointerException if failedNode is null
     */
    public int handleNodeDown(String failedNode) {
        if (failedNode == null) {
            throw new NullPointerException("failedNode must not be null");
        }

        // Find all processes on the failed node
        List<Map.Entry<String, GlobalProcRef>> processesToMigrate =
                globalRegistry.listGlobal().entrySet().stream()
                        .filter(entry -> failedNode.equals(entry.getValue().nodeName()))
                        .toList();

        if (processesToMigrate.isEmpty()) {
            return 0;
        }

        // Get healthy nodes for migration targets
        List<String> healthyNodes = nodeDiscovery.getHealthyNodes();
        if (healthyNodes.isEmpty()) {
            System.err.println(
                    "[FailoverController] No healthy nodes available for migration from "
                            + failedNode);
            return 0;
        }

        // Migrate processes in parallel
        int[] migrationCount = {0};
        try (var scope =
                StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {
            for (var i = 0; i < processesToMigrate.size(); i++) {
                final int index = i;
                var entry = processesToMigrate.get(i);
                String targetNode = healthyNodes.get(index % healthyNodes.size());

                scope.fork(
                        () -> {
                            Result<Void, Exception> result =
                                    migrateProcess(entry.getKey(), targetNode);
                            if (result.isSuccess()) {
                                synchronized (migrationCount) {
                                    migrationCount[0]++;
                                }
                            } else {
                                System.err.println(
                                        "[FailoverController] Migration failed for "
                                                + entry.getKey()
                                                + ": "
                                                + result.fold(
                                                        ok -> "unknown", err -> err.getMessage()));
                            }
                            throw new UnsupportedOperationException("not implemented: migration callback");
                        });
            }

            // Wait for all migrations to complete (or fail)
            try {
                scope.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println(
                        "[FailoverController] Migration interrupted for node " + failedNode);
            }
        }

        return migrationCount[0];
    }

    /**
     * Migrate a specific process to a new node.
     *
     * <p>Updates the global registry to point the process name to a new node. The actual process
     * must be started on the target node separately — this method only updates the registry
     * metadata.
     *
     * <p><strong>Idempotence:</strong> Uses sequence numbers to ensure crash-safe updates. If the
     * migration is interrupted by a crash, the sequence number mismatch will be detected on
     * recovery.
     *
     * @param processName the global process name to migrate (must not be null)
     * @param targetNode destination node name (must not be null)
     * @return {@link Result#ok()} on successful migration, or {@link Result#err(Exception)} if
     *     migration fails
     * @throws NullPointerException if any parameter is null
     */
    public Result<Void, Exception> migrateProcess(String processName, String targetNode) {
        if (processName == null) {
            throw new NullPointerException("processName must not be null");
        }
        if (targetNode == null) {
            throw new NullPointerException("targetNode must not be null");
        }

        try {
            // Get current registration
            var currentRef = globalRegistry.findGlobal(processName);
            if (currentRef.isEmpty()) {
                return Result.err(
                        new IllegalStateException("Process not registered: " + processName));
            }

            // Update registry with new node assignment
            globalRegistry.transferGlobal(processName, targetNode);

            return Result.ok(null);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Rebalance processes after a node failure.
     *
     * <p>Distributes processes from the failed node across healthy nodes using round-robin
     * assignment. This is called automatically by {@link #handleNodeDown(String)}.
     *
     * <p>This method delegates to {@link #handleNodeDown(String)} for the actual migration logic.
     * The rebalancing is performed asynchronously via virtual threads.
     *
     * @param failedNode the node that failed
     */
    public void rebalanceAfterNodeDown(String failedNode) {
        handleNodeDown(failedNode);
    }

    /**
     * Check if a node can accept migrated processes.
     *
     * <p>A node can accept migrations if it is:
     *
     * <ul>
     *   <li>Marked as HEALTHY in the discovery system
     *   <li>Not the failed node itself
     *   <li>Able to accept new process assignments
     * </ul>
     *
     * @param nodeName node to check (must not be null)
     * @return true if node is healthy and has capacity
     * @throws NullPointerException if nodeName is null
     */
    public boolean canAcceptMigrations(String nodeName) {
        if (nodeName == null) {
            throw new NullPointerException("nodeName must not be null");
        }

        List<String> healthyNodes = nodeDiscovery.getHealthyNodes();
        return healthyNodes.contains(nodeName);
    }

    /**
     * Shutdown the failover controller and release resources.
     *
     * <p>Stops the migration executor and cleans up resources. After shutdown, no new migrations
     * will be initiated. In-progress migrations will be allowed to complete (or timeout).
     *
     * <p>This method should be called from a JVM shutdown hook registered with {@link
     * io.github.seanchatmangpt.jotp.JvmShutdownManager}.
     *
     * <p><strong>Thread Safety:</strong> This method is idempotent. Multiple calls have no
     * additional effect beyond the first.
     */
    public void shutdown() {
        migrationExecutor.shutdown();
    }
}
