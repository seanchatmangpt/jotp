package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import static org.awaitility.Awaitility.await;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.BeforeEach;
@DisplayName("MultiTenantSupervisor: Joe Armstrong tenant-per-supervisor isolation with 99.95% SLA")
class MultiTenantSupervisorTest implements WithAssertions {
    private MultiTenantSupervisor supervisor;
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        supervisor = MultiTenantSupervisor.create();
    }
    @AfterEach
    void tearDown() {
        supervisor.shutdown();
    // ============================================================================
    // HELPER METHODS
    private TenantConfig defaultTenantConfig(String id) {
        return TenantConfig.builder(id)
                .maxConcurrentProcesses(100)
                .maxRestarts(5)
                .window(Duration.ofMinutes(1))
                .build();
    // CREATION TESTS
    @Test
    @DisplayName("create: returns non-null instance")
    void create_returnsInstance() {
        assertThat(MultiTenantSupervisor.create()).isNotNull();
    // REGISTRATION TESTS
    @DisplayName("registerTenant: valid config returns non-null tenant supervisor")
    void registerTenant_validConfig_returnsTenantSupervisor() {
        TenantConfig config = defaultTenantConfig("t1");
        var result = supervisor.registerTenant(config);
        assertThat(result).isNotNull();
    @DisplayName("registerTenant: duplicate id throws IllegalArgumentException")
    void registerTenant_duplicateId_throwsIllegalArgument() {
        supervisor.registerTenant(defaultTenantConfig("t1"));
        assertThatThrownBy(() -> supervisor.registerTenant(defaultTenantConfig("t1")))
                .isInstanceOf(IllegalArgumentException.class);
    // GET TENANT TESTS
    @DisplayName("getTenant: existing id returns supervisor")
    void getTenant_existingId_returnsSupervisor() {
        assertThat(supervisor.getTenant("t1")).isNotNull();
    @DisplayName("getTenant: unknown id returns null")
    void getTenant_unknownId_returnsNull() {
        assertThat(supervisor.getTenant("nonexistent")).isNull();
    // LIST TENANTS TESTS
    @DisplayName("listTenants: empty initially")
    void listTenants_emptyInitially() {
        assertThat(supervisor.listTenants()).isEmpty();
    @DisplayName("listTenants: after registration contains tenant info with correct tenantId")
    void listTenants_afterRegistration_containsTenantInfo() {
        var tenants = supervisor.listTenants();
        assertThat(tenants).hasSize(1);
        assertThat(tenants[0].tenantId()).isEqualTo("t1");
    // DEREGISTRATION TESTS
    @DisplayName("deregisterTenant: removed from list after deregistration")
    void deregisterTenant_removedFromList() {
        supervisor.deregisterTenant("t1", "test removal");
    @DisplayName("deregisterTenant: unknown id is a no-op (no exception)")
    void deregisterTenant_unknownId_noOp() {
        assertThatNoException().isThrownBy(() -> supervisor.deregisterTenant("unknown", "test"));
    // LISTENER TESTS
    @DisplayName("tenantListener: onTenantOnboarded fired when tenant registered")
    void tenantListener_onTenantOnboarded_fired() {
        AtomicBoolean called = new AtomicBoolean(false);
        supervisor.addListener(
                new MultiTenantSupervisor.MultiTenantListener() {
                    @Override
                    public void onTenantOnboarded(String tenantId, TenantConfig config) {
                        called.set(true);
                    }
                    public void onTenantOffboarded(String tenantId, String reason) {}
                    public void onTenantRestartLimitExceeded(String tenantId) {}
                });
        await().atMost(Duration.ofSeconds(2)).until(called::get);
    @DisplayName("tenantListener: onTenantOffboarded fired when tenant deregistered")
    void tenantListener_onTenantOffboarded_fired() {
        AtomicBoolean offboarded = new AtomicBoolean(false);
                    public void onTenantOnboarded(String tenantId, TenantConfig config) {}
                    public void onTenantOffboarded(String tenantId, String reason) {
                        offboarded.set(true);
        supervisor.deregisterTenant("t1", "test offboard");
        await().atMost(Duration.ofSeconds(2)).until(offboarded::get);
    // TENANT CONFIG VALIDATION TESTS
    @DisplayName("tenantConfig: validation constraints reject invalid values")
    void tenantConfig_validationConstraints() {
        assertThatThrownBy(() -> TenantConfig.builder("").build())
        assertThatThrownBy(() -> TenantConfig.builder("t1").maxConcurrentProcesses(0).build())
        assertThatThrownBy(() -> TenantConfig.builder("t1").maxRestarts(0).build())
    // SHUTDOWN TESTS
    @DisplayName("shutdown: deregisters all tenants without throwing")
    void shutdown_deregistersAllTenants() {
        supervisor.registerTenant(defaultTenantConfig("t2"));
        assertThatNoException().isThrownBy(() -> supervisor.shutdown());
}
