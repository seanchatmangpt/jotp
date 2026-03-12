package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import java.util.Map;

/**
 * Sealed interface for tenant lifecycle events.
 *
 * Broadcast via EventManager to track tenant onboarding, failures, and offboarding.
 */
public sealed interface TenantLifecycleEvent permits
    TenantLifecycleEvent.TenantOnboarded,
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
