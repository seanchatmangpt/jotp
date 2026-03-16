package io.github.seanchatmangpt.jotp.enterprise.saga;

import java.util.function.Function;

/**
 * Sealed interface for saga execution steps and compensating actions.
 *
 * <p>Represents forward actions (execute during saga) and compensating actions (execute on
 * rollback) for distributed saga transactions. Steps are executed sequentially, and compensations
 * are run in reverse order when a step fails. Each step has a unique name for observability and
 * debugging.
 *
 * <h2>Step Types:</h2>
 *
 * <ul>
 *   <li><b>Action<I, O></b>: Forward action that transforms input to output. Executed during saga
 *       forward phase. Takes input I, returns output O. Output stored for potential compensation.
 *       Example: Reserve inventory (input: productId, quantity; output: reservationId)
 *   <li><b>Compensation<S></b>: Compensating action that undoes a previous action. Executed during
 *       saga rollback phase in reverse order. Takes previous action's output as input. Returns
 *       void. Example: Release inventory (input: reservationId; no output)
 *   <li><b>Conditional</b>: Branching logic for conditional execution. Executes one branch or
 *       another based on predicate condition. Allows for complex saga workflows with if/else logic.
 *       Example: If order > $1000, require manual approval, else auto-approve
 * </ul>
 *
 * <h2>Execution Order:</h2>
 *
 * <pre>
 * Forward phase (execute all actions):
 *   Action 1 → Action 2 → Action 3 → Action 4
 *
 * If Action 3 fails, compensation phase (reverse order):
 *   Compensation 2 → Compensation 1
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Define forward actions
 * SagaStep reserveInventory = new SagaStep.Action<ReservationRequest, ReservationResult>(
 *     "reserveInventory",
 *     input -> inventoryService.reserve(input.productId, input.quantity)
 * );
 *
 * SagaStep chargePayment = new SagaStep.Action<PaymentRequest, PaymentResult>(
 *     "chargePayment",
 *     input -> paymentService.charge(input.method, input.amount)
 * );
 *
 * SagaStep scheduleShipment = new SagaStep.Action<ShipmentRequest, ShipmentResult>(
 *     "scheduleShipment",
 *     input -> shippingService.schedule(input.address, input.items)
 * );
 *
 * // Define compensations (use previous action's output)
 * SagaStep refundPayment = new SagaStep.Compensation<PaymentResult>(
 *     "refundPayment",
 *     result -> paymentService.refund(result.transactionId)
 * );
 *
 * SagaStep releaseInventory = new SagaStep.Compensation<ReservationResult>(
 *     "releaseInventory",
 *     result -> inventoryService.release(result.reservationId)
 * );
 *
 * // Conditional step
 * SagaStep approvalStep = new SagaStep.Conditional(
 *     "approvalCheck",
 *     order -> order.amount() > 1000,
 *     new SagaStep.Action<>("manualApproval", input -> approvalService.request(input)),
 *     new SagaStep.Action<>("autoApproval", input -> approvalService.autoApprove(input))
 * );
 * }</pre>
 *
 * <h2>Step Design Guidelines:</h2>
 *
 * <ul>
 *   <li><b>Idempotent</b>: Steps should be safe to retry (no side effects on duplicate execution)
 *   <li><b>Atomic</b>: Each step should do one thing (single responsibility)
 *   <li><b>Serializable</b>: Inputs and outputs must be serializable for logging and replay
 *   <li><b>Compensatable</b>: Every action should have a compensating action (except last step)
 *   <li><b>Fast</b>: Steps should complete quickly (timeout per step = total timeout / steps)
 * </ul>
 *
 * <h2>Error Handling:</h2>
 *
 * <ul>
 *   <li>Action throws exception → Compensation triggered
 *   <li>Compensation throws exception → Logged but continues (best-effort)
 *   <li>Step timeout → Saga aborted, compensation triggered
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link SagaConfig} to define saga workflow
 *   <li>Executed by {@link DistributedSagaCoordinator} in process-based coordinator
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Generic types I/O enable type-safe input/output passing
 * </ul>
 *
 * @see SagaConfig
 * @see DistributedSagaCoordinator
 * @see SagaTransaction
 * @since 1.0
 */
public sealed interface SagaStep
        permits SagaStep.Action, SagaStep.Compensation, SagaStep.Conditional {

    /**
     * Forward action: Execute this step.
     *
     * @param name Step identifier
     * @param task Function from input to output
     */
    record Action<I, O>(String name, Function<I, O> task) implements SagaStep {}

    /**
     * Compensation action: Undo this step on rollback.
     *
     * @param name Step identifier
     * @param task Function to execute on rollback
     */
    record Compensation<S>(String name, java.util.function.Consumer<S> task) implements SagaStep {}

    /**
     * Conditional step: Execute one branch or another.
     *
     * @param name Step identifier
     * @param condition Predicate to check
     * @param ifTrue Step to execute if condition is true
     * @param ifFalse Step to execute if condition is false
     */
    record Conditional(
            String name,
            java.util.function.Predicate<?> condition,
            SagaStep ifTrue,
            SagaStep ifFalse)
            implements SagaStep {}

    default String name() {
        return switch (this) {
            case Action(var n, _) -> n;
            case Compensation(var n, _) -> n;
            case Conditional(var n, _, _, _) -> n;
        };
    }
}
