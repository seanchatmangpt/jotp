package io.github.seanchatmangpt.jotp.video.demos;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * JOTP Video Tutorial - Demo 2: Payment Processor
 *
 * This example demonstrates:
 * - Multi-process coordination
 * - tell() for fire-and-forget messaging
 * - ask() for request-reply patterns
 * - Error handling with Result types
 * - Building workflows with multiple processes
 *
 * Video: 03 - Messaging Patterns (tell/ask)
 */
public class PaymentProcessorDemo {

    // ========== ORDER PROCESS STATE ==========
    record OrderState(
        String orderId,
        String customerId,
        double amount,
        OrderStatus status
    ) {
        enum OrderStatus {
            PENDING, PAID, FAILED, REFUNDED
        }
    }

    // ========== ORDER PROCESS MESSAGES ==========
    sealed interface OrderMsg permits ProcessPayment, ConfirmPayment, RefundPayment, GetStatus {}
    record ProcessPayment(String paymentMethodId) implements OrderMsg {}
    record ConfirmPayment(String transactionId) implements OrderMsg {}
    record RefundPayment(String reason) implements OrderMsg {}
    record GetStatus() implements OrderMsg {}

    // ========== PAYMENT GATEWAY STATE ==========
    record PaymentGatewayState(boolean available) {}

    // ========== PAYMENT GATEWAY MESSAGES ==========
    sealed interface GatewayMsg implements GatewayMsg {}
    record ChargeRequest(double amount, String paymentMethod) implements GatewayMsg {}
    record RefundRequest(String transactionId) implements GatewayMsg {}

    // ========== AUDIT LOGGER STATE ==========
    record AuditLogState(long entryCount) {}

    // ========== AUDIT LOGGER MESSAGES ==========
    sealed interface AuditMsg implements AuditMsg {}
    record LogPayment(String orderId, double amount, String status) implements AuditMsg {}

    // ========== MAIN DEMO ==========
    public static void main(String[] args) throws Exception {
        System.out.println("=== JOTP Payment Processor Demo ===\n");

        // Create supervisor for all processes
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // Spawn payment gateway (simulated)
        ProcRef<PaymentGatewayState, GatewayMsg> gateway = supervisor.supervise(
            "payment-gateway",
            new PaymentGatewayState(true),
            paymentGatewayHandler()
        );

        // Spawn audit logger (fire-and-forget logging)
        ProcRef<AuditLogState, AuditMsg> auditLog = supervisor.supervise(
            "audit-log",
            new AuditLogState(0),
            auditLogHandler()
        );

        // Demo 1: Successful payment workflow
        System.out.println("=== Demo 1: Successful Payment ===");
        demonstrateSuccessfulPayment(supervisor, gateway, auditLog);

        // Demo 2: Failed payment (gateway unavailable)
        System.out.println("\n=== Demo 2: Failed Payment (Gateway Down) ===");
        demonstrateFailedPayment(supervisor, gateway, auditLog);

        // Demo 3: Refund workflow
        System.out.println("\n=== Demo 3: Refund Workflow ===");
        demonstrateRefund(supervisor, gateway, auditLog);

        // Cleanup
        System.out.println("\nShutting down...");
        supervisor.shutdown();
        System.out.println("Done!");
    }

    // ========== DEMO SCENARIOS ==========

    private static void demonstrateSuccessfulPayment(
        Supervisor supervisor,
        ProcRef<PaymentGatewayState, GatewayMsg> gateway,
        ProcRef<AuditLogState, AuditMsg> auditLog
    ) throws Exception {
        // Create an order process
        ProcRef<OrderState, OrderMsg> order = supervisor.supervise(
            "order-001",
            new OrderState("order-001", "customer-123", 99.99, OrderState.OrderStatus.PENDING),
            orderHandler(gateway, auditLog)
        );

        // Process payment using ask() (request-reply)
        System.out.println("Processing payment for $99.99...");
        CompletableFuture<OrderState> future = order.ask(
            new ProcessPayment("pm_12345"),
            Duration.ofSeconds(5)
        );

        OrderState result = future.get();
        System.out.println("Payment status: " + result.status);
        System.out.println("→ Payment processed successfully!\n");
    }

    private static void demonstrateFailedPayment(
        Supervisor supervisor,
        ProcRef<PaymentGatewayState, GatewayMsg> gateway,
        ProcRef<AuditLogState, AuditMsg> auditLog
    ) throws Exception {
        // Create an order
        ProcRef<OrderState, OrderMsg> order = supervisor.supervise(
            "order-002",
            new OrderState("order-002", "customer-456", 199.99, OrderState.OrderStatus.PENDING),
            orderHandler(gateway, auditLog)
        );

        // Simulate gateway failure by sending a message to gateway
        // (In a real system, this would be via health checks)
        gateway.tell(new msg -> new PaymentGatewayState(false));

        // Try to process payment
        System.out.println("Processing payment (gateway is down)...");
        try {
            CompletableFuture<OrderState> future = order.ask(
                new ProcessPayment("pm_67890"),
                Duration.ofSeconds(5)
            );
            OrderState result = future.get();
            System.out.println("Payment status: " + result.status);
        } catch (Exception e) {
            System.out.println("→ Payment failed: " + e.getMessage());
        }

        // Restore gateway
        gateway.tell(new msg -> new PaymentGatewayState(true));
        System.out.println();
    }

    private static void demonstrateRefund(
        Supervisor supervisor,
        ProcRef<PaymentGatewayState, GatewayMsg> gateway,
        ProcRef<AuditLogState, AuditMsg> auditLog
    ) throws Exception {
        // Create an order that's already paid
        ProcRef<OrderState, OrderMsg> order = supervisor.supervise(
            "order-003",
            new OrderState("order-003", "customer-789", 49.99, OrderState.OrderStatus.PAID),
            orderHandler(gateway, auditLog)
        );

        // Process refund
        System.out.println("Processing refund...");
        CompletableFuture<OrderState> future = order.ask(
            new RefundPayment("Customer request"),
            Duration.ofSeconds(5)
        );

        OrderState result = future.get();
        System.out.println("Order status: " + result.status);
        System.out.println("→ Refund processed successfully!\n");
    }

    // ========== HANDLERS ==========

    /**
     * Order process handler: Coordinates payment workflow.
     *
     * This handler demonstrates:
     * - Using ask() to query other processes
     * - Using tell() to send notifications
     * - Error handling with try-catch
     */
    private static java.util.function.BiFunction<OrderState, OrderMsg, OrderState> orderHandler(
        ProcRef<PaymentGatewayState, GatewayMsg> gateway,
        ProcRef<AuditLogState, AuditMsg> auditLog
    ) {
        return (state, msg) -> {
            return switch (msg) {
                case ProcessPayment(var paymentMethod) -> {
                    System.out.println("  [Order] Processing payment...");

                    try {
                        // Ask gateway to charge the payment
                        // This blocks until gateway responds (or times out)
                        var chargeResult = gateway.ask(
                            new ChargeRequest(state.amount(), paymentMethod),
                            Duration.ofSeconds(3)
                        ).get();

                        // Payment succeeded
                        OrderState newState = new OrderState(
                            state.orderId(),
                            state.customerId(),
                            state.amount(),
                            OrderState.OrderStatus.PAID
                        );

                        // Tell audit log about the payment (fire-and-forget)
                        auditLog.tell(new LogPayment(state.orderId(), state.amount(), "PAID"));

                        System.out.println("  [Order] Payment charged successfully");
                        yield newState;
                    } catch (Exception e) {
                        // Payment failed
                        System.out.println("  [Order] Payment failed: " + e.getMessage());

                        // Log the failure
                        auditLog.tell(new LogPayment(state.orderId(), state.amount(), "FAILED"));

                        yield new OrderState(
                            state.orderId(),
                            state.customerId(),
                            state.amount(),
                            OrderState.OrderStatus.FAILED
                        );
                    }
                }

                case ConfirmPayment(var transactionId) -> {
                    System.out.println("  [Order] Confirming payment: " + transactionId);
                    yield state;  // Already updated by ProcessPayment
                }

                case RefundPayment(var reason) -> {
                    System.out.println("  [Order] Processing refund: " + reason);

                    try {
                        // Ask gateway to process refund
                        gateway.ask(
                            new RefundRequest("txn_" + state.orderId()),
                            Duration.ofSeconds(3)
                        ).get();

                        // Refund succeeded
                        OrderState newState = new OrderState(
                            state.orderId(),
                            state.customerId(),
                            state.amount(),
                            OrderState.OrderStatus.REFUNDED
                        );

                        // Log the refund
                        auditLog.tell(new LogPayment(state.orderId(), state.amount(), "REFUNDED"));

                        System.out.println("  [Order] Refund processed");
                        yield newState;
                    } catch (Exception e) {
                        System.out.println("  [Order] Refund failed: " + e.getMessage());
                        yield state;  // Keep current state
                    }
                }

                case GetStatus() -> {
                    System.out.println("  [Order] Status: " + state.status());
                    yield state;
                }
            };
        };
    }

    /**
     * Payment gateway handler: Simulates payment processing.
     *
     * In a real system, this would call external APIs (Stripe, PayPal, etc.).
     */
    private static java.util.function.BiFunction<PaymentGatewayState, GatewayMsg, PaymentGatewayState> paymentGatewayHandler() {
        return (state, msg) -> {
            return switch (msg) {
                case ChargeRequest(var amount, var paymentMethod) -> {
                    if (!state.available()) {
                        System.out.println("  [Gateway] Unavailable (simulated failure)");
                        throw new RuntimeException("Gateway unavailable");
                    }

                    System.out.println("  [Gateway] Charging $" + amount + " to " + paymentMethod);
                    // Simulate processing delay
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                    System.out.println("  [Gateway] Charge successful");
                    yield state;
                }

                case RefundRequest(var transactionId) -> {
                    System.out.println("  [Gateway] Refunding transaction: " + transactionId);
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                    System.out.println("  [Gateway] Refund successful");
                    yield state;
                }
            };
        };
    }

    /**
     * Audit log handler: Logs payment events.
     *
     * This handler uses tell() only (fire-and-forget).
     * No responses needed, just logging.
     */
    private static java.util.function.BiFunction<AuditLogState, AuditMsg, AuditLogState> auditLogHandler() {
        return (state, msg) -> {
            return switch (msg) {
                case LogPayment(var orderId, var amount, var status) -> {
                    long entryNum = state.entryCount() + 1;
                    System.out.println("  [Audit] Entry #" + entryNum + ": " +
                                      orderId + " | $" + amount + " | " + status);
                    yield new AuditLogState(entryNum);
                }
            };
        };
    }
}

/**
 * EXPECTED OUTPUT:
 *
 * === JOTP Payment Processor Demo ===
 *
 * === Demo 1: Successful Payment ===
 * Processing payment for $99.99...
 *   [Order] Processing payment...
 *   [Gateway] Charging $99.99 to pm_12345
 *   [Gateway] Charge successful
 *   [Audit] Entry #1: order-001 | $99.99 | PAID
 *   [Order] Payment charged successfully
 * Payment status: PAID
 * → Payment processed successfully!
 *
 * === Demo 2: Failed Payment (Gateway Down) ===
 * Processing payment (gateway is down)...
 *   [Order] Processing payment...
 *   [Gateway] Unavailable (simulated failure)
 *   [Audit] Entry #2: order-002 | $199.99 | FAILED
 *   [Order] Payment failed: java.lang.RuntimeException: Gateway unavailable
 * → Payment failed: java.lang.RuntimeException: Gateway unavailable
 *
 * === Demo 3: Refund Workflow ===
 * Processing refund...
 *   [Order] Processing refund: Customer request
 *   [Gateway] Refunding transaction: txn_order-003
 *   [Gateway] Refund successful
 *   [Audit] Entry #3: order-003 | $49.99 | REFUNDED
 *   [Order] Refund processed
 * Order status: REFUNDED
 * → Refund processed successfully!
 *
 * Shutting down...
 * Done!
 */
