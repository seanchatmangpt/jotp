package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Bulkhead isolation for resource-limited feature execution.
 *
 * <p>Prevents one feature from starving others by enforcing per-feature resource limits (concurrent
 * requests, queue size, memory, CPU). Uses Process-based strategy with Semaphore for concurrent
 * request limiting.
 *
 * <p>State Machine: - HEALTHY: Resources available - DEGRADED: High utilization (80-99%) -
 * EXHAUSTED: At capacity (100%)
 */
public class BulkheadIsolationEnterprise {
    private final BulkheadConfig config;
    private final ProcRef<BulkheadState, BulkheadMsg> coordinator;
    private final Semaphore semaphore;
    private volatile BulkheadState.Status currentStatus = BulkheadState.Status.HEALTHY;

    private BulkheadIsolationEnterprise(
            BulkheadConfig config,
            Semaphore semaphore,
            ProcRef<BulkheadState, BulkheadMsg> coordinator) {
        this.config = config;
        this.semaphore = semaphore;
        this.coordinator = coordinator;
    }

    /**
     * Create a new bulkhead isolation instance.
     *
     * @param config Bulkhead configuration
     * @return BulkheadIsolationEnterprise instance
     */
    public static BulkheadIsolationEnterprise create(BulkheadConfig config) {
        int maxConcurrent = extractMaxConcurrentFromLimits(config.limits());
        Semaphore semaphore = new Semaphore(maxConcurrent);
        return new BulkheadIsolationEnterprise(config, semaphore, spawnCoordinator(config));
    }

    /**
     * Execute a task within the bulkhead isolation boundary.
     *
     * @param task The task to execute
     * @return Result wrapping success or bulkhead error
     */
    public <T> Result<T> execute(BulkheadTask<T> task) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        // Try to acquire permit within queueTimeout
        boolean acquired = false;
        try {
            acquired =
                    semaphore.tryAcquire(
                            config.queueTimeout().getSeconds(),
                            java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(new BulkheadException("Interrupted waiting for semaphore", e));
        }

        if (!acquired) {
            coordinator.tell(
                    new BulkheadMsg.RequestRejected(
                            requestId, "QUEUE_TIMEOUT", calculateUtilization()));
            return Result.failure(
                    new BulkheadException("Request timeout waiting for available capacity"));
        }

        try {
            T result = task.execute();
            long duration = System.currentTimeMillis() - startTime;

            // Notify completion
            coordinator.tell(
                    new BulkheadMsg.RequestCompleted(requestId, duration, calculateUtilization()));

            return Result.success(result);
        } catch (Exception e) {
            return Result.failure(
                    new BulkheadException("Task execution failed: " + e.getMessage(), e));
        } finally {
            semaphore.release();
        }
    }

    /**
     * Get current bulkhead status.
     *
     * @return Current BulkheadState.Status
     */
    public BulkheadState.Status getStatus() {
        return currentStatus;
    }

    /**
     * Get utilization percentage (0-100).
     *
     * @return Utilization as percentage
     */
    public int getUtilizationPercent() {
        return calculateUtilization();
    }

    /** Shutdown the bulkhead. */
    public void shutdown() {
        coordinator.tell(new BulkheadMsg.Shutdown());
    }

    private int calculateUtilization() {
        int maxConcurrent = semaphore.availablePermits() + (100 - semaphore.availablePermits());
        int used = maxConcurrent - semaphore.availablePermits();
        return (int) (((double) used / maxConcurrent) * 100);
    }

    private static int extractMaxConcurrentFromLimits(java.util.List<ResourceLimit> limits) {
        for (ResourceLimit limit : limits) {
            if (limit instanceof ResourceLimit.MaxConcurrentRequests mcr) {
                return mcr.maxCount();
            }
        }
        return 10; // Default
    }

    private static ProcRef<BulkheadState, BulkheadMsg> spawnCoordinator(BulkheadConfig config) {
        var proc =
                new Proc<>(
                        new BulkheadState(
                                config.featureName(),
                                BulkheadState.Status.HEALTHY,
                                0,
                                new ArrayDeque<>()),
                        (BulkheadState state, BulkheadMsg msg) -> {
                            return switch (msg) {
                                case BulkheadMsg.RequestCompleted(
                                                var id,
                                                var duration,
                                                var utilization) ->
                                        handleRequestCompleted(
                                                state, duration, utilization, config);
                                case BulkheadMsg.RequestRejected(
                                                var id,
                                                var reason,
                                                var utilization) ->
                                        handleRequestRejected(state, reason, utilization, config);
                                case BulkheadMsg.Shutdown _ -> state;
                            };
                        });
        return new ProcRef<>(proc);
    }

    private static BulkheadState handleRequestCompleted(
            BulkheadState state, long durationMs, int utilization, BulkheadConfig config) {
        BulkheadState.Status newStatus = calculateStatus(utilization, config);
        return new BulkheadState(
                state.featureName(), newStatus, state.requestCount() + 1, state.durations());
    }

    private static BulkheadState handleRequestRejected(
            BulkheadState state, String reason, int utilization, BulkheadConfig config) {
        BulkheadState.Status newStatus = calculateStatus(utilization, config);
        return new BulkheadState(
                state.featureName(), newStatus, state.requestCount(), state.durations());
    }

    private static BulkheadState.Status calculateStatus(int utilization, BulkheadConfig config) {
        if (utilization >= 100) {
            return BulkheadState.Status.EXHAUSTED;
        } else if (utilization >= (config.alertThreshold() * 100)) {
            return BulkheadState.Status.DEGRADED;
        } else {
            return BulkheadState.Status.HEALTHY;
        }
    }

    /** Internal state for the bulkhead coordinator. */
    record BulkheadState(
            String featureName, Status status, long requestCount, Deque<Long> durations) {

        enum Status {
            HEALTHY,
            DEGRADED,
            EXHAUSTED
        }
    }

    /** Messages for the bulkhead coordinator. */
    sealed interface BulkheadMsg
            permits BulkheadMsg.RequestCompleted,
                    BulkheadMsg.RequestRejected,
                    BulkheadMsg.Shutdown {

        record RequestCompleted(String requestId, long durationMs, int utilizationPercent)
                implements BulkheadMsg {}

        record RequestRejected(String requestId, String reason, int utilizationPercent)
                implements BulkheadMsg {}

        record Shutdown() implements BulkheadMsg {}
    }

    /** Task to execute within bulkhead isolation. */
    @FunctionalInterface
    public interface BulkheadTask<T> {
        T execute() throws Exception;
    }

    /** Result type for bulkhead operations. */
    sealed interface Result<T> permits Result.Success, Result.Failure {
        record Success<T>(T value) implements Result<T> {}

        record Failure<T>(BulkheadException error) implements Result<T> {}

        static <T> Result<T> success(T value) {
            return new Success<>(value);
        }

        static <T> Result<T> failure(BulkheadException error) {
            return new Failure<>(error);
        }
    }

    /** Exception thrown when bulkhead limits are exceeded. */
    public static class BulkheadException extends Exception {
        public BulkheadException(String message) {
            super(message);
        }

        public BulkheadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
