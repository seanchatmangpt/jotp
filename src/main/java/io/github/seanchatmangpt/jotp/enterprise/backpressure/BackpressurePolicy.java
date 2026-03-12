package io.github.seanchatmangpt.jotp.enterprise.backpressure;

/**
 * Sealed interface for backpressure handling policies.
 *
 * Defines how to handle timeouts and circuit breaker states when downstream services
 * are slow or failing.
 */
public sealed interface BackpressurePolicy permits
    BackpressurePolicy.Strict,
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
