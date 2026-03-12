package io.github.seanchatmangpt.jotp.pool;

/**
 * Statistics snapshot for a {@link PoolSupervisor}.
 *
 * <p>Provides a read-only view of pool metrics at a point in time:
 *
 * <ul>
 *   <li><strong>Active Workers:</strong> Number of worker processes currently alive
 *   <li><strong>Completed Tasks:</strong> Total number of tasks successfully completed
 *   <li><strong>Average Response Time:</strong> Mean response time in milliseconds
 *   <li><strong>Total Workers:</strong> Configured pool size
 * </ul>
 *
 * @param activeWorkers number of active (alive) worker processes
 * @param completedTasks total number of tasks that have completed
 * @param avgResponseTimeMs average response time in milliseconds
 * @param totalWorkers total configured worker count
 */
public record PoolStats(
        int activeWorkers,
        long completedTasks,
        long avgResponseTimeMs,
        int totalWorkers
) {

    /**
     * Get the number of active worker processes.
     *
     * @return number of currently alive workers
     */
    public int activeWorkers() {
        return activeWorkers;
    }

    /**
     * Get the total number of completed tasks.
     *
     * @return number of tasks that have completed execution
     */
    public long completedTasks() {
        return completedTasks;
    }

    /**
     * Get the average response time.
     *
     * @return mean response time in milliseconds
     */
    public long avgResponseTimeMs() {
        return avgResponseTimeMs;
    }

    /**
     * Get the total worker count.
     *
     * @return configured pool size
     */
    public int totalWorkers() {
        return totalWorkers;
    }

    /**
     * Get the percentage of active workers.
     *
     * @return active workers as a percentage of total (0-100)
     */
    public double activePercentage() {
        return totalWorkers > 0 ? (100.0 * activeWorkers / totalWorkers) : 0;
    }

    /**
     * Returns a formatted string representation of the stats.
     *
     * @return human-readable stats string
     */
    @Override
    public String toString() {
        return String.format(
                "PoolStats{active=%d/%d (%.0f%%), completed=%d, avgResponseTime=%dms}",
                activeWorkers,
                totalWorkers,
                activePercentage(),
                completedTasks,
                avgResponseTimeMs
        );
    }
}
