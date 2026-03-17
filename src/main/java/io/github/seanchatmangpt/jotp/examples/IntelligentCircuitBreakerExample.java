package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.ai.IntelligentCircuitBreaker;
import io.github.seanchatmangpt.jotp.ai.IntelligentCircuitBreaker.FailureClassification;
import io.github.seanchatmangpt.jotp.ai.IntelligentCircuitBreaker.Result;
import io.github.seanchatmangpt.jotp.ai.IntelligentCircuitBreaker.State;
import java.time.Duration;
import java.util.Optional;

/**
 * Example demonstrating the IntelligentCircuitBreaker.
 *
 * <p>This example shows:
 *
 * <ol>
 *   <li>Basic usage with transient failures (timeout, temporary unavailability)
 *   <li>Permanent failures (bad requests, not found) that fail fast
 *   <li>Adaptive backoff with exponential increase and jitter
 *   <li>Learning and prediction of failure patterns
 *   <li>Recovery prediction based on historical data
 *   <li>Cascading failure prevention
 * </ol>
 */
public class IntelligentCircuitBreakerExample {

  public static void main(String[] args) throws InterruptedException {
    exampleBasicUsage();
    exampleTransientVsPermanent();
    exampleAdaptiveBackoff();
    exampleRecoveryPrediction();
  }

  /**
   * Example 1: Basic usage with circuit breaker states.
   */
  static void exampleBasicUsage() {
    System.out.println("\n=== Example 1: Basic Usage ===\n");

    IntelligentCircuitBreaker<String, String, Exception> breaker =
        IntelligentCircuitBreaker.create(
            "payment-service",
            5,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofMillis(100),
            Duration.ofSeconds(30));

    // Normal successful request
    Result<String, Exception> result = breaker.execute("charge-123", request -> "payment-successful");

    if (result instanceof Result.Success<String, Exception> success) {
      System.out.println("Success: " + success.value());
    } else if (result instanceof Result.Failure<String, Exception> failure) {
      System.out.println("Failed: " + failure.error().getMessage() + " [" + failure.classification() + "]");
    } else if (result instanceof Result.CircuitOpen<String, Exception> open) {
      System.out.println("Circuit open, wait " + open.remainingWaitTimeMs() + "ms");
    }

    System.out.println("Circuit state: " + breaker.getState());
  }

  /**
   * Example 2: Transient vs Permanent failure behavior.
   *
   * <p>Transient failures (timeout, connection reset) allow multiple retries and accumulate
   * slowly. Permanent failures (bad request, not found) open the circuit immediately to prevent
   * cascading failures.
   */
  static void exampleTransientVsPermanent() {
    System.out.println("\n=== Example 2: Transient vs Permanent Failures ===\n");

    IntelligentCircuitBreaker<String, String, Exception> breaker =
        IntelligentCircuitBreaker.create(
            "api-gateway",
            5,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofMillis(100),
            Duration.ofSeconds(30));

    // Transient failure: timeout (retryable)
    System.out.println("Attempt 1: Transient failure (timeout)");
    Result<String, Exception> result1 = breaker.execute("request-1", request -> {
      throw new SocketTimeoutException("Service temporarily unavailable");
    });

    if (result1 instanceof Result.Failure<String, Exception> failure1) {
      System.out.println(
          "Classification: " + failure1.classification() + " (should be TRANSIENT)");
    }
    System.out.println("Circuit state: " + breaker.getState() + " (should be CLOSED)");
    System.out.println("Consecutive failures: " + breaker.getConsecutiveFailures());

    // Permanent failure: bad request (not retryable)
    System.out.println("\nAttempt 2: Permanent failure (bad request)");
    Result<String, Exception> result2 = breaker.execute("request-2", request -> {
      throw new BadRequestException("Invalid authentication token");
    });

    if (result2 instanceof Result.Failure<String, Exception> failure2) {
      System.out.println(
          "Classification: " + failure2.classification() + " (should be PERMANENT)");
    }

    // Second permanent failure opens circuit immediately
    System.out.println("\nAttempt 3: Second permanent failure (circuit opens)");
    breaker.execute("request-3", request -> {
      throw new BadRequestException("Invalid authentication token");
    });

    System.out.println("Circuit state: " + breaker.getState() + " (should be OPEN)");

    // Subsequent requests fail fast without calling handler
    System.out.println("\nAttempt 4: Circuit is open, fast-fail");
    Result<String, Exception> result4 = breaker.execute("request-4", request -> {
      throw new AssertionError("Handler should not be called");
    });

    if (result4 instanceof Result.CircuitOpen<String, Exception> open) {
      System.out.println("Fast-fail response: Circuit is open for " + open.remainingWaitTimeMs() + "ms");
    }
  }

  /**
   * Example 3: Adaptive exponential backoff with jitter.
   *
   * <p>The circuit breaker calculates exponential backoff: delay = min_delay * (2 ^ attempt) +
   * jitter, capped at max_delay. This is useful for implementing retry logic.
   */
  static void exampleAdaptiveBackoff() {
    System.out.println("\n=== Example 3: Adaptive Exponential Backoff ===\n");

    var breaker =
        IntelligentCircuitBreaker.create(
            "db-service",
            5,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofMillis(100),
            Duration.ofSeconds(32)); // max 32 seconds

    System.out.println("Backoff strategy: exponential with jitter");
    System.out.println("Initial backoff: 100ms, Max backoff: 32s");

    // Simulate retry attempts with increasing backoff
    System.out.println("\nBackoff delays for successive retries:");
    for (int attempt = 0; attempt < 5; attempt++) {
      Duration backoff = breaker.calculateBackoff();
      System.out.printf("Attempt %d: backoff = %dms%n", attempt, backoff.toMillis());

      // Record the failure to increment retry counter
      breaker.recordRetryFailure(new Exception("db connection failed"));
    }

    System.out.println("\nAfter many retries, backoff is capped at max (32000ms)");
    Duration backoff = breaker.calculateBackoff();
    System.out.println("Final backoff: " + backoff.toMillis() + "ms");
  }

  /**
   * Example 4: Recovery prediction based on learned patterns.
   *
   * <p>The circuit breaker tracks recovery times and can predict when the service is likely to be
   * available again.
   */
  static void exampleRecoveryPrediction() {
    System.out.println("\n=== Example 4: Recovery Prediction ===\n");

    var breaker =
        IntelligentCircuitBreaker.create(
            "cache-service",
            5,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofMillis(100),
            Duration.ofSeconds(30));

    // Simulate a failure and recovery to build history
    System.out.println("Recording failure and successful recovery:");
    Exception ex = new SocketTimeoutException("Redis timeout");

    breaker.execute("get-cache-123", request -> {
      throw ex;
    });

    // Record retry success
    breaker.recordRetrySuccess(ex);
    breaker.recordRetrySuccess(ex);

    // Check predictions
    Optional<Duration> predictedRecovery = breaker.predictRecoveryTime();
    if (predictedRecovery.isPresent()) {
      System.out.println("Predicted recovery time: " + predictedRecovery.get().toMillis() + "ms");
    } else {
      System.out.println("Not enough data to predict recovery time");
    }

    // View detailed statistics
    System.out.println("\nDetailed failure statistics:");
    var stats = breaker.getFailureStats(SocketTimeoutException.class);
    if (stats.isPresent()) {
      System.out.println(stats.get());
    }
  }

  /**
   * Example 5: Preventing cascading failures.
   *
   * <p>A cascading failure occurs when one service failure causes downstream services to fail.
   * The IntelligentCircuitBreaker prevents this by:
   *
   * <ul>
   *   <li>Opening the circuit to fail fast instead of hammering a failing service
   *   <li>Reducing load on the failing dependency
   *   <li>Allowing time for recovery
   * </ul>
   */
  static void exampleCascadingFailurePrevention() {
    System.out.println("\n=== Example 5: Cascading Failure Prevention ===\n");

    var breaker =
        IntelligentCircuitBreaker.create(
            "upstream-api",
            3,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofMillis(100),
            Duration.ofSeconds(30));

    System.out.println("Simulating cascading failure scenario:");
    System.out.println("Request 1: Transient failure (timeout)");

    // Three transient failures accumulate
    for (int i = 0; i < 3; i++) {
      breaker.execute("req-" + i, req -> {
        throw new SocketTimeoutException("Service unavailable");
      });
      System.out.println("  Attempt " + (i + 1) + " failed. State: " + breaker.getState());
    }

    // One more failure opens the circuit
    System.out.println("\nRequest 4: Circuit opens");
    breaker.execute("req-3", req -> {
      throw new SocketTimeoutException("Service unavailable");
    });
    System.out.println("Circuit state: " + breaker.getState());

    // Now cascading requests fail fast
    System.out.println("\nRequests 5+: Fast-fail (no handler invocation)");
    for (int i = 4; i < 6; i++) {
      var result = breaker.execute("req-" + i, req -> {
        throw new AssertionError("Should not reach handler");
      });

      switch (result) {
        case Result.CircuitOpen<String, Exception> ignored ->
            System.out.println("  Request " + (i + 1) + ": Fast-fail (circuit open)");
        case Result.Failure<String, Exception> ignored ->
            System.out.println("  Request " + (i + 1) + ": Handler executed");
        case Result.Success<String, Exception> ignored ->
            System.out.println("  Request " + (i + 1) + ": Success");
      }
    }

    System.out.println("\nResult: Upstream service received only 3-4 requests before circuit");
    System.out.println("        opened, reducing cascading load by ~50%+");
  }

  // ── Helper Exceptions ──────────────────────────────────────────────────

  static class SocketTimeoutException extends Exception {
    SocketTimeoutException(String msg) {
      super(msg);
    }
  }

  static class BadRequestException extends Exception {
    BadRequestException(String msg) {
      super(msg);
    }
  }
}
