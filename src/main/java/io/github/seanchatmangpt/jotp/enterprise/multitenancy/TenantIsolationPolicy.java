package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

/**
 * Sealed interface for tenant isolation strategies.
 *
 * <p>Defines how tenants are isolated from each other in the supervisor hierarchy.
 */
public sealed interface TenantIsolationPolicy
        permits TenantIsolationPolicy.Strict,
                TenantIsolationPolicy.Pooled,
                TenantIsolationPolicy.Hybrid {

    /** Each tenant gets its own Supervisor (highest isolation). */
    record Strict() implements TenantIsolationPolicy {}

    /** N supervisors shared by M tenants (M > N) (resource efficient). */
    record Pooled(int poolSize) implements TenantIsolationPolicy {}

    /** Large tenants isolated, small tenants pooled (balanced). */
    record Hybrid(long tenantSizeThreshold) implements TenantIsolationPolicy {}
}
