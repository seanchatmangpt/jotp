package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;

@DisplayName(
    "BulkheadIsolationEnterprise: Joe Armstrong fault isolation with semaphore-based resource limits")
class BulkheadIsolationEnterpriseTest implements WithAssertions {

  // -------------------------------------------------------------------------
  // 1. Config construction — happy path
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("createWithValidConfig_returnsInstance: valid config produces non-null bulkhead")
  void createWithValidConfig_returnsInstance() {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
            .build();

    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    assertThat(bulkhead).isNotNull();
    bulkhead.shutdown();
  }

  // -------------------------------------------------------------------------
  // 2. Config builder validations
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "configBuilder_rejectsEmptyFeatureName: empty featureName throws IllegalArgumentException")
  void configBuilder_rejectsEmptyFeatureName() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BulkheadConfig.builder("")
                    .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
                    .build());
  }

  @Test
  @DisplayName(
      "configBuilder_rejectsZeroQueueTimeout: zero queueTimeout throws IllegalArgumentException")
  void configBuilder_rejectsZeroQueueTimeout() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BulkheadConfig.builder("payments")
                    .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
                    .queueTimeout(Duration.ZERO)
                    .build());
  }

  @Test
  @DisplayName("configBuilder_rejectsNoLimits: empty limits list throws IllegalArgumentException")
  void configBuilder_rejectsNoLimits() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> BulkheadConfig.builder("payments").limits(List.of()).build());
  }

  @Test
  @DisplayName(
      "configBuilder_rejectsAlertThresholdOutOfRange: alertThreshold=1.5 throws IllegalArgumentException")
  void configBuilder_rejectsAlertThresholdOutOfRange() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BulkheadConfig.builder("payments")
                    .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
                    .alertThreshold(1.5)
                    .build());
  }

  // -------------------------------------------------------------------------
  // 3. execute — success and failure paths
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "execute_successfulTask_returnsSuccess: task returning 'done' produces Success variant")
  void execute_successfulTask_returnsSuccess() {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    BulkheadIsolationEnterprise.Result<String> result = bulkhead.execute(() -> "done");

    assertThat(result).isInstanceOf(BulkheadIsolationEnterprise.Result.Success.class);
    BulkheadIsolationEnterprise.Result.Success<String> success =
        (BulkheadIsolationEnterprise.Result.Success<String>) result;
    assertThat(success.value()).isEqualTo("done");

    bulkhead.shutdown();
  }

  @Test
  @DisplayName(
      "execute_taskThrows_returnsFailure: task throwing RuntimeException produces Failure variant")
  void execute_taskThrows_returnsFailure() {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    BulkheadIsolationEnterprise.Result<String> result =
        bulkhead.execute(
            () -> {
              throw new RuntimeException("deliberate failure");
            });

    assertThat(result).isInstanceOf(BulkheadIsolationEnterprise.Result.Failure.class);

    bulkhead.shutdown();
  }

  @Test
  @DisplayName(
      "execute_semaphoreTimeout_returnsFailure: second request times out when single permit is held")
  void execute_semaphoreTimeout_returnsFailure() throws Exception {
    // queueTimeout uses getSeconds() internally; 1s is minimum for actual waiting
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(1)))
            .queueTimeout(Duration.ofSeconds(1))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);

    // First task: acquires the single permit and holds it
    Thread holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    bulkhead.execute(
                        () -> {
                          taskStarted.countDown();
                          releaseTask.await();
                          return "held";
                        });
                  } catch (Exception e) {
                    Thread.currentThread().interrupt();
                  }
                });

    // Wait until the holder has acquired the permit
    taskStarted.await();

    // Second task: should fail to acquire the permit within 1 second
    BulkheadIsolationEnterprise.Result<String> result = bulkhead.execute(() -> "second");

    assertThat(result).isInstanceOf(BulkheadIsolationEnterprise.Result.Failure.class);

    releaseTask.countDown();
    holder.join();
    bulkhead.shutdown();
  }

  // -------------------------------------------------------------------------
  // 4. Status and utilization
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("getStatus_initiallyHealthy: freshly created bulkhead reports HEALTHY status")
  void getStatus_initiallyHealthy() {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(5)))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    assertThat(bulkhead.getStatus())
        .isEqualTo(BulkheadIsolationEnterprise.BulkheadState.Status.HEALTHY);

    bulkhead.shutdown();
  }

  @Test
  @DisplayName("getUtilizationPercent_initiallyZero: utilization is 0 when no tasks are running")
  void getUtilizationPercent_initiallyZero() {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(5)))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    assertThat(bulkhead.getUtilizationPercent()).isEqualTo(0);

    bulkhead.shutdown();
  }

  // -------------------------------------------------------------------------
  // 5. Concurrent requests
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "resourceLimit_maxConcurrentRequests_permits3Concurrent: 3 concurrent tasks all succeed with MaxConcurrentRequests(3)")
  void resourceLimit_maxConcurrentRequests_permits3Concurrent() throws Exception {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    CountDownLatch allStarted = new CountDownLatch(3);
    CountDownLatch releaseAll = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch allDone = new CountDownLatch(3);

    for (int i = 0; i < 3; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  BulkheadIsolationEnterprise.Result<String> result =
                      bulkhead.execute(
                          () -> {
                            allStarted.countDown();
                            releaseAll.await();
                            return "ok";
                          });
                  if (result instanceof BulkheadIsolationEnterprise.Result.Success<?>) {
                    successCount.incrementAndGet();
                  }
                } catch (Exception e) {
                  Thread.currentThread().interrupt();
                } finally {
                  allDone.countDown();
                }
              });
    }

    // All 3 should acquire their permits concurrently before any is released
    allStarted.await();
    releaseAll.countDown();
    allDone.await();

    assertThat(successCount.get()).isEqualTo(3);

    bulkhead.shutdown();
  }

  // -------------------------------------------------------------------------
  // 6. ResourceLimit variants
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "resourceLimit_allVariantsInstantiable: all ResourceLimit sealed variants construct without error")
  void resourceLimit_allVariantsInstantiable() {
    ResourceLimit maxConcurrent = new ResourceLimit.MaxConcurrentRequests(10);
    ResourceLimit maxQueue = new ResourceLimit.MaxQueueSize(100);
    ResourceLimit maxMemory = new ResourceLimit.MaxMemoryBytes(1024 * 1024 * 256L);
    ResourceLimit maxCpu = new ResourceLimit.MaxCPUPercent(75.0);
    ResourceLimit composite = new ResourceLimit.Composite(List.of(maxConcurrent, maxQueue));

    assertThat(maxConcurrent).isNotNull();
    assertThat(maxQueue).isNotNull();
    assertThat(maxMemory).isNotNull();
    assertThat(maxCpu).isNotNull();
    assertThat(composite).isNotNull();
  }

  // -------------------------------------------------------------------------
  // 7. BulkheadStrategy variants
  // -------------------------------------------------------------------------

  @Test
  @DisplayName(
      "bulkheadStrategy_allVariantsInstantiable: all BulkheadStrategy sealed variants construct without error")
  void bulkheadStrategy_allVariantsInstantiable() {
    BulkheadStrategy threadPool = new BulkheadStrategy.ThreadPoolBased(8);
    BulkheadStrategy processBased = new BulkheadStrategy.ProcessBased();
    BulkheadStrategy weighted = new BulkheadStrategy.Weighted(0.7, 0.3);
    BulkheadStrategy adaptive = new BulkheadStrategy.Adaptive(2, 20);

    assertThat(threadPool).isNotNull();
    assertThat(processBased).isNotNull();
    assertThat(weighted).isNotNull();
    assertThat(adaptive).isNotNull();
  }

  // -------------------------------------------------------------------------
  // 8. Shutdown
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("shutdown_doesNotThrow: calling shutdown on a live bulkhead throws no exception")
  void shutdown_doesNotThrow() {
    BulkheadConfig config =
        BulkheadConfig.builder("payments")
            .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
            .build();
    BulkheadIsolationEnterprise bulkhead = BulkheadIsolationEnterprise.create(config);

    assertThatNoException().isThrownBy(bulkhead::shutdown);
  }
}
