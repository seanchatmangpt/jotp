package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;

/**
 * Sealed interface for health check lifecycle events and observability.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track health check
 * execution, status transitions, failure detection, and service degradation. These events provide
 * comprehensive observability into service health for monitoring dashboards, alerting, automated
 * remediation, and post-incident analysis.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>HealthCheckStarted</b>: Check execution initiated. Tracks check rate
 *   <li><b>HealthCheckPassed</b>: Check succeeded. Tracks success latency and rate
 *   <li><b>HealthCheckFailed</b>: Check failed. Tracks failure reasons and latency
 *   <li><b>StatusTransition</b>: Service health status changed. Tracks state machine transitions
 *   <li><b>AlertTriggered</b>: Alert threshold crossed. Tracks alerting lifecycle
 *   <li><b>ServiceDown</b>: Service detected as down. Tracks critical failures
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to health events
 * EventManager<HealthEvent> events = EventManager.create();
 * events.subscribe(HealthEvent.class, event -> {
 *     switch (event) {
 *         case HealthEvent.StatusTransition(var svc, var from, var to, var ts) ->
 *             log.warn("Service {} status: {} → {}", svc, from, to);
 *
 *         case HealthEvent.HealthCheckFailed(var svc, var type, var err, var dur, var ts) ->
 *             metrics.counter("health.check.failures",
 *                 "service", svc, "type", type).increment();
 *
 *         case HealthEvent.AlertTriggered(var svc, var severity, var msg, var ts) ->
 *             alertManager.send(severity, svc + ": " + msg);
 *
 *         case HealthEvent.ServiceDown(var svc, var reason, var ts) ->
 *             pager.duty("CRITICAL: Service " + svc + " is DOWN: " + reason);
 *
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Monitoring Metrics:</h2>
 *
 * <pre>
 * HealthCheckStarted rate   → Check frequency (checks/min)
 * HealthCheckPassed rate    → Success rate (passes/min, %)
 * HealthCheckFailed rate    → Failure rate (failures/min, %)
 * StatusTransition count    → Status change frequency (flapping indicator)
 * AlertTriggered count      → Alert volume (alerts/hour)
 * ServiceDown count         → Service availability incidents
 * </pre>
 *
 * <h2>Alerting Rules:</h2>
 *
 * <pre>
 * ServiceDown                    → CRITICAL (page immediately)
 * StatusTransition to UNHEALTHY   → WARNING (prepare for remediation)
 * StatusTransition to Degraded    → INFO (monitor closely)
 * 3+ HealthCheckFailed in 1min    → WARNING (potential degradation)
 * AlertTriggered severity=HIGH    → WARNING (investigate)
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link HealthCheckManager} coordinator on all state changes
 *   <li>Consumed by monitoring systems (Prometheus, Grafana, Datadog, PagerDuty)
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Non-blocking emission (doesn't slow down health checks)
 * </ul>
 *
 * @see HealthCheckManager
 * @see HealthStatus
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface HealthEvent
        permits HealthEvent.HealthCheckStarted,
                HealthEvent.HealthCheckPassed,
                HealthEvent.HealthCheckFailed,
                HealthEvent.StatusTransition,
                HealthEvent.AlertTriggered,
                HealthEvent.ServiceDown {

    record HealthCheckStarted(String service, String checkType, long timestamp)
            implements HealthEvent {}

    record HealthCheckPassed(String service, String checkType, Duration duration, long timestamp)
            implements HealthEvent {}

    record HealthCheckFailed(
            String service, String checkType, String error, Duration duration, long timestamp)
            implements HealthEvent {}

    record StatusTransition(String service, HealthStatus from, HealthStatus to, long timestamp)
            implements HealthEvent {}

    record AlertTriggered(String service, String severity, String message, long timestamp)
            implements HealthEvent {}

    record ServiceDown(String service, String reason, long timestamp) implements HealthEvent {}

    default String serviceName() {
        return switch (this) {
            case HealthCheckStarted(var s, _, _) -> s;
            case HealthCheckPassed(var s, _, _, _) -> s;
            case HealthCheckFailed(var s, _, _, _, _) -> s;
            case StatusTransition(var s, _, _, _) -> s;
            case AlertTriggered(var s, _, _, _) -> s;
            case ServiceDown(var s, _, _) -> s;
        };
    }

    default long timestamp() {
        return switch (this) {
            case HealthCheckStarted(_, _, var ts) -> ts;
            case HealthCheckPassed(_, _, _, var ts) -> ts;
            case HealthCheckFailed(_, _, _, _, var ts) -> ts;
            case StatusTransition(_, _, _, var ts) -> ts;
            case AlertTriggered(_, _, _, var ts) -> ts;
            case ServiceDown(_, _, var ts) -> ts;
        };
    }
}
