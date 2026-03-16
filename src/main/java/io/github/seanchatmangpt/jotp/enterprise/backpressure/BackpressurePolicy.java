package io.github.seanchatmangpt.jotp.enterprise.backpressure;

/**
 * Sealed interface for backpressure handling policies.
 *
 * <p>Defines how to handle timeouts and circuit breaker states when downstream services are slow or
 * failing. Each policy represents a different strategy for maintaining system stability under load.
 *
 * <h2>Policy Types:</h2>
 *
 * <ul>
 *   <li><b>Strict</b>: Reject immediately on timeout (fail-fast). No retries, no adaptation. Best
 *       for: Critical paths where fast failure is preferred over waiting
 *   <li><b>Adaptive</b>: Adjust timeout based on success rate (dynamic timeout). Increase timeout
 *       on failures, decrease on successes. Best for: Variable-latency services
 *   <li><b>CircuitBreak</b>: Trip circuit breaker after N consecutive timeouts. Reject all requests
 *       until timeout expires. Best for: Protecting against cascading failures
 *   <li><b>Exponential</b>: Use exponential backoff for retries. Paired with retry logic. Best for:
 *       Transient failures that resolve with time
 * </ul>
 *
 * <h2>Selection Guide:</h2>
 *
 * <pre>
 * High throughput required          → Adaptive
 * Fast failure required             → Strict
 * Preventing cascading failures     → CircuitBreak
 * Transient network issues          → Exponential
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Adaptive: adjust timeout based on 95% success rate
 * BackpressurePolicy adaptive = new BackpressurePolicy.Adaptive(0.95, 100);
 *
 * // Circuit break: open after 5 consecutive failures
 * BackpressurePolicy circuit = new BackpressurePolicy.CircuitBreak(5, 30000);
 *
 * // Strict: fail immediately on timeout
 * BackpressurePolicy strict = new BackpressurePolicy.Strict();
 * }</pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link Backpressure} to determine behavior on timeouts
 *   <li>Compatible with {@link
 *       io.github.seanchatmangpt.jotp.enterprise.circuitbreaker.CircuitBreakerPattern}
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see Backpressure
 * @see BackpressureConfig
 * @since 1.0
 */
public sealed interface BackpressurePolicy
        permits BackpressurePolicy.Strict,
                BackpressurePolicy.Adaptive,
                BackpressurePolicy.CircuitBreak,
                BackpressurePolicy.Exponential {

    /** Reject immediately on timeout (fail-fast). */
    record Strict() implements BackpressurePolicy {}

    /** Adjust timeout based on success rate (dynamic timeout). */
    record Adaptive(double successRateThreshold, int windowSize) implements BackpressurePolicy {}

    /** Trip circuit breaker after N consecutive timeouts. */
    record CircuitBreak(int failureThreshold, long retryAfterMs) implements BackpressurePolicy {}

    /** Use exponential backoff for retries (paired with retry logic). */
    record Exponential(double multiplier, long maxBackoffMs) implements BackpressurePolicy {}
}
