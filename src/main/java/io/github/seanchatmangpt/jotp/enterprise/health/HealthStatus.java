package io.github.seanchatmangpt.jotp.enterprise.health;

/**
 * Sealed interface representing the health status of a monitored service.
 *
 * <p>Used by {@link HealthCheckManager} to track service health transitions and provide exhaustive
 * pattern matching for status handling. Each status represents a different level of service
 * operational capability and triggers different remediation actions.
 *
 * <h2>Status Types:</h2>
 *
 * <ul>
 *   <li><b>Healthy</b>: All health checks passed, service is fully operational. Service can handle
 *       normal traffic load. No remediation needed. Timestamp indicates when status was achieved
 *   <li><b>Degraded</b>: Some checks failed but service is still functional. Partial degradation,
 *       may have reduced capacity or performance. Monitor closely, prepare for potential
 *       remediation. Includes reason and failure rate for context
 *   <li><b>Unhealthy</b>: Critical checks failed, service is significantly degraded. May be unable
 *       to handle requests or have severe performance issues. Remediation required (restart,
 *       traffic shift, alerting). Includes reason and last error for diagnostics
 *   <li><b>Unreachable</b>: Service cannot be contacted or does not respond to health checks. May
 *       be down, crashed, or network partitioned. Critical condition requiring immediate attention.
 *       Includes reason for connectivity failure
 * </ul>
 *
 * <h2>State Transitions:</h2>
 *
 * <pre>
 *                        All checks pass
 *                   ┌─────────────────────┐
 *                   │                     ↓
 *  Unreachable ←──────────────→ Healthy
 *       ↑                              │
 *       │     Some critical fails       │  All checks pass
 *       │     (≥ failThreshold)         │  (≥ passThreshold)
 *       │                              ↓
 *       └────────────────────────→ Degraded ←─────────┘
 *            Some fails              ↑    │
 *            (< failThreshold)      │    │ Any fail
 *                                   │    │
 *                                   └────┘
 *                                   More fails
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Handle status transitions
 * manager.addListener((from, to) -> {
 *     log.warn("Health status changed: {} → {}", from, to);
 *
 *     switch (to) {
 *         case HealthStatus.Healthy(var ts) ->
 *             log.info("Service recovered at {}", Instant.ofEpochMilli(ts)));
 *
 *         case HealthStatus.Degraded(var ts, var reason, var failureRate) ->
 *             log.warn("Service degraded: {} (failure rate: {}%)", reason, failureRate * 100);
 *
 *         case HealthStatus.Unhealthy(var ts, var reason, var error) ->
 *             alerts.fire("Service UNHEALTHY: " + reason);
 *             supervisor.restart();
 *
 *         case HealthStatus.Unreachable(var ts, var reason) ->
 *             alerts.fireCritical("Service UNREACHABLE: " + reason);
 *             loadBalancer.shiftTrafficAway();
 *     }
 * });
 *
 * // Check if service is operational
 * HealthStatus status = manager.getStatus();
 * if (status.isOperational()) {
 *     // Can send traffic (Healthy or Degraded)
 * } else {
 *     // Shift traffic away (Unhealthy or Unreachable)
 * }
 * }</pre>
 *
 * <h2>Remediation Actions:</h2>
 *
 * <pre>
 * Healthy          → No action, continue monitoring
 * Degraded         → Log warning, increase monitoring frequency
 * Unhealthy        → Restart service, shift traffic, alert on-call
 * Unreachable      → Check process/network, restart if needed, escalate
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link HealthCheckManager} on threshold transitions
 *   <li>Wrapped in {@link HealthEvent.StatusTransition} for observability
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Can trigger {@link io.github.seanchatmangpt.jotp.Supervisor} restarts
 * </ul>
 *
 * @see HealthCheckManager
 * @see HealthEvent
 * @since 1.0
 */
public sealed interface HealthStatus
        permits HealthStatus.Healthy,
                HealthStatus.Degraded,
                HealthStatus.Unhealthy,
                HealthStatus.Unreachable {

    /** All health checks passed; service operational. */
    record Healthy(long timestamp) implements HealthStatus {}

    /** Some checks failed but service still functional. */
    record Degraded(long timestamp, String reason, double failureRate) implements HealthStatus {}

    /** Critical checks failed; service degraded. */
    record Unhealthy(long timestamp, String reason, Throwable lastError) implements HealthStatus {}

    /** Service unreachable; cannot connect. */
    record Unreachable(long timestamp, String reason) implements HealthStatus {}

    /** Get timestamp of last status change. */
    default long timestamp() {
        return switch (this) {
            case Healthy(var ts) -> ts;
            case Degraded(var ts, _, _) -> ts;
            case Unhealthy(var ts, _, _) -> ts;
            case Unreachable(var ts, _) -> ts;
        };
    }

    /** Check if service is operational (Healthy or Degraded). */
    default boolean isOperational() {
        return switch (this) {
            case Healthy _, Degraded _ -> true;
            case Unhealthy _, Unreachable _ -> false;
        };
    }
}
