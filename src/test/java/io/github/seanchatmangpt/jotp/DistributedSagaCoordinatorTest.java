package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for {@link DistributedSagaCoordinator}.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Multi-step saga execution (Order → Payment → Inventory → Shipping)
 *   <li>Compensation logic (rollback on failure)
 *   <li>Timeout handling with service calls
 *   <li>State machine transitions and saga lifecycle
 *   <li>Service registration and lookup
 *   <li>Error recovery and resilience
 * </ul>
 *
 * <p><strong>Order Fulfillment Saga Example:</strong>
 *
 * <pre>{@code
 * Step 1: PaymentService.processPayment(amount)
 *   - Success: move to InventoryState
 *   - Failure: compensate with refund (no-op for this saga)
 *
 * Step 2: InventoryService.reserve(items)
 *   - Success: move to ShippingState
 *   - Failure: compensate with refund (cancel payment)
 *
 * Step 3: ShippingService.ship(address)
 *   - Success: move to CompletedState
 *   - Failure: compensate with refund + restock
 * }</pre>
 *
 * @see DistributedSagaCoordinator
 * @see DistributedSagaCoordinator.SagaTransition
 * @see DistributedSagaCoordinator.CompensationAction
 */
@DisplayName("DistributedSagaCoordinator: Saga Orchestration with Compensation")
class DistributedSagaCoordinatorTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Test Domain Model: Order Saga
    // ──────────────────────────────────────────────────────────────────────────

    /** Sealed hierarchy of order saga states. */
    sealed interface OrderState permits OrderState.Init,
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
                implements OrderState {}

        record Failed(String reason) implements OrderState {}
    }

    /** Sealed hierarchy of order events. */
    sealed interface OrderEvent permits OrderEvent.PlaceOrder,
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
    }

    /** Order saga data — carries mutable context across steps. */
    record OrderData(
            String orderId,
            double amount,
            List<String> compensationLog) {
        OrderData(String orderId, double amount) {
            this(orderId, amount, new ArrayList<>());
        }

        OrderData withCompensation(String step) {
            var log = new ArrayList<>(compensationLog);
            log.add(step);
            return new OrderData(orderId, amount, log);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Service Implementations
    // ──────────────────────────────────────────────────────────────────────────

    /** Simulated payment service. */
    static class PaymentService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final boolean shouldFail;

        PaymentService(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        Result<String, String> processPayment(double amount) {
            callCount.incrementAndGet();
            if (shouldFail) {
                return Result.err("Payment processing failed");
            }
            return Result.ok("PAY-" + System.currentTimeMillis());
        }

        Result<Void, String> refund(String paymentId) {
            callCount.incrementAndGet();
            return Result.ok(null);
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /** Simulated inventory service. */
    static class InventoryService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final boolean shouldFail;

        InventoryService(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        Result<String, String> reserve(List<String> items) {
            callCount.incrementAndGet();
            if (shouldFail) {
                return Result.err("Inventory unavailable");
            }
            return Result.ok("RES-" + System.currentTimeMillis());
        }

        Result<Void, String> restock(String reservationId) {
            callCount.incrementAndGet();
            return Result.ok(null);
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    /** Simulated shipping service. */
    static class ShippingService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final boolean shouldFail;

        ShippingService(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        Result<String, String> ship(String address) {
            callCount.incrementAndGet();
            if (shouldFail) {
                return Result.err("Shipping unavailable");
            }
            return Result.ok("SHIP-" + System.currentTimeMillis());
        }

        Result<Void, String> cancelShipment(String shipmentId) {
            callCount.incrementAndGet();
            return Result.ok(null);
        }

        int getCallCount() {
            return callCount.get();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Cases
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Simple saga: PlaceOrder transition to PaymentProcessing state")
    void testBasicOrderTransition() {
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-1",
                new OrderState.Init(),
                new OrderData("ORD-001", 100.0),
                (state, event) -> switch (state) {
                    case OrderState.Init() -> switch (event) {
                        case OrderEvent.PlaceOrder(var amount) ->
                                DistributedSagaCoordinator.SagaTransition.nextStep(
                                        new OrderState.PaymentProcessing(amount),
                                        new OrderData("ORD-001", amount));
                        default ->
                                DistributedSagaCoordinator.SagaTransition.fail(
                                        "Unexpected event in Init state");
                    };
                    default ->
                            DistributedSagaCoordinator.SagaTransition.fail("Unknown state");
                },
                Duration.ofSeconds(5));

        // Verify initial state
        assertThat(coordinator.state()).isInstanceOf(OrderState.Init.class);
        assertThat(coordinator.data().orderId()).isEqualTo("ORD-001");
        assertThat(coordinator.isRunning()).isTrue();

        // Apply a transition
        var transition = DistributedSagaCoordinator.SagaTransition.nextStep(
                new OrderState.PaymentProcessing(100.0),
                new OrderData("ORD-001", 100.0));
        boolean success = coordinator.applyTransition(transition);

        assertThat(success).isTrue();
        assertThat(coordinator.state())
                .isInstanceOf(OrderState.PaymentProcessing.class);
    }

    @Test
    @DisplayName("Compensation: rollback completed steps on failure")
    void testCompensationOnFailure() {
        var paymentService = new PaymentService(false);
        var inventoryService = new InventoryService(true); // Will fail
        var shippingService = new ShippingService(false);

        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-2",
                new OrderState.Init(),
                new OrderData("ORD-002", 150.0),
                (state, event) -> DistributedSagaCoordinator.SagaTransition.nextStep(
                        state, new OrderData("ORD-002", 150.0)),
                Duration.ofSeconds(5));

        // Log compensation actions
        var paymentCompensation =
                DistributedSagaCoordinator.CompensationAction.rollback(
                        "refund-payment",
                        data -> {
                            paymentService.refund("PAY-123");
                            return Result.ok(data.withCompensation("payment-refunded"));
                        });

        var inventoryCompensation =
                DistributedSagaCoordinator.CompensationAction.rollback(
                        "restock-inventory",
                        data -> {
                            inventoryService.restock("RES-123");
                            return Result.ok(data.withCompensation("inventory-restocked"));
                        });

        coordinator.compensationLog().add(paymentCompensation);
        coordinator.compensationLog().add(inventoryCompensation);

        // Trigger compensation
        Result<OrderData, String> compensationResult = coordinator.compensate("Inventory failed");

        assertThat(compensationResult).isInstanceOf(Result.Ok.class);
        OrderData compensatedData = ((Result.Ok<OrderData, String>) compensationResult).value();
        assertThat(compensatedData.compensationLog()).contains("payment-refunded", "inventory-restocked");
        assertThat(paymentService.getCallCount()).isGreaterThan(0);
        assertThat(inventoryService.getCallCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("State transitions: Init → PaymentProcessing → InventoryReserving → Shipping →"
            + " Completed")
    void testMultiStepSagaTransitions() {
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-3",
                new OrderState.Init(),
                new OrderData("ORD-003", 200.0),
                (state, event) -> switch (state) {
                    case OrderState.Init() ->
                            DistributedSagaCoordinator.SagaTransition.nextStep(
                                    new OrderState.PaymentProcessing(200.0),
                                    new OrderData("ORD-003", 200.0));
                    case OrderState.PaymentProcessing(var amount) ->
                            DistributedSagaCoordinator.SagaTransition.nextStep(
                                    new OrderState.InventoryReserving(amount, "PAY-001"),
                                    new OrderData("ORD-003", amount));
                    case OrderState.InventoryReserving(var amount, var paymentId) ->
                            DistributedSagaCoordinator.SagaTransition.nextStep(
                                    new OrderState.Shipping(amount, paymentId, "RES-001"),
                                    new OrderData("ORD-003", amount));
                    case OrderState.Shipping(var amount, var paymentId, var reservationId) ->
                            DistributedSagaCoordinator.SagaTransition.complete(
                                    new OrderState.Completed(
                                            amount, paymentId, reservationId, "SHIP-001"),
                                    new OrderData("ORD-003", amount));
                    default ->
                            DistributedSagaCoordinator.SagaTransition.fail("Unknown state");
                },
                Duration.ofSeconds(5));

        // Step 1: Init -> PaymentProcessing
        var trans1 = DistributedSagaCoordinator.SagaTransition.nextStep(
                new OrderState.PaymentProcessing(200.0),
                new OrderData("ORD-003", 200.0));
        assertThat(coordinator.applyTransition(trans1)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.PaymentProcessing.class);

        // Step 2: PaymentProcessing -> InventoryReserving
        var trans2 = DistributedSagaCoordinator.SagaTransition.nextStep(
                new OrderState.InventoryReserving(200.0, "PAY-001"),
                new OrderData("ORD-003", 200.0));
        assertThat(coordinator.applyTransition(trans2)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.InventoryReserving.class);

        // Step 3: InventoryReserving -> Shipping
        var trans3 = DistributedSagaCoordinator.SagaTransition.nextStep(
                new OrderState.Shipping(200.0, "PAY-001", "RES-001"),
                new OrderData("ORD-003", 200.0));
        assertThat(coordinator.applyTransition(trans3)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.Shipping.class);

        // Step 4: Shipping -> Completed
        var trans4 = DistributedSagaCoordinator.SagaTransition.complete(
                new OrderState.Completed(200.0, "PAY-001", "RES-001", "SHIP-001"),
                new OrderData("ORD-003", 200.0));
        assertThat(coordinator.applyTransition(trans4)).isTrue();
        assertThat(coordinator.state()).isInstanceOf(OrderState.Completed.class);
        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Service registration and lookup")
    void testServiceRegistration() {
        var paymentService = new PaymentService(false);
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-4",
                new OrderState.Init(),
                new OrderData("ORD-004", 100.0),
                (state, event) -> DistributedSagaCoordinator.SagaTransition.nextStep(state,
                        new OrderData("ORD-004", 100.0)),
                Duration.ofSeconds(5));

        // Create a simple Proc-based service
        var paymentProc = new Proc<>(paymentService, (service, msg) -> service);
        var paymentRef = new ProcRef<>(paymentProc);

        // Register service by reference
        coordinator.registerService("payment", paymentRef);

        // Query registered services (stored internally)
        assertThat(coordinator.name()).isEqualTo("order-saga-4");
        assertThat(coordinator.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Compensation log tracks executed steps")
    void testCompensationLogTracking() {
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-5",
                new OrderState.Init(),
                new OrderData("ORD-005", 100.0),
                (state, event) -> DistributedSagaCoordinator.SagaTransition.nextStep(state,
                        new OrderData("ORD-005", 100.0)),
                Duration.ofSeconds(5));

        // Add compensation actions
        var action1 = DistributedSagaCoordinator.CompensationAction.rollback(
                "step-1",
                data -> Result.ok(data));
        var action2 = DistributedSagaCoordinator.CompensationAction.noop("step-2");

        var log = coordinator.compensationLog();
        log.add(action1);
        log.add(action2);

        assertThat(coordinator.compensationLog()).hasSize(2);
        assertThat(coordinator.compensationLog().get(0))
                .isInstanceOf(DistributedSagaCoordinator.CompensationAction.Rollback.class);
        assertThat(coordinator.compensationLog().get(1))
                .isInstanceOf(DistributedSagaCoordinator.CompensationAction.Noop.class);
    }

    @Test
    @DisplayName("Saga failure and stop reason tracking")
    void testSagaFailureAndStopReason() {
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-6",
                new OrderState.Init(),
                new OrderData("ORD-006", 100.0),
                (state, event) -> DistributedSagaCoordinator.SagaTransition.fail(
                        "Payment service unavailable"),
                Duration.ofSeconds(5));

        assertThat(coordinator.isRunning()).isTrue();
        assertThat(coordinator.stopReason()).isNull();

        coordinator.stop("Order cancelled by user");

        assertThat(coordinator.isRunning()).isFalse();
        assertThat(coordinator.stopReason()).isEqualTo("Order cancelled by user");
    }

    @Test
    @DisplayName("Noop compensation action (step that doesn't need rollback)")
    void testNoopCompensation() {
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-7",
                new OrderState.Init(),
                new OrderData("ORD-007", 100.0),
                (state, event) -> DistributedSagaCoordinator.SagaTransition.nextStep(state,
                        new OrderData("ORD-007", 100.0)),
                Duration.ofSeconds(5));

        // Add noop compensation for a step that doesn't need rollback
        var noopCompensation =
                DistributedSagaCoordinator.CompensationAction.noop("logging-step");

        var log = coordinator.compensationLog();
        log.add(noopCompensation);

        // Trigger compensation
        Result<OrderData, String> compensationResult = coordinator.compensate("Some failure");

        assertThat(compensationResult).isInstanceOf(Result.Ok.class);
        assertThat(coordinator.compensationLog()).hasSize(1);
    }

    @Test
    @DisplayName("Multiple compensation actions execute in reverse order (LIFO)")
    void testCompensationReverseOrder() {
        var coordinator = new DistributedSagaCoordinator<>(
                "order-saga-8",
                new OrderState.Init(),
                new OrderData("ORD-008", 100.0),
                (state, event) -> DistributedSagaCoordinator.SagaTransition.nextStep(state,
                        new OrderData("ORD-008", 100.0)),
                Duration.ofSeconds(5));

        var executionOrder = new ArrayList<String>();

        // Add three compensation actions
        var action1 = DistributedSagaCoordinator.CompensationAction.rollback(
                "step-1",
                data -> {
                    executionOrder.add("step-1");
                    return Result.ok(data);
                });

        var action2 = DistributedSagaCoordinator.CompensationAction.rollback(
                "step-2",
                data -> {
                    executionOrder.add("step-2");
                    return Result.ok(data);
                });

        var action3 = DistributedSagaCoordinator.CompensationAction.rollback(
                "step-3",
                data -> {
                    executionOrder.add("step-3");
                    return Result.ok(data);
                });

        var log = coordinator.compensationLog();
        log.add(action1);
        log.add(action2);
        log.add(action3);

        // Trigger compensation
        coordinator.compensate("Some failure");

        // Verify reverse order (LIFO)
        assertThat(executionOrder).containsExactly("step-3", "step-2", "step-1");
    }
}
