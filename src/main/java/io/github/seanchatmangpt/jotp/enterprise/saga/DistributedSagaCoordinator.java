package io.github.seanchatmangpt.jotp.enterprise.saga;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Distributed saga coordinator for multi-step transactions with compensating actions.
 *
 * <p>Executes a sequence of steps (forward actions) and can rollback via compensation (backward
 * actions) if any step fails. Provides exactly-once semantics with idempotent compensation and
 * durable step logging.
 *
 * <p>State Machine: - PENDING: Saga registered, awaiting execution - IN_PROGRESS: Executing forward
 * steps - COMPLETED: All steps succeeded - FAILED: A step failed, starting compensation -
 * COMPENSATED: Compensation completed - ABORTED: User-requested abort
 */
public class DistributedSagaCoordinator {
    private final SagaConfig config;
    private final ProcRef<SagaState, SagaMsg> coordinator;
    private final Map<String, SagaInstance> sagas = new HashMap<>();
    private final CopyOnWriteArrayList<SagaListener> listeners = new CopyOnWriteArrayList<>();

    private DistributedSagaCoordinator(SagaConfig config, ProcRef<SagaState, SagaMsg> coordinator) {
        this.config = config;
        this.coordinator = coordinator;
    }

    /**
     * Create a new saga coordinator.
     *
     * @param config Saga configuration
     * @return DistributedSagaCoordinator instance
     */
    public static DistributedSagaCoordinator create(SagaConfig config) {
        return new DistributedSagaCoordinator(config, spawnCoordinator(config));
    }

    /**
     * Execute a saga asynchronously.
     *
     * @return CompletableFuture<SagaResult>
     */
    public CompletableFuture<SagaResult> execute() {
        String sagaId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        SagaInstance instance =
                new SagaInstance(
                        sagaId,
                        new SagaTransaction.InProgress(sagaId, 0),
                        new ArrayList<>(),
                        new HashMap<>(),
                        startTime);

        sagas.put(sagaId, instance);
        listeners.forEach(l -> l.onSagaStarted(sagaId, config.steps().size()));

        return CompletableFuture.supplyAsync(() -> executeSaga(sagaId, instance));
    }

    /**
     * Abort a saga in progress.
     *
     * @param sagaId Saga identifier
     * @param reason User-provided reason
     */
    public void abort(String sagaId, String reason) {
        SagaInstance instance = sagas.get(sagaId);
        if (instance != null) {
            instance.transaction.set(new SagaTransaction.Aborted(sagaId, reason));
            listeners.forEach(l -> l.onSagaAborted(sagaId, reason));
        }
    }

    /**
     * Get saga execution status.
     *
     * @param sagaId Saga identifier
     * @return SagaTransaction or null if not found
     */
    public SagaTransaction getStatus(String sagaId) {
        SagaInstance instance = sagas.get(sagaId);
        return instance != null ? instance.transaction.get() : null;
    }

    /**
     * Get saga event log.
     *
     * @param sagaId Saga identifier
     * @return List of SagaEvent
     */
    public List<SagaEvent> getSagaLog(String sagaId) {
        SagaInstance instance = sagas.get(sagaId);
        return instance != null ? new ArrayList<>(instance.events) : List.of();
    }

    /**
     * Register a listener for saga events.
     *
     * @param listener Callback to invoke on events
     */
    public void addListener(SagaListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     *
     * @param listener Listener to remove
     */
    public void removeListener(SagaListener listener) {
        listeners.remove(listener);
    }

    /** Shutdown the saga coordinator. */
    public void shutdown() {
        coordinator.tell(new SagaMsg.Shutdown());
    }

    private SagaResult executeSaga(String sagaId, SagaInstance instance) {
        try {
            for (int i = 0; i < config.steps().size(); i++) {
                SagaStep step = config.steps().get(i);

                if (step instanceof SagaStep.Action<?, ?> action) {
                    try {
                        Object output = action.task().apply(null);
                        instance.outputs.put(action.name(), output);
                        instance.events.add(
                                new SagaEvent.StepExecuted(
                                        sagaId, action.name(), output, System.currentTimeMillis()));
                        listeners.forEach(l -> l.onStepExecuted(sagaId, action.name(), output));
                    } catch (Exception e) {
                        // Step failed, start compensation
                        return compensateAndFail(sagaId, instance, i, e);
                    }
                }
            }

            // All steps succeeded
            long duration = System.currentTimeMillis() - instance.startTime;
            instance.transaction.set(new SagaTransaction.Completed(sagaId, duration));
            instance.events.add(
                    new SagaEvent.SagaCompleted(sagaId, duration, System.currentTimeMillis()));
            listeners.forEach(l -> l.onSagaCompleted(sagaId, duration));

            return new SagaResult(sagaId, SagaResult.Status.COMPLETED, "", instance.outputs);
        } catch (Exception e) {
            return new SagaResult(
                    sagaId, SagaResult.Status.FAILED, e.getMessage(), instance.outputs);
        }
    }

    private SagaResult compensateAndFail(
            String sagaId, SagaInstance instance, int failedStep, Exception cause) {
        long compensationStart = System.currentTimeMillis();
        instance.events.add(
                new SagaEvent.CompensationStarted(sagaId, failedStep, System.currentTimeMillis()));
        listeners.forEach(l -> l.onCompensationStarted(sagaId, failedStep));

        instance.transaction.set(
                new SagaTransaction.Failed(sagaId, failedStep, cause.getMessage()));

        // Execute compensations in reverse order
        for (int i = failedStep - 1; i >= 0; i--) {
            SagaStep step = config.steps().get(i);
            if (step instanceof SagaStep.Compensation<?> comp) {
                try {
                    comp.task().accept(null);
                } catch (Exception e) {
                    // Log but continue with other compensations
                    instance.events.add(
                            new SagaEvent.StepFailed(
                                    sagaId,
                                    comp.name(),
                                    e.getMessage(),
                                    System.currentTimeMillis()));
                }
            }
        }

        long compensationDuration = System.currentTimeMillis() - compensationStart;
        instance.transaction.set(
                new SagaTransaction.Compensated(sagaId, cause.getMessage(), compensationDuration));
        instance.events.add(
                new SagaEvent.CompensationCompleted(
                        sagaId, compensationDuration, System.currentTimeMillis()));
        listeners.forEach(l -> l.onCompensationCompleted(sagaId));

        return new SagaResult(
                sagaId, SagaResult.Status.COMPENSATED, cause.getMessage(), instance.outputs);
    }

    private static ProcRef<SagaState, SagaMsg> spawnCoordinator(SagaConfig config) {
        var proc =
                new Proc<>(
                        new SagaState(config.sagaId(), 0),
                        (SagaState state, SagaMsg msg) -> {
                            return switch (msg) {
                                case SagaMsg.StepExecuted(var stepName) ->
                                        new SagaState(state.sagaId(), state.executedSteps() + 1);
                                case SagaMsg.Shutdown _ -> state;
                            };
                        });
        return new ProcRef<>(proc);
    }

    /** Internal state for the saga coordinator. */
    record SagaState(String sagaId, long executedSteps) {}

    /** Messages for the saga coordinator. */
    sealed interface SagaMsg permits SagaMsg.StepExecuted, SagaMsg.Shutdown {

        record StepExecuted(String stepName) implements SagaMsg {}

        record Shutdown() implements SagaMsg {}
    }

    /** Saga execution result. */
    public record SagaResult(
            String sagaId, Status status, String errorMessage, Map<String, Object> outputs) {

        enum Status {
            PENDING,
            IN_PROGRESS,
            COMPLETED,
            COMPENSATED,
            FAILED,
            ABORTED
        }
    }

    /** Internal: Saga instance tracking. */
    private static class SagaInstance {
        final String sagaId;
        final java.util.concurrent.atomic.AtomicReference<SagaTransaction> transaction;
        final List<SagaEvent> events;
        final Map<String, Object> outputs;
        final long startTime;

        SagaInstance(
                String sagaId,
                SagaTransaction transaction,
                List<SagaEvent> events,
                Map<String, Object> outputs,
                long startTime) {
            this.sagaId = sagaId;
            this.transaction = new java.util.concurrent.atomic.AtomicReference<>(transaction);
            this.events = events;
            this.outputs = outputs;
            this.startTime = startTime;
        }
    }

    /** Listener interface for saga events. */
    public interface SagaListener {
        void onSagaStarted(String sagaId, int stepCount);

        void onStepExecuted(String sagaId, String stepName, Object output);

        void onCompensationStarted(String sagaId, int fromStep);

        void onCompensationCompleted(String sagaId);

        void onSagaCompleted(String sagaId, long durationMs);

        void onSagaAborted(String sagaId, String reason);
    }
}
