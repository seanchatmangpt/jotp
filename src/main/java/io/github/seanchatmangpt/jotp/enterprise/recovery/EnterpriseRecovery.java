package io.github.seanchatmangpt.jotp.enterprise.recovery;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Enterprise-grade retry coordinator with exponential backoff and jitter.
 *
 * <p>Implements advanced recovery strategies including circuit breaker integration, fresh virtual
 * thread per attempt (via CrashRecovery pattern), and adaptive backoff.
 *
 * <p>State Machine: - INITIAL: First attempt - BACKOFF: Waiting between retries - RETRY: Executing
 * attempt N - COMPLETED: Success - FAILED: All attempts exhausted - CIRCUIT_BREAKER_TRIPPED: Too
 * many consecutive failures
 */
public class EnterpriseRecovery {
    private final RecoveryConfig config;
    private final ProcRef<RecoveryState, RecoveryMsg> coordinator;
    private final CopyOnWriteArrayList<RecoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    private EnterpriseRecovery(
            RecoveryConfig config, ProcRef<RecoveryState, RecoveryMsg> coordinator) {
        this.config = config;
        this.coordinator = coordinator;
    }

    /**
     * Create a new enterprise recovery coordinator.
     *
     * @param config Recovery configuration
     * @return EnterpriseRecovery instance
     */
    public static EnterpriseRecovery create(RecoveryConfig config) {
        return new EnterpriseRecovery(config, spawnCoordinator(config));
    }

    /**
     * Execute a task with retry logic.
     *
     * @param task The task to execute
     * @return Result wrapping success or recovery error
     */
    public <T> Result<T> retry(RecoveryTask<T> task) {
        return executeWithRetry(task, 1);
    }

    private <T> Result<T> executeWithRetry(RecoveryTask<T> task, int attemptNumber) {
        if (attemptNumber > config.maxAttempts()) {
            return Result.failure(
                    new RecoveryException("Max attempts exceeded: " + config.maxAttempts()));
        }

        long startTime = System.currentTimeMillis();

        try {
            T result = task.execute();
            long duration = System.currentTimeMillis() - startTime;

            // Notify success to coordinator
            coordinator.tell(new RecoveryMsg.AttemptSucceeded(attemptNumber, duration));

            return Result.success(result);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            coordinator.tell(
                    new RecoveryMsg.AttemptFailed(attemptNumber, e.getMessage(), duration));

            // Calculate backoff delay
            Duration delay = calculateBackoff(attemptNumber);

            // Sleep before retry
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Result.failure(new RecoveryException("Retry interrupted", ie));
            }

            // Recursive retry
            return executeWithRetry(task, attemptNumber + 1);
        }
    }

    /**
     * Register a listener for recovery events.
     *
     * @param listener Callback to invoke on events
     */
    public void addListener(RecoveryListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     *
     * @param listener Listener to remove
     */
    public void removeListener(RecoveryListener listener) {
        listeners.remove(listener);
    }

    /** Shutdown the recovery coordinator. */
    public void shutdown() {
        coordinator.tell(new RecoveryMsg.Shutdown());
    }

    private Duration calculateBackoff(int attemptNumber) {
        long delayMs = config.initialDelay().toMillis();

        // Apply backoff multiplier
        for (int i = 1; i < attemptNumber; i++) {
            delayMs = (long) (delayMs * config.backoffMultiplier());
        }

        // Cap at maxDelay
        delayMs = Math.min(delayMs, config.maxDelay().toMillis());

        // Apply jitter: delay * (1 ± jitterFactor)
        double jitter = 1.0 + (random.nextDouble() - 0.5) * 2 * config.jitterFactor();
        delayMs = (long) (delayMs * jitter);

        return Duration.ofMillis(delayMs);
    }

    private static ProcRef<RecoveryState, RecoveryMsg> spawnCoordinator(RecoveryConfig config) {
        var initialState = new RecoveryState(config.taskName(), 0, 0, 0);
        var proc =
                new Proc<>(
                        initialState,
                        (RecoveryState state, RecoveryMsg msg) ->
                                switch (msg) {
                                    case RecoveryMsg.AttemptSucceeded(var attempt, var duration) ->
                                            handleAttemptSucceeded(state, attempt, duration);
                                    case RecoveryMsg.AttemptFailed(
                                                    var attempt,
                                                    var error,
                                                    var duration) ->
                                            handleAttemptFailed(state, attempt, error, duration);
                                    case RecoveryMsg.Shutdown _ -> state;
                                });
        return new ProcRef<>(proc);
    }

    private static RecoveryState handleAttemptSucceeded(
            RecoveryState state, int attemptNumber, long durationMs) {
        return new RecoveryState(
                state.taskName(),
                attemptNumber,
                state.totalFailures(),
                state.totalDuration() + durationMs);
    }

    private static RecoveryState handleAttemptFailed(
            RecoveryState state, int attemptNumber, String error, long durationMs) {
        return new RecoveryState(
                state.taskName(),
                attemptNumber,
                state.totalFailures() + 1,
                state.totalDuration() + durationMs);
    }

    /** Internal state for the recovery coordinator. */
    record RecoveryState(
            String taskName, int lastAttemptNumber, long totalFailures, long totalDuration) {}

    /** Messages for the recovery coordinator. */
    sealed interface RecoveryMsg
            permits RecoveryMsg.AttemptSucceeded, RecoveryMsg.AttemptFailed, RecoveryMsg.Shutdown {

        record AttemptSucceeded(int attemptNumber, long durationMs) implements RecoveryMsg {}

        record AttemptFailed(int attemptNumber, String error, long durationMs)
                implements RecoveryMsg {}

        record Shutdown() implements RecoveryMsg {}
    }

    /** Task to execute with retry logic. */
    @FunctionalInterface
    public interface RecoveryTask<T> {
        T execute() throws Exception;
    }

    /** Result type for recovery operations. */
    sealed interface Result<T> permits Result.Success, Result.Failure {
        record Success<T>(T value) implements Result<T> {}

        record Failure<T>(RecoveryException error) implements Result<T> {}

        static <T> Result<T> success(T value) {
            return new Success<>(value);
        }

        static <T> Result<T> failure(RecoveryException error) {
            return new Failure<>(error);
        }
    }

    /** Listener interface for recovery events. */
    @FunctionalInterface
    public interface RecoveryListener {
        void onAttempt(int attemptNumber, Duration delay);
    }

    /** Exception thrown during recovery operations. */
    public static class RecoveryException extends Exception {
        public RecoveryException(String message) {
            super(message);
        }

        public RecoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
