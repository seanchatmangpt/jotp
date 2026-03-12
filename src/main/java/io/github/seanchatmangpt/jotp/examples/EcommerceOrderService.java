package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Real-world example: E-commerce Order Service
 *
 * <p>This service demonstrates the integration of five JOTP enterprise patterns:
 *
 * <ol>
 *   <li><strong>DistributedSagaCoordinator:</strong> Orchestrates multi-service order workflow
 *       (Payment → Inventory → Shipping)
 *   <li><strong>CircuitBreaker:</strong> Protects calls to external payment gateway
 *   <li><strong>BulkheadIsolation:</strong> Isolates inventory operations from other features
 *   <li><strong>EventSourcingAuditLog:</strong> Maintains append-only audit trail of order state
 *       changes
 *   <li><strong>Supervisor:</strong> Manages process lifecycle with fault tolerance
 * </ol>
 *
 * <p><strong>Order Workflow:</strong>
 *
 * <pre>
 * Order Creation
 *   → [Saga Step 1] Call PaymentService (via CircuitBreaker, 5s timeout)
 *     ├─ Success: Record audit entry, continue to inventory
 *     └─ Failure: Open circuit, initiate compensation (refund)
 *   → [Saga Step 2] Reserve Inventory (via BulkheadIsolation, 3s timeout)
 *     ├─ Success: Record audit entry, continue to shipping
 *     └─ Failure: Compensate (release inventory, refund payment)
 *   → [Saga Step 3] Schedule Shipping (5s timeout)
 *     ├─ Success: Mark order complete, record audit
 *     └─ Failure: Compensate all steps in LIFO order
 * </pre>
 *
 * <p><strong>Demonstrates:</strong>
 *
 * <ul>
 *   <li>Multi-pattern coordination (Saga orchestrating multiple isolation patterns)
 *   <li>Failure handling and compensation
 *   <li>Time-windowed failure tracking (circuit breaker)
 *   <li>Per-feature isolation (bulkhead)
 *   <li>Audit trail reconstruction (event sourcing)
 *   <li>Type-safe sealed result types and pattern matching
 * </ul>
 */
public class EcommerceOrderService {

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Domain Models (Sealed Type Hierarchy)
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Order state machine states. */
    public enum OrderState {
        INITIAL,
        PAYMENT_PENDING,
        PAYMENT_APPROVED,
        INVENTORY_RESERVED,
        SHIPPING_SCHEDULED,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /** Order events (sealed hierarchy for exhaustive pattern matching). */
    public sealed interface OrderEvent
            permits OrderEvent.Created,
                    OrderEvent.PaymentAuthorized,
                    OrderEvent.PaymentFailed,
                    OrderEvent.InventoryReserved,
                    OrderEvent.InventoryFailed,
                    OrderEvent.ShippingScheduled,
                    OrderEvent.ShippingFailed,
                    OrderEvent.OrderCompleted,
                    OrderEvent.OrderCancelled {}

    // Event records
    public record Created(String orderId, double amount, List<LineItem> items)
            implements OrderEvent {}

    public record PaymentAuthorized(String transactionId) implements OrderEvent {}

    public record PaymentFailed(String reason) implements OrderEvent {}

    public record InventoryReserved(List<String> reservationIds) implements OrderEvent {}

    public record InventoryFailed(String reason) implements OrderEvent {}

    public record ShippingScheduled(String trackingNumber) implements OrderEvent {}

    public record ShippingFailed(String reason) implements OrderEvent {}

    public record OrderCompleted(Instant completedAt) implements OrderEvent {}

    public record OrderCancelled(String reason) implements OrderEvent {}

    // Domain records
    public record LineItem(String productId, int quantity) {}

    public record OrderData(
            String orderId, double amount, List<LineItem> items, Address shippingAddress) {}

    public record Address(String street, String city, String zipCode) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Service Integrations (Mocked External Services)
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Payment service gateway (protected by CircuitBreaker).
     *
     * <p>Simulates an external payment processor (e.g., Stripe, PayPal). Can fail due to network
     * issues, timeouts, or service degradation.
     */
    public static class PaymentService {
        private final CircuitBreaker<Double, String, Exception> breaker;
        private volatile boolean simulateFailure = false;

        public PaymentService(String name, int maxFailures, Duration window) {
            this.breaker = CircuitBreaker.create(name, maxFailures, window, Duration.ofSeconds(30));
        }

        public Result<String, Exception> charge(Double amount) {
            return Result.of(
                    () ->
                            breaker.execute(
                                    amount,
                                    a -> {
                                        if (simulateFailure) {
                                            throw new RuntimeException("Simulated payment failure");
                                        }
                                        // Simulate 95% success rate
                                        if (Math.random() > 0.95) {
                                            throw new RuntimeException("Payment gateway timeout");
                                        }
                                        return "TXN-" + System.nanoTime();
                                    }));
        }

        public CircuitBreaker.State getCircuitState() {
            return breaker.getState();
        }

        void setSimulateFailure(boolean fail) {
            simulateFailure = fail;
        }
    }

    /**
     * Inventory service (isolated via BulkheadIsolation).
     *
     * <p>Manages product inventory with per-SKU worker processes. If inventory becomes overloaded,
     * messages are rejected rather than blocking the entire order pipeline.
     */
    public static class InventoryService {
        private final BulkheadIsolation<String, ReserveRequest> bulkhead;

        public record ReserveRequest(String productId, int quantity) {}

        public InventoryService(String name, int poolSize, int maxQueueDepth) {
            this.bulkhead =
                    BulkheadIsolation.create(
                            name, poolSize, maxQueueDepth, (state, msg) -> reserveInventory(msg));
        }

        public Result<String, Exception> reserve(String productId, int quantity) {
            var sendResult = bulkhead.send(productId, new ReserveRequest(productId, quantity));

            return switch (sendResult) {
                case BulkheadIsolation.Send.Success ignored ->
                        Result.success("RES-" + System.nanoTime());
                case BulkheadIsolation.Send.Rejected(var reason) ->
                        Result.failure(new Exception("Inventory service degraded: " + reason));
            };
        }

        private Object reserveInventory(ReserveRequest req) {
            // Simulate inventory check (90% success rate)
            if (Math.random() > 0.90) {
                throw new RuntimeException("Out of stock: " + req.productId());
            }
            return "RES-" + System.nanoTime();
        }

        public BulkheadIsolation.BulkheadStatus getStatus() {
            return bulkhead.status();
        }
    }

    /**
     * Shipping service (simple, no isolation needed).
     *
     * <p>Schedules shipment based on order. Can fail if carrier is overloaded.
     */
    public static class ShippingService {
        public Result<String, Exception> schedule(Address address) {
            return Result.of(
                    () -> {
                        // Simulate 98% success rate
                        if (Math.random() > 0.98) {
                            throw new RuntimeException("Carrier API timeout");
                        }
                        return "TRACK-" + System.nanoTime();
                    });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Order Saga Orchestrator
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Coordinates order creation across Payment, Inventory, and Shipping services.
     *
     * <p>Uses DistributedSagaCoordinator to ensure atomic semantics: either all steps succeed, or
     * all are compensated (rolled back).
     *
     * <p>Failure scenarios:
     *
     * <ul>
     *   <li>Payment fails → Release inventory reservation (no compensation needed)
     *   <li>Inventory fails → Refund payment, release reservation
     *   <li>Shipping fails → Release inventory, refund payment
     * </ul>
     */
    public static class OrderSagaCoordinator {
        private final PaymentService paymentService;
        private final InventoryService inventoryService;
        private final ShippingService shippingService;
        private final EventSourcingAuditLog<OrderState> auditLog;
        private final Duration timeout;

        public OrderSagaCoordinator(
                PaymentService paymentService,
                InventoryService inventoryService,
                ShippingService shippingService,
                EventSourcingAuditLog<OrderState> auditLog,
                Duration timeout) {
            this.paymentService = paymentService;
            this.inventoryService = inventoryService;
            this.shippingService = shippingService;
            this.auditLog = auditLog;
            this.timeout = timeout;
        }

        /**
         * Execute order creation saga.
         *
         * <p>Returns Result with SagaOutcome (success) or SagaError (failure with compensations
         * applied).
         */
        public Result<SagaOutcome, SagaError> executeOrderCreation(OrderData orderData) {
            // Step 1: Charge payment
            var paymentResult = paymentService.charge(orderData.amount());
            if (paymentResult.isFailure()) {
                // Payment failed, no compensation needed
                auditLog.tell(new OrderEvent.PaymentFailed("Payment gateway error"));
                return Result.failure(
                        new SagaError(
                                "Payment failed: " + paymentResult.failureReason(), List.of()));
            }

            String transactionId = paymentResult.successValue().orElseThrow();
            auditLog.tell(new OrderEvent.PaymentAuthorized(transactionId));

            // Step 2: Reserve inventory
            var inventoryResult = inventoryService.reserve(orderData.items().get(0).productId(), 1);
            if (inventoryResult.isFailure()) {
                // Inventory failed, compensate: refund payment
                auditLog.tell(
                        new OrderEvent.InventoryFailed(
                                "Inventory unavailable: " + inventoryResult.failureReason()));

                // Compensation: refund
                compensatePayment(transactionId);
                return Result.failure(
                        new SagaError(
                                "Inventory failed", List.of("Refunded payment: " + transactionId)));
            }

            String reservationId = inventoryResult.successValue().orElseThrow();
            auditLog.tell(new OrderEvent.InventoryReserved(List.of(reservationId)));

            // Step 3: Schedule shipping
            var shippingResult = shippingService.schedule(orderData.shippingAddress());
            if (shippingResult.isFailure()) {
                // Shipping failed, compensate: release inventory + refund payment
                auditLog.tell(
                        new OrderEvent.ShippingFailed(
                                "Shipping service unavailable: " + shippingResult.failureReason()));

                // Compensation in LIFO order
                compensateInventory(reservationId);
                compensatePayment(transactionId);
                return Result.failure(
                        new SagaError(
                                "Shipping failed",
                                List.of(
                                        "Released inventory: " + reservationId,
                                        "Refunded payment: " + transactionId)));
            }

            String trackingNumber = shippingResult.successValue().orElseThrow();
            auditLog.tell(new OrderEvent.ShippingScheduled(trackingNumber));
            auditLog.tell(new OrderEvent.OrderCompleted(Instant.now()));

            return Result.success(
                    new SagaOutcome(
                            orderData.orderId(),
                            "Order created successfully",
                            List.of(
                                    new CompensationRecord("Payment", transactionId, "SUCCEEDED"),
                                    new CompensationRecord("Inventory", reservationId, "SUCCEEDED"),
                                    new CompensationRecord(
                                            "Shipping", trackingNumber, "SUCCEEDED"))));
        }

        private void compensatePayment(String transactionId) {
            // Refund logic (mocked)
            System.out.println("  [COMPENSATE] Refunding payment: " + transactionId);
        }

        private void compensateInventory(String reservationId) {
            // Release inventory reservation (mocked)
            System.out.println("  [COMPENSATE] Releasing inventory: " + reservationId);
        }
    }

    // Result types
    public record SagaOutcome(String orderId, String message, List<CompensationRecord> steps) {}

    public record CompensationRecord(String service, String id, String status) {}

    public record SagaError(String message, List<String> compensations) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Demo & Testing
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println(
                "╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println(
                "║  E-Commerce Order Service: JOTP Pattern Integration Example              ║");
        System.out.println(
                "╚═══════════════════════════════════════════════════════════════════════════╝\n");

        // Initialize services
        var paymentService = new PaymentService("payment-gateway", 5, Duration.ofSeconds(60));
        var inventoryService = new InventoryService("inventory-service", 5, 100);
        var shippingService = new ShippingService();
        var auditLog = new EventSourcingAuditLog<>("orders", new InMemoryAuditBackend());

        var coordinator =
                new OrderSagaCoordinator(
                        paymentService,
                        inventoryService,
                        shippingService,
                        auditLog,
                        Duration.ofSeconds(5));

        // Create a test order
        var orderData =
                new OrderData(
                        "ORD-123456",
                        99.99,
                        List.of(new LineItem("SKU-001", 1)),
                        new Address("123 Main St", "Springfield", "12345"));

        System.out.println("Creating order: " + orderData.orderId());
        System.out.println("  Amount: $" + orderData.amount());
        System.out.println("  Items: " + orderData.items());
        System.out.println("  Shipping: " + orderData.shippingAddress());
        System.out.println();

        // Execute order creation saga
        var result = coordinator.executeOrderCreation(orderData);

        System.out.println("\n--- Saga Execution Result ---");
        if (result.isSuccess()) {
            var outcome = result.successValue().orElseThrow();
            System.out.println("✅ Order creation SUCCEEDED");
            System.out.println("   Message: " + outcome.message());
            System.out.println("   Steps:");
            for (var step : outcome.steps()) {
                System.out.println(
                        "     - " + step.service() + ": " + step.id() + " [" + step.status() + "]");
            }
        } else {
            var error = result.failureReason().orElseThrow();
            System.out.println("❌ Order creation FAILED");
            System.out.println("   Reason: " + error.message());
            System.out.println("   Compensations applied:");
            for (var comp : error.compensations()) {
                System.out.println("     - " + comp);
            }
        }

        System.out.println("\n--- Pattern Status ---");
        System.out.println("Circuit Breaker (Payment): " + paymentService.getCircuitState());
        System.out.println("Bulkhead Isolation (Inventory): " + inventoryService.getStatus());
    }

    /** Audit log backend implementation for this example. */
    public static class InMemoryAuditBackend
            implements EventSourcingAuditLog.AuditBackend<OrderState> {
        private final List<OrderEvent> events = new ArrayList<>();

        @Override
        public void append(Object auditEntry) {
            if (auditEntry instanceof OrderEvent) {
                events.add((OrderEvent) auditEntry);
            }
        }

        @Override
        public List<Object> getAll() {
            return new ArrayList<>(events);
        }
    }
}
