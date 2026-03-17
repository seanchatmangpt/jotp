package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ai.IntelligentCircuitBreaker.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

@DisplayName("IntelligentCircuitBreaker Tests")
class IntelligentCircuitBreakerTest {

  private IntelligentCircuitBreaker<String, String, Exception> breaker;

  @BeforeEach
  void setup() {
    breaker =
        IntelligentCircuitBreaker.create(
            "test-breaker",
            5,
            Duration.ofSeconds(10),
            Duration.ofSeconds(2),
            Duration.ofMillis(100),
            Duration.ofSeconds(10));
  }

  // ── Basic Circuit Breaker Behavior ──────────────────────────────────────

  @Test
  @DisplayName("Circuit starts in CLOSED state")
  void testInitialStateClosed() {
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);
  }

  @Test
  @DisplayName("Successful request in CLOSED state remains CLOSED")
  void testSuccessfulRequestKeepsCircuitClosed() {
    var result = breaker.execute("test", r -> "success");

    assertThat(result).isInstanceOf(Result.Success.class);
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    assertThat(breaker.getConsecutiveFailures()).isZero();
  }

  @Test
  @DisplayName("Transient failure detected and classified")
  void testTransientFailureDetected() {
    var result = breaker.execute("test", r -> {
      throw new SocketTimeoutException("timeout");
    });

    assertThat(result).isInstanceOf(Result.Failure.class);
    var failure = (Result.Failure<String, Exception>) result;
    assertThat(failure.classification()).isEqualTo(FailureClassification.TRANSIENT);
  }

  @Test
  @DisplayName("Permanent failure detected and classified")
  void testPermanentFailureDetected() {
    var result = breaker.execute("test", r -> {
      throw new BadRequestException("invalid input");
    });

    assertThat(result).isInstanceOf(Result.Failure.class);
    var failure = (Result.Failure<String, Exception>) result;
    assertThat(failure.classification()).isEqualTo(FailureClassification.PERMANENT);
  }

  @Test
  @DisplayName("Unknown failure classified initially")
  void testUnknownFailureInitiallyClassified() {
    var result = breaker.execute("test", r -> {
      throw new Exception("generic error");
    });

    assertThat(result).isInstanceOf(Result.Failure.class);
    var failure = (Result.Failure<String, Exception>) result;
    assertThat(failure.classification()).isIn(
        FailureClassification.UNKNOWN, FailureClassification.TRANSIENT);
  }

  // ── Transient Failure Behavior ──────────────────────────────────────────

  @Test
  @DisplayName("Transient failures accumulate before opening circuit")
  void testTransientFailuresAccumulate() {
    // Transient failures don't open immediately, but accumulate
    for (int i = 0; i < 3; i++) {
      breaker.execute("test", r -> {
        throw new SocketTimeoutException("timeout");
      });
    }

    // Circuit should still be closed (allows more transient failures)
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    assertThat(breaker.getConsecutiveFailures()).isEqualTo(3);
    assertThat(breaker.getRecentFailureCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("Circuit opens after many transient failures")
  void testCircuitOpensAfterManyTransientFailures() {
    // Simulate 5 transient failures (at threshold)
    for (int i = 0; i < 5; i++) {
      breaker.execute("test", r -> {
        throw new SocketTimeoutException("timeout");
      });
    }

    // Circuit should now be OPEN
    assertThat(breaker.getState()).isEqualTo(State.OPEN);
  }

  @Test
  @DisplayName("Success resets consecutive failure counter")
  void testSuccessResetsCounter() {
    // Fail twice
    breaker.execute("test", r -> {
      throw new SocketTimeoutException("timeout");
    });
    breaker.execute("test", r -> {
      throw new SocketTimeoutException("timeout");
    });
    assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);

    // Succeed once
    breaker.execute("test", r -> "success");

    // Counter should be reset
    assertThat(breaker.getConsecutiveFailures()).isZero();
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);
  }

  // ── Permanent Failure Behavior ──────────────────────────────────────────

  @Test
  @DisplayName("Two permanent failures open the circuit immediately")
  void testPermanentFailuresOpenFastly() {
    // First permanent failure
    breaker.execute("test", r -> {
      throw new BadRequestException("invalid");
    });
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);

    // Second permanent failure opens immediately
    breaker.execute("test", r -> {
      throw new BadRequestException("invalid");
    });
    assertThat(breaker.getState()).isEqualTo(State.OPEN);
  }

  @Test
  @DisplayName("Permanent failures prevent cascading by fast-fail")
  void testPermanentFailurePreventsCascading() {
    // Two permanent failures to open
    breaker.execute("test", r -> {
      throw new NotFoundException("resource not found");
    });
    breaker.execute("test", r -> {
      throw new NotFoundException("resource not found");
    });
    assertThat(breaker.getState()).isEqualTo(State.OPEN);

    // Circuit open: requests fail without calling handler
    var result = breaker.execute("test", r -> {
      throw new AssertionError("handler should not be called");
    });

    assertThat(result).isInstanceOf(Result.CircuitOpen.class);
  }

  // ── HALF_OPEN and Recovery ─────────────────────────────────────────────

  @Test
  @DisplayName("Circuit transitions to HALF_OPEN after timeout")
  void testCircuitTransitionsToHalfOpen() throws InterruptedException {
    // Open the circuit
    breaker.open();
    assertThat(breaker.getState()).isEqualTo(State.OPEN);

    // Wait for half-open timeout
    Thread.sleep(2100); // halfOpenTimeout is 2 seconds

    // Execute a request, should transition to HALF_OPEN
    var result = breaker.execute("test", r -> "success");

    assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);
    assertThat(result).isInstanceOf(Result.Success.class);
  }

  @Test
  @DisplayName("Successful recovery closes circuit from HALF_OPEN")
  void testSuccessfulRecoveryCloseCircuit() throws InterruptedException {
    // Open the circuit
    breaker.open();
    Thread.sleep(2100);

    // Successful request in HALF_OPEN closes circuit
    var result = breaker.execute("test", r -> "recovered");

    assertThat(result).isInstanceOf(Result.Success.class);
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);
  }

  @Test
  @DisplayName("Failed recovery in HALF_OPEN reopens circuit")
  void testFailedRecoveryReopens() throws InterruptedException {
    // Open the circuit
    breaker.open();
    Thread.sleep(2100);

    // Failed request in HALF_OPEN reopens
    breaker.execute("test", r -> {
      throw new SocketTimeoutException("still down");
    });

    assertThat(breaker.getState()).isEqualTo(State.OPEN);
  }

  @Test
  @DisplayName("Multiple failed attempts in HALF_OPEN stay open")
  void testMultipleFailedAttemptsInHalfOpen() throws InterruptedException {
    // Open the circuit
    breaker.open();
    Thread.sleep(2100);

    // Fail multiple times in HALF_OPEN
    for (int i = 0; i < 3; i++) {
      var result = breaker.execute("test", r -> {
        throw new SocketTimeoutException("still down");
      });

      if (i < 2) {
        // First failures remain in HALF_OPEN
        assertThat(result).isInstanceOf(Result.Failure.class);
      } else {
        // After 3 failures, circuit reopens
        assertThat(result).isInstanceOf(Result.CircuitOpen.class);
      }
    }

    assertThat(breaker.getState()).isEqualTo(State.OPEN);
  }

  // ── Backoff Calculation ────────────────────────────────────────────────

  @Test
  @DisplayName("Backoff increases exponentially with attempts")
  void testExponentialBackoffIncrease() {
    Duration backoff0 = breaker.calculateBackoff();
    assertThat(backoff0.toMillis()).isGreaterThanOrEqualTo(100);

    breaker.recordRetryFailure(new Exception());
    Duration backoff1 = breaker.calculateBackoff();
    assertThat(backoff1.toMillis()).isGreaterThanOrEqualTo(backoff0.toMillis());

    breaker.recordRetryFailure(new Exception());
    Duration backoff2 = breaker.calculateBackoff();
    assertThat(backoff2.toMillis()).isGreaterThanOrEqualTo(backoff1.toMillis());
  }

  @Test
  @DisplayName("Backoff is capped at maximum")
  void testBackoffCapAtMaximum() {
    // Simulate many failed retries
    for (int i = 0; i < 20; i++) {
      breaker.recordRetryFailure(new Exception());
    }

    Duration backoff = breaker.calculateBackoff();
    assertThat(backoff.toMillis()).isLessThanOrEqualTo(10000); // maxBackoff
  }

  // ── Retry Decision Making ──────────────────────────────────────────────

  @Test
  @DisplayName("shouldRetry returns true for transient failures")
  void testShouldRetryTransient() {
    var result = breaker.execute("test", r -> {
      throw new SocketTimeoutException("timeout");
    });

    var failure = (Result.Failure<String, Exception>) result;
    boolean shouldRetry = breaker.shouldRetry(failure.error());

    assertThat(shouldRetry).isTrue();
  }

  @Test
  @DisplayName("shouldRetry returns false for permanent failures")
  void testShouldRetryPermanent() {
    var result = breaker.execute("test", r -> {
      throw new BadRequestException("invalid");
    });

    var failure = (Result.Failure<String, Exception>) result;
    boolean shouldRetry = breaker.shouldRetry(failure.error());

    assertThat(shouldRetry).isFalse();
  }

  @Test
  @DisplayName("shouldRetry explores unknown failures initially")
  void testShouldRetryExploresUnknown() {
    var result = breaker.execute("test", r -> {
      throw new Exception("unknown");
    });

    var failure = (Result.Failure<String, Exception>) result;
    boolean shouldRetry = breaker.shouldRetry(failure.error());

    // Unknown failures are explored initially
    assertThat(shouldRetry).isTrue();
  }

  // ── Recovery Prediction ────────────────────────────────────────────────

  @Test
  @DisplayName("Predicts recovery time from learned patterns")
  void testRecoveryTimePrediction() {
    // Simulate failures and recoveries
    breaker.execute("test", r -> {
      throw new SocketTimeoutException("timeout");
    });
    breaker.recordRetrySuccess(new SocketTimeoutException("timeout"));

    // Should be able to predict recovery time
    var prediction = breaker.predictRecoveryTime();
    assertThat(prediction).isPresent();
  }

  @Test
  @DisplayName("No prediction when insufficient history")
  void testNoPredictionWithoutHistory() {
    var prediction = breaker.predictRecoveryTime();
    assertThat(prediction).isEmpty();
  }

  // ── Statistics Tracking ────────────────────────────────────────────────

  @Test
  @DisplayName("Tracks retry success statistics")
  void testRetrySuccessStatistics() {
    Exception ex = new SocketTimeoutException("timeout");

    breaker.execute("test", r -> {
      throw ex;
    });

    breaker.recordRetrySuccess(ex);
    breaker.recordRetrySuccess(ex);
    breaker.recordRetryFailure(ex);

    var stats = breaker.getFailureStats(SocketTimeoutException.class);
    assertThat(stats).isPresent();
    assertThat(stats.get()).contains("Successful Retries: 2", "Failed Retries: 1");
  }

  @Test
  @DisplayName("Detailed stats includes all exception types")
  void testDetailedStatistics() {
    breaker.execute("test", r -> {
      throw new SocketTimeoutException("timeout");
    });
    breaker.execute("test", r -> {
      throw new BadRequestException("invalid");
    });

    String stats = breaker.getDetailedStats();

    assertThat(stats).contains("SocketTimeoutException");
    assertThat(stats).contains("BadRequestException");
  }

  // ── State Management ────────────────────────────────────────────────────

  @Test
  @DisplayName("Reset clears all state and statistics")
  void testResetClearsState() {
    // Simulate failures
    for (int i = 0; i < 5; i++) {
      breaker.execute("test", r -> {
        throw new SocketTimeoutException("timeout");
      });
    }

    assertThat(breaker.getState()).isEqualTo(State.OPEN);
    assertThat(breaker.getConsecutiveFailures()).isGreaterThan(0);

    // Reset
    breaker.reset();

    assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    assertThat(breaker.getConsecutiveFailures()).isZero();
    assertThat(breaker.getRecentFailureCount()).isZero();
  }

  @Test
  @DisplayName("Manual open overrides circuit state")
  void testManualOpen() {
    var result = breaker.execute("test", r -> "success");
    assertThat(breaker.getState()).isEqualTo(State.CLOSED);

    breaker.open();
    assertThat(breaker.getState()).isEqualTo(State.OPEN);

    var openResult = breaker.execute("test", r -> "success");
    assertThat(openResult).isInstanceOf(Result.CircuitOpen.class);
  }

  // ── Cascading Failure Prevention ────────────────────────────────────────

  @Test
  @DisplayName("Prevents cascading failures from transient issues")
  void testPreventsCascadingFromTransient() {
    AtomicInteger callCount = new AtomicInteger(0);

    // Simulate cascading calls with circuit breaker
    for (int i = 0; i < 10; i++) {
      var result = breaker.execute("test", r -> {
        callCount.incrementAndGet();
        throw new SocketTimeoutException("timeout");
      });

      // After circuit opens, handler is not called
      if (breaker.getState() == State.OPEN) {
        var nextResult = breaker.execute("test", r -> {
          callCount.incrementAndGet();
          throw new SocketTimeoutException("timeout");
        });
        assertThat(nextResult).isInstanceOf(Result.CircuitOpen.class);
        break;
      }
    }

    // Verify handler was not called for every attempt
    assertThat(callCount.get()).isLessThan(10);
  }

  @Test
  @DisplayName("Prevents cascading failures from permanent issues")
  void testPreventsCascadingFromPermanent() {
    AtomicInteger callCount = new AtomicInteger(0);

    // Permanent failures open circuit very quickly
    for (int i = 0; i < 5; i++) {
      var result = breaker.execute("test", r -> {
        callCount.incrementAndGet();
        throw new NotFoundException("not found");
      });

      if (breaker.getState() == State.OPEN) {
        // Subsequent requests should not call handler
        var nextResult = breaker.execute("test", r -> {
          throw new AssertionError("handler should not be called");
        });
        assertThat(nextResult).isInstanceOf(Result.CircuitOpen.class);
        break;
      }
    }

    // Handler was called only ~2 times before opening
    assertThat(callCount.get()).isLessThan(5);
  }

  // ── Integration Test: Mixed Transient/Permanent ─────────────────────────

  @Test
  @DisplayName("Handles mixed transient and permanent failures intelligently")
  void testMixedFailureScenario() {
    // Start with transient failures
    for (int i = 0; i < 3; i++) {
      breaker.execute("test", r -> {
        throw new SocketTimeoutException("timeout");
      });
    }

    // Then encounter permanent failure
    breaker.execute("test", r -> {
      throw new BadRequestException("invalid");
    });

    // Should open quickly due to permanent failure
    assertThat(breaker.getState()).isEqualTo(State.OPEN);

    // Verify subsequent requests fail fast
    var result = breaker.execute("test", r -> "success");
    assertThat(result).isInstanceOf(Result.CircuitOpen.class);
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

  static class NotFoundException extends Exception {
    NotFoundException(String msg) {
      super(msg);
    }
  }
}
