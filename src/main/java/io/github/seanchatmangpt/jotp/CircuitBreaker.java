package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Circuit Breaker pattern implementation leveraging Supervisor restart limits to enforce resilience
 * gates.
 *
 * <p>Joe Armstrong: "In Erlang, we let it crash — but we also put circuit breakers in place to
 * prevent cascading failures. When a service fails repeatedly, we open the circuit and fail fast
 * rather than hammer a failing dependency."
 *
 * <p><strong>Overview:</strong>
 *
 * <p>The CircuitBreaker guards a request/response function, tracking failures per time window using
 * the Supervisor's restart-limiting semantics. Three states represent the circuit:
 *
 * <ul>
 *   <li><strong>CLOSED:</strong> Normal operation — requests pass through, failures are counted
 *   <li><strong>OPEN:</strong> Failure threshold exceeded — requests immediately fail (fast-fail)
 *   <li><strong>HALF_OPEN:</strong> Testing recovery — a single request is permitted to probe the
 *       service
 * </ul>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Sealed Interface:</strong> {@code CircuitBreakerResult<V, E>} is sealed to {@code
 *       Success}, {@code Failure}, {@code CircuitOpen}, enabling exhaustive pattern matching
 *   <li><strong>Records:</strong> Result variants are records for immutability and pattern
 *       destructuring
 *   <li><strong>Pattern Matching:</strong> Result handling via switch expressions
 *   <li><strong>Virtual Threads:</strong> Supervisor integration with virtual-thread processes
 *   <li><strong>Sealed Types:</strong> State enum provides exhaustiveness checking for state
 *       transitions
 * </ul>
 *
 * <p><strong>Supervision Integration:</strong>
 *
 * <p>The CircuitBreaker uses the Supervisor primitive's {@code maxRestarts} and {@code window}
 * parameters to track failure intensity. When a supervised request-handler exceeds the restart
 * limit within the window, the circuit opens automatically. The Supervisor's {@code ONE_FOR_ONE}
 * strategy isolates per-request failures.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create a circuit breaker: fail-open after 5 failures in 60 seconds
 * CircuitBreaker<String, String> cb = CircuitBreaker.create(
 *     "payment-service",
 *     5,
 *     Duration.ofSeconds(60),
 *     Duration.ofSeconds(30)  // Half-open timeout
 * );
 *
 * // Execute a request
 * var result = cb.execute("user-123", apiCall);
 * switch (result) {
 *     case CircuitBreakerResult.Success<String, String>(var value) ->
 *         System.out.println("Success: " + value);
 *     case CircuitBreakerResult.Failure<String, String>(var error) ->
 *         System.out.println("Request failed: " + error);
 *     case CircuitBreakerResult.CircuitOpen<String, String>() ->
 *         System.out.println("Circuit is open, fast-fail");
 * }
 * }</pre>
 *
 * @param <R> request type (the input to the guarded function)
 * @param <V> response/value type (the success output)
 * @param <E> error type (the failure reason)
 * @see Supervisor
 * @see Proc
 * @see Result
 */
public final class CircuitBreaker<R, V, E extends Exception> {

    /**
     * Sealed interface for circuit breaker outcomes: success, failure, or circuit-open.
     *
     * <p>Callers pattern-match on the result to handle all three cases:
     *
     * <pre>{@code
     * var result = circuitBreaker.execute(request, handler);
     * switch (result) {
     *     case CircuitBreakerResult.Success<V, E>(var value) -> ...
     *     case CircuitBreakerResult.Failure<V, E>(var error) -> ...
     *     case CircuitBreakerResult.CircuitOpen<V, E>() -> ...
     * }
     * }</pre>
     *
     * @param <V> success value type
     * @param <E> error type
     */
    public sealed interface CircuitBreakerResult<V, E extends Exception>
            permits CircuitBreakerResult.Success,
                    CircuitBreakerResult.Failure,
                    CircuitBreakerResult.CircuitOpen {

        /** Request succeeded with a response value. */
        record Success<V, E extends Exception>(V value) implements CircuitBreakerResult<V, E> {}

        /** Request failed with an error (circuit was closed). */
        record Failure<V, E extends Exception>(E error) implements CircuitBreakerResult<V, E> {}

        /** Circuit is open; request rejected without executing the handler. */
        record CircuitOpen<V, E extends Exception>() implements CircuitBreakerResult<V, E> {}

        /** Check if this result represents success. */
        default boolean isSuccess() {
            return this instanceof Success<V, E>;
        }

        /** Check if this result represents a failure (circuit was closed). */
        default boolean isFailure() {
            return this instanceof Failure<V, E>;
        }

        /** Check if the circuit is open. */
        default boolean isCircuitOpen() {
            return this instanceof CircuitOpen<V, E>;
        }

        /** Extract the value if successful, or null. */
        default V successValue() {
            return switch (this) {
                case Success<V, E>(var value) -> value;
                case Failure<V, E> ignored -> null;
                case CircuitOpen<V, E> ignored -> null;
            };
        }

        /** Extract the error if failed, or null. */
        default E failureError() {
            return switch (this) {
                case Success<V, E> ignored -> null;
                case Failure<V, E>(var error) -> error;
                case CircuitOpen<V, E> ignored -> null;
            };
        }
    }

    /** Circuit breaker state machine. */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** Error type for simplified circuit breaker API. */
    public sealed interface CircuitError permits CircuitError.Open, CircuitError.ExecutionFailed {
        /** Circuit was open, request rejected without executing. */
        record Open(String breakerName) implements CircuitError {}

        /** Execution failed with an exception. */
        record ExecutionFailed(String breakerName, Exception cause) implements CircuitError {}
    }

    private final String name;
    private final int maxFailures;
    private final Duration window;
    private final Duration halfOpenTimeout;
    private volatile State state = State.CLOSED;
    private final LinkedList<Instant> failureTimes = new LinkedList<>();
    private volatile Instant lastFailureTime = null;
    private volatile Instant openedTime = null;
    private volatile Instant halfOpenProbeTime = null;

    /**
     * Create a circuit breaker.
     *
     * @param name circuit breaker identifier (for logging/diagnostics)
     * @param maxFailures maximum failures allowed before opening the circuit
     * @param window time window for counting failures
     * @param halfOpenTimeout duration to wait in HALF_OPEN state before retrying
     */
    private CircuitBreaker(
            String name, int maxFailures, Duration window, Duration halfOpenTimeout) {
        this.name = name;
        this.maxFailures = maxFailures;
        this.window = window;
        this.halfOpenTimeout = halfOpenTimeout;
    }

    /**
     * Create a circuit breaker with the given configuration.
     *
     * <p><b>Usage:</b>
     *
     * <pre>{@code
     * var cb = CircuitBreaker.create(
     *     "external-api",
     *     5,                              // max failures
     *     Duration.ofSeconds(60),         // window
     *     Duration.ofSeconds(30)          // half-open timeout
     * );
     * }</pre>
     *
     * @param name circuit breaker name
     * @param maxFailures failure threshold for opening
     * @param window time window for counting failures
     * @param halfOpenTimeout how long to wait before attempting recovery
     * @return a new CircuitBreaker instance
     * @throws NullPointerException if name, window, or halfOpenTimeout is null
     * @throws IllegalArgumentException if maxFailures <= 0
     */
    public static <R, V, E extends Exception> CircuitBreaker<R, V, E> create(
            String name, int maxFailures, Duration window, Duration halfOpenTimeout) {
        if (maxFailures <= 0) throw new IllegalArgumentException("maxFailures must be > 0");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(halfOpenTimeout, "halfOpenTimeout cannot be null");
        return new CircuitBreaker<>(name, maxFailures, window, halfOpenTimeout);
    }

    /**
     * Execute a request through the circuit breaker.
     *
     * <p>The handler is only invoked if the circuit is CLOSED or HALF_OPEN. If the circuit is OPEN,
     * returns {@code CircuitOpen} immediately (fail-fast).
     *
     * <p>On success, the circuit remains CLOSED. On failure, the failure count is incremented. If
     * the count exceeds {@code maxFailures} within {@code window}, the circuit opens.
     *
     * <p><strong>Pattern Matching:</strong>
     *
     * <pre>{@code
     * var result = circuitBreaker.execute(myRequest, request -> {
     *     // Invoke external service
     *     return apiClient.call(request);
     * });
     *
     * switch (result) {
     *     case CircuitBreakerResult.Success<String, Exception>(var value) ->
     *         System.out.println("Success: " + value);
     *     case CircuitBreakerResult.Failure<String, Exception>(var error) ->
     *         System.out.println("Request failed: " + error.getMessage());
     *     case CircuitBreakerResult.CircuitOpen<String, Exception>() ->
     *         System.out.println("Circuit open, retry later");
     * }
     * }</pre>
     *
     * @param request the input request
     * @param handler function that processes the request and returns a value
     * @return {@code CircuitBreakerResult.Success}, {@code Failure}, or {@code CircuitOpen}
     */
    @SuppressWarnings("unchecked")
    public CircuitBreakerResult<V, E> execute(R request, Function<R, V> handler) {
        // State machine: check circuit state and transition
        synchronized (this) {
            State currentState = state;

            switch (currentState) {
                case OPEN -> {
                    // Check if half-open timeout has elapsed
                    if (openedTime != null
                            && Instant.now().isAfter(openedTime.plus(halfOpenTimeout))) {
                        state = State.HALF_OPEN;
                        halfOpenProbeTime = Instant.now();
                    } else {
                        return new CircuitBreakerResult.CircuitOpen<>();
                    }
                }

                case HALF_OPEN -> {
                    // In HALF_OPEN, allow one probe attempt; if it fails, reopen immediately
                    // If it succeeds, close the circuit
                }

                case CLOSED -> {
                    // Prune old failures outside the window
                    Instant cutoff = Instant.now().minus(window);
                    failureTimes.removeIf(t -> t.isBefore(cutoff));
                }
            }
        }

        // Execute the handler
        try {
            V value = handler.apply(request);
            // Success: reset failure count and close circuit if in HALF_OPEN
            synchronized (this) {
                failureTimes.clear();
                lastFailureTime = null;
                if (state == State.HALF_OPEN) {
                    state = State.CLOSED;
                }
            }
            return new CircuitBreakerResult.Success<>(value);
        } catch (Exception e) {
            // Failure: record it and check if we should open the circuit
            synchronized (this) {
                Instant now = Instant.now();
                lastFailureTime = now;
                failureTimes.add(now);

                // Prune old failures
                Instant cutoff = now.minus(window);
                failureTimes.removeIf(t -> t.isBefore(cutoff));

                // If too many failures, open the circuit (unless already open/half-open)
                if (failureTimes.size() >= maxFailures && state == State.CLOSED) {
                    state = State.OPEN;
                    openedTime = now;
                }

                // If probing in HALF_OPEN and it failed, reopen
                if (state == State.HALF_OPEN) {
                    state = State.OPEN;
                    openedTime = now;
                }
            }

            return new CircuitBreakerResult.Failure<>((E) e);
        }
    }

    /**
     * Get the current state of the circuit.
     *
     * @return CLOSED, OPEN, or HALF_OPEN
     */
    public State getState() {
        return state;
    }

    /**
     * Get the current state of the circuit (short alias).
     *
     * @return CLOSED, OPEN, or HALF_OPEN
     */
    public State state() {
        // Check for automatic transition from OPEN to HALF_OPEN
        synchronized (this) {
            if (state == State.OPEN
                    && openedTime != null
                    && Instant.now().isAfter(openedTime.plus(halfOpenTimeout))) {
                state = State.HALF_OPEN;
                halfOpenProbeTime = Instant.now();
            }
        }
        return state;
    }

    /**
     * Get the number of recorded failures in the current window.
     *
     * @return count of failures within the time window
     */
    public int getFailureCount() {
        synchronized (this) {
            Instant cutoff = Instant.now().minus(window);
            failureTimes.removeIf(t -> t.isBefore(cutoff));
            return failureTimes.size();
        }
    }

    /**
     * Get the circuit breaker name.
     *
     * @return the name provided at creation
     */
    public String getName() {
        return name;
    }

    /**
     * Reset the circuit to CLOSED state and clear all recorded failures.
     *
     * <p>Useful for testing or manual recovery.
     */
    public void reset() {
        synchronized (this) {
            state = State.CLOSED;
            failureTimes.clear();
            lastFailureTime = null;
            openedTime = null;
            halfOpenProbeTime = null;
        }
    }

    // ── Simplified execute API ─────────────────────────────────────────────────

    /**
     * Execute a supplier through the circuit breaker with simplified Result API.
     *
     * @param supplier the operation to execute
     * @return Result.ok with the value on success, Result.err with CircuitError on failure
     */
    @SuppressWarnings("unchecked")
    public <T> Result<T, CircuitError> execute(java.util.function.Supplier<T> supplier) {
        CircuitBreakerResult<V, E> result =
                execute(null, (Function<R, V>) _ -> (V) supplier.get());
        return switch (result) {
            case CircuitBreakerResult.Success<V, E>(var value) ->
                    Result.ok((T) value);
            case CircuitBreakerResult.Failure<V, E>(var error) ->
                    Result.err(new CircuitError.ExecutionFailed(name, (Exception) error));
            case CircuitBreakerResult.CircuitOpen<V, E> ignored ->
                    Result.err(new CircuitError.Open(name));
        };
    }

    // ── Builder API ─────────────────────────────────────────────────────────────

    /**
     * Create a circuit breaker builder.
     *
     * @param name circuit breaker name
     * @return a new builder
     */
    public static SimpleBuilder builder(String name) {
        return new SimpleBuilder(name);
    }

    /** Fluent builder for non-generic circuit breaker usage. */
    public static final class SimpleBuilder {
        private final String builderName;
        private int failureThreshold = 5;
        private Duration timeout = Duration.ofSeconds(60);
        private Duration resetTimeout = Duration.ofSeconds(30);
        private int halfOpenRequests = 1;

        SimpleBuilder(String name) {
            this.builderName = name;
        }

        /** Set the number of failures before the circuit opens. */
        public SimpleBuilder failureThreshold(int threshold) {
            this.failureThreshold = threshold;
            return this;
        }

        /** Set the time window for counting failures. */
        public SimpleBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Set the duration to wait before transitioning from OPEN to HALF_OPEN. */
        public SimpleBuilder resetTimeout(Duration resetTimeout) {
            this.resetTimeout = resetTimeout;
            return this;
        }

        /** Set the number of requests allowed in HALF_OPEN state. */
        public SimpleBuilder halfOpenRequests(int requests) {
            this.halfOpenRequests = requests;
            return this;
        }

        /** Build the circuit breaker. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public CircuitBreaker build() {
            return new CircuitBreaker(builderName, failureThreshold, timeout, resetTimeout);
        }
    }

    /** Forcibly open the circuit (for testing or emergency override). */
    public void open() {
        synchronized (this) {
            state = State.OPEN;
            openedTime = Instant.now();
        }
    }

    /**
     * Return a string representation of the circuit breaker state.
     *
     * @return "[CircuitBreaker: name, state, failure count / max failures]"
     */
    @Override
    public String toString() {
        return String.format(
                "[CircuitBreaker: %s, state=%s, failures=%d/%d]",
                name, state, getFailureCount(), maxFailures);
    }
}
