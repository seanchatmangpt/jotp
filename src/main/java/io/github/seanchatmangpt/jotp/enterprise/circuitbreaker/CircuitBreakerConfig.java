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
