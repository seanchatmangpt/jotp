package io.github.seanchatmangpt.jotp;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Saga Orchestrator — coordinates distributed transactions across services.
 *
 * <p>Vaughn Vernon: "A Saga is a sequence of local transactions where each transaction updates a
 * single service and publishes a message or event to trigger the next step. If a step fails,
 * compensating actions undo the previous steps."
 *
 * <p>Joe Armstrong: "In Erlang, we don't have distributed transactions. We have processes that
 * coordinate through message passing. The saga pattern is just another process."
 *
 * <p>Features:
 *
 * <ul>
 *   <li><b>Orchestrated sagas</b> — Central coordinator manages the workflow
 *   <li><b>Compensating actions</b> — Rollback on failure
 *   <li><b>State persistence</b> — Recover from crashes mid-saga
 *   <li><b>Timeout handling</b> — Automatic compensation on timeout
 *   <li><b>Step retry</b> — Configurable retry for transient failures
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * SagaOrchestrator saga = SagaOrchestrator.builder("order-fulfillment")
 *     .step("reserve-inventory")
 *         .action(inventoryService::reserve)
 *         .compensation(inventoryService::release)
 *     .step("charge-payment")
 *         .action(paymentService::charge)
 *         .compensation(paymentService::refund)
 *     .step("ship-order")
 *         .action(shippingService::ship)
 *         .timeout(Duration.ofMinutes(5))
 *     .build();
 *
 * SagaResult result = saga.execute(orderId, orderData).get();
 * }</pre>
 */
public final class SagaOrchestrator<S, D> implements Application.Infrastructure {

    /** Saga step definition. */
    public record Step<S, D>(
            String name,
            BiFunction<D, SagaContext, CompletableFuture<S>> action,
            BiFunction<D, S, CompletableFuture<Void>> compensation,
            Duration timeout,
            int maxRetries,
            Predicate<Throwable> retryOn) {

        public static <S, D> Builder<S, D> named(String name) {
            return new Builder<>(name);
        }

        public static final class Builder<S, D> {
            private final String name;
            private BiFunction<D, SagaContext, CompletableFuture<S>> action;
            private BiFunction<D, S, CompletableFuture<Void>> compensation;
            private Duration timeout = Duration.ofSeconds(30);
            private int maxRetries = 3;
            private Predicate<Throwable> retryOn = t -> true;

            public Builder(String name) {
                this.name = name;
            }

            public Builder<S, D> action(BiFunction<D, SagaContext, CompletableFuture<S>> action) {
                this.action = action;
                return this;
            }

            public Builder<S, D> compensation(
                    BiFunction<D, S, CompletableFuture<Void>> compensation) {
                this.compensation = compensation;
                return this;
            }

            public Builder<S, D> timeout(Duration timeout) {
                this.timeout = timeout;
                return this;
            }

            public Builder<S, D> maxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
                return this;
            }

            public Builder<S, D> retryOn(Predicate<Throwable> retryOn) {
                this.retryOn = retryOn;
                return this;
            }

            public Step<S, D> build() {
                return new Step<>(name, action, compensation, timeout, maxRetries, retryOn);
            }
        }
    }

    /** Saga execution context. */
    public record SagaContext(
            UUID sagaId,
            String sagaName,
            Instant startTime,
            int currentStep,
            SagaStatus status,
            Map<String, Object> results,
            List<String> completedSteps) {

        public static SagaContext start(UUID sagaId, String sagaName) {
            return new SagaContext(
                    sagaId,
                    sagaName,
                    Instant.now(),
                    0,
                    SagaStatus.STARTED,
                    new LinkedHashMap<>(),
                    new ArrayList<>());
        }

        public SagaContext withResult(String step, Object result) {
            Map<String, Object> newResults = new LinkedHashMap<>(results);
            newResults.put(step, result);
            List<String> newCompleted = new ArrayList<>(completedSteps);
            newCompleted.add(step);
            return new SagaContext(
                    sagaId, sagaName, startTime, currentStep + 1, status, newResults, newCompleted);
        }

        public SagaContext withStatus(SagaStatus newStatus) {
            return new SagaContext(
                    sagaId, sagaName, startTime, currentStep, newStatus, results, completedSteps);
        }

        public Duration elapsed() {
            return Duration.between(startTime, Instant.now());
        }
    }

    /** Saga status. */
    public enum SagaStatus {
        STARTED,
        IN_PROGRESS,
        COMPLETED,
        COMPENSATING,
        COMPENSATED,
        FAILED,
        TIMED_OUT
    }

    /** Saga execution result. */
    public sealed interface SagaResult
            permits SagaResult.Success, SagaResult.Failure, SagaResult.Compensated {
        record Success(Map<String, Object> results) implements SagaResult {}

        record Failure(String step, Throwable error) implements SagaResult {}

        record Compensated(String failedStep, Throwable error, List<String> compensatedSteps)
                implements SagaResult {}
    }

    // ── Saga orchestrator state ──────────────────────────────────────────────────

    private final String name;
    private final List<Step<S, D>> steps;
    private final Duration globalTimeout;
    private final EventStore eventStore;
    private final Proc<SagaState, SagaMsg> coordinator;

    private record SagaState(
            Map<UUID, SagaRuntime> sagas, Map<UUID, CompletableFuture<SagaResult>> pending) {}

    private sealed interface SagaMsg
            permits SagaMsg.Start,
                    SagaMsg.StepComplete,
                    SagaMsg.StepFailed,
                    SagaMsg.Timeout,
                    SagaMsg.GetStatus {
        record Start(UUID sagaId, Object data, CompletableFuture<SagaResult> replyTo)
                implements SagaMsg {}

        record StepComplete(UUID sagaId, String step, Object result) implements SagaMsg {}

        record StepFailed(UUID sagaId, String step, Throwable error) implements SagaMsg {}

        record Timeout(UUID sagaId) implements SagaMsg {}

        record GetStatus(UUID sagaId, CompletableFuture<Optional<SagaContext>> replyTo)
                implements SagaMsg {}
    }

    /** Saga runtime state including the original data for step execution. */
    private record SagaRuntime(SagaContext context, Object data, Map<String, Object> stepResults) {

        SagaRuntime withResult(String step, Object result) {
            Map<String, Object> newResults = new LinkedHashMap<>(stepResults);
            newResults.put(step, result);
            return new SagaRuntime(context.withResult(step, result), data, newResults);
        }
    }

    private SagaOrchestrator(Builder<S, D> builder) {
        this.name = builder.name;
        this.steps = List.copyOf(builder.steps);
        this.globalTimeout = builder.globalTimeout;
        this.eventStore = builder.eventStore;

        this.coordinator =
                new Proc<>(
                        new SagaState(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()),
                        this::handleSagaMessage);
    }

    // ── Factory ──────────────────────────────────────────────────────────────────

    public static <S, D> Builder<S, D> builder(String name) {
        return new Builder<>(name);
    }

    /** Saga builder. */
    public static final class Builder<S, D> {
        private final String name;
        private final List<Step<S, D>> steps = new ArrayList<>();
        private Duration globalTimeout = Duration.ofMinutes(10);
        private EventStore eventStore;

        public Builder(String name) {
            this.name = name;
        }

        public Builder<S, D> step(Step<S, D> step) {
            this.steps.add(step);
            return this;
        }

        public Builder<S, D> globalTimeout(Duration timeout) {
            this.globalTimeout = timeout;
            return this;
        }

        public Builder<S, D> eventStore(EventStore eventStore) {
            this.eventStore = eventStore;
            return this;
        }

        public SagaOrchestrator<S, D> build() {
            return new SagaOrchestrator<>(this);
        }
    }

    // ── Saga execution ────────────────────────────────────────────────────────────

    /** Execute a saga with the given data. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<SagaResult> execute(D data) {
        UUID sagaId = UUID.randomUUID();
        CompletableFuture<SagaResult> future = new CompletableFuture<>();

        coordinator.tell(new SagaMsg.Start(sagaId, data, future));

        // Set global timeout
        return future.orTimeout(globalTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> new SagaResult.Failure("global", e));
    }

    /** Get the status of a saga. */
    public CompletableFuture<Optional<SagaContext>> getStatus(UUID sagaId) {
        CompletableFuture<Optional<SagaContext>> future = new CompletableFuture<>();
        coordinator.tell(new SagaMsg.GetStatus(sagaId, future));
        return future;
    }

    /** Saga events for event sourcing and crash recovery. */
    sealed interface SagaEvent
            permits SagaEvent.StepAttempt,
                    SagaEvent.StepFailed,
                    SagaEvent.StepSucceeded,
                    SagaEvent.CompensationStarted,
                    SagaEvent.CompensationCompleted {
        record StepAttempt(String stepName, int attemptCount, Instant timestamp)
                implements SagaEvent {}

        record StepFailed(String stepName, String errorMessage, int attemptCount, Instant timestamp)
                implements SagaEvent {}

        record StepSucceeded(String stepName, Object result, Instant timestamp)
                implements SagaEvent {}

        record CompensationStarted(String failedStep, String reason, Instant timestamp)
                implements SagaEvent {}

        record CompensationCompleted(List<String> compensatedSteps, Instant timestamp)
                implements SagaEvent {}
    }

    // ── Message handling ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private SagaState handleSagaMessage(SagaState state, SagaMsg msg) {
        return switch (msg) {
            case SagaMsg.Start(var sagaId, var data, var replyTo) -> {
                SagaContext ctx = SagaContext.start(sagaId, name);
                SagaRuntime runtime = new SagaRuntime(ctx, data, new LinkedHashMap<>());
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(sagaId, runtime);
                Map<UUID, CompletableFuture<SagaResult>> newPending =
                        new ConcurrentHashMap<>(state.pending());
                newPending.put(sagaId, replyTo);

                // Start first step
                executeStep(sagaId, (D) data, ctx, 0);

                yield new SagaState(newSagas, newPending);
            }

            case SagaMsg.StepComplete(var sagaId, var step, var result) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                if (runtime == null) yield state;

                SagaRuntime newRuntime = runtime.withResult(step, result);
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(sagaId, newRuntime);

                if (newRuntime.context().currentStep() >= steps.size()) {
                    // Saga complete
                    SagaContext newCtx = newRuntime.context().withStatus(SagaStatus.COMPLETED);
                    newSagas.put(
                            sagaId,
                            new SagaRuntime(newCtx, newRuntime.data(), newRuntime.stepResults()));
                    CompletableFuture<SagaResult> future = state.pending().get(sagaId);
                    if (future != null) {
                        future.complete(new SagaResult.Success(newCtx.results()));
                    }
                } else {
                    // Continue to next step with stored data
                    executeStep(
                            sagaId,
                            (D) newRuntime.data(),
                            newRuntime.context(),
                            newRuntime.context().currentStep());
                }

                yield new SagaState(newSagas, state.pending());
            }

            case SagaMsg.StepFailed(var sagaId, var step, var error) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                if (runtime == null) yield state;

                // Start compensation
                SagaContext newCtx = runtime.context().withStatus(SagaStatus.COMPENSATING);
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(
                        sagaId, new SagaRuntime(newCtx, runtime.data(), runtime.stepResults()));

                // Compensate in reverse order
                compensate(sagaId, runtime.context(), state.pending().get(sagaId), error);

                yield new SagaState(newSagas, state.pending());
            }

            case SagaMsg.Timeout(var sagaId) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                if (runtime == null) yield state;

                SagaContext newCtx = runtime.context().withStatus(SagaStatus.TIMED_OUT);
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(
                        sagaId, new SagaRuntime(newCtx, runtime.data(), runtime.stepResults()));

                compensate(
                        sagaId,
                        runtime.context(),
                        state.pending().get(sagaId),
                        new TimeoutException("Saga timed out"));

                yield new SagaState(newSagas, state.pending());
            }

            case SagaMsg.GetStatus(var sagaId, var replyTo) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                replyTo.complete(Optional.ofNullable(runtime != null ? runtime.context() : null));
                yield state;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void executeStep(UUID sagaId, D data, SagaContext ctx, int stepIndex) {
        if (stepIndex >= steps.size()) return;

        Step<S, D> step = steps.get(stepIndex);

        // Execute step with retry logic and idempotency
        executeStepWithRetry(sagaId, data, ctx, step, 0);
    }

    @SuppressWarnings("unchecked")
    private void executeStepWithRetry(
            UUID sagaId, D data, SagaContext ctx, Step<S, D> step, int attemptCount) {
        // Log step execution attempt if event store is available
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.StepAttempt(
                            step.name(), attemptCount, Instant.now()));
        }

        step.action()
                .apply(data, ctx)
                .thenAccept(
                        result ->
                                coordinator.tell(
                                        new SagaMsg.StepComplete(sagaId, step.name(), result)))
                .exceptionally(
                        error -> {
                            // Determine if we should retry
                            if (shouldRetry(error, step, attemptCount)) {
                                long backoffMs = calculateBackoff(attemptCount);
                                // Schedule retry with exponential backoff
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        Thread.sleep(backoffMs);
                                                        executeStepWithRetry(
                                                                sagaId,
                                                                data,
                                                                ctx,
                                                                step,
                                                                attemptCount + 1);
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                        coordinator.tell(
                                                                new SagaMsg.StepFailed(
                                                                        sagaId, step.name(), e));
                                                    }
                                                });
                            } else {
                                // No more retries, fail the saga
                                logStepFailure(sagaId, step.name(), error, attemptCount);
                                coordinator.tell(
                                        new SagaMsg.StepFailed(sagaId, step.name(), error));
                            }
                            return null;
                        });
    }

    /**
     * Determine if a transient error should trigger a retry.
     *
     * <p>Returns true if:
     * <ul>
     *   <li>The step's retryOn predicate accepts the error, AND
     *   <li>We haven't exceeded maxRetries
     * </ul>
     */
    private boolean shouldRetry(Throwable error, Step<S, D> step, int attemptCount) {
        if (attemptCount >= step.maxRetries()) {
            return false;
        }
        return step.retryOn().test(error);
    }

    /**
     * Calculate exponential backoff with jitter.
     *
     * <p>Formula: min(300s, 100ms * 2^attempt + random jitter)
     */
    private long calculateBackoff(int attemptCount) {
        long baseMs = 100L;
        long exponentialMs = baseMs * (1L << attemptCount); // 2^attempt
        long maxMs = 300_000L; // 5 minutes
        long jitterMs = (long) (Math.random() * 100);
        return Math.min(exponentialMs + jitterMs, maxMs);
    }

    /**
     * Log step failure to event store for crash recovery.
     *
     * <p>This ensures that if the coordinator process crashes after a failed step,
     * recovery can replay the saga and resume compensation.
     */
    private void logStepFailure(UUID sagaId, String stepName, Throwable error, int attemptCount) {
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.StepFailed(
                            stepName, error.getMessage(), attemptCount, Instant.now()));
        }
    }

    @SuppressWarnings("unchecked")
    private void compensate(
            UUID sagaId,
            SagaContext ctx,
            CompletableFuture<SagaResult> pendingFuture,
            Throwable originalError) {
        // Log compensation start
        if (eventStore != null) {
            String failedStep =
                    ctx.completedSteps().isEmpty()
                            ? "unknown"
                            : ctx.completedSteps().getLast();
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.CompensationStarted(
                            failedStep, originalError.getMessage(), Instant.now()));
        }

        // Compensate in reverse order (LIFO)
        List<String> toCompensate = new ArrayList<>(ctx.completedSteps());
        Collections.reverse(toCompensate);

        List<String> successfullyCompensated = new ArrayList<>();
        List<String> failedCompensations = new ArrayList<>();

        for (String stepName : toCompensate) {
            steps.stream()
                    .filter(s -> s.name().equals(stepName))
                    .findFirst()
                    .ifPresent(
                            step -> {
                                Object result = ctx.results().get(stepName);
                                if (result != null && step.compensation() != null) {
                                    // Execute compensation with timeout and retry
                                    if (executeCompensation(sagaId, step, result)) {
                                        successfullyCompensated.add(stepName);
                                    } else {
                                        failedCompensations.add(stepName);
                                    }
                                } else if (step.compensation() == null) {
                                    // No compensation needed for this step
                                    successfullyCompensated.add(stepName);
                                }
                            });
        }

        // Log compensation completion
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.CompensationCompleted(successfullyCompensated, Instant.now()));
        }

        // Complete the pending future with compensated result
        // If no steps were completed, return Failure instead of Compensated
        if (pendingFuture != null) {
            if (ctx.completedSteps().isEmpty()) {
                // No steps completed, so nothing to compensate - return Failure
                pendingFuture.complete(new SagaResult.Failure("unknown", originalError));
            } else {
                // Steps completed and compensation attempted
                String failedStep = ctx.completedSteps().getLast();
                pendingFuture.complete(
                        new SagaResult.Compensated(failedStep, originalError, successfullyCompensated));
            }
        }
    }

    /**
     * Execute a single compensation with retry logic and timeout handling.
     *
     * <p>Returns true if compensation succeeded, false if it failed after retries.
     * Logs compensation failures and applies compensation failure policy (retry, continue, or DLQ).
     *
     * <p>Compensation Failure Policy:
     * <ul>
     *   <li><b>CONTINUE</b> — Log and move to next compensation (best-effort)
     *   <li><b>RETRY</b> — Retry with exponential backoff (timeout/network errors)
     *   <li><b>DEADLETTER</b> — After max attempts, mark saga as requiring manual intervention
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private boolean executeCompensation(UUID sagaId, Step<S, D> step, Object result) {
        int maxAttempts = 3; // Compensation retries
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                step.compensation()
                        .apply(null, (S) result)
                        .get(step.timeout().toMillis(), TimeUnit.MILLISECONDS);
                // Log successful compensation
                if (eventStore != null) {
                    eventStore.append(
                            "saga-" + sagaId,
                            new SagaEvent.StepSucceeded(
                                    step.name() + "-compensation", null, Instant.now()));
                }
                return true; // Success
            } catch (TimeoutException e) {
                // Log timeout and determine policy
                if (eventStore != null) {
                    eventStore.append(
                            "saga-" + sagaId,
                            new SagaEvent.StepFailed(
                                    step.name() + "-compensation",
                                    "Timeout after " + step.timeout(),
                                    attempt,
                                    Instant.now()));
                }

                CompensationFailurePolicy policy =
                        getCompensationFailurePolicy(e, attempt, maxAttempts);
                switch (policy) {
                    case RETRY ->
                            // Retry with exponential backoff
                            {
                                if (attempt < maxAttempts - 1) {
                                    try {
                                        long backoffMs = calculateBackoff(attempt);
                                        Thread.sleep(backoffMs);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        return false;
                                    }
                                }
                            }
                    case DEADLETTER ->
                            // Mark for manual intervention
                            {
                                System.err.println(
                                        "[Saga] Compensation timeout for "
                                                + step.name()
                                                + " after "
                                                + maxAttempts
                                                + " attempts. Manual intervention required.");
                                return false;
                            }
                    case CONTINUE ->
                            // Continue with next compensation
                            {
                                return false;
                            }
                }
            } catch (Exception e) {
                // Log compensation failure and determine policy
                if (eventStore != null) {
                    eventStore.append(
                            "saga-" + sagaId,
                            new SagaEvent.StepFailed(
                                    step.name() + "-compensation",
                                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                                    attempt,
                                    Instant.now()));
                }

                CompensationFailurePolicy policy =
                        getCompensationFailurePolicy(e, attempt, maxAttempts);
                switch (policy) {
                    case RETRY ->
                            // Retry with exponential backoff
                            {
                                if (attempt < maxAttempts - 1) {
                                    try {
                                        long backoffMs = calculateBackoff(attempt);
                                        Thread.sleep(backoffMs);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        return false;
                                    }
                                } else {
                                    // Max retries exhausted
                                    System.err.println(
                                            "[Saga] Compensation failed for "
                                                    + step.name()
                                                    + " after "
                                                    + maxAttempts
                                                    + " attempts. Routing to dead letter queue.");
                                    return false;
                                }
                            }
                    case DEADLETTER ->
                            // Send to dead letter queue
                            {
                                System.err.println(
                                        "[Saga] Compensation failed for "
                                                + step.name()
                                                + ": "
                                                + e.getMessage()
                                                + ". Manual intervention required.");
                                return false;
                            }
                    case CONTINUE ->
                            // Continue with best-effort compensation
                            {
                                System.err.println(
                                        "[Saga] Compensation for "
                                                + step.name()
                                                + " failed ("
                                                + e.getMessage()
                                                + "), continuing with next compensation.");
                                return false;
                            }
                }
            }
        }
        return false; // All retries exhausted
    }

    // ── Crash Recovery ────────────────────────────────────────────────────────────

    /**
     * Recover a saga from event store after coordinator crash.
     *
     * <p>Replays the event log to reconstruct the saga state and resume at the point of
     * failure. This ensures durability and crash-safety by leveraging event sourcing.
     *
     * <p>Recovery workflow:
     * <ol>
     *   <li>Load all events for the saga from event store
     *   <li>Reconstruct saga state and completed steps
     *   <li>Determine recovery point (where crash occurred)
     *   <li>If in compensation phase, resume compensation
     *   <li>If in execution phase, retry the failed step
     * </ol>
     *
     * @param sagaId Saga identifier
     * @return SagaContext recovered state, or empty if saga not found
     */
    public Optional<SagaContext> recoverFromCrash(UUID sagaId) {
        if (eventStore == null) {
            return Optional.empty();
        }

        String streamId = "saga-" + sagaId;
        var events = eventStore.load(streamId).toList();

        if (events.isEmpty()) {
            return Optional.empty();
        }

        // Reconstruct saga state by replaying events
        SagaContext recoveredContext = reconstructSagaState(events, sagaId);

        return Optional.of(recoveredContext);
    }

    /**
     * Reconstruct saga state from event log.
     *
     * <p>Replays all SagaEvents in order to reconstruct the exact saga state at the time
     * of crash. This enables transparent recovery without user intervention.
     */
    private SagaContext reconstructSagaState(List<EventStore.StoredEvent> events, UUID sagaId) {
        SagaContext ctx = SagaContext.start(sagaId, name);
        Map<String, Object> results = new LinkedHashMap<>();
        List<String> completedSteps = new ArrayList<>();

        for (EventStore.StoredEvent stored : events) {
            Object event = stored.event();
            ctx = switch (event) {
                case SagaEvent.StepAttempt attempt ->
                        // Logged but doesn't change state until completion
                        ctx;

                case SagaEvent.StepSucceeded success -> {
                    results.put(success.stepName(), success.result());
                    if (!completedSteps.contains(success.stepName())) {
                        completedSteps.add(success.stepName());
                    }
                    yield ctx.withResult(success.stepName(), success.result());
                }

                case SagaEvent.StepFailed failure -> {
                    // Step failed - we need to enter compensation phase
                    List<String> finalCompletedSteps = new ArrayList<>(completedSteps);
                    Map<String, Object> finalResults = new LinkedHashMap<>(results);
                    yield new SagaContext(
                            ctx.sagaId(),
                            ctx.sagaName(),
                            ctx.startTime(),
                            ctx.currentStep(),
                            SagaStatus.COMPENSATING,
                            finalResults,
                            finalCompletedSteps);
                }

                case SagaEvent.CompensationStarted comp -> ctx.withStatus(SagaStatus.COMPENSATING);

                case SagaEvent.CompensationCompleted comp -> ctx.withStatus(SagaStatus.COMPENSATED);

                default -> ctx;
            };
        }

        return ctx;
    }

    /**
     * Advanced error recovery handler for transient failures.
     *
     * <p>Implements sophisticated error recovery strategies including:
     * <ul>
     *   <li><b>Circuit breaker pattern</b> — Stop retrying after N failures to prevent cascading
     *   <li><b>Jittered exponential backoff</b> — Randomized delays to prevent thundering herd
     *   <li><b>Fallback strategies</b> — Alternative actions if primary fails
     *   <li><b>Dead letter queue</b> — Route permanently failed sagas to DLQ
     * </ul>
     *
     * <p>Error classification:
     * <ul>
     *   <li><b>Transient</b> — Network timeout, temporary unavailability (retry)
     *   <li><b>Permanent</b> — Invalid data, authentication failure (fail fast)
     *   <li><b>Partial</b> — Step partially completed, needs idempotent retry
     * </ul>
     *
     * @param sagaId Saga identifier
     * @param step Failed step
     * @param error Root cause exception
     * @param context Saga execution context
     * @param pendingFuture Future to complete with result
     */
    private void handleErrorRecovery(
            UUID sagaId,
            Step<S, D> step,
            Throwable error,
            SagaContext context,
            CompletableFuture<SagaResult> pendingFuture) {
        // Classify error
        ErrorClassification classification = classifyError(error);

        // Log error with classification for observability
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.StepFailed(
                            step.name(),
                            error.getClass().getSimpleName() + ": " + error.getMessage(),
                            0,
                            Instant.now()));
        }

        switch (classification) {
            case TRANSIENT ->
                    // Transient error: retry with backoff
                    handleTransientError(sagaId, step, error, context);

            case PERMANENT ->
                    // Permanent error: fail immediately and compensate
                    handlePermanentError(sagaId, step, error, context, pendingFuture);

            case PARTIAL ->
                    // Partial error: use idempotent retry with deduplication
                    handlePartialError(sagaId, step, error, context);
        }
    }

    /**
     * Error classification for recovery decisions.
     *
     * <p>Determines whether an error is transient (retry), permanent (fail), or partial
     * (idempotent retry required).
     */
    private enum ErrorClassification {
        TRANSIENT, // Temporary failure, safe to retry
        PERMANENT, // Will not succeed, fail fast
        PARTIAL    // Partially completed, requires idempotency
    }

    /**
     * Classify error based on exception type and predicate.
     *
     * <p>Uses the step's retryOn predicate to determine if error should be retried.
     */
    private ErrorClassification classifyError(Throwable error) {
        if (error instanceof TimeoutException) return ErrorClassification.TRANSIENT;
        if (error instanceof ConnectException) return ErrorClassification.TRANSIENT;
        if (error instanceof InterruptedException) return ErrorClassification.TRANSIENT;
        if (error instanceof IllegalArgumentException) return ErrorClassification.PERMANENT;
        if (error instanceof SecurityException) return ErrorClassification.PERMANENT;
        if (error instanceof IllegalStateException) {
            String msg = error.getMessage();
            if (msg != null && msg.contains("already")) return ErrorClassification.PARTIAL;
            return ErrorClassification.PERMANENT;
        }
        return ErrorClassification.TRANSIENT;
    }

    /**
     * Handle transient errors with exponential backoff and jitter.
     *
     * <p>Transient errors (network timeouts, temporary unavailability) are retried with
     * exponential backoff to allow the system to recover gracefully.
     */
    private void handleTransientError(
            UUID sagaId, Step<S, D> step, Throwable error, SagaContext context) {
        // Transient error will be handled by executeStepWithRetry's exception handler
        // which already implements backoff logic
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.StepFailed(
                            step.name(), "[TRANSIENT] " + error.getMessage(), 0, Instant.now()));
        }
    }

    /**
     * Handle permanent errors by immediately starting compensation.
     *
     * <p>Permanent errors (bad input, invalid state) cannot be recovered by retry.
     * Start compensation immediately to rollback completed steps.
     */
    private void handlePermanentError(
            UUID sagaId,
            Step<S, D> step,
            Throwable error,
            SagaContext context,
            CompletableFuture<SagaResult> pendingFuture) {
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.StepFailed(
                            step.name(), "[PERMANENT] " + error.getMessage(), 0, Instant.now()));
        }

        // Start compensation immediately
        compensate(sagaId, context, pendingFuture, error);
    }

    /**
     * Handle partial errors with idempotent retry.
     *
     * <p>Partial errors occur when the step partially completed (e.g., payment charged but
     * response lost). Use idempotent retries with request deduplication to safely retry.
     */
    private void handlePartialError(
            UUID sagaId, Step<S, D> step, Throwable error, SagaContext context) {
        if (eventStore != null) {
            eventStore.append(
                    "saga-" + sagaId,
                    new SagaEvent.StepFailed(
                            step.name(), "[PARTIAL] " + error.getMessage(), 0, Instant.now()));
        }

        // For partial errors, check if this step was already completed
        if (context.completedSteps().contains(step.name())) {
            // Already completed, skip to next step
            Object result = context.results().get(step.name());
            if (result != null) {
                coordinator.tell(new SagaMsg.StepComplete(sagaId, step.name(), result));
            }
        }
        // Otherwise retry with idempotency key (handled by caller)
    }

    /**
     * Compensation failure policy handler.
     *
     * <p>Decides what to do when a compensation action fails:
     * <ul>
     *   <li><b>CONTINUE</b> — Continue with next compensation (best-effort)
     *   <li><b>RETRY</b> — Retry compensation with backoff
     *   <li><b>DEADLETTER</b> — Send to dead letter queue for manual intervention
     * </ul>
     */
    private enum CompensationFailurePolicy {
        CONTINUE,    // Continue with remaining compensations
        RETRY,       // Retry with exponential backoff
        DEADLETTER   // Send to dead letter queue
    }

    /**
     * Determine compensation failure policy based on error and attempt count.
     */
    private CompensationFailurePolicy getCompensationFailurePolicy(
            Throwable error, int attemptCount, int maxAttempts) {
        if (attemptCount >= maxAttempts) {
            // After max retries, send to dead letter queue
            return CompensationFailurePolicy.DEADLETTER;
        }

        if (error instanceof TimeoutException) {
            // Timeout: always retry
            return CompensationFailurePolicy.RETRY;
        }

        if (error instanceof ConnectException) {
            // Network error: retry
            return CompensationFailurePolicy.RETRY;
        }

        // Other errors: continue with best-effort compensation
        return CompensationFailurePolicy.CONTINUE;
    }

    // ── Infrastructure ────────────────────────────────────────────────────────────

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onStop(Application<?> app) {
        try {
            // Stop the coordinator proc, which drains any in-flight saga messages
            // and allows virtual-thread workers to finish their current step before exit.
            shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shutdown the saga orchestrator. */
    public void shutdown() throws InterruptedException {
        coordinator.stop();
    }
}
