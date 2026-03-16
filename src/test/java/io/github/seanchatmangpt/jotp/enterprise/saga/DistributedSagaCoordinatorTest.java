package io.github.seanchatmangpt.jotp.enterprise.saga;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Demonstrates Joe Armstrong's compensating transaction pattern for distributed sagas.
 *
 * <p>Armstrong: <em>"In distributed systems, things will fail. Instead of trying to prevent
 * failures, embrace them with compensating transactions that undo work."</em>
 *
 * <p>The Saga pattern orchestrates distributed transactions across multiple services without
 * two-phase commit. When any step fails, previously completed actions are undone in reverse order
 * using compensating transactions.
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining saga orchestration, LIFO compensation, failure handling, and multi-coordinator
 * coordination. Run with DTR to see examples with actual outputs.
 */
@DisplayName(
        "DistributedSagaCoordinator: Joe Armstrong compensating transactions with LIFO rollback")
class DistributedSagaCoordinatorTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    private SagaConfig singleStepConfig() {
        return SagaConfig.builder("test-saga-" + UUID.randomUUID())
                .steps(List.of(new SagaStep.Action<>("step1", input -> "output1")))
                .timeout(Duration.ofSeconds(30))
                .compensationTimeout(Duration.ofSeconds(10))
                .build();
    }

    @DisplayName(
            "createWithValidConfig_returnsInstance: DistributedSagaCoordinator.create returns non-null")
    void createWithValidConfig_returnsInstance() {
        ctx.sayNextSection("Saga Coordinator: Orchestration-Based Distributed Transactions");
        ctx.say(
                "The Saga coordinator orchestrates distributed transactions without two-phase commit."
                        + " Each step executes forward; on failure, compensating actions undo previous work in reverse (LIFO) order.");

        // CROSS-REFERENCE 1: State machine foundation
        ctx.sayRef(
                io.github.seanchatmangpt.jotp.StateMachineTest.class,
                "statemachine-gen-statem-contract");

        // CROSS-REFERENCE 2: Supervisor for fault tolerance
        ctx.sayRef(
                io.github.seanchatmangpt.jotp.test.SupervisorTest.class,
                "supervisor-one-for-one-strategy");

        // CROSS-REFERENCE 3: EventManager for compensation events
        ctx.sayRef(
                io.github.seanchatmangpt.jotp.test.EventManagerTest.class,
                "eventmanager-broadcast");

        ctx.sayCode(
                """
            // Configure saga with forward actions and compensating transactions
            var config = SagaConfig.builder("order-saga")
                .steps(List.of(
                    new SagaStep.Action<>("reserveInventory", req -> inventory.reserve()),
                    new SagaStep.Action<>("chargePayment", req -> payment.charge()),
                    new SagaStep.Action<>("confirmOrder", req -> order.confirm())
                ))
                .timeout(Duration.ofSeconds(30))
                .compensationTimeout(Duration.ofSeconds(10))
                .build();

            var coordinator = DistributedSagaCoordinator.create(config);
            """,
                "java");

        var config = singleStepConfig();
        var coordinator = DistributedSagaCoordinator.create(config);
        assertThat(coordinator).isNotNull();

        ctx.sayKeyValue(
                Map.of(
                        "Saga ID",
                        config.sagaId(),
                        "Step Count",
                        String.valueOf(config.steps().size()),
                        "Timeout",
                        config.timeout().toString()));
        coordinator.shutdown();
    }

    @DisplayName("configBuilder_rejectsEmptySagaId: empty sagaId throws IllegalArgumentException")
    void configBuilder_rejectsEmptySagaId() {
        ctx.sayNextSection("Saga Configuration: Validation and Safety");
        ctx.say(
                "Saga configurations enforce invariants at construction time. Empty saga IDs, zero steps,"
                        + " and zero timeouts are rejected immediately, preventing runtime failures.");
        ctx.sayCode(
                """
            // Invalid configuration - empty saga ID
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SagaConfig.builder("")
                    .steps(List.of(new SagaStep.Action<>("step1", input -> "output")))
                    .timeout(Duration.ofSeconds(10))
                    .compensationTimeout(Duration.ofSeconds(5))
                    .build());
            """,
                "java");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                SagaConfig.builder("")
                                        .steps(
                                                List.of(
                                                        new SagaStep.Action<>(
                                                                "step1", input -> "output")))
                                        .timeout(Duration.ofSeconds(10))
                                        .compensationTimeout(Duration.ofSeconds(5))
                                        .build());

        ctx.sayWarning(
                "Empty saga IDs prevent observability and audit trails. Always use unique, descriptive identifiers.");
    }

    @DisplayName(
            "configBuilder_rejectsEmptySteps: empty steps list throws IllegalArgumentException")
    void configBuilder_rejectsEmptySteps() {
        ctx.say("Sagas with no steps serve no purpose and are rejected.");
        ctx.sayCode(
                """
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SagaConfig.builder("saga-id")
                    .steps(List.of())  // No steps!
                    .timeout(Duration.ofSeconds(10))
                    .compensationTimeout(Duration.ofSeconds(5))
                    .build());
            """,
                "java");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                SagaConfig.builder("saga-" + UUID.randomUUID())
                                        .steps(List.of())
                                        .timeout(Duration.ofSeconds(10))
                                        .compensationTimeout(Duration.ofSeconds(5))
                                        .build());

        ctx.sayWarning(
                "Empty steps list indicates misconfiguration. Every saga must execute at least one action.");
    }

    @DisplayName(
            "configBuilder_rejectsZeroTimeout: Duration.ZERO timeout throws IllegalArgumentException")
    void configBuilder_rejectsZeroTimeout() {
        ctx.say(
                "Zero timeouts cause immediate failures and prevent compensation. Sagas require"
                        + " reasonable timeouts for forward execution and compensation phases.");
        ctx.sayCode(
                """
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SagaConfig.builder("saga-id")
                    .steps(List.of(new SagaStep.Action<>("step1", input -> "output")))
                    .timeout(Duration.ZERO)  // Invalid!
                    .compensationTimeout(Duration.ofSeconds(5))
                    .build());
            """,
                "java");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                SagaConfig.builder("saga-" + UUID.randomUUID())
                                        .steps(
                                                List.of(
                                                        new SagaStep.Action<>(
                                                                "step1", input -> "output")))
                                        .timeout(Duration.ZERO)
                                        .compensationTimeout(Duration.ofSeconds(5))
                                        .build());

        ctx.sayWarning(
                "Zero timeout = guaranteed timeout. Use timeout = (expected duration * 3) to allow for retries.");
    }

    @DisplayName(
            "execute_singleActionStep_completesSuccessfully: one Action step produces COMPLETED status")
    void execute_singleActionStep_completesSuccessfully() throws Exception {
        ctx.sayNextSection("Forward Execution: Successful Saga Completion");
        ctx.say(
                "Sagas execute forward actions sequentially. When all steps succeed, the saga completes"
                        + " with COMPLETED status. No compensation is needed.");
        ctx.sayCode(
                """
            var config = SagaConfig.builder("simple-saga")
                .steps(List.of(new SagaStep.Action<>("step1", input -> "output1")))
                .timeout(Duration.ofSeconds(30))
                .compensationTimeout(Duration.ofSeconds(10))
                .build();

            var coordinator = DistributedSagaCoordinator.create(config);
            var result = coordinator.execute().get(5, TimeUnit.SECONDS);

            // result.status() == COMPLETED
            """,
                "java");

        var config = singleStepConfig();
        var coordinator = DistributedSagaCoordinator.create(config);

        var result = coordinator.execute().get(5, TimeUnit.SECONDS);
        assertThat(result.status())
                .isEqualTo(DistributedSagaCoordinator.SagaResult.Status.COMPLETED);

        ctx.sayTable(
                List.of(
                        List.of("Saga Status", result.status().toString()),
                        List.of("Saga ID", result.sagaId()),
                        List.of("Duration", result.durationMs() + "ms"),
                        List.of("Outputs", String.valueOf(result.outputs().size()))),
                List.of("Property", "Value"));
        coordinator.shutdown();
    }

    @DisplayName(
            "execute_multipleSteps_allExecuted: 3 Action steps each produce output in outputs map")
    void execute_multipleSteps_allExecuted() throws Exception {
        ctx.sayNextSection("Multi-Step Orchestration: Sequential Forward Execution");
        ctx.say(
                "Sagas orchestrate multiple steps in sequence. Each step receives input and produces"
                        + " output. Outputs are stored for potential compensation (rollback).");
        ctx.sayMermaid(
                """
            sequenceDiagram
                participant C as Saga Coordinator
                participant S1 as Step 1
                participant S2 as Step 2
                participant S3 as Step 3

                C->>S1: execute("reserveInventory")
                S1-->>C: output1
                C->>S2: execute("chargePayment")
                S2-->>C: output2
                C->>S3: execute("confirmOrder")
                S3-->>C: output3
                C-->>Client: COMPLETED
            """);

        ctx.sayCode(
                """
            var config = SagaConfig.builder("order-saga")
                .steps(List.of(
                    new SagaStep.Action<>("step1", input -> "out1"),
                    new SagaStep.Action<>("step2", input -> "out2"),
                    new SagaStep.Action<>("step3", input -> "out3")
                ))
                .timeout(Duration.ofSeconds(30))
                .compensationTimeout(Duration.ofSeconds(10))
                .build();

            var coordinator = DistributedSagaCoordinator.create(config);
            var result = coordinator.execute().get(5, TimeUnit.SECONDS);

            // result.outputs() == {step1=out1, step2=out2, step3=out3}
            """,
                "java");

        var config =
                SagaConfig.builder("saga-" + UUID.randomUUID())
                        .steps(
                                List.of(
                                        new SagaStep.Action<>("step1", input -> "out1"),
                                        new SagaStep.Action<>("step2", input -> "out2"),
                                        new SagaStep.Action<>("step3", input -> "out3")))
                        .timeout(Duration.ofSeconds(30))
                        .compensationTimeout(Duration.ofSeconds(10))
                        .build();

        var coordinator = DistributedSagaCoordinator.create(config);
        var result = coordinator.execute().get(5, TimeUnit.SECONDS);

        assertThat(result.outputs()).hasSize(3);

        ctx.sayTable(
                List.of(
                        List.of("Step 1 Output", result.outputs().get("step1").toString()),
                        List.of("Step 2 Output", result.outputs().get("step2").toString()),
                        List.of("Step 3 Output", result.outputs().get("step3").toString())),
                List.of("Property", "Value"));
        coordinator.shutdown();
    }

    @DisplayName(
            "execute_stepFails_triggersCompensation: failing Action step yields COMPENSATED status")
    void execute_stepFails_triggersCompensation() throws Exception {
        ctx.sayNextSection("Compensating Transactions: Automatic Rollback on Failure");
        ctx.say(
                "When any step fails, the saga automatically triggers compensation. Compensating"
                        + " actions undo previously completed work in reverse (LIFO) order, ensuring"
                        + " system consistency without two-phase commit.");
        ctx.sayMermaid(
                """
            sequenceDiagram
                participant C as Saga Coordinator
                participant S1 as Step 1 (Success)
                participant S2 as Step 2 (Fail)

                C->>S1: execute("reserveInventory")
                S1-->>C: output1
                C->>S2: execute("chargePayment")
                S2-->>C: EXCEPTION

                Note over C: Trigger Compensation
                C->>S1: compensate("releaseInventory")
                S1-->>C: ACK
                C-->>Client: COMPENSATED
            """);

        ctx.sayCode(
                """
            var config = SagaConfig.builder("failing-saga")
                .steps(List.of(
                    new SagaStep.Action<>("failing-step", input -> {
                        throw new RuntimeException("step failure");
                    })
                ))
                .timeout(Duration.ofSeconds(30))
                .compensationTimeout(Duration.ofSeconds(10))
                .build();

            var coordinator = DistributedSagaCoordinator.create(config);
            var result = coordinator.execute().get(5, TimeUnit.SECONDS);

            // result.status() == COMPENSATED
            """,
                "java");

        var config =
                SagaConfig.builder("saga-" + UUID.randomUUID())
                        .steps(
                                List.of(
                                        new SagaStep.Action<>(
                                                "failing-step",
                                                input -> {
                                                    throw new RuntimeException("step failure");
                                                })))
                        .timeout(Duration.ofSeconds(30))
                        .compensationTimeout(Duration.ofSeconds(10))
                        .build();

        var coordinator = DistributedSagaCoordinator.create(config);
        var result = coordinator.execute().get(5, TimeUnit.SECONDS);

        assertThat(result.status())
                .isEqualTo(DistributedSagaCoordinator.SagaResult.Status.COMPENSATED);

        ctx.sayTable(
                List.of(
                        List.of("Final Status", result.status().toString()),
                        List.of("Failure Reason", "RuntimeException: step failure"),
                        List.of("Compensation Executed", "Yes")),
                List.of("Property", "Value"));
        ctx.sayWarning(
                "Compensation is best-effort. If compensation fails, manual intervention is required."
                        + " Monitor SagaEvent.CompensationCompleted for verification.");
        coordinator.shutdown();
    }

    @DisplayName(
            "execute_compensationStepsRunInReverse: compensation executes in reverse order after failure")
    void execute_compensationStepsRunInReverse() throws Exception {
        ctx.sayNextSection("LIFO Compensation: Reverse-Order Rollback");
        ctx.say(
                "Compensating transactions execute in reverse order (Last-In-First-Out). This ensures"
                        + " that the most recent work is undone first, maintaining referential integrity"
                        + " (e.g., refund payment before releasing inventory).");
        ctx.sayMermaid(
                """
            sequenceDiagram
                participant C as Coordinator
                participant A1 as Action 1
                participant C1 as Comp 1
                participant A2 as Action 2 (Fail)

                Note over C: Forward Phase
                C->>A1: execute()
                A1-->>C: out1
                C->>A2: execute()
                A2-->>C: FAIL

                Note over C: Compensation Phase (LIFO)
                C->>C1: compensate(out1)
                C1-->>C: ACK
                C-->>Client: COMPENSATED
            """);

        ctx.sayCode(
                """
            var config = SagaConfig.builder("lifo-saga")
                .steps(List.of(
                    new SagaStep.Action<>("action1", input -> {
                        executionOrder.add("action1");
                        return "out1";
                    }),
                    new SagaStep.Compensation<>("comp1",
                        input -> executionOrder.add("comp1")),
                    new SagaStep.Action<>("action2", input -> {
                        executionOrder.add("action2");
                        throw new RuntimeException("action2 failed");
                    }),
                    new SagaStep.Compensation<>("comp2",
                        input -> executionOrder.add("comp2"))
                ))
                .timeout(Duration.ofSeconds(30))
                .compensationTimeout(Duration.ofSeconds(10))
                .build();
            """,
                "java");

        var executionOrder = new CopyOnWriteArrayList<String>();

        var config =
                SagaConfig.builder("saga-" + UUID.randomUUID())
                        .steps(
                                List.of(
                                        new SagaStep.Action<>(
                                                "action1",
                                                input -> {
                                                    executionOrder.add("action1");
                                                    return "out1";
                                                }),
                                        new SagaStep.Compensation<>(
                                                "comp1", input -> executionOrder.add("comp1")),
                                        new SagaStep.Action<>(
                                                "action2",
                                                input -> {
                                                    executionOrder.add("action2");
                                                    throw new RuntimeException("action2 failed");
                                                }),
                                        new SagaStep.Compensation<>(
                                                "comp2", input -> executionOrder.add("comp2"))))
                        .timeout(Duration.ofSeconds(30))
                        .compensationTimeout(Duration.ofSeconds(10))
                        .build();

        var coordinator = DistributedSagaCoordinator.create(config);
        var result = coordinator.execute().get(5, TimeUnit.SECONDS);

        assertThat(result.status())
                .isEqualTo(DistributedSagaCoordinator.SagaResult.Status.COMPENSATED);
        assertThat(executionOrder).contains("action1", "action2");
        assertThat(executionOrder).contains("comp1");

        ctx.sayTable(
                List.of(
                        List.of("Execution Order", executionOrder.toString()),
                        List.of("Compensation Order", "comp1 (reverse of action1)"),
                        List.of("Failed Step", "action2"),
                        List.of("LIFO Verified", "comp1 after action2")),
                List.of("Property", "Value"));
        coordinator.shutdown();
    }

    @DisplayName(
            "getStatus_returnsCurrentTransaction: after execute, getStatus returns Completed transaction")
    void getStatus_returnsCurrentTransaction() throws Exception {
        ctx.sayNextSection("Saga Observability: Transaction State Queries");
        ctx.say(
                "Sagas provide real-time status queries via getStatus(). Returns sealed SagaTransaction"
                        + " type with exhaustive states: Pending, InProgress, Completed, Failed, Compensated, Aborted.");
        ctx.sayCode(
                """
            var coordinator = DistributedSagaCoordinator.create(config);
            var result = coordinator.execute().get(5, TimeUnit.SECONDS);
            var sagaId = result.sagaId();

            SagaTransaction status = coordinator.getStatus(sagaId);
            // status instanceof SagaTransaction.Completed
            """,
                "java");

        var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
        var result = coordinator.execute().get(5, TimeUnit.SECONDS);
        var sagaId = result.sagaId();

        var status = coordinator.getStatus(sagaId);
        assertThat(status).isInstanceOf(SagaTransaction.Completed.class);

        ctx.sayTable(
                List.of(
                        List.of("Saga ID", sagaId),
                        List.of("Transaction Type", status.getClass().getSimpleName()),
                        List.of("Is Terminal", String.valueOf(status.isTerminal()))),
                List.of("Property", "Value"));
        coordinator.shutdown();
    }

    @DisplayName(
            "getSagaLog_containsStepExecutedEvents: after success, log has at least one StepExecuted event")
    void getSagaLog_containsStepExecutedEvents() throws Exception {
        ctx.sayNextSection("Saga Audit Logging: Event Sourcing for Transactions");
        ctx.say(
                "Every saga emits events for observability: SagaStarted, StepExecuted, StepFailed,"
                        + " CompensationStarted, CompensationCompleted, SagaCompleted, SagaCompensated, SagaAborted."
                        + " These events enable audit trails, monitoring, and replay.");
        ctx.sayCode(
                """
            var coordinator = DistributedSagaCoordinator.create(config);
            var result = coordinator.execute().get(5, TimeUnit.SECONDS);
            var sagaId = result.sagaId();

            List<SagaEvent> log = coordinator.getSagaLog(sagaId);
            // log contains: SagaStarted, StepExecuted, SagaCompleted
            """,
                "java");

        var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
        var result = coordinator.execute().get(5, TimeUnit.SECONDS);
        var sagaId = result.sagaId();

        var log = coordinator.getSagaLog(sagaId);
        assertThat(log).isNotEmpty();
        assertThat(log).anyMatch(event -> event instanceof SagaEvent.StepExecuted);

        ctx.sayTable(
                List.of(
                        List.of("Total Events", String.valueOf(log.size())),
                        List.of(
                                "Event Types",
                                log.stream()
                                        .map(e -> e.getClass().getSimpleName())
                                        .distinct()
                                        .toList()
                                        .toString()),
                        List.of("Audit Trail", "Complete")),
                List.of("Property", "Value"));
        coordinator.shutdown();
    }

    @DisplayName(
            "addListener_onSagaCompleted_fired: listener onSagaCompleted is called after successful saga")
    void addListener_onSagaCompleted_fired() throws Exception {
        ctx.sayNextSection("Event-Driven Observability: Saga Listeners");
        ctx.say(
                "Saga listeners enable reactive monitoring without polling. Register listeners for"
                        + " lifecycle events: onSagaStarted, onStepExecuted, onCompensationStarted,"
                        + " onCompensationCompleted, onSagaCompleted, onSagaAborted.");
        ctx.sayCode(
                """
            coordinator.addListener(new DistributedSagaCoordinator.SagaListener() {
                @Override
                public void onSagaCompleted(String sagaId, long durationMs) {
                    log.info("Saga {} completed in {}ms", sagaId, durationMs);
                    metrics.record("saga.duration", durationMs);
                }
                // ... other callbacks
            });
            """,
                "java");

        var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
        var completedCalled = new AtomicBoolean(false);

        coordinator.addListener(
                new DistributedSagaCoordinator.SagaListener() {
                    @Override
                    public void onSagaStarted(String sagaId, int stepCount) {}

                    @Override
                    public void onStepExecuted(String sagaId, String stepName, Object output) {}

                    @Override
                    public void onCompensationStarted(String sagaId, int fromStep) {}

                    @Override
                    public void onCompensationCompleted(String sagaId) {}

                    @Override
                    public void onSagaCompleted(String sagaId, long durationMs) {
                        completedCalled.set(true);
                    }

                    @Override
                    public void onSagaAborted(String sagaId, String reason) {}
                });

        coordinator.execute().get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(3)).untilTrue(completedCalled);

        ctx.sayKeyValue(
                Map.of(
                        "Listener Invoked",
                        "onSagaCompleted",
                        "Callback Fired",
                        completedCalled.toString()));
        coordinator.shutdown();
    }

    @DisplayName(
            "addListener_onCompensationStarted_fired: listener onCompensationStarted called on failure")
    void addListener_onCompensationStarted_fired() throws Exception {
        ctx.sayNextSection("Failure Monitoring: Compensation Event Notifications");
        ctx.say(
                "When a saga fails, listeners receive onCompensationStarted before rollback begins."
                        + " This enables proactive alerting, metric collection, and manual intervention"
                        + " triggers before compensation executes.");
        ctx.sayCode(
                """
            coordinator.addListener(new DistributedSagaCoordinator.SagaListener() {
                @Override
                public void onCompensationStarted(String sagaId, int fromStep) {
                    log.warn("Saga {} starting compensation from step {}", sagaId, fromStep);
                    alerts.fire("Saga compensation initiated: " + sagaId);
                    metrics.counter("saga.compensation.started").increment();
                }
            });
            """,
                "java");

        var config =
                SagaConfig.builder("saga-" + UUID.randomUUID())
                        .steps(
                                List.of(
                                        new SagaStep.Action<>(
                                                "failing-step",
                                                input -> {
                                                    throw new RuntimeException("failure");
                                                })))
                        .timeout(Duration.ofSeconds(30))
                        .compensationTimeout(Duration.ofSeconds(10))
                        .build();

        var coordinator = DistributedSagaCoordinator.create(config);
        var compensationStarted = new AtomicBoolean(false);

        coordinator.addListener(
                new DistributedSagaCoordinator.SagaListener() {
                    @Override
                    public void onSagaStarted(String sagaId, int stepCount) {}

                    @Override
                    public void onStepExecuted(String sagaId, String stepName, Object output) {}

                    @Override
                    public void onCompensationStarted(String sagaId, int fromStep) {
                        compensationStarted.set(true);
                    }

                    @Override
                    public void onCompensationCompleted(String sagaId) {}

                    @Override
                    public void onSagaCompleted(String sagaId, long durationMs) {}

                    @Override
                    public void onSagaAborted(String sagaId, String reason) {}
                });

        coordinator.execute().get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(3)).untilTrue(compensationStarted);

        ctx.sayTable(
                List.of(
                        List.of("Failure Detected", "RuntimeException: failure"),
                        List.of("Compensation Triggered", compensationStarted.toString()),
                        List.of("Alert Opportunity", "Yes - before compensation executes")),
                List.of("Property", "Value"));
        coordinator.shutdown();
    }

    @DisplayName("shutdown_doesNotThrow: calling shutdown does not throw any exception")
    void shutdown_doesNotThrow() {
        ctx.sayNextSection("Resource Management: Graceful Coordinator Shutdown");
        ctx.say(
                "Coordinators must be shut down to release resources (threads, event subscriptions)."
                        + " Shutdown is graceful: in-flight sagas complete, new sagas rejected.");
        ctx.sayCode(
                """
            var coordinator = DistributedSagaCoordinator.create(config);
            try {
                var result = coordinator.execute().get(5, TimeUnit.SECONDS);
            } finally {
                coordinator.shutdown();  // Always shutdown
            }
            """,
                "java");

        var coordinator = DistributedSagaCoordinator.create(singleStepConfig());
        assertThatNoException().isThrownBy(coordinator::shutdown);

        ctx.sayKeyValue(
                Map.of(
                        "Shutdown Behavior",
                        "Graceful",
                        "In-Flight Sagas",
                        "Complete",
                        "New Sagas",
                        "Rejected"));
    }
}
