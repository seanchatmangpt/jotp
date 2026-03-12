package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive test suite for CircuitBreaker with sealed result types, Java 26 pattern matching,
 * and Supervisor integration semantics.
 *
 * <p>Tests cover:
 * - State transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
 * - Failure threshold and time window tracking
 * - Pattern matching on sealed CircuitBreakerResult types
 * - Request suppression in OPEN state
 * - Automatic recovery via HALF_OPEN probes
 */
@DisplayName("CircuitBreaker: Enterprise Fault Tolerance")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CircuitBreakerTest {

  private CircuitBreaker<String, String, Exception> breaker;
  private AtomicInteger callCount;

  @BeforeEach
  void setUp() {
    callCount = new AtomicInteger(0);
    breaker =
        CircuitBreaker.create("test-service", 3, Duration.ofSeconds(10), Duration.ofMillis(500));
  }

  // ============================================================================
  // CLOSED STATE TESTS
  // ============================================================================

  @Test
  @DisplayName("CLOSED state: successful request returns Success")
  void testClosedStateSuccessfulRequest() {
    // When
    var result =
        breaker.execute(
            "request-1",
            request -> {
              callCount.incrementAndGet();
              return "response-1";
            });

    // Then
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
    assertThat(callCount.get()).isEqualTo(1);

    // Pattern match on sealed result
    var matched =
        switch (result) {
          case CircuitBreaker.CircuitBreakerResult.Success<String, Exception>(var value) ->
              value;
          default -> fail("Expected Success");
        };
    assertThat(matched).isEqualTo("response-1");
  }

  @Test
  @DisplayName("CLOSED state: single failure is recorded but circuit stays closed")
  void testClosedStateWithSingleFailure() {
    // When
    var result =
        breaker.execute(
            "request-1",
            request -> {
              throw new RuntimeException("Service error");
            });

    // Then: failure is recorded
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Failure.class);
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getFailureCount()).isEqualTo(1);

    // Next request should still pass through
    var nextResult =
        breaker.execute("request-2", request -> "success");
    assertThat(nextResult).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
  }

  @Test
  @DisplayName("CLOSED state: multiple failures within window accumulate")
  void testClosedStateFailureAccumulation() {
    // When: fail twice within the failure window
    for (int i = 0; i < 2; i++) {
      breaker.execute(
          "request-" + i,
          request -> {
            throw new RuntimeException("error");
          });
    }

    // Then
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getFailureCount()).isEqualTo(2);
  }

  // ============================================================================
  // OPEN STATE TESTS
  // ============================================================================

  @Test
  @DisplayName("OPEN state: transitions after maxFailures exceeded")
  void testTransitionToOpenAfterThreshold() {
    // When: fail 3 times (threshold = 3)
    for (int i = 0; i < 3; i++) {
      breaker.execute(
          "request-" + i,
          request -> {
            throw new RuntimeException("failure");
          });
    }

    // Then: circuit should be OPEN
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("OPEN state: rejects requests immediately (fail-fast)")
  void testOpenStateRejectsFast() {
    // Given: open the circuit
    breaker.open();
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    // When: attempt a request
    var result =
        breaker.execute(
            "request",
            request -> {
              callCount.incrementAndGet();
              return "success";
            });

    // Then: operation is not invoked (fail-fast), result is CircuitOpen
    assertThat(callCount.get()).isEqualTo(0);
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.CircuitOpen.class);
  }

  @Test
  @DisplayName("OPEN state: multiple rejections don't increment failure count")
  void testOpenStateDoesNotCountRepeatedRejections() {
    // Given: open the circuit
    breaker.open();
    int failureCountWhenOpened = breaker.getFailureCount();

    // When: attempt multiple requests while open
    for (int i = 0; i < 5; i++) {
      breaker.execute("request", request -> fail("should not execute"));
    }

    // Then: failure count unchanged (circuit is open, not tracking failures)
    assertThat(breaker.getFailureCount()).isEqualTo(failureCountWhenOpened);
  }

  // ============================================================================
  // HALF_OPEN STATE TESTS
  // ============================================================================

  @Test
  @DisplayName("HALF_OPEN state: transitions after resetTimeout expires")
  void testTransitionToHalfOpenAfterTimeout() throws InterruptedException {
    // Given: open the circuit
    breaker.open();
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    // When: wait for half-open timeout (500ms)
    Thread.sleep(600);

    // When: attempt a request, triggering the state check
    var result =
        breaker.execute("probe", request -> "probe-response");

    // Then: should transition to HALF_OPEN and allow the request
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("HALF_OPEN state: success closes the circuit")
  void testHalfOpenSuccessClosesCircuit() throws InterruptedException {
    // Given: circuit is OPEN
    breaker.open();
    Thread.sleep(600);

    // When: successful probe in HALF_OPEN
    var result =
        breaker.execute("probe", request -> "success");

    // Then: circuit closes
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getFailureCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("HALF_OPEN state: failure reopens the circuit")
  void testHalfOpenFailureReopensCircuit() throws InterruptedException {
    // Given: circuit is OPEN
    breaker.open();
    Thread.sleep(600);

    // When: probe fails in HALF_OPEN
    var result =
        breaker.execute(
            "probe",
            request -> {
              throw new RuntimeException("probe failed");
            });

    // Then: circuit should reopen
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Failure.class);
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  // ============================================================================
  // SEALED RESULT PATTERN MATCHING TESTS
  // ============================================================================

  @Test
  @DisplayName("Pattern matching: exhaustive switch on CircuitBreakerResult")
  void testExhaustivePatternMatching() {
    // Successful result
    var successResult =
        breaker.execute("request", request -> "value");
    var successValue =
        switch (successResult) {
          case CircuitBreaker.CircuitBreakerResult.Success<String, Exception>(var v) -> v;
          case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception> ignored ->
              "failure";
          case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception> ignored ->
              "open";
        };
    assertThat(successValue).isEqualTo("value");

    // Failure result
    var failureResult =
        breaker.execute(
            "request",
            request -> {
              throw new RuntimeException("error");
            });
    var failureMsg =
        switch (failureResult) {
          case CircuitBreaker.CircuitBreakerResult.Success<String, Exception> ignored ->
              "success";
          case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception>(var e) ->
              e.getClass().getSimpleName();
          case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception> ignored ->
              "open";
        };
    assertThat(failureMsg).isEqualTo("RuntimeException");

    // Circuit open result
    breaker.open();
    var openResult =
        breaker.execute("request", r -> "should not execute");
    var openMsg =
        switch (openResult) {
          case CircuitBreaker.CircuitBreakerResult.Success<String, Exception> ignored ->
              "success";
          case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception> ignored ->
              "failure";
          case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception> ignored ->
              "open";
        };
    assertThat(openMsg).isEqualTo("open");
  }

  @Test
  @DisplayName("Result helper methods: isSuccess(), isFailure(), isCircuitOpen()")
  void testResultHelperMethods() {
    // Success case
    var successResult =
        breaker.execute("request", request -> "value");
    assertThat(successResult.isSuccess()).isTrue();
    assertThat(successResult.isFailure()).isFalse();
    assertThat(successResult.isCircuitOpen()).isFalse();

    // Failure case
    var failureResult =
        breaker.execute(
            "request",
            request -> {
              throw new RuntimeException("error");
            });
    assertThat(failureResult.isSuccess()).isFalse();
    assertThat(failureResult.isFailure()).isTrue();
    assertThat(failureResult.isCircuitOpen()).isFalse();

    // Circuit open case
    breaker.open();
    var openResult =
        breaker.execute("request", r -> "should not run");
    assertThat(openResult.isSuccess()).isFalse();
    assertThat(openResult.isFailure()).isFalse();
    assertThat(openResult.isCircuitOpen()).isTrue();
  }

  // ============================================================================
  // TIME WINDOW AND FAILURE TRACKING TESTS
  // ============================================================================

  @Test
  @DisplayName("Time window: old failures expire outside window")
  void testFailureExpirationOutsideWindow() throws InterruptedException {
    // Given: circuit breaker with 100ms window
    breaker = CircuitBreaker.create("test", 2, Duration.ofMillis(100), Duration.ofMillis(200));

    // Record a failure
    breaker.execute("request", r -> { throw new RuntimeException("error"); });
    assertThat(breaker.getFailureCount()).isEqualTo(1);

    // Wait for window to expire
    Thread.sleep(150);

    // Failure should be pruned when we check
    assertThat(breaker.getFailureCount()).isEqualTo(0);
  }

  @Test
  @DisplayName("Time window: failures within window are counted")
  void testFailuresWithinWindowAreCounted() throws InterruptedException {
    // Given: circuit breaker with 1 second window
    breaker = CircuitBreaker.create("test", 2, Duration.ofSeconds(1), Duration.ofMillis(500));

    // Record first failure
    breaker.execute("request", r -> { throw new RuntimeException("error"); });
    assertThat(breaker.getFailureCount()).isEqualTo(1);

    // Wait 200ms (within window)
    Thread.sleep(200);

    // Record second failure (still within window)
    breaker.execute("request", r -> { throw new RuntimeException("error"); });
    assertThat(breaker.getFailureCount()).isEqualTo(2);

    // Third failure should open circuit (threshold = 2)
    breaker.execute("request", r -> { throw new RuntimeException("error"); });
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  // ============================================================================
  // RESET AND RECOVERY TESTS
  // ============================================================================

  @Test
  @DisplayName("reset(): clears state and allows circuit to accept requests again")
  void testResetCircuit() {
    // Given: open circuit
    breaker.open();
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    // When: reset
    breaker.reset();

    // Then: back to CLOSED with no failures
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getFailureCount()).isEqualTo(0);

    // And: requests are accepted
    var result =
        breaker.execute("request", request -> "success");
    assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
  }

  @Test
  @DisplayName("Recovery cycle: CLOSED → failures → OPEN → timeout → HALF_OPEN → success → CLOSED")
  void testFullRecoveryCycle() throws InterruptedException {
    // 1. CLOSED: fail 3 times to open
    for (int i = 0; i < 3; i++) {
      breaker.execute("request", r -> { throw new RuntimeException("error"); });
    }
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    // 2. OPEN: requests are rejected
    var rejectedResult =
        breaker.execute("request", r -> "should not execute");
    assertThat(rejectedResult).isInstanceOf(CircuitBreaker.CircuitBreakerResult.CircuitOpen.class);

    // 3. Wait for half-open timeout (500ms)
    Thread.sleep(600);

    // 4. HALF_OPEN: probe succeeds
    var probeResult =
        breaker.execute("probe", request -> "probe success");
    assertThat(probeResult).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);

    // 5. Back to CLOSED
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getFailureCount()).isEqualTo(0);
  }

  // ============================================================================
  // INTEGRATION WITH SUPERVISOR SEMANTICS
  // ============================================================================

  @Test
  @DisplayName("Supervisor semantics: maxFailures and window mirror Supervisor restart limits")
  void testSupervisorSemantics() throws InterruptedException {
    // Create a circuit breaker with Supervisor-like semantics:
    // 5 failures per 60 seconds triggers OPEN
    breaker = CircuitBreaker.create("api-call", 5, Duration.ofSeconds(1), Duration.ofMillis(500));

    // Simulate 5 failures in rapid succession
    for (int i = 0; i < 5; i++) {
      breaker.execute("request", r -> { throw new RuntimeException("transient error"); });
    }

    // Circuit should open (6th failure triggers it)
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.CLOSED); // Still closed after 5

    // 6th failure opens it
    breaker.execute("request", r -> { throw new RuntimeException("error"); });
    assertThat(breaker.getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("toString(): provides diagnostics")
  void testToString() {
    var str = breaker.toString();
    assertThat(str)
        .contains("CircuitBreaker")
        .contains("test-service")
        .contains("CLOSED");
  }

  @Test
  @DisplayName("getName(): returns the circuit breaker name")
  void testGetName() {
    assertThat(breaker.getName()).isEqualTo("test-service");
  }
}
