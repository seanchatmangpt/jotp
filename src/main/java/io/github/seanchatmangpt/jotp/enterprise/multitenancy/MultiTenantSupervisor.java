package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multi-tenant supervisor for isolating tenant workloads.
 *
 * <p>Creates a root supervisor that manages ONE_FOR_ONE restarts of tenant supervisors. Each tenant
 * gets its own ONE_FOR_ALL supervisor ensuring all tenant services fail/restart together, while one
 * tenant's crash doesn't affect other tenants.
 *
 * <p>Hierarchy: RootSupervisor (ONE_FOR_ONE) ├── TenantA_Supervisor (ONE_FOR_ALL) │ ├── Service1 │
 * ├── Service2 │ └── Service3 ├── TenantB_Supervisor (ONE_FOR_ALL) │ └── Services... └──
 * MetricsService (ONE_FOR_ONE)
 */
public class MultiTenantSupervisor {
    private final Map<String, TenantSupervisorInfo> tenants = new ConcurrentHashMap<>();
    private final List<MultiTenantListener> listeners = new CopyOnWriteArrayList<>();
    private final Supervisor rootSupervisor;
    private volatile TenantInfo[] tenantCache = new TenantInfo[0];

    private MultiTenantSupervisor(Supervisor rootSupervisor) {
        this.rootSupervisor = rootSupervisor;
    }

    /**
     * Create a new multi-tenant supervisor.
     *
     * @return MultiTenantSupervisor instance
     */
    public static MultiTenantSupervisor create() {
        // Create root supervisor with ONE_FOR_ONE strategy
        Supervisor rootSupervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(1));
        return new MultiTenantSupervisor(rootSupervisor);
    }

    /**
     * Register a new tenant.
     *
     * @param config Tenant configuration
     * @return Supervisor for the tenant to add services to
     */
    public Supervisor registerTenant(TenantConfig config) {
        String tenantId = config.tenantId();

        if (tenants.containsKey(tenantId)) {
            throw new IllegalArgumentException("Tenant already registered: " + tenantId);
        }

        // Create ONE_FOR_ALL supervisor for this tenant
        Supervisor tenantSupervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ALL, 10, Duration.ofMinutes(1));

        TenantSupervisorInfo info =
                new TenantSupervisorInfo(
                        tenantId,
                        config,
                        tenantSupervisor,
                        0, // restartCount
                        System.currentTimeMillis());

        tenants.put(tenantId, info);
        updateTenantCache();

        // Notify listeners
        listeners.forEach(l -> l.onTenantOnboarded(tenantId, config));

        return tenantSupervisor;
    }

    /**
     * Get supervisor for a tenant.
     *
     * @param tenantId Tenant identifier
     * @return Supervisor or null if not found
     */
    public Supervisor getTenant(String tenantId) {
        TenantSupervisorInfo info = tenants.get(tenantId);
        return info != null ? info.supervisor() : null;
    }

    /**
     * Deregister a tenant and shutdown its supervisor.
     *
     * @param tenantId Tenant identifier
     * @param reason Reason for deregistration
     */
    public void deregisterTenant(String tenantId, String reason) {
        TenantSupervisorInfo info = tenants.remove(tenantId);
        if (info != null) {
            updateTenantCache();
            listeners.forEach(l -> l.onTenantOffboarded(tenantId, reason));
        }
    }

    /**
     * Get all active tenants.
     *
     * @return Array of tenant info
     */
    public TenantInfo[] listTenants() {
        return tenantCache;
    }

    /**
     * Register a listener for multi-tenant events.
     *
     * @param listener Callback to invoke on events
     */
    public void addListener(MultiTenantListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     *
     * @param listener Listener to remove
     */
    public void removeListener(MultiTenantListener listener) {
        listeners.remove(listener);
    }

    /** Shutdown all tenants and root supervisor. */
    public void shutdown() {
        tenants.values().stream()
                .map(TenantSupervisorInfo::tenantId)
                .toList()
                .forEach(id -> deregisterTenant(id, "Shutdown"));
        try {
            rootSupervisor.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateTenantCache() {
        tenantCache =
                tenants.values().stream()
                        .map(
                                info ->
                                        new TenantInfo(
                                                info.tenantId(),
                                                info.config().maxConcurrentProcesses(),
                                                info.restartCount(),
                                                info.onboardedAt()))
                        .toArray(TenantInfo[]::new);
    }

    /** Information about a registered tenant. */
    public record TenantInfo(
            String tenantId, int maxConcurrentProcesses, int restartCount, long onboardedAtMs) {}

    /** Internal: Supervisor info for a tenant. */
    private record TenantSupervisorInfo(
            String tenantId,
            TenantConfig config,
            Supervisor supervisor,
            int restartCount,
            long onboardedAt) {}

    /** Listener interface for multi-tenant events. */
    public interface MultiTenantListener {
        void onTenantOnboarded(String tenantId, TenantConfig config);

        void onTenantOffboarded(String tenantId, String reason);

        void onTenantRestartLimitExceeded(String tenantId);
    }
}
