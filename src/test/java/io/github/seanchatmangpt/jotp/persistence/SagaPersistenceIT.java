package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.DurableState;
import io.github.seanchatmangpt.jotp.EventSourcingAuditLog;
import io.github.seanchatmangpt.jotp.PersistenceConfig;
import io.github.seanchatmangpt.jotp.PersistenceConfig.DurabilityLevel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for saga crash recovery and compensation.
 *
 * <p>Tests comprehensive saga persistence patterns including:
 *
 * <ul>
 *   <li>Saga state persistence across crashes
 *   <li>Compensation execution on recovery after crash
 *   <li>Atomic state transitions during saga execution
 *   <li>Multi-step saga recovery mid-execution
 *   <li>Compensation log verification
 * </ul>
 *
 * <p><strong>Saga Crash Scenarios:</strong>
 *
 * <ol>
 *   <li><strong>Crash During Execution:</strong> Saga crashes while executing a step
 *   <li><strong>Crash During Compensation:</strong> Saga crashes while compensating
 *   <li><strong>Recovery with Compensation:</strong> On restart, execute compensation for completed
 *       steps
 * </ol>
 *
 * @see DurableState
 * @see EventSourcingAuditLog
 * @see io.github.seanchatmangpt.jotp.DistributedSagaCoordinator
 */
@DisplayName("Saga Persistence Integration Tests")
class SagaPersistenceIT implements WithAssertions {

    @TempDir Path tempDir;

    private PersistenceConfig config;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();

        config =
                PersistenceConfig.builder()
                        .durabilityLevel(DurabilityLevel.DURABLE)
                        .persistenceDirectory(tempDir)
                        .snapshotInterval(1)
                        .eventsPerSnapshot(3) // Snapshot frequently for testing
                        .syncWrites(true)
                        .build();
    }

    @AfterEach
    void tearDown() {
        ApplicationController.reset();
    }

    // ── Test Domain: Order Fulfillment Saga ───────────────────────────────────────

    sealed interface SagaState
            permits SagaState.NotStarted,
                    SagaState.InProgress,
                    SagaState.Completed,
                    SagaState.Failed {
        record NotStarted() implements SagaState {}

        record InProgress(
                String sagaId,
                List<SagaStep> completedSteps,
                SagaStep currentStep,
                List<SagaStep> remainingSteps)
                implements SagaState {}

        record Completed(String sagaId, Instant completedAt, List<SagaStep> allSteps)
                implements SagaState {}

        record Failed(
                String sagaId,
                List<SagaStep> completedSteps,
                String failureReason,
                Instant failedAt,
                List<String> compensationLog)
                implements SagaState {}
    }

    sealed interface SagaEvent
            permits SagaEvent.Start,
                    SagaEvent.StepCompleted,
                    SagaEvent.StepFailed,
                    SagaEvent.Compensate {
        record Start(String sagaId, List<SagaStep> steps) implements SagaEvent {}

        record StepCompleted(String sagaId, SagaStep step, String result) implements SagaEvent {}

        record StepFailed(String sagaId, SagaStep step, String reason) implements SagaEvent {}

        record Compensate(String sagaId) implements SagaEvent {}
    }

    record SagaStep(
            String stepId,
            String serviceName,
            Duration timeout,
            Supplier<CompletionStage<MapResult>> action,
            Consumer<MapResult> compensation) {

        @Override
        public String toString() {
            return "Step{" + stepId + ", service=" + serviceName + "}";
        }
    }

    record MapResult(String stepId, String status, String data) {}

    // ── Tests ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should recover saga state after crash during execution")
    void shouldRecoverSagaStateAfterCrashDuringExecution() throws Exception {
        // Create saga steps
        var steps = createOrderFulfillmentSteps();

        // Phase 1: Start saga and execute first step
        var saga1 =
                DurableState.<SagaState>builder()
                        .entityId("order-saga-1")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        // Start saga
        var startEvent = new SagaEvent.Start("order-123", steps);
        saga1.recordEvent(startEvent);
        saga1.saveCurrentState();

        // Execute first step
        var inProgress =
                new SagaState.InProgress(
                        "order-123", List.of(), steps.get(0), steps.subList(1, steps.size()));
        saga1.updateState(inProgress);
        saga1.recordEvent(new SagaEvent.StepCompleted("order-123", steps.get(0), "reserved"));
        saga1.saveCurrentState();

        // Verify state
        assertThat(saga1.getCurrentState()).isInstanceOf(SagaState.InProgress.class);

        // Phase 2: Simulate crash (no graceful shutdown)
        var stateBeforeCrash = saga1.getCurrentState();

        // Phase 3: Recover after crash
        var saga2 =
                DurableState.<SagaState>builder()
                        .entityId("order-saga-1")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        SagaState recoveredState = saga2.recover(() -> new SagaState.NotStarted());

        // Verify saga recovered with in-progress state
        assertThat(recoveredState).isInstanceOf(SagaState.InProgress.class);
        var recoveredInProgress = (SagaState.InProgress) recoveredState;
        assertThat(recoveredInProgress.sagaId()).isEqualTo("order-123");
        assertThat(recoveredInProgress.completedSteps()).hasSize(1);
    }

    @Test
    @DisplayName("Should execute compensation on recovery after crash")
    void shouldExecuteCompensationOnRecoveryAfterCrash() throws Exception {
        var compensationLog = new ArrayList<String>();

        var steps = createCompensatableSteps(compensationLog);

        // Phase 1: Execute 2 steps, then crash
        var saga1 =
                DurableState.<SagaState>builder()
                        .entityId("compensation-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        // Start and execute step 1
        saga1.updateState(
                new SagaState.InProgress(
                        "saga-1", List.of(), steps.get(0), steps.subList(1, steps.size())));
        saga1.recordEvent(new SagaEvent.StepCompleted("saga-1", steps.get(0), "done"));
        saga1.saveCurrentState();

        // Execute step 2
        var inProgress =
                new SagaState.InProgress(
                        "saga-1",
                        List.of(steps.get(0)),
                        steps.get(1),
                        steps.subList(2, steps.size()));
        saga1.updateState(inProgress);
        saga1.recordEvent(new SagaEvent.StepCompleted("saga-1", steps.get(1), "done"));
        saga1.saveCurrentState();

        // Crash before step 3

        // Phase 2: Recover and compensate
        var saga2 =
                DurableState.<SagaState>builder()
                        .entityId("compensation-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        SagaState recoveredState = saga2.recover(() -> new SagaState.NotStarted());

        // Verify in-progress state with 2 completed steps
        assertThat(recoveredState).isInstanceOf(SagaState.InProgress.class);
        var inProgressRecovered = (SagaState.InProgress) recoveredState;
        assertThat(inProgressRecovered.completedSteps()).hasSize(2);

        // Simulate step 3 failure triggering compensation
        var failedState =
                new SagaState.Failed(
                        "saga-1",
                        inProgressRecovered.completedSteps(),
                        "Step 3 failed",
                        Instant.now(),
                        new ArrayList<>());

        saga2.updateState(failedState);
        saga2.recordEvent(new SagaEvent.Compensate("saga-1"));

        // Execute compensations in reverse order
        var completedSteps = inProgressRecovered.completedSteps();
        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            var step = completedSteps.get(i);
            step.compensation().accept(new MapResult(step.stepId(), "compensated", ""));
            compensationLog.add("COMPENSATED: " + step.stepId());
        }

        saga2.saveCurrentState();

        // Verify compensations executed
        assertThat(compensationLog).containsExactly("COMPENSATED: step-2", "COMPENSATED: step-1");
    }

    @Test
    @DisplayName("Should verify atomic state transitions during saga execution")
    void shouldVerifyAtomicStateTransitionsDuringSagaExecution() throws Exception {
        var saga =
                DurableState.<SagaState>builder()
                        .entityId("atomic-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        var steps = createOrderFulfillmentSteps();

        // Verify initial state
        assertThat(saga.getCurrentState()).isInstanceOf(SagaState.NotStarted.class);

        // Transition to InProgress (atomic)
        var inProgress =
                new SagaState.InProgress(
                        "atomic-saga-1", List.of(), steps.get(0), steps.subList(1, steps.size()));
        saga.updateState(inProgress);
        saga.recordEvent(new SagaEvent.StepCompleted("atomic-saga-1", steps.get(0), "done"));

        assertThat(saga.getCurrentState()).isInstanceOf(SagaState.InProgress.class);

        // Transition to Completed (atomic)
        var completed =
                new SagaState.Completed("atomic-saga-1", Instant.now(), List.of(steps.get(0)));
        saga.updateState(completed);
        saga.recordEvent(new SagaEvent.Start("atomic-saga-1", steps));

        assertThat(saga.getCurrentState()).isInstanceOf(SagaState.Completed.class);

        // Verify persistence
        saga.saveCurrentState();

        // Recover and verify atomicity
        var recovered =
                DurableState.<SagaState>builder()
                        .entityId("atomic-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        SagaState recoveredState = recovered.recover(() -> new SagaState.NotStarted());

        // Should be in Completed state, not intermediate state
        assertThat(recoveredState).isInstanceOf(SagaState.Completed.class);
    }

    @Test
    @DisplayName("Should recover mid-saga and continue execution")
    void shouldRecoverMidSagaAndContinueExecution() throws Exception {
        var steps = createOrderFulfillmentSteps();

        // Phase 1: Execute first 2 of 4 steps
        var saga1 =
                DurableState.<SagaState>builder()
                        .entityId("mid-saga-recovery")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        // Step 1 completed
        var inProgress1 =
                new SagaState.InProgress(
                        "mid-saga", List.of(), steps.get(0), steps.subList(1, steps.size()));
        saga1.updateState(inProgress1);
        saga1.recordEvent(new SagaEvent.StepCompleted("mid-saga", steps.get(0), "done"));

        // Step 2 completed
        var inProgress2 =
                new SagaState.InProgress(
                        "mid-saga",
                        List.of(steps.get(0)),
                        steps.get(1),
                        steps.subList(2, steps.size()));
        saga1.updateState(inProgress2);
        saga1.recordEvent(new SagaEvent.StepCompleted("mid-saga", steps.get(1), "done"));
        saga1.saveCurrentState();

        // Phase 2: Crash and recover
        var saga2 =
                DurableState.<SagaState>builder()
                        .entityId("mid-saga-recovery")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        SagaState recoveredState = saga2.recover(() -> new SagaState.NotStarted());

        // Verify we're at step 3
        assertThat(recoveredState).isInstanceOf(SagaState.InProgress.class);
        var inProgressRecovered = (SagaState.InProgress) recoveredState;
        assertThat(inProgressRecovered.completedSteps()).hasSize(2);
        assertThat(inProgressRecovered.currentStep().stepId()).isEqualTo("step-3");

        // Continue execution: Complete step 3
        saga2.recordEvent(new SagaEvent.StepCompleted("mid-saga", steps.get(2), "done"));

        var inProgress3 =
                new SagaState.InProgress(
                        "mid-saga",
                        List.of(steps.get(0), steps.get(1), steps.get(2)),
                        steps.get(3),
                        List.of());
        saga2.updateState(inProgress3);

        // Complete step 4 (final)
        saga2.recordEvent(new SagaEvent.StepCompleted("mid-saga", steps.get(3), "done"));

        var completed = new SagaState.Completed("mid-saga", Instant.now(), steps);
        saga2.updateState(completed);
        saga2.saveCurrentState();

        // Verify saga completed
        assertThat(saga2.getCurrentState()).isInstanceOf(SagaState.Completed.class);
    }

    @Test
    @DisplayName("Should verify compensation log after crash")
    void shouldVerifyCompensationLogAfterCrash() throws Exception {
        var compensationLog = new ArrayList<String>();
        var steps = createCompensatableSteps(compensationLog);

        // Phase 1: Execute steps, fail, compensate
        var saga1 =
                DurableState.<SagaState>builder()
                        .entityId("compensation-log-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        // Execute 3 steps
        var completedSteps = new ArrayList<SagaStep>();
        for (int i = 0; i < 3; i++) {
            saga1.recordEvent(new SagaEvent.StepCompleted("log-saga", steps.get(i), "done"));
            completedSteps.add(steps.get(i));
        }

        saga1.saveCurrentState();

        // Fail and compensate
        var failed =
                new SagaState.Failed(
                        "log-saga",
                        completedSteps,
                        "Payment failed",
                        Instant.now(),
                        new ArrayList<>(compensationLog));

        // Execute compensations
        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            completedSteps
                    .get(i)
                    .compensation()
                    .accept(new MapResult(steps.get(i).stepId(), "compensated", ""));
            compensationLog.add("COMPENSATED: " + steps.get(i).stepId());
        }

        failed =
                new SagaState.Failed(
                        "log-saga",
                        completedSteps,
                        "Payment failed",
                        Instant.now(),
                        new ArrayList<>(compensationLog));
        saga1.updateState(failed);
        saga1.saveCurrentState();

        // Phase 2: Recover and verify compensation log
        var saga2 =
                DurableState.<SagaState>builder()
                        .entityId("compensation-log-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        SagaState recoveredState = saga2.recover(() -> new SagaState.NotStarted());

        assertThat(recoveredState).isInstanceOf(SagaState.Failed.class);
        var failedRecovered = (SagaState.Failed) recoveredState;

        // Verify compensation log contains all compensations
        assertThat(failedRecovered.compensationLog())
                .containsExactly(
                        "COMPENSATED: step-3", "COMPENSATED: step-2", "COMPENSATED: step-1");
    }

    @Test
    @DisplayName("Should handle multiple saga crashes with recovery")
    void shouldHandleMultipleSagaCrashesWithRecovery() throws Exception {
        var steps = createOrderFulfillmentSteps();

        // Cycle 1: Start saga, crash
        var saga1 =
                DurableState.<SagaState>builder()
                        .entityId("multi-crash-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        saga1.updateState(
                new SagaState.InProgress(
                        "multi-crash", List.of(), steps.get(0), steps.subList(1, steps.size())));
        saga1.saveCurrentState();

        // Crash 1: Recover
        var saga2 =
                DurableState.<SagaState>builder()
                        .entityId("multi-crash-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        saga2.recover(() -> new SagaState.NotStarted());

        // Cycle 2: Execute step, crash again
        saga2.recordEvent(new SagaEvent.StepCompleted("multi-crash", steps.get(0), "done"));
        saga2.updateState(
                new SagaState.InProgress(
                        "multi-crash",
                        List.of(steps.get(0)),
                        steps.get(1),
                        steps.subList(2, steps.size())));
        saga2.saveCurrentState();

        // Crash 2: Recover
        var saga3 =
                DurableState.<SagaState>builder()
                        .entityId("multi-crash-saga")
                        .config(config)
                        .initialState(new SagaState.NotStarted())
                        .build();

        SagaState finalState = saga3.recover(() -> new SagaState.NotStarted());

        // Verify saga recovered at step 2
        assertThat(finalState).isInstanceOf(SagaState.InProgress.class);
        var inProgress = (SagaState.InProgress) finalState;
        assertThat(inProgress.completedSteps()).hasSize(1);
        assertThat(inProgress.currentStep().stepId()).isEqualTo("step-2");
    }

    // ── Helper Methods ──────────────────────────────────────────────────────────

    private List<SagaStep> createOrderFulfillmentSteps() {
        var steps = new ArrayList<SagaStep>();

        // Step 1: Reserve Inventory
        steps.add(
                new SagaStep(
                        "step-1",
                        "InventoryService",
                        Duration.ofSeconds(5),
                        () ->
                                CompletableFuture.completedFuture(
                                        new MapResult("step-1", "success", "reserved")),
                        result -> {
                            // Compensate: Release reservation
                        }));

        // Step 2: Process Payment
        steps.add(
                new SagaStep(
                        "step-2",
                        "PaymentService",
                        Duration.ofSeconds(5),
                        () ->
                                CompletableFuture.completedFuture(
                                        new MapResult("step-2", "success", "paid")),
                        result -> {
                            // Compensate: Refund payment
                        }));

        // Step 3: Ship Order
        steps.add(
                new SagaStep(
                        "step-3",
                        "ShippingService",
                        Duration.ofSeconds(5),
                        () ->
                                CompletableFuture.completedFuture(
                                        new MapResult("step-3", "success", "shipped")),
                        result -> {
                            // Compensate: Cancel shipment
                        }));

        // Step 4: Send Confirmation
        steps.add(
                new SagaStep(
                        "step-4",
                        "NotificationService",
                        Duration.ofSeconds(5),
                        () ->
                                CompletableFuture.completedFuture(
                                        new MapResult("step-4", "success", "notified")),
                        result -> {
                            // Compensate: Send cancellation notice
                        }));

        return steps;
    }

    private List<SagaStep> createCompensatableSteps(List<String> compensationLog) {
        var steps = new ArrayList<SagaStep>();

        for (int i = 1; i <= 3; i++) {
            String stepId = "step-" + i;
            steps.add(
                    new SagaStep(
                            stepId,
                            "Service-" + i,
                            Duration.ofSeconds(5),
                            () ->
                                    CompletableFuture.completedFuture(
                                            new MapResult(stepId, "success", "done")),
                            result -> {
                                compensationLog.add("COMPENSATED: " + stepId);
                            }));
        }

        return steps;
    }
}
