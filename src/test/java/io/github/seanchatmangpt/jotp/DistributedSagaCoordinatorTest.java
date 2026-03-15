package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Comprehensive test suite for {@link DistributedSagaCoordinator}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Multi-step saga execution (Order → Payment → Inventory → Shipping)
 *   <li>Compensation logic (rollback on failure)
 *   <li>Timeout handling with service calls
 *   <li>State machine transitions and saga lifecycle
 *   <li>Service registration and lookup
 *   <li>Error recovery and resilience
 * </ul>
 * <p><strong>Order Fulfillment Saga Example:</strong>
 * <pre>{@code
 * Step 1: PaymentService.processPayment(amount)
 *   - Success: move to InventoryState
 *   - Failure: compensate with refund (no-op for this saga)
 * Step 2: InventoryService.reserve(items)
 *   - Success: move to ShippingState
 *   - Failure: compensate with refund (cancel payment)
 * Step 3: ShippingService.ship(address)
 *   - Success: move to CompletedState
 *   - Failure: compensate with refund + restock
 * }</pre>
 * @see DistributedSagaCoordinator
 * @see DistributedSagaCoordinator.SagaTransition
 * @see DistributedSagaCoordinator.CompensationAction
 */
@DisplayName("DistributedSagaCoordinator: Saga Orchestration with Compensation")
class DistributedSagaCoordinatorTest {
    // ──────────────────────────────────────────────────────────────────────────
    // Test Domain Model: Order Saga
    /** Sealed hierarchy of order saga states. */
    sealed interface OrderState
            permits OrderState.Init,
                    OrderState.PaymentProcessing,
                    OrderState.InventoryReserving,
                    OrderState.Shipping,
                    OrderState.Completed,
                    OrderState.Failed {
        record Init() implements OrderState {}
        record PaymentProcessing(double amount) implements OrderState {}
        record InventoryReserving(double amount, String paymentId) implements OrderState {}
        record Shipping(double amount, String paymentId, String reservationId)
                implements OrderState {}
        record Completed(double amount, String paymentId, String reservationId, String shipmentId)
        record Failed(String reason) implements OrderState {}
    }
    /** Sealed hierarchy of order events. */
    sealed interface OrderEvent
            permits OrderEvent.PlaceOrder,
                    OrderEvent.PaymentSucceeded,
                    OrderEvent.PaymentFailed,
                    OrderEvent.InventoryReserved,
                    OrderEvent.InventoryFailed,
                    OrderEvent.ShipmentCreated,
                    OrderEvent.ShipmentFailed {
        record PlaceOrder(double amount) implements OrderEvent {}
        record PaymentSucceeded(String paymentId) implements OrderEvent {}
        record PaymentFailed(String reason) implements OrderEvent {}
        record InventoryReserved(String reservationId) implements OrderEvent {}
        record InventoryFailed(String reason) implements OrderEvent {}
        record ShipmentCreated(String shipmentId) implements OrderEvent {}
        record ShipmentFailed(String reason) implements OrderEvent {}
    /** Order saga data — carries mutable context across steps. */
    record OrderData(String orderId, double amount, List<String> compensationLog) {
        OrderData(String orderId, double amount) {
            this(orderId, amount, new ArrayList<>());
        }
        OrderData withCompensation(String step) {
            var log = new ArrayList<>(compensationLog);
            log.add(step);
            return new OrderData(orderId, amount, log);
    // Test Service Implementations
    /** Simulated payment service. */
    static class PaymentService {
        @BeforeEach
        void setUp() {
            ApplicationController.reset();
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final boolean shouldFail;
        PaymentService(boolean shouldFail) {
            this.shouldFail = shouldFail;
        Result<String, String> processPayment(double amount) {
            callCount.incrementAndGet();
            if (shouldFail) {
                return Result.err("Payment processing failed");
            }
            return Result.ok("PAY-" + System.currentTimeMillis());
        Result<Void, String> refund(String paymentId) {
            return Result.ok(null);
        int getCallCount() {
            return callCount.get();
    /** Simulated inventory service. */
    static class InventoryService {
        InventoryService(boolean shouldFail) {
        Result<String, String> reserve(List<String> items) {
                return Result.err("Inventory unavailable");
            return Result.ok("RES-" + System.currentTimeMillis());
        Result<Void, String> restock(String reservationId) {
    /** Simulated shipping service. */
    static class ShippingService {
        ShippingService(boolean shouldFail) {
        Result<String, String> ship(String address) {
                return Result.err("Shipping unavailable");
            return Result.ok("SHIP-" + System.currentTimeMillis());
        Result<Void, String> cancelShipment(String shipmentId) {
    // Test Cases
    @Test
    @DisplayName("Simple saga: PlaceOrder transition to PaymentProcessing state")
    void testBasicOrderTransition() {
        DistributedSagaCoordinator<OrderState, OrderEvent, OrderData> coordinator =
                new DistributedSagaCoordinator<>(
                        "order-saga-1",
                        new OrderState.Init(),
                        new OrderData("ORD-001", 100.0),
                        (state, event) ->
                                switch (state) {
                                    case OrderState.Init() ->
                                            switch (event) {
                                                case OrderEvent.PlaceOrder(var amount) ->
                                                        DistributedSagaCoordinator.SagaTransition
                                                                .nextStep(
                                                                        new OrderState
                                                                                .PaymentProcessing(
                                                                                amount),
                                                                        new OrderData(
                                                                                "ORD-001", amount));
                                                default ->
                                                                .fail(
                                                                        "Unexpected event in Init state");
                                            };
                                    default ->
                                            DistributedSagaCoordinator.SagaTransition.fail(
                                                    "Unknown state");
                                },
                        Duration.ofSeconds(5));
        // Verify initial state
        assertThat(coordinator.state()).isInstanceOf(OrderState.Init.class);
        assertThat(coordinator.data().orderId()).isEqualTo("ORD-001");
        assertThat(coordinator.isRunning()).isTrue();
        // Apply a transition
        DistributedSagaCoordinator.SagaTransition<OrderState, OrderData> transition =
                DistributedSagaCoordinator.SagaTransition.nextStep(
                        new OrderState.PaymentProcessing(100.0), new OrderData("ORD-001", 100.0));
        boolean success = coordinator.applyTransition(transition);
        assertThat(success).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.PaymentProcessing.class);
    @DisplayName("Compensation: rollback completed steps on failure")
    void testCompensationOnFailure() {
        var paymentService = new PaymentService(false);
        var inventoryService = new InventoryService(true); // Will fail
        var shippingService = new ShippingService(false);
        DistributedSagaCoordinator<OrderState, Object, OrderData> coordinator =
                        "order-saga-2",
                        new OrderData("ORD-002", 150.0),
                                DistributedSagaCoordinator.SagaTransition.nextStep(
                                        state, new OrderData("ORD-002", 150.0)),
        // Log compensation actions
        DistributedSagaCoordinator.CompensationAction<OrderData> paymentCompensation =
                DistributedSagaCoordinator.CompensationAction.rollback(
                        "refund-payment",
                        data -> {
                            paymentService.refund("PAY-123");
                            return Result.ok(data.withCompensation("payment-refunded"));
                        });
        DistributedSagaCoordinator.CompensationAction<OrderData> inventoryCompensation =
                        "restock-inventory",
                            inventoryService.restock("RES-123");
                            return Result.ok(data.withCompensation("inventory-restocked"));
        coordinator.compensationLog().add(paymentCompensation);
        coordinator.compensationLog().add(inventoryCompensation);
        // Trigger compensation
        Result<OrderData, String> compensationResult = coordinator.compensate("Inventory failed");
        assertThat(compensationResult).isInstanceOf(Result.Ok.class);
        OrderData compensatedData = ((Result.Ok<OrderData, String>) compensationResult).value();
        assertThat(compensatedData.compensationLog())
                .contains("payment-refunded", "inventory-restocked");
        assertThat(paymentService.getCallCount()).isGreaterThan(0);
        assertThat(inventoryService.getCallCount()).isGreaterThan(0);
    @DisplayName(
            "State transitions: Init → PaymentProcessing → InventoryReserving → Shipping →"
                    + " Completed")
    void testMultiStepSagaTransitions() {
                        "order-saga-3",
                        new OrderData("ORD-003", 200.0),
                                            DistributedSagaCoordinator.SagaTransition.nextStep(
                                                    new OrderState.PaymentProcessing(200.0),
                                                    new OrderData("ORD-003", 200.0));
                                    case OrderState.PaymentProcessing(var amount) ->
                                                    new OrderState.InventoryReserving(
                                                            amount, "PAY-001"),
                                                    new OrderData("ORD-003", amount));
                                    case OrderState.InventoryReserving(var amount, var paymentId) ->
                                                    new OrderState.Shipping(
                                                            amount, paymentId, "RES-001"),
                                    case OrderState.Shipping(
                                                    var amount,
                                                    var paymentId,
                                                    var reservationId) ->
                                            DistributedSagaCoordinator.SagaTransition.complete(
                                                    new OrderState.Completed(
                                                            amount,
                                                            paymentId,
                                                            reservationId,
                                                            "SHIP-001"),
        // Step 1: Init -> PaymentProcessing
        DistributedSagaCoordinator.SagaTransition<OrderState, OrderData> trans1 =
                        new OrderState.PaymentProcessing(200.0), new OrderData("ORD-003", 200.0));
        assertThat(coordinator.applyTransition(trans1)).isTrue();
        // Step 2: PaymentProcessing -> InventoryReserving
        DistributedSagaCoordinator.SagaTransition<OrderState, OrderData> trans2 =
                        new OrderState.InventoryReserving(200.0, "PAY-001"),
                        new OrderData("ORD-003", 200.0));
        assertThat(coordinator.applyTransition(trans2)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.InventoryReserving.class);
        // Step 3: InventoryReserving -> Shipping
        DistributedSagaCoordinator.SagaTransition<OrderState, OrderData> trans3 =
                        new OrderState.Shipping(200.0, "PAY-001", "RES-001"),
        assertThat(coordinator.applyTransition(trans3)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.Shipping.class);
        // Step 4: Shipping -> Completed
        DistributedSagaCoordinator.SagaTransition<OrderState, OrderData> trans4 =
                DistributedSagaCoordinator.SagaTransition.complete(
                        new OrderState.Completed(200.0, "PAY-001", "RES-001", "SHIP-001"),
        assertThat(coordinator.applyTransition(trans4)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.Completed.class);
        assertThat(coordinator.isRunning()).isFalse();
    @DisplayName("Service registration and lookup")
    void testServiceRegistration() {
                        "order-saga-4",
                        new OrderData("ORD-004", 100.0),
                                        state, new OrderData("ORD-004", 100.0)),
        // Create a simple Proc-based service
        var paymentProc = new Proc<>(paymentService, (service, msg) -> service);
        var paymentRef = new ProcRef<>(paymentProc);
        // Register service by reference
        coordinator.registerService("payment", paymentRef);
        // Query registered services (stored internally)
        assertThat(coordinator.name()).isEqualTo("order-saga-4");
    @DisplayName("Compensation log tracks executed steps")
    void testCompensationLogTracking() {
                        "order-saga-5",
                        new OrderData("ORD-005", 100.0),
                                        state, new OrderData("ORD-005", 100.0)),
        // Add compensation actions
        DistributedSagaCoordinator.CompensationAction<OrderData> action1 =
                        "step-1", data -> Result.ok(data));
        DistributedSagaCoordinator.CompensationAction<OrderData> action2 =
                DistributedSagaCoordinator.CompensationAction.noop("step-2");
        var log = coordinator.compensationLog();
        log.add(action1);
        log.add(action2);
        assertThat(coordinator.compensationLog()).hasSize(2);
        assertThat(coordinator.compensationLog().get(0))
                .isInstanceOf(DistributedSagaCoordinator.CompensationAction.Rollback.class);
        assertThat(coordinator.compensationLog().get(1))
                .isInstanceOf(DistributedSagaCoordinator.CompensationAction.Noop.class);
    @DisplayName("Saga failure and stop reason tracking")
    void testSagaFailureAndStopReason() {
                        "order-saga-6",
                        new OrderData("ORD-006", 100.0),
                                DistributedSagaCoordinator.SagaTransition.fail(
                                        "Payment service unavailable"),
        assertThat(coordinator.stopReason()).isNull();
        coordinator.stop("Order cancelled by user");
        assertThat(coordinator.stopReason()).isEqualTo("Order cancelled by user");
    @DisplayName("Noop compensation action (step that doesn't need rollback)")
    void testNoopCompensation() {
                        "order-saga-7",
                        new OrderData("ORD-007", 100.0),
                                        state, new OrderData("ORD-007", 100.0)),
        // Add noop compensation for a step that doesn't need rollback
        DistributedSagaCoordinator.CompensationAction<OrderData> noopCompensation =
                DistributedSagaCoordinator.CompensationAction.noop("logging-step");
        log.add(noopCompensation);
        Result<OrderData, String> compensationResult = coordinator.compensate("Some failure");
        assertThat(coordinator.compensationLog()).hasSize(1);
    @DisplayName("Multiple compensation actions execute in reverse order (LIFO)")
    void testCompensationReverseOrder() {
                        "order-saga-8",
                        new OrderData("ORD-008", 100.0),
                                        state, new OrderData("ORD-008", 100.0)),
        var executionOrder = new ArrayList<String>();
        // Add three compensation actions
                        "step-1",
                            executionOrder.add("step-1");
                            return Result.ok(data);
                        "step-2",
                            executionOrder.add("step-2");
        DistributedSagaCoordinator.CompensationAction<OrderData> action3 =
                        "step-3",
                            executionOrder.add("step-3");
        log.add(action3);
        coordinator.compensate("Some failure");
        // Verify reverse order (LIFO)
        assertThat(executionOrder).containsExactly("step-3", "step-2", "step-1");
}
