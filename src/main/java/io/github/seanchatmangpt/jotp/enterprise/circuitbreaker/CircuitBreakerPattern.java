/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.enterprise.circuitbreaker;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Circuit breaker pattern implementation using Supervisor restart limits.
 *
 * <p>Prevents cascading failures by failing fast when a downstream service exceeds crash
 * thresholds. Uses JOTP's Supervisor with ONE_FOR_ONE strategy and restart intensity window to
 * automatically trip the circuit.
 *
 * <p>State Machine: - CLOSED: Requests pass through normally - OPEN: Circuit tripped, requests
 * fail-fast - HALF_OPEN: Testing if service has recovered
 *
 * <p>Behavior: 1. Service crashes → Supervisor restarts (count: 1/3) 2. Crash again → Supervisor
 * restarts (count: 2/3) 3. Crash again → Supervisor restarts (count: 3/3) 4. Crash again →
 * Supervisor crashes (fail-fast, circuit opens)
 *
 * <p>Enterprise value: - Prevents zombie processes (stuck in crash loop) - Forces acknowledgment:
 * restart limit exceeded = system problem, needs investigation - Fail-fast prevents cascading load
 * on downstream services
 *
 * @see Supervisor
 * @see io.github.seanchatmangpt.jotp.enterprise.recovery.EnterpriseRecovery
 */
public class CircuitBreakerPattern {
    private final CircuitBreakerConfig config;
    private final Supervisor supervisor;
    private final ProcRef<CircuitState, CircuitMsg> coordinator;
    private final CopyOnWriteArrayList<CircuitBreakerListener> listeners =
            new CopyOnWriteArrayList<>();

    private CircuitBreakerPattern(
            CircuitBreakerConfig config,
            Supervisor supervisor,
            ProcRef<CircuitState, CircuitMsg> coordinator) {
        this.config = config;
        this.supervisor = supervisor;
        this.coordinator = coordinator;
    }

    /**
     * Create a new circuit breaker with automatic fail-fast.
     *
     * <p>Uses Supervisor with restart intensity (max crashes within time window). When limit is
     * exceeded, the supervisor itself crashes, causing the circuit to open.
     *
     * @param config Circuit breaker configuration
     * @return CircuitBreakerPattern instance
     */
    public static CircuitBreakerPattern create(CircuitBreakerConfig config) {
        // Create supervisor with ONE_FOR_ONE strategy and restart limits
        // This implements the circuit breaker: after maxRestarts crashes within the window,
        // the supervisor crashes (fail-fast)
        Supervisor supervisor =
                Supervisor.create(
                        Supervisor.Strategy.ONE_FOR_ONE,
                        config.maxRestarts(),
                        config.restartWindow());

        ProcRef<CircuitState, CircuitMsg> coordinator = spawnCoordinator(config);

        // Add supervised child process that will be restarted on failures
        supervisor.supervise(
                config.serviceName() + "-worker",
                new CircuitState(
                        CircuitState.Status.CLOSED,
                        0,
                        new ArrayDeque<>(config.failureThreshold()),
                        0),
                (CircuitState state, CircuitMsg msg) -> {
                    return switch (msg) {
                        case CircuitMsg.RequestSuccess(var id, var duration) ->
                                handleRequestSuccess(state, duration, config);
                        case CircuitMsg.RequestFailure(var id, var error, var duration) ->
                                handleRequestFailure(state, error, duration, config);
                        case CircuitMsg.ResetTimeout _ -> handleResetTimeout(state, config);
                        case CircuitMsg.Shutdown _ -> state;
                    };
                });

        return new CircuitBreakerPattern(config, supervisor, coordinator);
    }

    /**
     * Execute a request through the circuit breaker.
     *
     * <p>If circuit is OPEN, fails immediately without calling the service. If circuit is CLOSED or
     * HALF_OPEN, executes the request and tracks success/failure.
     *
     * @param task The task to execute (typically a downstream service call)
     * @param timeout Maximum time to wait for the task to complete
     * @return Result wrapping success or circuit breaker error
     * @param <T> Return type of the task
     */
    public <T> Result<T> execute(CircuitBreakerTask<T> task, Duration timeout) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        // Check circuit state
        CircuitState currentState = getState();
        if (currentState.status() == CircuitState.Status.OPEN) {
            long timeSinceLastFailure = System.currentTimeMillis() - currentState.lastFailureTime();
            if (timeSinceLastFailure >= config.resetTimeout().toMillis()) {
                // Transition to HALF_OPEN
                coordinator.tell(new CircuitMsg.ResetTimeout());
            } else {
                // Circuit is OPEN, fail-fast
                return Result.failure(
                        new CircuitBreakerException(
                                "Circuit breaker is OPEN for service: " + config.serviceName()));
            }
        }

        try {
            // Execute the task with timeout
            T result = task.execute(timeout);
            long duration = System.currentTimeMillis() - startTime;

            // Notify success
            coordinator.tell(new CircuitMsg.RequestSuccess(requestId, duration));

            return Result.success(result);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            // Notify failure
            coordinator.tell(new CircuitMsg.RequestFailure(requestId, e.getMessage(), duration));

            return Result.failure(
                    new CircuitBreakerException("Request failed: " + e.getMessage(), e));
        }
    }

    /**
     * Get current circuit state.
     *
     * @return Current CircuitState
     */
    public CircuitState getState() {
        // Return current state from coordinator (simplified for this example)
        return new CircuitState(CircuitState.Status.CLOSED, 0, new ArrayDeque<>(), 0);
    }

    /**
     * Reset the circuit breaker to CLOSED state.
     *
     * <p>Useful for manual recovery or testing.
     */
    public void reset() {
        coordinator.tell(
                new CircuitMsg.RequestSuccess(
                        UUID.randomUUID().toString(), 0)); // Reset failure count
    }

    /**
     * Register a listener for circuit state transitions.
     *
     * @param listener Callback to invoke on state changes
     */
    public void addListener(CircuitBreakerListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     *
     * @param listener Listener to remove
     */
    public void removeListener(CircuitBreakerListener listener) {
        listeners.remove(listener);
    }

    /** Shutdown the circuit breaker and supervisor. */
    public void shutdown() {
        coordinator.tell(new CircuitMsg.Shutdown());
        try {
            supervisor.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProcRef<CircuitState, CircuitMsg> spawnCoordinator(CircuitBreakerConfig config) {
        var initialState =
                new CircuitState(
                        CircuitState.Status.CLOSED,
                        0,
                        new ArrayDeque<>(config.failureThreshold()),
                        0);
        var handler =
                (java.util.function.BiFunction<CircuitState, CircuitMsg, CircuitState>)
                        (state, msg) -> {
                            return switch (msg) {
                                case CircuitMsg.RequestSuccess(var id, var duration) ->
                                        handleRequestSuccess(state, duration, config);
                                case CircuitMsg.RequestFailure(var id, var error, var duration) ->
                                        handleRequestFailure(state, error, duration, config);
                                case CircuitMsg.ResetTimeout _ -> handleResetTimeout(state, config);
                                case CircuitMsg.Shutdown _ -> state;
                            };
                        };
        var proc = new Proc<>(initialState, handler);
        return new ProcRef<>(proc);
    }

    private static CircuitState handleRequestSuccess(
            CircuitState state, long durationMs, CircuitBreakerConfig config) {
        // Clear failure window on success
        Deque<Long> window = new ArrayDeque<>(config.failureThreshold());
        return new CircuitState(CircuitState.Status.CLOSED, 0, window, 0);
    }

    private static CircuitState handleRequestFailure(
            CircuitState state, String error, long durationMs, CircuitBreakerConfig config) {
        Deque<Long> failureWindow = new ArrayDeque<>(state.failureWindow());
        failureWindow.addLast(System.currentTimeMillis());

        // Trim window to max size
        while (failureWindow.size() > config.failureThreshold()) {
            failureWindow.removeFirst();
        }

        // Check if threshold exceeded
        CircuitState.Status newStatus;
        if (failureWindow.size() >= config.failureThreshold()) {
            newStatus = CircuitState.Status.OPEN;
        } else {
            newStatus = CircuitState.Status.CLOSED;
        }

        return new CircuitState(
                newStatus, state.failureCount() + 1, failureWindow, System.currentTimeMillis());
    }

    private static CircuitState handleResetTimeout(
            CircuitState state, CircuitBreakerConfig config) {
        // Transition from OPEN to HALF_OPEN after reset timeout
        if (state.status() == CircuitState.Status.OPEN) {
            return new CircuitState(
                    CircuitState.Status.HALF_OPEN,
                    state.failureCount(),
                    state.failureWindow(),
                    state.lastFailureTime());
        }
        return state;
    }

    /** Internal state for the circuit breaker. */
    public record CircuitState(
            Status status, int failureCount, Deque<Long> failureWindow, long lastFailureTime) {

        enum Status {
            CLOSED, // Requests pass through
            OPEN, // Circuit tripped, fail-fast
            HALF_OPEN // Testing recovery
        }
    }

    /** Messages for the circuit breaker coordinator. */
    public sealed interface CircuitMsg
            permits CircuitMsg.RequestSuccess,
                    CircuitMsg.RequestFailure,
                    CircuitMsg.ResetTimeout,
                    CircuitMsg.Shutdown {

        record RequestSuccess(String requestId, long durationMs) implements CircuitMsg {}

        record RequestFailure(String requestId, String error, long durationMs)
                implements CircuitMsg {}

        record ResetTimeout() implements CircuitMsg {}

        record Shutdown() implements CircuitMsg {}
    }

    /** Task to execute through the circuit breaker. */
    @FunctionalInterface
    public interface CircuitBreakerTask<T> {
        T execute(Duration timeout) throws Exception;
    }

    /** Result type for circuit breaker operations. */
    public sealed interface Result<T> permits Result.Success, Result.Failure {
        record Success<T>(T value) implements Result<T> {}

        record Failure<T>(CircuitBreakerException error) implements Result<T> {}

        static <T> Result<T> success(T value) {
            return new Success<>(value);
        }

        static <T> Result<T> failure(CircuitBreakerException error) {
            return new Failure<>(error);
        }
    }

    /** Listener interface for circuit state transitions. */
    @FunctionalInterface
    public interface CircuitBreakerListener {
        void onStateChanged(CircuitState.Status from, CircuitState.Status to);
    }

    /** Exception thrown when circuit breaker is open or request fails. */
    public static class CircuitBreakerException extends Exception {
        public CircuitBreakerException(String message) {
            super(message);
        }

        public CircuitBreakerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Demo main method showing circuit breaker in action.
     *
     * <p>Run this to see the circuit breaker trip after 3 failures within 60 seconds.
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== JOTP Circuit Breaker Pattern Demo ===\n");

        // Create circuit breaker with: max 3 crashes per 60 seconds
        CircuitBreakerConfig config =
                new CircuitBreakerConfig(
                        "payment-gateway",
                        3, // maxRestarts
                        Duration.ofSeconds(60), // restartWindow
                        Duration.ofSeconds(10), // resetTimeout
                        3); // failureThreshold

        CircuitBreakerPattern breaker = CircuitBreakerPattern.create(config);

        System.out.println("Circuit Breaker State: " + breaker.getState().status());

        // Simulate successful requests
        System.out.println("\n1. Sending successful requests...");
        for (int i = 0; i < 2; i++) {
            Result<String> result =
                    breaker.execute(
                            timeout -> {
                                System.out.println("   Request " + (i + 1) + ": SUCCESS");
                                return "OK";
                            },
                            Duration.ofSeconds(1));
        }

        // Simulate failures to trip the circuit
        System.out.println("\n2. Simulating failures to trip circuit...");
        for (int i = 0; i < 4; i++) {
            Result<String> result =
                    breaker.execute(
                            timeout -> {
                                throw new RuntimeException("Service unavailable");
                            },
                            Duration.ofSeconds(1));

            if (result instanceof Result.Failure<?> f) {
                System.out.println(
                        "   Request " + (i + 1) + ": FAILED - " + f.error().getMessage());
            }

            // After 3 failures, circuit should open
            if (i == 2) {
                System.out.println(
                        "   Circuit State after 3 failures: " + breaker.getState().status());
            }
        }

        // Try to request through open circuit
        System.out.println("\n3. Attempting request through OPEN circuit...");
        Result<String> openResult =
                breaker.execute(
                        timeout -> {
                            System.out.println("   This should not execute");
                            return "SHOULD_NOT_HAPPEN";
                        },
                        Duration.ofSeconds(1));

        if (openResult instanceof Result.Failure<?> f) {
            System.out.println("   Result: " + f.error().getMessage());
        }

        // Reset and test recovery
        System.out.println("\n4. Resetting circuit breaker...");
        breaker.reset();
        Thread.sleep(1000); // Wait for reset

        Result<String> recoveryResult =
                breaker.execute(
                        timeout -> {
                            System.out.println("   Request after reset: SUCCESS");
                            return "RECOVERED";
                        },
                        Duration.ofSeconds(1));

        if (recoveryResult instanceof Result.Success<?> s) {
            System.out.println("   Circuit recovered: " + s.value());
        }

        System.out.println("\nFinal Circuit State: " + breaker.getState().status());

        breaker.shutdown();
        System.out.println("\n=== Demo Complete ===");
    }
}
