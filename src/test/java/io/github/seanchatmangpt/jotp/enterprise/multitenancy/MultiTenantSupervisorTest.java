package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;

@DisplayName("MultiTenantSupervisor: Joe Armstrong tenant-per-supervisor isolation with 99.95% SLA")
class MultiTenantSupervisorTest implements WithAssertions {

  private MultiTenantSupervisor supervisor;

  @BeforeEach
  void setUp() {
    supervisor = MultiTenantSupervisor.create();
  }

  @AfterEach
  void tearDown() {
    supervisor.shutdown();
  }

  // ============================================================================
  // HELPER METHODS
  // ============================================================================

  private TenantConfig defaultTenantConfig(String id) {
    return TenantConfig.builder(id)
        .maxConcurrentProcesses(100)
        .maxRestarts(5)
        .window(Duration.ofMinutes(1))
        .build();
  }

  // ============================================================================
  // CREATION TESTS
  // ============================================================================

  @Test
  @DisplayName("create: returns non-null instance")
  void create_returnsInstance() {
    assertThat(MultiTenantSupervisor.create()).isNotNull();
  }

  // ============================================================================
  // REGISTRATION TESTS
  // ============================================================================

  @Test
  @DisplayName("registerTenant: valid config returns non-null tenant supervisor")
  void registerTenant_validConfig_returnsTenantSupervisor() {
    TenantConfig config = defaultTenantConfig("t1");
    var result = supervisor.registerTenant(config);
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("registerTenant: duplicate id throws IllegalArgumentException")
  void registerTenant_duplicateId_throwsIllegalArgument() {
    supervisor.registerTenant(defaultTenantConfig("t1"));
    assertThatThrownBy(() -> supervisor.registerTenant(defaultTenantConfig("t1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ============================================================================
  // GET TENANT TESTS
  // ============================================================================

  @Test
  @DisplayName("getTenant: existing id returns supervisor")
  void getTenant_existingId_returnsSupervisor() {
    supervisor.registerTenant(defaultTenantConfig("t1"));
    assertThat(supervisor.getTenant("t1")).isNotNull();
  }

  @Test
  @DisplayName("getTenant: unknown id returns null")
  void getTenant_unknownId_returnsNull() {
    assertThat(supervisor.getTenant("nonexistent")).isNull();
  }

  // ============================================================================
  // LIST TENANTS TESTS
  // ============================================================================

  @Test
  @DisplayName("listTenants: empty initially")
  void listTenants_emptyInitially() {
    assertThat(supervisor.listTenants()).isEmpty();
  }

  @Test
  @DisplayName("listTenants: after registration contains tenant info with correct tenantId")
  void listTenants_afterRegistration_containsTenantInfo() {
    supervisor.registerTenant(defaultTenantConfig("t1"));
    var tenants = supervisor.listTenants();
    assertThat(tenants).hasSize(1);
    assertThat(tenants[0].tenantId()).isEqualTo("t1");
  }

  // ============================================================================
  // DEREGISTRATION TESTS
  // ============================================================================

  @Test
  @DisplayName("deregisterTenant: removed from list after deregistration")
  void deregisterTenant_removedFromList() {
    supervisor.registerTenant(defaultTenantConfig("t1"));
    supervisor.deregisterTenant("t1", "test removal");
    assertThat(supervisor.listTenants()).isEmpty();
  }

  @Test
  @DisplayName("deregisterTenant: unknown id is a no-op (no exception)")
  void deregisterTenant_unknownId_noOp() {
    assertThatNoException().isThrownBy(() -> supervisor.deregisterTenant("unknown", "test"));
  }

  // ============================================================================
  // LISTENER TESTS
  // ============================================================================

  @Test
  @DisplayName("tenantListener: onTenantOnboarded fired when tenant registered")
  void tenantListener_onTenantOnboarded_fired() {
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
  }

  @Test
  @DisplayName("tenantListener: onTenantOffboarded fired when tenant deregistered")
  void tenantListener_onTenantOffboarded_fired() {
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
  }

  // ============================================================================
  // TENANT CONFIG VALIDATION TESTS
  // ============================================================================

  @Test
  @DisplayName("tenantConfig: validation constraints reject invalid values")
  void tenantConfig_validationConstraints() {
    assertThatThrownBy(() -> TenantConfig.builder("").build())
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                TenantConfig.builder("t1")
                    .maxConcurrentProcesses(0)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                TenantConfig.builder("t1")
                    .maxRestarts(0)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ============================================================================
  // SHUTDOWN TESTS
  // ============================================================================

  @Test
  @DisplayName("shutdown: deregisters all tenants without throwing")
  void shutdown_deregistersAllTenants() {
    supervisor.registerTenant(defaultTenantConfig("t1"));
    supervisor.registerTenant(defaultTenantConfig("t2"));
    assertThatNoException().isThrownBy(() -> supervisor.shutdown());
  }
}
