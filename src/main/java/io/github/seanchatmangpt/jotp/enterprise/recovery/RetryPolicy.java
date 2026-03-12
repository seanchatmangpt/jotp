package io.github.seanchatmangpt.jotp.enterprise.recovery;

/**
 * Sealed interface for retry backoff policies.
 *
 * Defines how delays are calculated between retry attempts.
 */
public sealed interface RetryPolicy permits
    RetryPolicy.ExponentialBackoff,
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
