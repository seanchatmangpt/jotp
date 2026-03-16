package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

/**
 * Sealed interface for tenant isolation strategies in multi-tenant systems.
 *
 * <p>Defines how tenants are isolated from each other in the supervisor hierarchy. Each strategy
 * represents a different trade-off between isolation, resource efficiency, and complexity. The
 * choice of strategy depends on tenant tier, resource requirements, and operational considerations.
 *
 * <h2>Strategy Types:</h2>
 *
 * <ul>
 *   <li><b>Strict</b>: Each tenant gets its own dedicated Supervisor (highest isolation). No
 *       resource sharing between tenants. One tenant's crash cannot affect others. Higher memory
 *       overhead due to per-tenant supervisor. Best for: Enterprise tenants, compliance
 *       requirements, high-security environments
 *   <li><b>Pooled</b>: N supervisors shared by M tenants (M > N) for resource efficiency. Tenants
 *       are distributed across pools using hash or round-robin. Lower memory overhead but potential
 *       for noisy neighbor within pool. Best for: Startup tier, low-priority tenants, cost
 *       optimization
 *   <li><b>Hybrid</b>: Large tenants get dedicated supervisors, small tenants are pooled. Automatic
 *       classification based on tenantSizeThreshold. Balances isolation and efficiency. Best for:
 *       Mixed-tier environments, auto-scaling systems, dynamic tenant onboarding
 * </ul>
 *
 * <h2>Selection Guide:</h2>
 *
 * <pre>
 * Highest isolation required    → Strict (dedicated supervisor per tenant)
 * Resource efficiency priority  → Pooled (share supervisors)
 * Mixed tenant tiers            → Hybrid (auto-classify by size)
 * Compliance/regulatory         → Strict (complete isolation)
 * Cost optimization             → Pooled or Hybrid
 * </pre>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Strict: Enterprise tenant with dedicated supervisor
 * TenantIsolationPolicy strict = new TenantIsolationPolicy.Strict();
 *
 * // Pooled: 10 supervisors shared by 100 tenants
 * TenantIsolationPolicy pooled = new TenantIsolationPolicy.Pooled(10);
 *
 * // Hybrid: Large tenants (>1000 processes) isolated, small tenants pooled
 * TenantIsolationPolicy hybrid = new TenantIsolationPolicy.Hybrid(1000);
 *
 * // Apply to tenant configuration
 * TenantConfig config = TenantConfig.builder("tenant-123")
 *     .strategy(strict)  // or pooled, or hybrid
 *     .build();
 * }</pre>
 *
 * <h2>Resource Comparison:</h2>
 *
 * <ul>
 *   <li><b>Strict</b>: O(tenants) supervisors. Memory: ~1KB per supervisor (virtual threads)
 *   <li><b>Pooled</b>: O(poolSize) supervisors. Memory: ~1KB per supervisor
 *   <li><b>Hybrid</b>: O(largeTenants + poolSize) supervisors. Memory: varies by distribution
 * </ul>
 *
 * <h2>Failure Impact:</h2>
 *
 * <pre>
 * Strict:    Tenant crash → only that tenant affected
 * Pooled:    Tenant crash → all tenants in that supervisor affected
 * Hybrid:    Large tenant → only that tenant affected
 *            Small tenant → all tenants in pool affected
 * </pre>
 *
 * <h2>Integration with JOTP:</h2>
 *
 * <ul>
 *   <li>Used by {@link MultiTenantSupervisor} to determine supervisor allocation
 *   <li>Strict strategy creates one {@link io.github.seanchatmangpt.jotp.Supervisor} per tenant
 *   <li>Pooled strategy maps tenants to pre-allocated supervisor pool
 *   <li>Type-safe via sealed interface with exhaustive pattern matching
 * </ul>
 *
 * @see MultiTenantSupervisor
 * @see TenantConfig
 * @see io.github.seanchatmangpt.jotp.Supervisor
 * @since 1.0
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
