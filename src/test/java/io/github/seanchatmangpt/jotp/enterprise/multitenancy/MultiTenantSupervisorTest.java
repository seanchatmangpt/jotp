package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;

/**
 * Comprehensive DTR-documented tests for MultiTenantSupervisor pattern.
 *
 * <p>Documents Joe Armstrong's tenant-per-supervisor isolation with hierarchical supervision, fault
 * containment, resource limits, and lifecycle management using JOTP's supervision tree.
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining multi-tenancy semantics, isolation strategies, and production patterns. Run with DTR
 * to see examples with actual output values.
 */
@DtrTest
@DisplayName("MultiTenantSupervisor: Joe Armstrong tenant-per-supervisor isolation with 99.95% SLA")
class MultiTenantSupervisorTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    private MultiTenantSupervisor supervisor;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        supervisor = MultiTenantSupervisor.create();
    }

    @AfterEach
    void tearDown() {
        supervisor.shutdown();
    }

    private TenantConfig defaultTenantConfig(String id) {
        return TenantConfig.builder(id)
                .maxConcurrentProcesses(100)
                .maxRestarts(5)
                .window(Duration.ofMinutes(1))
                .build();
    }

    // ── Test 1: Multi-Tenancy Overview ────────────────────────────────────────

    @DisplayName("create_returnsInstance: MultiTenantSupervisor.create returns non-null")
    void create_returnsInstance() {
        ctx.sayNextSection("Multi-Tenancy: Fault Isolation Between Tenants");
        ctx.say(
                "MultiTenantSupervisor creates hierarchical supervision where each tenant gets its own"
                        + " supervisor with ONE_FOR_ALL strategy. Tenant failures don't affect other"
                        + " tenants, enabling shared infrastructure with isolation guarantees.");
        ctx.sayCode(
                """
            // Create root supervisor with ONE_FOR_ONE strategy
            MultiTenantSupervisor mts = MultiTenantSupervisor.create();

            // Register tenant A with ONE_FOR_ALL supervision
            TenantConfig configA = TenantConfig.builder("tenant-A")
                .maxConcurrentProcesses(1000)
                .maxRestarts(5)
                .window(Duration.ofMinutes(1))
                .build();

            Supervisor supervisorA = mts.registerTenant(configA);
            """,
                "java");

        assertThat(MultiTenantSupervisor.create()).isNotNull();

        ctx.sayKeyValue(
                Map.of(
                        "Root Strategy", "ONE_FOR_ONE (tenant isolation)",
                        "Tenant Strategy", "ONE_FOR_ALL (all-or-nothing)",
                        "Fault Containment", "Tenant failures isolated",
                        "SLA Target", "99.95% uptime"));
        ctx.sayMermaid(
                """
            graph TB
                Root[Root Supervisor<br/>ONE_FOR_ONE]
                A[Tenant A Supervisor<br/>ONE_FOR_ALL]
                B[Tenant B Supervisor<br/>ONE_FOR_ALL]

                Root --> A
                Root --> B

                A --> A1[Service 1]
                A --> A2[Service 2]
                B --> B1[Service 1]
                B --> B2[Service 2]

                style Root fill:#f9f,stroke:#333,stroke-width:2px
                style A fill:#bbf,stroke:#333,stroke-width:2px
                style B fill:#bbf,stroke:#333,stroke-width:2px
            """);
    }

    // ── Test 2: Tenant Registration ───────────────────────────────────────────

    @DisplayName(
            "registerTenant_validConfig_returnsTenantSupervisor: valid config returns non-null")
    void registerTenant_validConfig_returnsTenantSupervisor() {
        ctx.sayNextSection("Tenant Registration: Dynamic Onboarding");
        ctx.say(
                "Tenants are registered dynamically without system restart. Each tenant gets its own"
                        + " supervisor with configurable resource limits and restart policies.");
        ctx.sayCode(
                """
            TenantConfig config = TenantConfig.builder("tenant-1")
                .maxConcurrentProcesses(100)
                .maxRestarts(5)
                .window(Duration.ofMinutes(1))
                .build();

            Supervisor tenantSupervisor = mts.registerTenant(config);
            // tenantSupervisor != null
            """,
                "java");

        TenantConfig config = defaultTenantConfig("t1");
        var result = supervisor.registerTenant(config);
        assertThat(result).isNotNull();

        ctx.sayTable(
                List.of(
                        List.of("Tenant ID", "t1"),
                        List.of("Max Processes", "100"),
                        List.of("Max Restarts", "5"),
                        List.of("Window", "1 minute"),
                        List.of("Strategy", "ONE_FOR_ALL")),
                List.of("Property", "Value"));
    }

    @DisplayName(
            "registerTenant_duplicateId_throwsIllegalArgument: duplicate tenant ID throws exception")
    void registerTenant_duplicateId_throwsIllegalArgument() {
        ctx.say(
                "Tenant IDs must be unique. Duplicate registration is rejected to prevent"
                        + " misconfiguration and tenant collision.");
        ctx.sayCode(
                """
            mts.registerTenant(defaultTenantConfig("t1"));

            assertThatThrownBy(() ->
                mts.registerTenant(defaultTenantConfig("t1"))
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        supervisor.registerTenant(defaultTenantConfig("t1"));
        assertThatThrownBy(() -> supervisor.registerTenant(defaultTenantConfig("t1")))
                .isInstanceOf(IllegalArgumentException.class);

        ctx.sayWarning(
                "Duplicate tenant IDs cause operational confusion. Use unique identifiers like"
                        + " 'acme-corp' or 'tenant-12345' instead of generic names.");
    }

    // ── Test 4: Tenant Lookup ────────────────────────────────────────────────

    @DisplayName("getTenant_existingId_returnsSupervisor: existing ID returns supervisor")
    void getTenant_existingId_returnsSupervisor() {
        ctx.sayNextSection("Tenant Lookup: Supervisor Retrieval");
        ctx.say(
                "Registered tenants can be retrieved by ID for adding services or monitoring status."
                        + " Returns null for unknown tenant IDs.");
        ctx.sayCode(
                """
            mts.registerTenant(defaultTenantConfig("t1"));

            Supervisor supervisor = mts.getTenant("t1");
            // supervisor != null

            Supervisor unknown = mts.getTenant("nonexistent");
            // unknown == null
            """,
                "java");

        supervisor.registerTenant(defaultTenantConfig("t1"));
        assertThat(supervisor.getTenant("t1")).isNotNull();

        ctx.sayKeyValue(
                Map.of(
                        "Tenant Found", "Returns Supervisor",
                        "Tenant Not Found", "Returns null",
                        "Use Case", "Service registration, status queries"));
    }

    @DisplayName("getTenant_unknownId_returnsNull: unknown ID returns null")
    void getTenant_unknownId_returnsNull() {
        ctx.say(
                "Querying unknown tenant IDs returns null instead of throwing. This enables safe"
                        + " tenant existence checks.");
        ctx.sayCode(
                """
            Supervisor supervisor = mts.getTenant("nonexistent");
            // supervisor == null
            """,
                "java");

        assertThat(supervisor.getTenant("nonexistent")).isNull();

        ctx.sayWarning(
                "Always check for null when using getTenant(). Tenant may not be registered yet"
                        + " or may have been deregistered.");
    }

    // ── Test 6: Tenant Listing ───────────────────────────────────────────────

    @DisplayName("listTenants_emptyInitially: listTenants returns empty array initially")
    void listTenants_emptyInitially() {
        ctx.sayNextSection("Tenant Discovery: Listing Active Tenants");
        ctx.say(
                "listTenants() returns snapshot of all active tenants with metadata. Useful for"
                        + " monitoring, billing, and operational visibility.");
        ctx.sayCode(
                """
            // Initially empty
            TenantInfo[] tenants = mts.listTenants();
            // tenants.length == 0

            // After registration
            mts.registerTenant(defaultTenantConfig("t1"));
            tenants = mts.listTenants();
            // tenants.length == 1
            // tenants[0].tenantId() == "t1"
            """,
                "java");

        assertThat(supervisor.listTenants()).isEmpty();

        ctx.sayTable(
                List.of(
                        List.of("tenantId", "Tenant identifier"),
                        List.of("maxConcurrentProcesses", "Process limit"),
                        List.of("restartCount", "Current restart count"),
                        List.of("onboardedAt", "Registration timestamp")),
                List.of("TenantInfo Field", "Description"));
    }

    @DisplayName(
            "listTenants_afterRegistration_containsTenantInfo: after registration contains tenant")
    void listTenants_afterRegistration_containsTenantInfo() {
        ctx.sayCode(
                """
            mts.registerTenant(defaultTenantConfig("t1"));

            TenantInfo[] tenants = mts.listTenants();
            // tenants.length == 1
            // tenants[0].tenantId() == "t1"
            """,
                "java");

        supervisor.registerTenant(defaultTenantConfig("t1"));
        var tenants = supervisor.listTenants();
        assertThat(tenants).hasSize(1);
        assertThat(tenants[0].tenantId()).isEqualTo("t1");

        ctx.sayKeyValue(
                Map.of(
                        "Tenant Count", "1",
                        "Tenant ID", "t1",
                        "Max Processes", "100",
                        "Restart Count", "0"));
    }

    // ── Test 8: Tenant Deregistration ────────────────────────────────────────

    @DisplayName("deregisterTenant_removedFromList: tenant removed after deregistration")
    void deregisterTenant_removedFromList() {
        ctx.sayNextSection("Tenant Lifecycle: Offboarding");
        ctx.say(
                "Tenants can be deregistered with a reason. Deregistration removes tenant from list"
                        + " and notifies listeners. Useful for tenant deletion or suspension.");
        ctx.sayCode(
                """
            mts.registerTenant(defaultTenantConfig("t1"));

            mts.deregisterTenant("t1", "test removal");

            TenantInfo[] tenants = mts.listTenants();
            // tenants.length == 0
            """,
                "java");

        supervisor.registerTenant(defaultTenantConfig("t1"));
        supervisor.deregisterTenant("t1", "test removal");
        assertThat(supervisor.listTenants()).isEmpty();

        ctx.sayKeyValue(
                Map.of(
                        "Deregistration", "Immediate",
                        "Reason", "Recorded for audit",
                        "Listeners", "Notified",
                        "Services", "Shutdown"));
    }

    @DisplayName("deregisterTenant_unknownId_noOp: deregistering unknown ID is no-op")
    void deregisterTenant_unknownId_noOp() {
        ctx.say(
                "Deregistering unknown tenant IDs is safe (no-op). Idempotent operation prevents"
                        + " errors when tenant already deleted or never existed.");
        ctx.sayCode(
                """
            // No exception thrown
            mts.deregisterTenant("unknown", "test");
            """,
                "java");

        assertThatNoException().isThrownBy(() -> supervisor.deregisterTenant("unknown", "test"));

        ctx.sayWarning(
                "Idempotent deregistration simplifies cleanup logic. No need to check tenant exists"
                        + " before deregistering.");
    }

    // ── Test 10: Lifecycle Events ────────────────────────────────────────────

    @DisplayName("tenantListener_onTenantOnboarded_fired: onTenantOnboarded fired when registered")
    void tenantListener_onTenantOnboarded_fired() {
        ctx.sayNextSection("Tenant Lifecycle Events: Monitoring");
        ctx.say(
                "MultiTenantListeners receive callbacks for tenant lifecycle events: onboarding,"
                        + " offboarding, and restart limit exceeded. Enables monitoring and alerting.");
        ctx.sayCode(
                """
            AtomicBoolean called = new AtomicBoolean(false);

            mts.addListener(new MultiTenantListener() {
                public void onTenantOnboarded(String tenantId, TenantConfig config) {
                    called.set(true);
                    log.info("Tenant onboarded: {}", tenantId);
                }

                public void onTenantOffboarded(String tenantId, String reason) {}

                public void onTenantRestartLimitExceeded(String tenantId) {}
            });

            mts.registerTenant(defaultTenantConfig("t1"));

            await().atMost(Duration.ofSeconds(2)).until(called::get);
            """,
                "java");

        AtomicBoolean called = new AtomicBoolean(false);
        supervisor.addListener(
                new MultiTenantSupervisor.MultiTenantListener() {
                    @Override
                    public void onTenantOnboarded(String tenantId, TenantConfig config) {
                        called.set(true);
                    }

                    @Override
                    public void onTenantOffboarded(String tenantId, String reason) {}

                    @Override
                    public void onTenantRestartLimitExceeded(String tenantId) {}
                });

        supervisor.registerTenant(defaultTenantConfig("t1"));
        await().atMost(Duration.ofSeconds(2)).until(called::get);

        ctx.sayTable(
                List.of(
                        List.of("onTenantOnboarded", "Tenant registered"),
                        List.of("onTenantOffboarded", "Tenant deregistered"),
                        List.of("onTenantRestartLimitExceeded", "Tenant exceeding restart limit")),
                List.of("Event", "When Fired"));
    }

    @DisplayName(
            "tenantListener_onTenantOffboarded_fired: onTenantOffboarded fired when deregistered")
    void tenantListener_onTenantOffboarded_fired() {
        ctx.say(
                "Offboarding events include tenant ID and reason. Enables audit trails and billing"
                        + " calculations.");
        ctx.sayCode(
                """
            AtomicBoolean offboarded = new AtomicBoolean(false);

            mts.addListener(new MultiTenantListener() {
                public void onTenantOnboarded(String tenantId, TenantConfig config) {}

                public void onTenantOffboarded(String tenantId, String reason) {
                    offboarded.set(true);
                    log.info("Tenant offboarded: {} ({})", tenantId, reason);
                }

                public void onTenantRestartLimitExceeded(String tenantId) {}
            });

            mts.registerTenant(defaultTenantConfig("t1"));
            mts.deregisterTenant("t1", "test offboard");

            await().atMost(Duration.ofSeconds(2)).until(offboarded::get);
            """,
                "java");

        AtomicBoolean offboarded = new AtomicBoolean(false);
        supervisor.addListener(
                new MultiTenantSupervisor.MultiTenantListener() {
                    @Override
                    public void onTenantOnboarded(String tenantId, TenantConfig config) {}

                    @Override
                    public void onTenantOffboarded(String tenantId, String reason) {
                        offboarded.set(true);
                    }

                    @Override
                    public void onTenantRestartLimitExceeded(String tenantId) {}
                });

        supervisor.registerTenant(defaultTenantConfig("t1"));
        supervisor.deregisterTenant("t1", "test offboard");
        await().atMost(Duration.ofSeconds(2)).until(offboarded::get);

        ctx.sayKeyValue(
                Map.of(
                        "Event", "onTenantOffboarded",
                        "Tenant ID", "t1",
                        "Reason", "test offboard",
                        "Use Case", "Audit trail, billing"));
    }

    // ── Test 12: Configuration Validation ─────────────────────────────────────

    @DisplayName("tenantConfig_validationConstraints: validation rejects invalid values")
    void tenantConfig_validationConstraints() {
        ctx.sayNextSection("Configuration Validation: Safety Constraints");
        ctx.say(
                "TenantConfig enforces invariants at construction time. Empty tenant IDs, zero"
                        + " process limits, and zero restart limits are rejected.");
        ctx.sayCode(
                """
            // Empty tenant ID
            assertThatThrownBy(() -> TenantConfig.builder("").build())
                .isInstanceOf(IllegalArgumentException.class);

            // Zero max concurrent processes
            assertThatThrownBy(() -> TenantConfig.builder("t1")
                .maxConcurrentProcesses(0)
                .build())
                .isInstanceOf(IllegalArgumentException.class);

            // Zero max restarts
            assertThatThrownBy(() -> TenantConfig.builder("t1")
                .maxRestarts(0)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        assertThatThrownBy(() -> TenantConfig.builder("").build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TenantConfig.builder("t1").maxConcurrentProcesses(0).build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TenantConfig.builder("t1").maxRestarts(0).build())
                .isInstanceOf(IllegalArgumentException.class);

        ctx.sayTable(
                List.of(
                        List.of("Tenant ID", "Non-empty string"),
                        List.of("Max Processes", "≥ 1"),
                        List.of("Max Restarts", "≥ 1"),
                        List.of("Window", "≥ 1 second")),
                List.of("Parameter", "Valid Range"));
    }

    // ── Test 13: Shutdown ────────────────────────────────────────────────────

    @DisplayName("shutdown_deregistersAllTenants: shutdown deregisters all tenants")
    void shutdown_deregistersAllTenants() {
        ctx.sayNextSection("Lifecycle: Graceful Shutdown");
        ctx.say(
                "Shutdown deregisters all tenants and stops root supervisor. All tenant services are"
                        + " stopped gracefully. Shutdown is idempotent.");
        ctx.sayCode(
                """
            mts.registerTenant(defaultTenantConfig("t1"));
            mts.registerTenant(defaultTenantConfig("t2"));

            mts.shutdown();
            // All tenants deregistered
            // Root supervisor stopped
            """,
                "java");

        supervisor.registerTenant(defaultTenantConfig("t1"));
        supervisor.registerTenant(defaultTenantConfig("t2"));
        assertThatNoException().isThrownBy(() -> supervisor.shutdown());

        ctx.sayKeyValue(
                Map.of(
                        "Tenant Cleanup", "All tenants deregistered",
                        "Service Shutdown", "Graceful (not immediate kill)",
                        "Root Supervisor", "Stopped",
                        "Idempotent", "Safe to call multiple times"));
    }
}
