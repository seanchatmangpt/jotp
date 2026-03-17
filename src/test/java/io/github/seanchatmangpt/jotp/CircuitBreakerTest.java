package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
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
 * <p>Tests cover: - State transitions (CLOSED → OPEN → HALF_OPEN → CLOSED) - Failure threshold and
 * time window tracking - Pattern matching on sealed CircuitBreakerResult types - Request
 * suppression in OPEN state - Automatic recovery via HALF_OPEN probes
 */
@DisplayName("CircuitBreaker: Enterprise Fault Tolerance")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CircuitBreakerTest {

    private CircuitBreaker<String, String, Exception> breaker;
    private AtomicInteger callCount;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        callCount = new AtomicInteger(0);
        breaker =
                CircuitBreaker.create(
                        "test-service", 3, Duration.ofSeconds(10), Duration.ofMillis(500));
    }

    // ============================================================================
    // CLOSED STATE TESTS
    // ============================================================================

    @Test
    @DisplayName("CLOSED state: successful request returns Success")
    void testClosedStateSuccessfulRequest() {
                "In CLOSED state, the circuit breaker allows all requests to pass through. "
                        + "This is the normal operating state where the service is healthy. "
                        + "Failures are tracked but don't block requests until the threshold is reached.");

        // CROSS-REFERENCE: Link to Supervisor (fault tolerance foundation)

                """
            var breaker = CircuitBreaker.create("test-service", 3, Duration.ofSeconds(10), Duration.ofMillis(500));
            var result = breaker.execute("request-1", request -> {
                callCount.incrementAndGet();
                return "response-1";
            });

            // In CLOSED state: requests execute normally
            // Success returns CircuitBreakerResult.Success<T>
            """,
                "java");

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
                    case CircuitBreaker.CircuitBreakerResult.Success<String, Exception>(
                                    var value) ->
                            value;
                    default -> fail("Expected Success");
                };
        assertThat(matched).isEqualTo("response-1");

                Map.of(
                        "State",
                        "CLOSED",
                        "Requests Executed",
                        String.valueOf(callCount.get()),
                        "Result Type",
                        "Success",
                        "Response Value",
                        String.valueOf(matched)));
    }

    @Test
    @DisplayName("CLOSED state: single failure is recorded but circuit stays closed")
    void testClosedStateWithSingleFailure() {
                "In CLOSED state, failures are tracked but requests still pass through. "
                        + "The circuit only opens when the failure threshold is reached. "
                        + "This allows for transient failures without blocking legitimate requests.");

                """
            var result = breaker.execute("request-1", request -> {
                throw new RuntimeException("Service error");
            });

            // Failure is recorded but circuit stays CLOSED
            // Next request still executes
            var nextResult = breaker.execute("request-2", request -> "success");
            """,
                "java");

        // When
        var result =
                breaker.execute(
                        "request-1",
                        request -> {
                            throw new RuntimeException("Service error");
                        });

        // Then: failure is recorded
        assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Failure.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getFailureCount()).isEqualTo(1);

        // Next request should still pass through
        var nextResult = breaker.execute("request-2", request -> "success");
        assertThat(nextResult).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);

                Map.of(
                        "State After Failure",
                        "CLOSED",
                        "Failure Count",
                        String.valueOf(breaker.getFailureCount()),
                        "Next Request Result",
                        "Success (still allowed)"));
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
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getFailureCount()).isEqualTo(2);
    }

    // ============================================================================
    // OPEN STATE TESTS
    // ============================================================================

    @Test
    @DisplayName("OPEN state: transitions after maxFailures exceeded")
    void testTransitionToOpenAfterThreshold() {
                "When failures reach the threshold (maxFailures), the circuit transitions to OPEN state. "
                        + "This is the fail-fast mechanism that prevents cascading failures by blocking requests "
                        + "to a known-unhealthy service.");

                """
            // Fail 3 times (threshold = 3)
            for (int i = 0; i < 3; i++) {
                breaker.execute("request-" + i, request -> {
                    throw new RuntimeException("failure");
                });
            }

            // Circuit transitions to OPEN
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            """,
                "java");

        // When: fail 3 times (threshold = 3)
        for (int i = 0; i < 3; i++) {
            breaker.execute(
                    "request-" + i,
                    request -> {
                        throw new RuntimeException("failure");
                    });
        }

        // Then: circuit should be OPEN
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

                Map.of(
                        "Threshold Reached",
                        "3 failures",
                        "New State",
                        "OPEN",
                        "Request Behavior",
                        "Fail-fast (rejected)"));
    }

    @Test
    @DisplayName("OPEN state: rejects requests immediately (fail-fast)")
    void testOpenStateRejectsFast() {
                "In OPEN state, the circuit breaker rejects requests immediately without executing them. "
                        + "This fail-fast behavior protects the system from waiting on timeouts for "
                        + "known-unhealthy services. Returns CircuitOpen result instead of executing.");

                """
            breaker.open(); // Force circuit to OPEN

            var result = breaker.execute("request", request -> {
                callCount.incrementAndGet(); // Never runs
                return "success";
            });

            // Result is CircuitOpen - operation never executed
            assertThat(callCount.get()).isEqualTo(0);
            assertThat(result).isInstanceOf(CircuitBreakerResult.CircuitOpen.class);
            """,
                "java");

        // Given: open the circuit
        breaker.open();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

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

                Map.of(
                        "State",
                        "OPEN",
                        "Operation Invoked",
                        "No (fail-fast)",
                        "Result Type",
                        "CircuitOpen",
                        "Latency Impact",
                        "< 1ms (no remote call)"));
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
                "After resetTimeout expires, the circuit transitions to HALF_OPEN state to test "
                        + "if the service has recovered. A single probe request is allowed - success closes "
                        + "the circuit, failure reopens it.");

                """
                stateDiagram-v2
                    [*] --> CLOSED
                    CLOSED --> OPEN: failures >= threshold
                    OPEN --> HALF_OPEN: after resetTimeout
                    HALF_OPEN --> CLOSED: probe succeeds
                    HALF_OPEN --> OPEN: probe fails
                    CLOSED --> CLOSED: reset()

                    note right of CLOSED
                        Normal operation
                        All requests pass through
                        Failures tracked
                    end note

                    note right of OPEN
                        Fail-fast mode
                        All requests rejected
                        No calls to service
                    end note

                    note right of HALF_OPEN
                        Recovery probe
                        One request allowed
                        Success → CLOSED
                        Failure → OPEN
                    end note
                """);

                """
            breaker.open();
            Thread.sleep(600); // Wait for resetTimeout

            // First request after timeout triggers HALF_OPEN probe
            var result = breaker.execute("probe", request -> "probe-response");

            // Success closes the circuit
            assertThat(result).isInstanceOf(CircuitBreakerResult.Success.class);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            """,
                "java");

        // Given: open the circuit
        breaker.open();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // When: wait for half-open timeout (500ms)
        Thread.sleep(600);

        // When: attempt a request, triggering the state check
        var result = breaker.execute("probe", request -> "probe-response");

        // Then: should transition to HALF_OPEN and allow the request
        assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

                Map.of(
                        "Initial State",
                        "OPEN",
                        "After Timeout",
                        "HALF_OPEN",
                        "Probe Result",
                        "Success",
                        "Final State",
                        "CLOSED"));
    }

    @Test
    @DisplayName("HALF_OPEN state: success closes the circuit")
    void testHalfOpenSuccessClosesCircuit() throws InterruptedException {
                "When the probe request in HALF_OPEN state succeeds, the circuit closes. "
                        + "Failure count is reset to 0, and normal operation resumes. "
                        + "This automatic recovery enables self-healing systems.");

                """
            breaker.open();
            Thread.sleep(600); // Transition to HALF_OPEN

            // Successful probe closes the circuit
            var result = breaker.execute("probe", request -> "success");

            assertThat(result).isInstanceOf(CircuitBreakerResult.Success.class);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.getFailureCount()).isEqualTo(0); // Reset
            """,
                "java");

        // Given: circuit is OPEN
        breaker.open();
        Thread.sleep(600);

        // When: successful probe in HALF_OPEN
        var result = breaker.execute("probe", request -> "success");

        // Then: circuit closes
        assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getFailureCount()).isEqualTo(0);

                Map.of(
                        "Probe Result",
                        "Success",
                        "New State",
                        "CLOSED",
                        "Failure Count",
                        "0 (reset)",
                        "Recovery Mode",
                        "Complete"));
    }

    @Test
    @DisplayName("HALF_OPEN state: failure reopens the circuit")
    void testHalfOpenFailureReopensCircuit() throws InterruptedException {
                "HALF_OPEN Probe Failure: When the probe request fails, the circuit reopens immediately. "
                        + "The resetTimeout starts again, preventing rapid oscillation between states. "
                        + "This backoff mechanism gives the service time to recover fully.");

                """
            breaker.open();
            Thread.sleep(600); // Transition to HALF_OPEN

            // Failed probe reopens the circuit
            var result = breaker.execute("probe", request -> {
                throw new RuntimeException("probe failed");
            });

            assertThat(result).isInstanceOf(CircuitBreakerResult.Failure.class);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            """,
                "java");

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
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

                Map.of(
                        "Probe Result",
                        "Failure",
                        "New State",
                        "OPEN (reopened)",
                        "Next Retry",
                        "After resetTimeout",
                        "Oscillation Prevention",
                        "Backoff timer"));
    }

    // ============================================================================
    // SEALED RESULT PATTERN MATCHING TESTS
    // ============================================================================

    @Test
    @DisplayName("Pattern matching: exhaustive switch on CircuitBreakerResult")
    void testExhaustivePatternMatching() {
        // Successful result
        var successResult = breaker.execute("request", request -> "value");
        var successValue =
                switch (successResult) {
                    case CircuitBreaker.CircuitBreakerResult.Success<String, Exception>(var v) -> v;
                    case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception> ignored ->
                            "failure";
                    case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception>
                                    ignored ->
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
                    case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception>
                                    ignored ->
                            "open";
                };
        assertThat(failureMsg).isEqualTo("RuntimeException");

        // Circuit open result
        breaker.open();
        var openResult = breaker.execute("request", r -> "should not execute");
        var openMsg =
                switch (openResult) {
                    case CircuitBreaker.CircuitBreakerResult.Success<String, Exception> ignored ->
                            "success";
                    case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception> ignored ->
                            "failure";
                    case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception>
                                    ignored ->
                            "open";
                };
        assertThat(openMsg).isEqualTo("open");
    }

    @Test
    @DisplayName("Result helper methods: isSuccess(), isFailure(), isCircuitOpen()")
    void testResultHelperMethods() {
        // Success case
        var successResult = breaker.execute("request", request -> "value");
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
        var openResult = breaker.execute("request", r -> "should not run");
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
        breaker.execute(
                "request",
                r -> {
                    throw new RuntimeException("error");
                });
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
        breaker.execute(
                "request",
                r -> {
                    throw new RuntimeException("error");
                });
        assertThat(breaker.getFailureCount()).isEqualTo(1);

        // Wait 200ms (within window)
        Thread.sleep(200);

        // Record second failure (still within window)
        breaker.execute(
                "request",
                r -> {
                    throw new RuntimeException("error");
                });
        assertThat(breaker.getFailureCount()).isEqualTo(2);

        // Third failure should open circuit (threshold = 2)
        breaker.execute(
                "request",
                r -> {
                    throw new RuntimeException("error");
                });
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ============================================================================
    // RESET AND RECOVERY TESTS
    // ============================================================================

    @Test
    @DisplayName("reset(): clears state and allows circuit to accept requests again")
    void testResetCircuit() {
        // Given: open circuit
        breaker.open();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // When: reset
        breaker.reset();

        // Then: back to CLOSED with no failures
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getFailureCount()).isEqualTo(0);

        // And: requests are accepted
        var result = breaker.execute("request", request -> "success");
        assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);
    }

    @Test
    @DisplayName(
            "Recovery cycle: CLOSED → failures → OPEN → timeout → HALF_OPEN → success → CLOSED")
    void testFullRecoveryCycle() throws InterruptedException {
                "The full lifecycle demonstrates automatic recovery: CLOSED accumulates failures, "
                        + "OPEN blocks requests, HALF_OPEN probes for recovery, and success returns to CLOSED.");

                new String[][] {
                    {"State", "Behavior", "Requests", "Failures"},
                    {"CLOSED", "Normal operation", "All pass through", "Tracked"}
                });

        // 1. CLOSED: fail 3 times to open
        for (int i = 0; i < 3; i++) {
            breaker.execute(
                    "request",
                    r -> {
                        throw new RuntimeException("error");
                    });
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2. OPEN: requests are rejected
        var rejectedResult = breaker.execute("request", r -> "should not execute");
        assertThat(rejectedResult)
                .isInstanceOf(CircuitBreaker.CircuitBreakerResult.CircuitOpen.class);

        // 3. Wait for half-open timeout (500ms)
        Thread.sleep(600);

        // 4. HALF_OPEN: probe succeeds
        var probeResult = breaker.execute("probe", request -> "probe success");
        assertThat(probeResult).isInstanceOf(CircuitBreaker.CircuitBreakerResult.Success.class);

        // 5. Back to CLOSED
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getFailureCount()).isEqualTo(0);

                Map.of(
                        "Step 1",
                        "CLOSED → OPEN (3 failures)",
                        "Step 2",
                        "OPEN: request rejected",
                        "Step 3",
                        "Wait 600ms for timeout",
                        "Step 4",
                        "HALF_OPEN: probe succeeds",
                        "Step 5",
                        "CLOSED: recovery complete"));
    }

    // ============================================================================
    // INTEGRATION WITH SUPERVISOR SEMANTICS
    // ============================================================================

    @Test
    @DisplayName("Supervisor semantics: maxFailures and window mirror Supervisor restart limits")
    void testSupervisorSemantics() throws InterruptedException {
                "CircuitBreaker mirrors OTP Supervisor restart semantics: maxFailures corresponds "
                        + "to Supervisor's max restart intensity, and the failure window corresponds to "
                        + "the Supervisor's time period. Both provide fault containment with automatic recovery.");

                """
            // Supervisor-like: 5 failures per 1 second triggers OPEN
            breaker = CircuitBreaker.create("api-call", 5, Duration.ofSeconds(1), Duration.ofMillis(500));

            // Simulate rapid failures
            for (int i = 0; i < 5; i++) {
                breaker.execute("request", r -> {
                    throw new RuntimeException("transient error");
                });
            }

            // Circuit opens (>= threshold)
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            """,
                "java");

        // Create a circuit breaker with Supervisor-like semantics:
        // 5 failures per 60 seconds triggers OPEN
        breaker =
                CircuitBreaker.create("api-call", 5, Duration.ofSeconds(1), Duration.ofMillis(500));

        // Simulate 5 failures in rapid succession
        for (int i = 0; i < 5; i++) {
            breaker.execute(
                    "request",
                    r -> {
                        throw new RuntimeException("transient error");
                    });
        }

        // Circuit opens when failures reach maxFailures (>= semantics)
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

                Map.of(
                        "maxFailures",
                        "5 (like Supervisor intensity)",
                        "Window",
                        "1 second (like Supervisor period)",
                        "Threshold Triggered",
                        "Yes (5 failures)",
                        "State",
                        "OPEN"));

                "CircuitBreaker complements Supervisor by preventing cascading failures at the "
                        + "service call level, while Supervisor handles process-level crashes. "
                        + "Use both for comprehensive fault tolerance.");
    }

    @Test
    @DisplayName("toString(): provides diagnostics")
    void testToString() {
        var str = breaker.toString();
        assertThat(str).contains("CircuitBreaker").contains("test-service").contains("CLOSED");
    }

    @Test
    @DisplayName("getName(): returns the circuit breaker name")
    void testGetName() {
        assertThat(breaker.getName()).isEqualTo("test-service");
    }

    // ============================================================================
    // LATENCY AND PERFORMANCE TESTS
    // ============================================================================

    @Test
    @DisplayName("Latency impact: OPEN state provides instant fail-fast")
    void testLatencyImpactOpenState() {
                "OPEN state provides instant fail-fast, avoiding network timeouts. "
                        + "This is critical for preventing cascading timeouts and thread pool exhaustion.");

                new String[][] {
                    {"Scenario", "Operation", "Latency", "Thread Usage"},
                    {
                        "Healthy Service (CLOSED)",
                        "Remote call executes",
                        "~50-200ms (network)",
                        "Blocked on I/O"
                    }
                });

                """
            breaker.open(); // Simulate unhealthy service

            long start = System.nanoTime();
            var result = breaker.execute("request", r -> "never executes");
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            // Result: CircuitOpen, latency: < 1ms
            assertThat(result).isInstanceOf(CircuitBreakerResult.CircuitOpen.class);
            assertThat(latencyMs).isLessThan(10); // Should be < 10ms
            """,
                "java");

        // Given: circuit is OPEN
        breaker.open();

        // When: measure latency
        long start = System.nanoTime();
        var result = breaker.execute("request", r -> "never executes");
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        // Then: instant fail-fast
        assertThat(result).isInstanceOf(CircuitBreaker.CircuitBreakerResult.CircuitOpen.class);
        assertThat(latencyMs).isLessThan(10); // Should be < 10ms

                Map.of(
                        "Latency",
                        latencyMs + "ms",
                        "Result Type",
                        "CircuitOpen",
                        "Remote Call Executed",
                        "No",
                        "Thread Blocked",
                        "No"));
    }
}
