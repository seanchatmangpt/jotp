package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a tenant in multi-tenant supervisor hierarchy.
 *
 * <p>Immutable record defining resource limits, restart policies, isolation strategy, and metadata
 * for a single tenant in a multi-tenant system. This configuration controls how much capacity a
 * tenant can use and how failures are handled.
 *
 * <h2>Key Parameters:</h2>
 *
 * <ul>
 *   <li><b>tenantId</b>: Unique identifier for the tenant (e.g., customer ID, organization ID).
 *       Must be non-empty. Used in logs, metrics, and tenant lookup
 *   <li><b>maxConcurrentProcesses</b>: Maximum concurrent processes/threads this tenant can create.
 *       Prevents resource exhaustion. Typical values: 100-10000 depending on tenant tier
 *   <li><b>maxRestarts</b>: Maximum restarts allowed within the time window before tenant
 *       supervisor crashes. Prevents crash loops. Typical values: 3-10
 *   <li><b>window</b>: Time window for counting restarts. Sliding window semantics. Typical values:
 *       1-5 minutes
 *   <li><b>strategy</b>: Isolation strategy (Strict, Pooled, Hybrid). Determines how tenant is
 *       isolated from others. Default: Strict (dedicated supervisor)
 *   <li><b>metadata</b>: Optional key-value pairs for tenant metadata (tier, region, custom
 *       limits). Used for analytics and custom routing
 * </ul>
 *
 * <h2>Resource Limit Enforcement:</h2>
 *
 * <ul>
 *   <li>Process creation blocked when maxConcurrentProcesses reached
 *   <li>Tenant supervisor crashes when maxRestarts exceeded in window
 *   <li>Resources freed on tenant offboarding or shutdown
 * </ul>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Enterprise tenant with high capacity
 * TenantConfig enterprise = TenantConfig.builder("acme-corp")
 *     .maxConcurrentProcesses(5000)
 *     .maxRestarts(10)
 *     .window(Duration.ofMinutes(5))
 *     .strategy(new TenantIsolationPolicy.Strict())
 *     .addMetadata("tier", "enterprise")
 *     .addMetadata("sla", "99.99")
 *     .build();
 *
 * // Startup tier with limited capacity
 * TenantConfig startup = TenantConfig.builder("startup-inc")
 *     .maxConcurrentProcesses(500)
 *     .maxRestarts(3)
 *     .window(Duration.ofMinutes(1))
 *     .strategy(new TenantIsolationPolicy.Pooled(10)) // Share supervisor
 *     .addMetadata("tier", "startup")
 *     .build();
 *
 * // Custom metadata for routing
 * TenantConfig regional = TenantConfig.builder("regional-tenant")
 *     .maxConcurrentProcesses(1000)
 *     .metadata(Map.of(
 *         "region", "us-west-2",
 *         "compliance", "HIPAA",
 *         "data-residency", "US"
 *     ))
 *     .build();
 * }</pre>
 *
 * <h2>Tier Strategy:</h2>
 *
 * <pre>
 * Enterprise    → maxConcurrentProcesses=5000, maxRestarts=10, Strict
 * Business      → maxConcurrentProcesses=2000, maxRestarts=5, Strict
 * Startup       → maxConcurrentProcesses=500, maxRestarts=3, Pooled(10)
 * Free          → maxConcurrentProcesses=100, maxRestarts=2, Pooled(50)
 * </pre>
 *
 * <h2>Performance Characteristics:</h2>
 *
 * <ul>
 *   <li>Memory: O(metadata) for tenant metadata storage
 *   <li>Latency: O(1) for limit checks (atomic counter)
 *   <li>Throughput: Limited by maxConcurrentProcesses setting
 * </ul>
 *
 * @see MultiTenantSupervisor
 * @see TenantIsolationPolicy
 * @since 1.0
 * @param tenantId Unique identifier for the tenant
 * @param maxConcurrentProcesses Maximum concurrent processes allowed
 * @param maxRestarts Maximum restarts allowed within the time window
 * @param window Time window for counting restarts
 * @param strategy Isolation strategy (Strict, Pooled, Hybrid)
 * @param metadata Optional key-value pairs for tenant metadata
 */
public record TenantConfig(
        String tenantId,
        int maxConcurrentProcesses,
        int maxRestarts,
        Duration window,
        TenantIsolationPolicy strategy,
        Map<String, String> metadata) {

    /** Validate configuration constraints. */
    public TenantConfig {
        if (tenantId == null || tenantId.isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be empty");
        }
        if (maxConcurrentProcesses <= 0) {
            throw new IllegalArgumentException("maxConcurrentProcesses must be > 0");
        }
        if (maxRestarts <= 0) {
            throw new IllegalArgumentException("maxRestarts must be > 0");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String tenantId) {
        return new Builder(tenantId);
    }

    public static class Builder {
        private final String tenantId;
        private int maxConcurrentProcesses = 1000;
        private int maxRestarts = 5;
        private Duration window = Duration.ofMinutes(1);
        private TenantIsolationPolicy strategy = new TenantIsolationPolicy.Strict();
        private Map<String, String> metadata = new HashMap<>();

        public Builder(String tenantId) {
            this.tenantId = tenantId;
        }

        public Builder maxConcurrentProcesses(int max) {
            this.maxConcurrentProcesses = max;
            return this;
        }

        public Builder maxRestarts(int restarts) {
            this.maxRestarts = restarts;
            return this;
        }

        public Builder window(Duration duration) {
            this.window = duration;
            return this;
        }

        public Builder strategy(TenantIsolationPolicy policy) {
            this.strategy = policy;
            return this;
        }

        public Builder metadata(Map<String, String> map) {
            this.metadata = new HashMap<>(map);
            return this;
        }

        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public TenantConfig build() {
            return new TenantConfig(
                    tenantId,
                    maxConcurrentProcesses,
                    maxRestarts,
                    window,
                    strategy,
                    new HashMap<>(metadata));
        }
    }
}
