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

import java.time.Duration;

/**
 * Configuration for circuit breaker pattern.
 *
 * <p>Immutable record defining the behavior of a circuit breaker, including restart limits, time
 * windows, and failure thresholds. This configuration controls when the circuit trips (opens) and
 * when it attempts recovery (half-open state).
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>maxRestarts</b>: Maximum restarts allowed within the restart window before the
 *       supervisor itself crashes (fail-fast)
 *   <li><b>restartWindow</b>: Time window in which restarts are counted. Sliding window semantics
 *       ensure old restarts don't count toward the limit
 *   <li><b>resetTimeout</b>: Minimum time the circuit stays OPEN before attempting recovery.
 *       Prevents flapping when downstream service is unstable
 *   <li><b>failureThreshold</b>: Number of failures required to trip the circuit. Independent of
 *       maxRestarts - this is the application-level failure count
 * </ul>
 *
 * <h2>State Transitions:</h2>
 *
 * <pre>
 * CLOSED → OPEN: After failureThreshold failures
 * OPEN → HALF_OPEN: After resetTimeout expires
 * HALF_OPEN → CLOSED: On first successful request
 * HALF_OPEN → OPEN: On first failed request
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Create circuit breaker with: max 3 crashes per 60 seconds
 * CircuitBreakerConfig config = CircuitBreakerConfig.of("payment-gateway")
 *     .withMaxRestarts(3)
 *     .withRestartWindow(Duration.ofSeconds(60))
 *     .withResetTimeout(Duration.ofSeconds(10))
 *     .withFailureThreshold(3);
 * }</pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(windowSize) for tracking failure timestamps
 *   <li>Latency: O(1) for request execution, O(windowSize) for state updates
 *   <li>Throughput: No blocking in CLOSED state, fail-fast in OPEN state
 * </ul>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Uses {@link Supervisor} with restart intensity for automatic circuit tripping
 *   <li>Supervisor crashes when maxRestarts exceeded, triggering fail-fast
 *   <li>Process-based coordinator tracks state and handles transitions
 * </ul>
 *
 * @see Supervisor
 * @see CircuitBreakerPattern
 * @see io.github.seanchatmangpt.jotp.enterprise.recovery.EnterpriseRecovery
 * @since 1.0
 * @param serviceName Name of the service being protected
 * @param maxRestarts Maximum number of restarts allowed within the restart window
 * @param restartWindow Time window for counting restarts
 * @param resetTimeout How long to wait before transitioning from OPEN to HALF_OPEN
 * @param failureThreshold Number of failures required to trip the circuit
 */
public record CircuitBreakerConfig(
        String serviceName,
        int maxRestarts,
        Duration restartWindow,
        Duration resetTimeout,
        int failureThreshold) {
    /**
     * Create a circuit breaker configuration with defaults.
     *
     * @param serviceName Name of the service being protected
     * @return CircuitBreakerConfig with sensible defaults
     */
    public static CircuitBreakerConfig of(String serviceName) {
        return new CircuitBreakerConfig(
                serviceName,
                3, // maxRestarts
                Duration.ofSeconds(60), // restartWindow
                Duration.ofSeconds(10), // resetTimeout
                3); // failureThreshold
    }
}
