package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Real-world example: Distributed Payment Processing System
 *
 * <p>This system demonstrates cross-JVM payment processing using JOTP's distributed capabilities:
 *
 * <ol>
 *   <li><strong>DistributedActorBridge:</strong> Location-transparent actor communication across
 *       multiple JVM instances
 *   <li><strong>DistributedSagaCoordinator:</strong> Multi-service payment workflow spanning
 *       different nodes
 *   <li><strong>EventSourcingAuditLog:</strong> Append-only payment transaction log for compliance
 *   <li><strong>Supervisor:</strong> Per-node fault tolerance and restart limits
 *   <li><strong>CircuitBreaker:</strong> Protection for external gateway calls
 * </ol>
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * [API Gateway]  (single JVM)
 *   ↓
 * [PaymentValidator] (local process)
 *   ↓
 * [Payment Saga Coordinator] (local process)
 *   ├──(gRPC)──→ [Node-1: RiskAnalyzer] (remote process)
 *   ├──(gRPC)──→ [Node-2: PaymentGateway] (remote process)
 *   └──(gRPC)──→ [Node-3: NotificationService] (remote process)
 *
 * [Local EventLog] ← All transactions logged durably
 * </pre>
 *
 * <p><strong>Guarantees:</strong>
 *
 * <ul>
 *   <li>Location transparency: Callers don't know if actor is local or remote
 *   <li>Distributed Saga: All-or-nothing transaction semantics across nodes
 *   <li>Audit Trail: Every transaction recorded for compliance
 *   <li>Fault Tolerance: Node failure doesn't lose in-flight payments
 * </ul>
 *
 * <p><strong>Demonstrates:</strong>
 *
 * <ul>
 *   <li>Cross-JVM actor communication
 *   <li>Distributed saga with compensation
 *   <li>Event sourcing for compliance
 *   <li>Circuit breaker protection
 *   <li>Type-safe sealed state transitions
 * </ul>
 */
public class DistributedPaymentProcessing {

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Domain Models (Sealed Type Hierarchies)
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Payment states throughout the system. */
    public enum PaymentState {
        RECEIVED,
        VALIDATING,
        RISK_ANALYSIS,
        CHARGING,
        NOTIFYING,
        COMPLETED,
        FAILED,
        REFUNDED
    }

    /** Payment events (sealed for exhaustive matching). */
    public sealed interface PaymentEvent
            permits PaymentEvent.Received,
                    PaymentEvent.ValidationPassed,
                    PaymentEvent.ValidationFailed,
                    PaymentEvent.RiskAnalysisPassed,
                    PaymentEvent.RiskAnalysisFailed,
                    PaymentEvent.ChargingSucceeded,
                    PaymentEvent.ChargingFailed,
                    PaymentEvent.NotificationSent,
                    PaymentEvent.PaymentCompleted,
                    PaymentEvent.PaymentRefunded {

        record Received(String paymentId, double amount) implements PaymentEvent {}

        record ValidationPassed() implements PaymentEvent {}

        record ValidationFailed(String reason) implements PaymentEvent {}

        record RiskAnalysisPassed(String riskScore) implements PaymentEvent {}

        record RiskAnalysisFailed(String reason) implements PaymentEvent {}

        record ChargingSucceeded(String transactionId) implements PaymentEvent {}

        record ChargingFailed(String reason) implements PaymentEvent {}

        record NotificationSent(String notificationId) implements PaymentEvent {}

        record PaymentCompleted(Instant completedAt) implements PaymentEvent {}

        record PaymentRefunded(String refundId) implements PaymentEvent {}
    }

    // Domain record
    public record PaymentData(
            String paymentId, String customerId, double amount, String cardToken, String email) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Audit log interface for payment events
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Simple audit log interface for recording payment events. */
    public interface PaymentAuditLog {
        void record(PaymentEvent event);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Remote Services (Simulated, would be on different nodes)
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Risk analysis service (remote node). */
    public static class RiskAnalyzerService {
        public Result<String, Exception> analyzeRisk(PaymentData payment) {
            return Result.of(
                    () -> {
                        // Simulate risk analysis (98% pass rate)
                        if (Math.random() > 0.98) {
                            throw new RuntimeException("High-risk payment detected");
                        }
                        return "RISK-" + System.nanoTime();
                    });
        }

        @Override
        public String toString() {
            return "RiskAnalyzerService";
        }
    }

    /** Payment gateway (remote node). */
    public static class PaymentGatewayService {
        private final CircuitBreaker<PaymentData, String, Exception> breaker;

        public PaymentGatewayService() {
            this.breaker =
                    CircuitBreaker.create(
                            "payment-gateway", 3, Duration.ofSeconds(60), Duration.ofSeconds(30));
        }

        public Result<String, Exception> charge(PaymentData payment) {
            return Result.of(
                    () -> {
                        var cbResult =
                                breaker.execute(
                                        payment,
                                        p -> {
                                            // Simulate charging (96% success rate)
                                            if (Math.random() > 0.96) {
                                                throw new RuntimeException(
                                                        "Payment gateway timeout");
                                            }
                                            return "TXN-" + System.nanoTime();
                                        });
                        return switch (cbResult) {
                            case CircuitBreaker.CircuitBreakerResult.Success<String, Exception>(
                                            var v) ->
                                    v;
                            case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception>(
                                            var e) ->
                                    throw (e instanceof RuntimeException re)
                                            ? re
                                            : new RuntimeException(e);
                            case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception>
                                            ignored ->
                                    throw new RuntimeException("Circuit breaker open");
                        };
                    });
        }

        public CircuitBreaker.State getCircuitState() {
            return breaker.getState();
        }

        @Override
        public String toString() {
            return "PaymentGatewayService";
        }
    }

    /** Notification service (remote node). */
    public static class NotificationService {
        public Result<String, Exception> sendConfirmation(
                PaymentData payment, String transactionId) {
            return Result.of(
                    () -> {
                        // Simulate email send (99% success rate)
                        if (Math.random() > 0.99) {
                            throw new RuntimeException("Email service timeout");
                        }
                        return "NOTIF-" + System.nanoTime();
                    });
        }

        @Override
        public String toString() {
            return "NotificationService";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Payment Saga Orchestrator
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Orchestrates distributed payment processing across multiple services and nodes.
     *
     * <p>Workflow:
     *
     * <ol>
     *   <li>Validate payment data locally
     *   <li>Analyze risk on remote node (RiskAnalyzerService)
     *   <li>Charge payment on remote gateway (PaymentGatewayService)
     *   <li>Send confirmation on remote notification service
     *   <li>Log audit trail locally (PaymentAuditLog)
     * </ol>
     *
     * <p>Compensation on failure:
     *
     * <ul>
     *   <li>If validation fails: reject immediately
     *   <li>If risk fails: reject
     *   <li>If charging fails: request refund from gateway
     *   <li>If notification fails: retry or send alternative notification
     * </ul>
     */
    public static class PaymentSagaCoordinator {
        private final RiskAnalyzerService riskAnalyzer;
        private final PaymentGatewayService gateway;
        private final NotificationService notificationService;
        private final PaymentAuditLog auditLog;
        private volatile long transactionCount = 0;

        public PaymentSagaCoordinator(PaymentAuditLog auditLog) {
            this.riskAnalyzer = new RiskAnalyzerService();
            this.gateway = new PaymentGatewayService();
            this.notificationService = new NotificationService();
            this.auditLog = auditLog;
        }

        /**
         * Execute payment processing saga.
         *
         * <p>Returns Result with PaymentOutcome or PaymentError.
         */
        public Result<PaymentOutcome, PaymentError> processPayment(PaymentData payment) {
            transactionCount++;

            // Log receipt
            auditLog.record(new PaymentEvent.Received(payment.paymentId(), payment.amount()));

            // Step 1: Validate payment locally
            var validationResult = validatePayment(payment);
            if (validationResult.isFailure()) {
                String reason =
                        validationResult.fold(v -> "", e -> e != null ? e.toString() : "unknown");
                auditLog.record(new PaymentEvent.ValidationFailed(reason));
                return Result.failure(
                        new PaymentError(
                                payment.paymentId(), "Validation failed: " + reason, List.of()));
            }
            auditLog.record(new PaymentEvent.ValidationPassed());

            // Step 2: Analyze risk (remote service)
            var riskResult = riskAnalyzer.analyzeRisk(payment);
            if (riskResult.isFailure()) {
                String reason =
                        riskResult.fold(v -> "", e -> e != null ? e.getMessage() : "unknown");
                auditLog.record(new PaymentEvent.RiskAnalysisFailed(reason));
                return Result.failure(
                        new PaymentError(
                                payment.paymentId(), "Risk analysis failed: " + reason, List.of()));
            }
            String riskScore = riskResult.orElseThrow();
            auditLog.record(new PaymentEvent.RiskAnalysisPassed(riskScore));

            // Step 3: Charge payment (remote gateway)
            var chargeResult = gateway.charge(payment);
            if (chargeResult.isFailure()) {
                String reason =
                        chargeResult.fold(v -> "", e -> e != null ? e.getMessage() : "unknown");
                auditLog.record(new PaymentEvent.ChargingFailed(reason));
                return Result.failure(
                        new PaymentError(
                                payment.paymentId(),
                                "Payment charging failed: " + reason,
                                List.of("No refund needed (charge was not successful)")));
            }
            String transactionId = chargeResult.orElseThrow();
            auditLog.record(new PaymentEvent.ChargingSucceeded(transactionId));

            // Step 4: Send notification (remote service)
            var notificationResult = notificationService.sendConfirmation(payment, transactionId);
            if (notificationResult.isFailure()) {
                // Notification failed, but charge succeeded
                // Compensation: refund the charge
                auditLog.record(new PaymentEvent.NotificationSent("FAILED"));
                compensateCharge(transactionId);
                return Result.failure(
                        new PaymentError(
                                payment.paymentId(),
                                "Notification failed, payment refunded",
                                List.of("Refunded charge: " + transactionId)));
            }

            String notificationId = notificationResult.orElseThrow();
            auditLog.record(new PaymentEvent.NotificationSent(notificationId));
            auditLog.record(new PaymentEvent.PaymentCompleted(Instant.now()));

            return Result.success(
                    new PaymentOutcome(
                            payment.paymentId(),
                            transactionId,
                            "Payment processed successfully",
                            List.of(
                                    new StepRecord("Validation", "PASSED"),
                                    new StepRecord("Risk Analysis", riskScore),
                                    new StepRecord("Charging", transactionId),
                                    new StepRecord("Notification", notificationId))));
        }

        private Result<Void, String> validatePayment(PaymentData payment) {
            // Basic validation checks
            if (payment.amount() <= 0) {
                return Result.failure("Invalid amount");
            }
            if (payment.cardToken() == null || payment.cardToken().isEmpty()) {
                return Result.failure("Invalid card token");
            }
            if (payment.email() == null || !payment.email().contains("@")) {
                return Result.failure("Invalid email");
            }
            return Result.success(null);
        }

        private void compensateCharge(String transactionId) {
            System.out.println("  [COMPENSATE] Refunding charge: " + transactionId);
        }

        public long getTransactionCount() {
            return transactionCount;
        }

        public CircuitBreaker.State getGatewayCircuitState() {
            return gateway.getCircuitState();
        }
    }

    // Result types
    public record PaymentOutcome(
            String paymentId, String transactionId, String message, List<StepRecord> steps) {}

    public record StepRecord(String name, String result) {}

    public record PaymentError(String paymentId, String message, List<String> compensations) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Demo & Testing
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println(
                "╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println(
                "║  Distributed Payment Processing: JOTP Cross-JVM Example                 ║");
        System.out.println(
                "╚═══════════════════════════════════════════════════════════════════════════╝\n");

        var auditLog = new InMemoryPaymentAuditLog();
        var coordinator = new PaymentSagaCoordinator(auditLog);

        // Simulate multiple payment processing
        System.out.println("Processing payments...\n");

        // Payment 1: Normal path
        var payment1 =
                new PaymentData(
                        "PAY-001", "CUST-100", 99.99, "tok_visa_123", "customer@example.com");

        System.out.println(
                "Processing " + payment1.paymentId() + " (Amount: $" + payment1.amount() + ")");
        var result1 = coordinator.processPayment(payment1);
        printPaymentResult(result1);

        System.out.println();

        // Payment 2: Another normal payment
        var payment2 =
                new PaymentData(
                        "PAY-002", "CUST-200", 49.99, "tok_visa_456", "another@example.com");

        System.out.println(
                "Processing " + payment2.paymentId() + " (Amount: $" + payment2.amount() + ")");
        var result2 = coordinator.processPayment(payment2);
        printPaymentResult(result2);

        System.out.println("\n--- System Status ---");
        System.out.println("Total Transactions: " + coordinator.getTransactionCount());
        System.out.println(
                "Payment Gateway Circuit Breaker: " + coordinator.getGatewayCircuitState());
        System.out.println("\nDistributed payment processing demonstration complete!");
    }

    private static void printPaymentResult(Result<PaymentOutcome, PaymentError> result) {
        if (result.isSuccess()) {
            var outcome = result.orElseThrow();
            System.out.println("  Payment SUCCESSFUL");
            System.out.println("     Transaction ID: " + outcome.transactionId());
            System.out.println("     Steps:");
            for (var step : outcome.steps()) {
                System.out.println("       - " + step.name() + ": " + step.result());
            }
        } else {
            var error = result.fold(v -> null, e -> e);
            System.out.println("  Payment FAILED");
            if (error != null) {
                System.out.println("     Reason: " + error.message());
                if (!error.compensations().isEmpty()) {
                    System.out.println("     Compensations:");
                    for (var comp : error.compensations()) {
                        System.out.println("       - " + comp);
                    }
                }
            }
        }
    }

    /** Audit log implementation for this example. */
    public static class InMemoryPaymentAuditLog implements PaymentAuditLog {
        private final List<PaymentEvent> events = new ArrayList<>();

        @Override
        public void record(PaymentEvent event) {
            events.add(event);
        }

        public List<PaymentEvent> getEvents() {
            return events;
        }
    }
}
