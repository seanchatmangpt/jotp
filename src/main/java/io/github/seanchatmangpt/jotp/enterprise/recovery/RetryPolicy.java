package io.github.seanchatmangpt.jotp.enterprise.recovery;

/**
 * Sealed interface for retry backoff policies and delay calculation strategies.
 *
 * <p>Defines how delays are calculated between retry attempts. Each policy represents a different
 * approach to balancing recovery speed, resource usage, and thundering herd prevention. The choice
 * of policy depends on failure characteristics, service capacity, and operational requirements.
 *
 * <h2>Policy Types:</h2>
 *
 * <ul>
 *   <li><b>ExponentialBackoff</b>: Delay doubles on each retry (delay = initialDelay * 2^(n-1)).
 *       Fast initial recovery but can grow exponentially. Good for: Transient network issues,
 *       service restarts. Risk: Can become very slow on long failure streaks
 *   <li><b>LinearBackoff</b>: Delay grows linearly (delay = baseDelay * n). Predictable growth,
 *       easier to reason about. Good for: Rate-limited APIs, predictable load. Risk: Slower initial
 *       recovery than exponential
 *   <li><b>FixedDelay</b>: Constant delay between retries. Simplest approach, no growth. Good for:
 *       Quick retries, testing, debugging. Risk: Thundering herd if many clients retry
 *       simultaneously
 *   <li><b>ExponentialCapped</b>: Exponential growth with max delay cap. Best of both worlds: fast
 *       initial recovery, bounded maximum delay. Good for: Most production use cases. Recommended
 *       default
 * </ul>
 *
 * <h2>Delay Calculation Examples:</h2>
 *
 * <pre>
 * initialDelay = 100ms, maxDelay = 30s
 *
 * ┌────────────┬──────────────┬─────────────┬─────────────┐
 * │ Attempt #  │ Exponential  │ Linear      │ Fixed       │
 * ├────────────┼──────────────┼─────────────┼─────────────┤
 * │ 1          │ 100ms        │ 100ms       │ 100ms       │
 * │ 2          │ 200ms        │ 200ms       │ 100ms       │
 * │ 3          │ 400ms        │ 300ms       │ 100ms       │
 * │ 4          │ 800ms        │ 400ms       │ 100ms       │
 * │ 5          │ 1.6s         │ 500ms       │ 100ms       │
 * │ 6          │ 3.2s         │ 600ms       │ 100ms       │
 * │ 7          │ 6.4s         │ 700ms       │ 100ms       │
 * │ 8          │ 12.8s        │ 800ms       │ 100ms       │
 * │ 9          │ 25.6s        │ 900ms       │ 100ms       │
 * │ 10+        │ 30s (capped) │ 1s+         │ 100ms       │
 * └────────────┴──────────────┴─────────────┴─────────────┘
 * </pre>
 *
 * <h2>Selection Guide:</h2>
 *
 * <pre>
 * Fast recovery needed          → ExponentialBackoff (or ExponentialCapped)
 * Predictable behavior          → LinearBackoff
 * Simple, quick retries         → FixedDelay
 * Production recommended        → ExponentialCapped (balances speed and bounds)
 * Rate-limited API              → LinearBackoff or FixedDelay
 * Unknown failure pattern       → ExponentialCapped (safest default)
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Exponential backoff (fast recovery, unbounded)
 * RetryPolicy exponential = new RetryPolicy.ExponentialBackoff();
 *
 * // Linear backoff (predictable growth)
 * RetryPolicy linear = new RetryPolicy.LinearBackoff();
 *
 * // Fixed delay (simple, constant)
 * RetryPolicy fixed = new RetryPolicy.FixedDelay();
 *
 * // Exponential with cap (recommended)
 * RetryPolicy capped = new RetryPolicy.ExponentialCapped();
 *
 * // Apply to recovery configuration
 * RecoveryConfig config = RecoveryConfig.builder("api-request")
 *     .initialDelay(Duration.ofMillis(100))
 *     .maxDelay(Duration.ofSeconds(30))
 *     .policy(capped)
 *     .build();
 * }</pre>
 *
 * <h2>Jitter Integration:</h2>
 *
 * <p>All policies work with jitter to prevent thundering herd:
 *
 * <pre>
 * baseDelay = policy.calculateDelay(attempt)
 * finalDelay = baseDelay * (1 ± jitterFactor)
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link RecoveryConfig} to configure backoff behavior
 *   <li>Executed by {@link EnterpriseRecovery} between retry attempts
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see EnterpriseRecovery
 * @see RecoveryConfig
 * @since 1.0
 */
public sealed interface RetryPolicy
        permits RetryPolicy.ExponentialBackoff,
                RetryPolicy.LinearBackoff,
                RetryPolicy.FixedDelay,
                RetryPolicy.ExponentialCapped {

    /** Exponential backoff: delay = initialDelay * 2^(attempt-1) */
    record ExponentialBackoff() implements RetryPolicy {}

    /** Linear backoff: delay = baseDelay * attempt */
    record LinearBackoff() implements RetryPolicy {}

    /** Fixed delay: always same delay between retries */
    record FixedDelay() implements RetryPolicy {}

    /** Exponential with cap: delay = min(initialDelay * 2^n, maxDelay) */
    record ExponentialCapped() implements RetryPolicy {}
}
