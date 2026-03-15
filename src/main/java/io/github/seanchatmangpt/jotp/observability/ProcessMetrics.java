package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.Application;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process lifecycle metrics collector for JOTP applications.
 *
 * <p>This collector provides zero-overhead metrics collection for process lifecycle events by using
 * atomic operations and avoiding synchronization in critical paths.
 *
 * <p>Metrics collected:
 *
 * <ul>
 *   <li>Process creation/termination counts
 *   <li>Crash detection and classification
 *   <li>Queue depth monitoring
 *   <li>Message throughput rates
 *   <li>Process health distribution
 * </ul>
 *
 * <p>This component integrates with the Application lifecycle as an Infrastructure component and
 * automatically cleans up resources on shutdown.
 *
 * @see Application.Infrastructure
 */
public final class ProcessMetrics implements Application.Infrastructure {

    private final String name;

    // Process lifecycle counters - using LongAdder for lock-free operations
    private final LongAdder processesCreated = new LongAdder();
    private final LongAdder processesTerminated = new LongAdder();
    private final LongAdder processesCrashed = new LongAdder();
    private final LongAdder processesRestarted = new LongAdder();

    // Message throughput counters
    private final LongAdder messagesSent = new LongAdder();
    private final LongAdder messagesReceived = new LongAdder();
    private final LongAdder messagesProcessed = new LongAdder();

    // Queue depth tracking - aggregated snapshot
    private final AtomicLong maxQueueDepth = new AtomicLong(0);
    private final AtomicLong currentQueueDepth = new AtomicLong(0);

    // Timing metrics (nanoseconds)
    private final AtomicLong totalProcessLifetime = new AtomicLong(0);
    private final AtomicLong minProcessLifetime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessLifetime = new AtomicLong(0);

    // Per-process metrics for detailed analysis
    private final ConcurrentHashMap<String, ProcessMetricEntry> processMetrics =
            new ConcurrentHashMap<>();

    // Health status distribution
    private final AtomicLong healthyProcesses = new AtomicLong(0);
    private final AtomicLong degradedProcesses = new AtomicLong(0);
    private final AtomicLong unhealthyProcesses = new AtomicLong(0);

    private volatile boolean running = true;

    private ProcessMetrics(String name) {
        this.name = name;
    }

    /**
     * Creates a new ProcessMetrics instance with default configuration.
     *
     * @return a new ProcessMetrics instance
     */
    public static ProcessMetrics create() {
        return new ProcessMetrics("process-metrics");
    }

    /**
     * Creates a new ProcessMetrics instance with a custom name.
     *
     * @param name the name for this metrics collector
     * @return a new ProcessMetrics instance
     */
    public static ProcessMetrics create(String name) {
        return new ProcessMetrics(name);
    }

    /**
     * Creates a new builder for configuring ProcessMetrics.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onStop(Application<?> app) {
        // Stop collection and clear resources
        running = false;
        processMetrics.clear();
    }

    // Process lifecycle tracking

    /**
     * Records the creation of a new process.
     *
     * @param processId the unique identifier for the process
     * @param processType the type/category of the process
     */
    public void recordProcessCreated(String processId, String processType) {
        if (!running) return;

        processesCreated.increment();
        processMetrics.compute(
                processId,
                (k, v) -> {
                    if (v == null) {
                        var entry = new ProcessMetricEntry(processId, processType);
                        entry.created = Instant.now();
                        return entry;
                    }
                    return v;
                });
    }

    /**
     * Records the termination of a process.
     *
     * @param processId the unique identifier for the process
     * @param reason the reason for termination
     */
    public void recordProcessTerminated(String processId, String reason) {
        if (!running) return;

        processesTerminated.increment();
        processMetrics.computeIfPresent(
                processId,
                (k, entry) -> {
                    entry.terminated = Instant.now();
                    entry.terminationReason = reason;

                    // Update lifetime statistics
                    if (entry.created != null) {
                        long lifetime = Duration.between(entry.created, entry.terminated).toNanos();
                        totalProcessLifetime.addAndGet(lifetime);

                        // Update min/max (with potential race, acceptable for metrics)
                        long currentMin = minProcessLifetime.get();
                        while (lifetime < currentMin
                                && !minProcessLifetime.compareAndSet(currentMin, lifetime)) {
                            currentMin = minProcessLifetime.get();
                        }

                        long currentMax = maxProcessLifetime.get();
                        while (lifetime > currentMax
                                && !maxProcessLifetime.compareAndSet(currentMax, lifetime)) {
                            currentMax = maxProcessLifetime.get();
                        }
                    }

                    return entry;
                });
    }

    /**
     * Records a process crash.
     *
     * @param processId the unique identifier for the process
     * @param crashType the type/classification of the crash
     * @param throwable the exception that caused the crash
     */
    public void recordProcessCrashed(String processId, String crashType, Throwable throwable) {
        if (!running) return;

        processesCrashed.increment();
        processMetrics.computeIfPresent(
                processId,
                (k, entry) -> {
                    entry.crashCount++;
                    entry.lastCrashType = crashType;
                    entry.lastCrashTime = Instant.now();
                    entry.lastCrashReason = throwable != null ? throwable.getMessage() : "unknown";
                    return entry;
                });
    }

    /**
     * Records a supervisor-initiated process restart.
     *
     * @param processId the unique identifier for the process
     * @param restartReason the reason for the restart
     */
    public void recordProcessRestarted(String processId, String restartReason) {
        if (!running) return;

        processesRestarted.increment();
        processMetrics.computeIfPresent(
                processId,
                (k, entry) -> {
                    entry.restartCount++;
                    entry.lastRestartReason = restartReason;
                    entry.lastRestartTime = Instant.now();
                    return entry;
                });
    }

    // Message tracking

    /**
     * Records a message being sent to a process.
     *
     * @param processId the target process identifier
     */
    public void recordMessageSent(String processId) {
        if (!running) return;
        messagesSent.increment();
    }

    /**
     * Records a message being received by a process.
     *
     * @param processId the receiving process identifier
     */
    public void recordMessageReceived(String processId) {
        if (!running) return;
        messagesReceived.increment();
    }

    /**
     * Records a message being processed by a process.
     *
     * @param processId the processing process identifier
     */
    public void recordMessageProcessed(String processId) {
        if (!running) return;
        messagesProcessed.increment();
    }

    // Queue depth monitoring

    /**
     * Updates the current queue depth for a process.
     *
     * @param processId the process identifier
     * @param depth the current queue depth
     */
    public void recordQueueDepth(String processId, long depth) {
        if (!running) return;

        currentQueueDepth.set(depth);

        // Update max depth (with potential race, acceptable for metrics)
        long currentMax = maxQueueDepth.get();
        while (depth > currentMax && !maxQueueDepth.compareAndSet(currentMax, depth)) {
            currentMax = maxQueueDepth.get();
        }

        processMetrics.computeIfPresent(
                processId,
                (k, entry) -> {
                    entry.currentQueueDepth = depth;
                    if (depth > entry.maxQueueDepth) {
                        entry.maxQueueDepth = depth;
                    }
                    return entry;
                });
    }

    // Health status tracking

    /**
     * Records the health status of a process.
     *
     * @param processId the process identifier
     * @param status the health status
     */
    public void recordHealthStatus(String processId, HealthStatus status) {
        if (!running) return;

        switch (status) {
            case HEALTHY -> healthyProcesses.incrementAndGet();
            case DEGRADED -> degradedProcesses.incrementAndGet();
            case UNHEALTHY -> unhealthyProcesses.incrementAndGet();
        }

        processMetrics.computeIfPresent(
                processId,
                (k, entry) -> {
                    entry.currentHealthStatus = status;
                    return entry;
                });
    }

    // Metrics accessors

    /**
     * Gets the total number of processes created.
     *
     * @return the total process creation count
     */
    public long getProcessesCreated() {
        return processesCreated.sum();
    }

    /**
     * Gets the total number of processes terminated.
     *
     * @return the total process termination count
     */
    public long getProcessesTerminated() {
        return processesTerminated.sum();
    }

    /**
     * Gets the total number of process crashes.
     *
     * @return the total crash count
     */
    public long getProcessesCrashed() {
        return processesCrashed.sum();
    }

    /**
     * Gets the total number of process restarts.
     *
     * @return the total restart count
     */
    public long getProcessesRestarted() {
        return processesRestarted.sum();
    }

    /**
     * Gets the total number of messages sent.
     *
     * @return the total message send count
     */
    public long getMessagesSent() {
        return messagesSent.sum();
    }

    /**
     * Gets the total number of messages received.
     *
     * @return the total message receive count
     */
    public long getMessagesReceived() {
        return messagesReceived.sum();
    }

    /**
     * Gets the total number of messages processed.
     *
     * @return the total message process count
     */
    public long getMessagesProcessed() {
        return messagesProcessed.sum();
    }

    /**
     * Gets the maximum observed queue depth across all processes.
     *
     * @return the maximum queue depth
     */
    public long getMaxQueueDepth() {
        return maxQueueDepth.get();
    }

    /**
     * Gets the current aggregate queue depth.
     *
     * @return the current queue depth
     */
    public long getCurrentQueueDepth() {
        return currentQueueDepth.get();
    }

    /**
     * Gets the average process lifetime in milliseconds.
     *
     * @return the average lifetime, or 0 if no processes have completed
     */
    public double getAverageProcessLifetimeMs() {
        long terminated = processesTerminated.sum();
        if (terminated == 0) return 0.0;
        return (totalProcessLifetime.get() / 1_000_000.0) / terminated;
    }

    /**
     * Gets the minimum process lifetime in milliseconds.
     *
     * @return the minimum lifetime, or 0 if no processes have completed
     */
    public long getMinProcessLifetimeMs() {
        long min = minProcessLifetime.get();
        return min == Long.MAX_VALUE ? 0 : min / 1_000_000;
    }

    /**
     * Gets the maximum process lifetime in milliseconds.
     *
     * @return the maximum lifetime, or 0 if no processes have completed
     */
    public long getMaxProcessLifetimeMs() {
        long max = maxProcessLifetime.get();
        return max == 0 ? 0 : max / 1_000_000;
    }

    /**
     * Gets the current number of healthy processes.
     *
     * @return the healthy process count
     */
    public long getHealthyProcesses() {
        return healthyProcesses.get();
    }

    /**
     * Gets the current number of degraded processes.
     *
     * @return the degraded process count
     */
    public long getDegradedProcesses() {
        return degradedProcesses.get();
    }

    /**
     * Gets the current number of unhealthy processes.
     *
     * @return the unhealthy process count
     */
    public long getUnhealthyProcesses() {
        return unhealthyProcesses.get();
    }

    /**
     * Gets metrics for a specific process.
     *
     * @param processId the process identifier
     * @return the process metrics entry, or null if not found
     */
    public ProcessMetricEntry getProcessMetrics(String processId) {
        return processMetrics.get(processId);
    }

    /**
     * Creates a snapshot of all current process metrics.
     *
     * @return a snapshot of current metrics
     */
    public ProcessMetricsSnapshot snapshot() {
        return new ProcessMetricsSnapshot(
                getProcessesCreated(),
                getProcessesTerminated(),
                getProcessesCrashed(),
                getProcessesRestarted(),
                getMessagesSent(),
                getMessagesReceived(),
                getMessagesProcessed(),
                getMaxQueueDepth(),
                getCurrentQueueDepth(),
                getAverageProcessLifetimeMs(),
                getMinProcessLifetimeMs(),
                getMaxProcessLifetimeMs(),
                getHealthyProcesses(),
                getDegradedProcesses(),
                getUnhealthyProcesses());
    }

    /**
     * Resets all metrics counters to zero.
     *
     * <p>This is primarily useful for testing and should not be called during normal operation.
     */
    public void reset() {
        processesCreated.reset();
        processesTerminated.reset();
        processesCrashed.reset();
        processesRestarted.reset();
        messagesSent.reset();
        messagesReceived.reset();
        messagesProcessed.reset();
        maxQueueDepth.set(0);
        currentQueueDepth.set(0);
        totalProcessLifetime.set(0);
        minProcessLifetime.set(Long.MAX_VALUE);
        maxProcessLifetime.set(0);
        healthyProcesses.set(0);
        degradedProcesses.set(0);
        unhealthyProcesses.set(0);
        processMetrics.clear();
    }

    /** Health status enumeration for process health monitoring. */
    public enum HealthStatus {
        /** Process is operating normally */
        HEALTHY,
        /** Process is experiencing degraded performance */
        DEGRADED,
        /** Process is failing or unresponsive */
        UNHEALTHY
    }

    /** Detailed metrics entry for a single process. */
    public static final class ProcessMetricEntry {
        private final String processId;
        private final String processType;
        private Instant created;
        private Instant terminated;
        private String terminationReason;
        private int crashCount;
        private String lastCrashType;
        private Instant lastCrashTime;
        private String lastCrashReason;
        private int restartCount;
        private String lastRestartReason;
        private Instant lastRestartTime;
        private long currentQueueDepth;
        private long maxQueueDepth;
        private HealthStatus currentHealthStatus;

        private ProcessMetricEntry(String processId, String processType) {
            this.processId = processId;
            this.processType = processType;
            this.currentHealthStatus = HealthStatus.HEALTHY;
        }

        public String getProcessId() {
            return processId;
        }

        public String getProcessType() {
            return processType;
        }

        public Instant getCreated() {
            return created;
        }

        public Instant getTerminated() {
            return terminated;
        }

        public String getTerminationReason() {
            return terminationReason;
        }

        public int getCrashCount() {
            return crashCount;
        }

        public String getLastCrashType() {
            return lastCrashType;
        }

        public Instant getLastCrashTime() {
            return lastCrashTime;
        }

        public String getLastCrashReason() {
            return lastCrashReason;
        }

        public int getRestartCount() {
            return restartCount;
        }

        public String getLastRestartReason() {
            return lastRestartReason;
        }

        public Instant getLastRestartTime() {
            return lastRestartTime;
        }

        public long getCurrentQueueDepth() {
            return currentQueueDepth;
        }

        public long getMaxQueueDepth() {
            return maxQueueDepth;
        }

        public HealthStatus getCurrentHealthStatus() {
            return currentHealthStatus;
        }

        /**
         * Calculates the uptime of this process in milliseconds.
         *
         * @return the uptime in milliseconds, or -1 if the process has not been created
         */
        public long getUptimeMs() {
            if (created == null) return -1;
            Instant end = terminated != null ? terminated : Instant.now();
            return Duration.between(created, end).toMillis();
        }

        /**
         * Checks if this process is currently running.
         *
         * @return true if the process is running, false otherwise
         */
        public boolean isRunning() {
            return created != null && terminated == null;
        }
    }

    /** Immutable snapshot of process metrics at a point in time. */
    public record ProcessMetricsSnapshot(
            long processesCreated,
            long processesTerminated,
            long processesCrashed,
            long processesRestarted,
            long messagesSent,
            long messagesReceived,
            long messagesProcessed,
            long maxQueueDepth,
            long currentQueueDepth,
            double averageProcessLifetimeMs,
            long minProcessLifetimeMs,
            long maxProcessLifetimeMs,
            long healthyProcesses,
            long degradedProcesses,
            long unhealthyProcesses) {
        /**
         * Calculates the crash rate as a percentage.
         *
         * @return the crash rate percentage, or 0 if no processes were created
         */
        public double crashRate() {
            if (processesCreated == 0) return 0.0;
            return (processesCrashed * 100.0) / processesCreated;
        }

        /**
         * Calculates the restart rate as a percentage.
         *
         * @return the restart rate percentage, or 0 if no processes were created
         */
        public double restartRate() {
            if (processesCreated == 0) return 0.0;
            return (processesRestarted * 100.0) / processesCreated;
        }

        /**
         * Calculates the message processing success rate.
         *
         * @return the success rate as a percentage, or 0 if no messages were received
         */
        public double messageSuccessRate() {
            if (messagesReceived == 0) return 0.0;
            return (messagesProcessed * 100.0) / messagesReceived;
        }
    }

    /** Builder for creating configured ProcessMetrics instances. */
    public static final class Builder {
        private String name = "process-metrics";

        private Builder() {}

        /**
         * Sets the name for this metrics collector.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Builds a new ProcessMetrics instance with the configured settings.
         *
         * @return a new ProcessMetrics instance
         */
        public ProcessMetrics build() {
            return new ProcessMetrics(name);
        }
    }
}
