package io.github.seanchatmangpt.jotp.enterprise.saga;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for a distributed saga transaction.
 *
 * <p>Immutable record defining the sequence of saga steps (forward actions and compensations),
 * timeout settings, retry behavior, and execution options for distributed saga coordination. This
 * configuration controls the reliability, latency, and observability of saga execution.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>sagaId</b>: Unique identifier for this saga type. Used in logs, metrics, and event
 *       correlation. Must be non-empty. Example: "order-processing", "payment-transfer"
 *   <li><b>steps</b>: Ordered list of saga steps (actions and compensations). At least one step
 *       required. Steps executed sequentially. Each step must have unique name within saga
 *   <li><b>timeout</b>: Maximum time allowed for saga completion. Includes forward execution and
 *       compensation. Prevents runaway sagas. Typical values: 5-30 minutes
 *   <li><b>compensationTimeout</b>: Maximum time for compensation phase. Should be shorter than
 *       timeout to allow time for forward steps. Typical values: 1-10 minutes
 *   <li><b>maxRetries</b>: Maximum retry attempts for failed steps. Each step retried before
 *       starting compensation. Typical values: 0-3 (0 = no retry, fail immediately)
 *   <li><b>asyncOutput</b>: Whether to return CompletableFuture immediately (true) or block until
 *       completion (false). True for non-blocking, false for synchronous
 *   <li><b>metricsEnabled</b>: Whether to emit performance metrics for monitoring dashboards
 * </ul>
 *
 * <h2>Step Definition:</h2>
 *
 * <p>Saga steps combine forward actions and compensations:
 *
 * <pre>
 * Action<String, Output>    → Forward step (executed during saga)
 * Compensation<Output>       → Compensating step (executed on rollback)
 * Conditional               → Branching logic (if/else)
 * </pre>
 *
 * <h2>Timeout Handling:</h2>
 *
 * <ul>
 *   <li>Individual step timeout: timeout / steps (allocated per step)
 *   <li>Saga timeout: entire saga must complete within timeout
 *   <li>Compensation timeout: compensation phase shorter than forward phase
 *   <li>On timeout: saga aborted, compensation started if steps executed
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Order processing saga with compensation
 * SagaConfig config = SagaConfig.builder("order-processing")
 *     .steps(List.of(
 *         // Forward actions
 *         new SagaStep.Action<>("reserveInventory", input ->
 *             inventoryService.reserve(input.productId, input.quantity)
 *         ),
 *         new SagaStep.Action<>("chargePayment", input ->
 *             paymentService.charge(input.paymentMethod, input.amount)
 *         ),
 *         new SagaStep.Action<>("scheduleShipment", input ->
 *             shippingService.schedule(input.address, input.items)
 *         ),
 *         // Compensations (in reverse order)
 *         new SagaStep.Compensation<PaymentResult>("refundPayment", result ->
 *             paymentService.refund(result.transactionId)
 *         ),
 *         new SagaStep.Compensation<InventoryResult>("releaseInventory", result ->
 *             inventoryService.release(result.reservationId)
 *         )
 *     ))
 *     .timeout(Duration.ofMinutes(10))
 *     .compensationTimeout(Duration.ofMinutes(5))
 *     .maxRetries(2)
 *     .asyncOutput(true)
 *     .metricsEnabled(true)
 *     .build();
 *
 * // Create and execute saga
 * DistributedSagaCoordinator saga = DistributedSagaCoordinator.create(config);
 * CompletableFuture<SagaResult> future = saga.execute();
 * }</pre>
 *
 * <h2>Retry Strategy:</h2>
 *
 * <pre>
 * maxRetries=0    → No retry, fail immediately (fast fail)
 * maxRetries=1-3  → Retry transient failures (recommended)
 * maxRetries>3    → High resilience, slow failure
 * </pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(steps) for step definitions and output storage
 *   <li>Latency: O(steps * avgStepLatency) for completion
 *   <li>Throughput: Limited by slowest step and maxRetries
 * </ul>
 *
 * @see DistributedSagaCoordinator
 * @see SagaStep
 * @see SagaTransaction
 * @since 1.0
 * @param sagaId Unique identifier for this saga type
 * @param steps Ordered list of saga steps (actions and compensations)
 * @param timeout Maximum time allowed for saga completion
 * @param compensationTimeout Maximum time for compensation phase
 * @param maxRetries Maximum retry attempts for failed steps
 * @param asyncOutput Whether to return CompletableFuture immediately
 * @param metricsEnabled Whether to emit performance metrics
 */
public record SagaConfig(
        String sagaId,
        List<SagaStep> steps,
        Duration timeout,
        Duration compensationTimeout,
        int maxRetries,
        boolean asyncOutput,
        boolean metricsEnabled) {

    /** Validate configuration constraints. */
    public SagaConfig {
        if (sagaId == null || sagaId.isEmpty()) {
            throw new IllegalArgumentException("sagaId must not be empty");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (compensationTimeout.isNegative() || compensationTimeout.isZero()) {
            throw new IllegalArgumentException("compensationTimeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String sagaId) {
        return new Builder(sagaId);
    }

    public static class Builder {
        private final String sagaId;
        private List<SagaStep> steps = List.of();
        private Duration timeout = Duration.ofMinutes(10);
        private Duration compensationTimeout = Duration.ofMinutes(5);
        private int maxRetries = 3;
        private boolean asyncOutput = false;
        private boolean metricsEnabled = true;

        public Builder(String sagaId) {
            this.sagaId = sagaId;
        }

        public Builder steps(List<SagaStep> steps) {
            this.steps = steps;
            return this;
        }

        public Builder timeout(Duration duration) {
            this.timeout = duration;
            return this;
        }

        public Builder compensationTimeout(Duration duration) {
            this.compensationTimeout = duration;
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        public Builder asyncOutput(boolean async) {
            this.asyncOutput = async;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public SagaConfig build() {
            return new SagaConfig(
                    sagaId,
                    steps,
                    timeout,
                    compensationTimeout,
                    maxRetries,
                    asyncOutput,
                    metricsEnabled);
        }
    }
}
