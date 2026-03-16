package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import java.util.Map;

/**
 * Sealed interface for tenant lifecycle events and observability.
 *
 * <p>Broadcast via {@link io.github.seanchatmangpt.jotp.EventManager} to track tenant onboarding,
 * service provisioning, health status, failures, restart limits, offboarding, and resource
 * exhaustion. These events provide comprehensive observability into tenant lifecycle for capacity
 * planning, billing, compliance, and operational dashboards.
 *
 * <h2>Event Types:</h2>
 *
 * <ul>
 *   <li><b>TenantOnboarded</b>: New tenant registered and resources allocated. Tracks tenant growth
 *   <li><b>TenantProvisioned</b>: Tenant supervisor created and ready. Tracks provisioning latency
 *   <li><b>TenantServicesHealthy</b>: All tenant services operational. Tracks tenant health
 *   <li><b>TenantServiceFailed</b>: Tenant service crashed or failed. Tracks failure rate per
 *       tenant
 *   <li><b>TenantRestartLimitExceeded</b>: Tenant exceeded maxRestarts threshold. Critical
 *       condition
 *   <li><b>TenantOffboarded</b>: Tenant deregistered and resources freed. Tracks tenant churn
 *   <li><b>TenantResourcesExhausted</b>: Tenant hit maxConcurrentProcesses limit. Resource
 *       exhaustion
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Subscribe to tenant lifecycle events
 * EventManager<TenantLifecycleEvent> events = EventManager.create();
 * events.subscribe(TenantLifecycleEvent.class, event -> {
 *     switch (event) {
 *         case TenantLifecycleEvent.TenantOnboarded(var id, var resources, var ts) ->
 *             log.info("Tenant {} onboarded with resources: {}", id, resources);
 *             billing.start(id);
 *
 *         case TenantLifecycleEvent.TenantServiceFailed(var id, var svc, var reason, var ts) ->
 *             metrics.counter("tenant.service.failures",
 *                 "tenant", id, "service", svc).increment();
 *
 *         case TenantLifecycleEvent.TenantRestartLimitExceeded(var id, var count, var ts) ->
 *             alerts.fire("Tenant {} restart limit exceeded: {} restarts", id, count);
 *             // Trigger manual intervention or auto-remediation
 *
 *         case TenantLifecycleEvent.TenantResourcesExhausted(var id, var memory, var ts) ->
 *             log.warn("Tenant {} exhausted resources: {}MB available", id, memory / 1024 / 1024);
 *             // Consider scaling up or throttling tenant
 *
 *         case TenantLifecycleEvent.TenantOffboarded(var id, var reason, var ts) ->
 *             log.info("Tenant {} offboarded: {}", id, reason);
 *             billing.stop(id);
 *             archiveTenantData(id);
 *
 *         default -> {}
 *     }
 * });
 * }</pre>
 *
 * <h2>Monitoring Metrics:</h2>
 *
 * <pre>
 * TenantOnboarded rate        → Tenant acquisition rate (tenants/day)
 * TenantServicesHealthy       → Active tenant health (healthy tenants / total)
 * TenantServiceFailed rate    → Tenant reliability (failures/tenant/hour)
 * TenantRestartLimitExceeded  → Tenant stability (restart limit violations/day)
 * TenantResourcesExhausted    → Resource utilization (exhaustion events/day)
 * TenantOffboarded rate       → Tenant churn rate (tenants lost/month)
 * </pre>
 *
 * <h2>Billing Integration:</h2>
 *
 * <pre>
 * TenantOnboarded             → Start billing cycle
 * TenantOffboarded            → End billing cycle, pro-rate final invoice
 * TenantResourcesExhausted    → Alert on resource overage, apply surcharge
 * </pre>
 *
 * <h2>Compliance & Auditing:</h2>
 *
 * <pre>
 * TenantOnboarded             → Log data residency agreement
 * TenantOffboarded            → Log data deletion confirmation
 * TenantServiceFailed         → Log SLA violation for SLA credits
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Emitted by {@link MultiTenantSupervisor} on all tenant state changes
 *   <li>Consumed by billing systems, monitoring dashboards, compliance auditors
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 *   <li>Non-blocking emission (doesn't slow down tenant operations)
 * </ul>
 *
 * @see MultiTenantSupervisor
 * @see TenantConfig
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @since 1.0
 */
public sealed interface TenantLifecycleEvent
        permits TenantLifecycleEvent.TenantOnboarded,
                TenantLifecycleEvent.TenantProvisioned,
                TenantLifecycleEvent.TenantServicesHealthy,
                TenantLifecycleEvent.TenantServiceFailed,
                TenantLifecycleEvent.TenantRestartLimitExceeded,
                TenantLifecycleEvent.TenantOffboarded,
                TenantLifecycleEvent.TenantResourcesExhausted {

    record TenantOnboarded(String tenantId, Map<String, String> resources, long timestamp)
            implements TenantLifecycleEvent {}

    record TenantProvisioned(String tenantId, String supervisorName, long timestamp)
            implements TenantLifecycleEvent {}

    record TenantServicesHealthy(String tenantId, int serviceCount, long timestamp)
            implements TenantLifecycleEvent {}

    record TenantServiceFailed(String tenantId, String serviceName, String reason, long timestamp)
            implements TenantLifecycleEvent {}

    record TenantRestartLimitExceeded(String tenantId, int restartCount, long timestamp)
            implements TenantLifecycleEvent {}

    record TenantOffboarded(String tenantId, String reason, long timestamp)
            implements TenantLifecycleEvent {}

    record TenantResourcesExhausted(String tenantId, long availableMemory, long timestamp)
            implements TenantLifecycleEvent {}

    default String tenantId() {
        return switch (this) {
            case TenantOnboarded(var t, _, _) -> t;
            case TenantProvisioned(var t, _, _) -> t;
            case TenantServicesHealthy(var t, _, _) -> t;
            case TenantServiceFailed(var t, _, _, _) -> t;
            case TenantRestartLimitExceeded(var t, _, _) -> t;
            case TenantOffboarded(var t, _, _) -> t;
            case TenantResourcesExhausted(var t, _, _) -> t;
        };
    }

    default long timestamp() {
        return switch (this) {
            case TenantOnboarded(_, _, var ts) -> ts;
            case TenantProvisioned(_, _, var ts) -> ts;
            case TenantServicesHealthy(_, _, var ts) -> ts;
            case TenantServiceFailed(_, _, _, var ts) -> ts;
            case TenantRestartLimitExceeded(_, _, var ts) -> ts;
            case TenantOffboarded(_, _, var ts) -> ts;
            case TenantResourcesExhausted(_, _, var ts) -> ts;
        };
    }
}
