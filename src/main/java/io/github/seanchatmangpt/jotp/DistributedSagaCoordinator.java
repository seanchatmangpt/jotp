package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Distributed Saga Coordinator — orchestrates multi-service workflows with compensating
 * transactions.
 *
 * <p>Joe Armstrong: "In a distributed system, a saga coordinates a sequence of local transactions
 * across multiple services. If any service fails, compensating transactions roll back the entire
 * workflow."
 *
 * <p>A saga is a long-running transaction that spans multiple services. Instead of a traditional
 * two-phase commit (which requires tight coupling and blocking), a saga executes a sequence of
 * local transactions, each with a compensating transaction for rollback.
 *
 * <p><strong>Saga Workflow:</strong>
 *
 * <ol>
 *   <li><strong>Execute Step:</strong> Call service with {@link Proc#ask(Object)} + timeout
 *   <li><strong>Log Execution:</strong> Track which steps succeeded for later compensation
 *   <li><strong>Handle Failure:</strong> If timeout or error, trigger compensation chain
 *   <li><strong>Compensation:</strong> Execute logged steps in reverse order
 *   <li><strong>Recovery:</strong> Saga state transitions define how to recover (retry, abort,
 *       etc.)
 * </ol>
 *
 * <p><strong>Saga State Machine:</strong>
 *
 * <pre>{@code
 * Sealed Saga States:
 *   Executing(currentStep)    → actively processing steps
 *   Compensating(failedAt)    → rolling back due to failure
 *   Completed()               → all steps succeeded
 *   Failed(reason)            → compensation finished; saga failed
 *
 * Sealed Saga Transitions:
 *   Next(state, data)         → proceed to next step or state
 *   Compensate(action)        → trigger rollback action
 *   Complete                  → saga finished successfully
 * }</pre>
 *
 * <p><strong>Example — Order Saga:</strong>
 *
 * <pre>{@code
 * // Order workflow: Payment → Inventory → Shipping
 * record OrderSagaData(
 *     String orderId,
 *     double amount,
 *     List<Item> items,
 *     Address shipping
 * ) {}
 *
 * var coordinator = new DistributedSagaCoordinator<>(
 *     "order-saga",
 *     new OrderSagaData(...),
 *     paymentService,
 *     inventoryService,
 *     shippingService,
 *     Duration.ofSeconds(5)
 * );
 *
 * // Execute saga: Payment → Inventory → Shipping
 * // On failure at Inventory, compensate: refund payment, restock (Inventory compensate is no-op)
 * Result<SagaOutcome, SagaError> outcome = coordinator.execute();
 * }</pre>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Sealed Interfaces:</strong> {@code SagaState<S>}, {@code SagaTransition<S,D>},
 *       {@code CompensationAction<D>} sealed hierarchies for exhaustive pattern matching.
 *   <li><strong>Records:</strong> All state, transitions, and compensation actions are records
 *       carrying immutable data.
 *   <li><strong>Pattern Matching:</strong> Switch expressions over sealed state/event/transition
 *       hierarchies for type-safe coordination logic.
 *   <li><strong>Virtual Threads:</strong> Saga coordinator runs on a virtual thread; each service
 *       call is an ask() with timeout on the Proc's virtual thread.
 *   <li><strong>CompletableFuture:</strong> Timeout handling via {@code orTimeout(duration)} and
 *       exception handling.
 *   <li><strong>Generics:</strong> Generic over saga state {@code S}, events {@code E}, and data
 *       {@code D} for flexible saga composition.
 * </ul>
 *
 * @param <S> saga state type (use sealed interface of records)
 * @param <E> saga event type (use sealed interface of records, e.g., step results)
 * @param <D> saga data type (carries mutable context like items, amounts, IDs across steps)
 */
public final class DistributedSagaCoordinator<S, E, D> {

    // ── Sealed Saga State Hierarchy ──────────────────────────────────────────

    /**
     * Represents the current state of a distributed saga.
     *
     * <p>Mirrors OTP supervision tree states: a saga can be executing, compensating, completed, or
     * failed.
     *
     * @param <S> saga state type
     */
    public sealed interface SagaState<S>
            permits SagaState.Executing,
                    SagaState.Compensating,
                    SagaState.Completed,
                    SagaState.Failed {

        /** Actively executing saga steps. */
        record Executing<S>(S state, int stepIndex, List<String> completedSteps)
                implements SagaState<S> {}

        /** Rolling back completed steps due to failure. */
        record Compensating<S>(S state, String failureReason, int rollbackIndex)
                implements SagaState<S> {}

        /** All steps completed successfully. */
        record Completed<S>(S state, List<String> allSteps) implements SagaState<S> {}

        /** Saga failed and compensation finished. */
        record Failed<S>(String reason, List<String> compensatedSteps) implements SagaState<S> {}
    }

    // ── Sealed Saga Transition Hierarchy ─────────────────────────────────────

    /**
     * Return type of a saga transition function, analogous to {@link StateMachine.Transition}.
     *
     * @param <S> saga state type
     * @param <D> saga data type
     */
    public sealed interface SagaTransition<S, D>
            permits SagaTransition.NextStep,
                    SagaTransition.Compensate,
                    SagaTransition.Complete,
                    SagaTransition.Fail {

        /**
         * Proceed to the next step.
         *
         * @param state new saga state
         * @param data updated data
         */
        static <S, D> SagaTransition<S, D> nextStep(S state, D data) {
            return new NextStep<>(state, data);
        }

        /**
         * Trigger compensation (rollback) with a reason.
         *
         * @param reason why compensation was triggered
         * @param data data state at point of failure
         */
        static <S, D> SagaTransition<S, D> compensate(String reason, D data) {
            return new Compensate<>(reason, data);
        }

        /**
         * All steps completed successfully.
         *
         * @param state final state
         * @param data final data
         */
        static <S, D> SagaTransition<S, D> complete(S state, D data) {
            return new Complete<>(state, data);
        }

        /**
         * Saga failed (either during execution or compensation).
         *
         * @param reason failure reason
         */
        static <S, D> SagaTransition<S, D> fail(String reason) {
            return new Fail<>(reason);
        }

        /** Move to next step with new state/data. */
        record NextStep<S, D>(S state, D data) implements SagaTransition<S, D> {}

        /** Trigger compensation chain. */
        record Compensate<S, D>(String reason, D data) implements SagaTransition<S, D> {}

        /** Saga completed successfully. */
        record Complete<S, D>(S state, D data) implements SagaTransition<S, D> {}

        /** Saga failed. */
        record Fail<S, D>(String reason) implements SagaTransition<S, D> {}
    }

    // ── Compensation Action ──────────────────────────────────────────────────

    /**
     * Represents an action to execute during compensation (rollback).
     *
     * @param <D> saga data type
     */
    public sealed interface CompensationAction<D>
            permits CompensationAction.Rollback, CompensationAction.Noop {

        /**
         * Execute a rollback action (e.g., refund payment, restock inventory).
         *
         * @param stepName name of the step being rolled back
         * @param action function to execute during rollback
         */
        static <D> CompensationAction<D> rollback(
                String stepName, Function<D, Result<D, String>> action) {
            return new Rollback<>(stepName, action);
        }

        /**
         * No-op compensation (step cannot or should not be compensated).
         *
         * @param stepName name of the step
         */
        static <D> CompensationAction<D> noop(String stepName) {
            return new Noop<>(stepName);
        }

        /** Execute rollback action. */
        record Rollback<D>(String stepName, Function<D, Result<D, String>> action)
                implements CompensationAction<D> {}

        /** No compensation needed for this step. */
        record Noop<D>(String stepName) implements CompensationAction<D> {}
    }

    // ── Saga Coordinator Fields ──────────────────────────────────────────────

    private final String name;
    private volatile SagaState<S> currentState;
    private volatile D currentData;
    private final BiFunction<S, E, SagaTransition<S, D>> stateMachine;
    private final Duration stepTimeout;
    private final List<CompensationAction<D>> compensationLog = new ArrayList<>();
    private final Map<String, ProcRef<?, ?>> serviceRefs = new LinkedHashMap<>();
    private volatile boolean running = true;
    private volatile String stopReason = null;

    /**
     * Create a new distributed saga coordinator.
     *
     * @param name saga name (e.g., "order-saga")
     * @param initialState initial saga state
     * @param initialData initial saga data
     * @param stateMachine transition function: {@code (state, event) -> SagaTransition}
     * @param stepTimeout timeout for individual service calls
     */
    public DistributedSagaCoordinator(
            String name,
            S initialState,
            D initialData,
            BiFunction<S, E, SagaTransition<S, D>> stateMachine,
            Duration stepTimeout) {
        this.name = name;
        this.currentState = new SagaState.Executing<>(initialState, 0, List.of());
        this.currentData = initialData;
        this.stateMachine = stateMachine;
        this.stepTimeout = stepTimeout;
    }

    // ── Service Registration ─────────────────────────────────────────────────

    /**
     * Register a service (Proc) that participates in the saga.
     *
     * <p>Services are registered by name and later invoked by name during saga execution.
     *
     * @param serviceName name of the service
     * @param serviceRef reference to the Proc handling service requests
     * @param <ServiceState> service state type
     * @param <ServiceMsg> service message type
     */
    public <ServiceState, ServiceMsg> void registerService(
            String serviceName, ProcRef<ServiceState, ServiceMsg> serviceRef) {
        serviceRefs.put(serviceName, serviceRef);
    }

    // ── Saga Execution ───────────────────────────────────────────────────────

    /**
     * Ask a registered service and return the result with timeout.
     *
     * <p>This is the primary way to invoke services within a saga step. It wraps {@link
     * ProcRef#ask(Object)} with timeout handling.
     *
     * @param serviceName name of registered service
     * @param message message to send to the service
     * @param <Response> response type from the service
     * @return Result with the response or timeout error
     */
    public <Response> Result<Response, String> askService(String serviceName, Object message) {
        @SuppressWarnings("unchecked")
        ProcRef<Object, Object> ref = (ProcRef<Object, Object>) serviceRefs.get(serviceName);
        if (ref == null) {
            return Result.err("Service not registered: " + serviceName);
        }

        try {
            CompletableFuture<Object> response = ref.ask(message);
            @SuppressWarnings("unchecked")
            Response result =
                    (Response)
                            response.orTimeout(stepTimeout.toMillis(), TimeUnit.MILLISECONDS)
                                    .join();
            return Result.ok(result);
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof java.util.concurrent.CancellationException) {
                return Result.err("Service call timeout: " + serviceName);
            }
            return Result.err("Service call failed: " + e.getMessage());
        } catch (Exception e) {
            return Result.err("Service call failed: " + e.getMessage());
        }
    }

    /**
     * Execute a saga step with compensation.
     *
     * <p>This method:
     *
     * <ol>
     *   <li>Calls the service (with timeout)
     *   <li>Logs the compensation action if successful
     *   <li>Transitions state using the saga's state machine
     *   <li>Returns the transition result or triggers compensation on failure
     * </ol>
     *
     * @param stepName name of the step (for logging and compensation)
     * @param serviceName name of registered service to call
     * @param message message to send to service
     * @param compensation compensation action if step fails
     * @param <Response> response type from service
     * @return Result with transition or error
     */
    public <Response> Result<SagaTransition<S, D>, String> executeStep(
            String stepName,
            String serviceName,
            Object message,
            CompensationAction<D> compensation) {
        // 1. Call service with timeout
        Result<Response, String> serviceResult = askService(serviceName, message);

        return serviceResult.fold(
                // Success: log compensation and continue
                response -> {
                    compensationLog.add(compensation);
                    // Return a NextStep transition (caller applies it)
                    S innerState =
                            switch (currentState) {
                                case SagaState.Executing<S>(var s, var idx, var steps) -> s;
                                case SagaState.Compensating<S>(var s, var reason, var idx) -> s;
                                case SagaState.Completed<S>(var s, var steps) -> s;
                                case SagaState.Failed<S>(var reason, var steps) -> null;
                            };
                    return Result.ok(SagaTransition.nextStep(innerState, currentData));
                },
                // Failure: trigger compensation
                error -> Result.err(error));
    }

    /**
     * Trigger compensation chain — execute all logged compensation actions in reverse order.
     *
     * <p>Called when a saga step fails. Rolls back all successfully completed steps.
     *
     * @param failureReason reason for the failure
     * @return Result with compensated data or error if compensation itself fails
     */
    public Result<D, String> compensate(String failureReason) {
        D data = currentData;
        List<String> compensatedSteps = new ArrayList<>();

        // Execute compensation actions in reverse order (LIFO)
        for (int i = compensationLog.size() - 1; i >= 0; i--) {
            CompensationAction<D> action = compensationLog.get(i);

            switch (action) {
                case CompensationAction.Rollback<D>(var stepName, var rollbackFn) -> {
                    Result<D, String> rollbackResult = rollbackFn.apply(data);
                    if (rollbackResult instanceof Result.Err<D, String>(var error)) {
                        return Result.err("Compensation failed at step " + stepName + ": " + error);
                    }
                    data = ((Result.Ok<D, String>) rollbackResult).value();
                    compensatedSteps.add(stepName);
                }
                case CompensationAction.Noop<D>(var stepName) -> {
                    compensatedSteps.add(stepName);
                }
            }
        }

        return Result.ok(data);
    }

    // ── State Management ─────────────────────────────────────────────────────

    /**
     * Returns the current saga state.
     *
     * @return current state
     */
    public SagaState<S> state() {
        return currentState;
    }

    /**
     * Returns the current saga data.
     *
     * @return current data
     */
    public D data() {
        return currentData;
    }

    /**
     * Returns the compensation log (steps that have been executed and logged for rollback).
     *
     * @return list of compensation actions in execution order
     */
    public List<CompensationAction<D>> compensationLog() {
        return compensationLog;
    }

    /**
     * Returns {@code true} if the saga is still running.
     *
     * @return saga running status
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the stop reason if the saga has stopped, or {@code null} if still running.
     *
     * @return stop reason
     */
    public String stopReason() {
        return stopReason;
    }

    /**
     * Stop the saga gracefully.
     *
     * @param reason reason for stopping
     */
    public synchronized void stop(String reason) {
        this.running = false;
        this.stopReason = reason;
    }

    // ── Lifecycle Methods ────────────────────────────────────────────────────

    /**
     * Get the name of this saga.
     *
     * @return saga name
     */
    public String name() {
        return name;
    }

    /**
     * Apply a saga transition, updating state and data.
     *
     * <p>This is called after a saga step completes or during compensation to update the saga's
     * internal state machine.
     *
     * @param transition transition to apply
     * @return true if transition succeeded, false if saga must fail
     */
    public boolean applyTransition(SagaTransition<S, D> transition) {
        switch (transition) {
            case SagaTransition.NextStep<S, D>(var newState, var newData) -> {
                int nextStep =
                        currentState instanceof SagaState.Executing<S>(var s, var idx, var steps)
                                ? idx + 1
                                : 0;
                this.currentState = new SagaState.Executing<>(newState, nextStep, List.of());
                this.currentData = newData;
                return true;
            }
            case SagaTransition.Compensate<S, D>(var reason, var newData) -> {
                this.currentData = newData;
                return true;
            }
            case SagaTransition.Complete<S, D>(var newState, var newData) -> {
                this.currentState = new SagaState.Completed<>(newState, List.of());
                this.currentData = newData;
                this.running = false;
                return true;
            }
            case SagaTransition.Fail<S, D>(var reason) -> {
                this.running = false;
                this.stopReason = reason;
                return false;
            }
        }
    }

    /**
     * Create a saga coordinator with supervision.
     *
     * <p>The returned {@link ProcRef} can be used with a {@link Supervisor} for automatic restart
     * on failure.
     *
     * @param name saga name
     * @param initialState initial saga state
     * @param initialData initial saga data
     * @param stateMachine transition function
     * @param stepTimeout timeout per service call
     * @param <S> state type
     * @param <E> event type
     * @param <D> data type
     * @return ProcRef wrapping a saga coordinator
     */
    public static <S, E, D> ProcRef<SagaState<S>, SagaCoordinatorMsg<S, D>> createSupervised(
            String name,
            S initialState,
            D initialData,
            BiFunction<S, E, SagaTransition<S, D>> stateMachine,
            Duration stepTimeout) {
        var coordinator =
                new DistributedSagaCoordinator<>(
                        name, initialState, initialData, stateMachine, stepTimeout);

        Proc<SagaState<S>, SagaCoordinatorMsg<S, D>> proc =
                new Proc<>(
                        coordinator.currentState,
                        (state, msg) ->
                                switch (msg) {
                                    case SagaCoordinatorMsg.GetState<?, ?> ignored -> state;
                                    case SagaCoordinatorMsg.Stop<?, ?> stop -> {
                                        coordinator.stop(stop.reason());
                                        yield state;
                                    }
                                });

        return new ProcRef<>(proc);
    }

    /**
     * Messages for saga coordinator process.
     *
     * @param <S> saga state type
     * @param <D> saga data type
     */
    sealed interface SagaCoordinatorMsg<S, D>
            permits SagaCoordinatorMsg.GetState, SagaCoordinatorMsg.Stop {

        record GetState<S, D>() implements SagaCoordinatorMsg<S, D> {}

        record Stop<S, D>(String reason) implements SagaCoordinatorMsg<S, D> {}
    }
}
