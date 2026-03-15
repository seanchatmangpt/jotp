package io.github.seanchatmangpt.jotp.enterprise.bulkhead;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.BeforeEach;
@DisplayName(
        "BulkheadIsolationEnterprise: Joe Armstrong fault isolation with semaphore-based resource limits")
class BulkheadIsolationEnterpriseTest implements WithAssertions {
    // -------------------------------------------------------------------------
    // 1. Config construction — happy path
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
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
    // 2. Config builder validations
    @DisplayName(
            "configBuilder_rejectsEmptyFeatureName: empty featureName throws IllegalArgumentException")
    void configBuilder_rejectsEmptyFeatureName() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                BulkheadConfig.builder("")
                                        .limits(List.of(new ResourceLimit.MaxConcurrentRequests(3)))
                                        .build());
            "configBuilder_rejectsZeroQueueTimeout: zero queueTimeout throws IllegalArgumentException")
    void configBuilder_rejectsZeroQueueTimeout() {
                                BulkheadConfig.builder("payments")
                                        .queueTimeout(Duration.ZERO)
    @DisplayName("configBuilder_rejectsNoLimits: empty limits list throws IllegalArgumentException")
    void configBuilder_rejectsNoLimits() {
                .isThrownBy(() -> BulkheadConfig.builder("payments").limits(List.of()).build());
            "configBuilder_rejectsAlertThresholdOutOfRange: alertThreshold=1.5 throws IllegalArgumentException")
    void configBuilder_rejectsAlertThresholdOutOfRange() {
                                        .alertThreshold(1.5)
    // 3. execute — success and failure paths
            "execute_successfulTask_returnsSuccess: task returning 'done' produces Success variant")
    void execute_successfulTask_returnsSuccess() {
        BulkheadIsolationEnterprise.Result<String> result = bulkhead.execute(() -> "done");
        assertThat(result).isInstanceOf(BulkheadIsolationEnterprise.Result.Success.class);
        BulkheadIsolationEnterprise.Result.Success<String> success =
                (BulkheadIsolationEnterprise.Result.Success<String>) result;
        assertThat(success.value()).isEqualTo("done");
            "execute_taskThrows_returnsFailure: task throwing RuntimeException produces Failure variant")
    void execute_taskThrows_returnsFailure() {
        BulkheadIsolationEnterprise.Result<String> result =
                bulkhead.execute(
                        () -> {
                            throw new RuntimeException("deliberate failure");
                        });
        assertThat(result).isInstanceOf(BulkheadIsolationEnterprise.Result.Failure.class);
            "execute_semaphoreTimeout_returnsFailure: second request times out when single permit is held")
    void execute_semaphoreTimeout_returnsFailure() throws Exception {
        // queueTimeout uses getSeconds() internally; 1s is minimum for actual waiting
                        .limits(List.of(new ResourceLimit.MaxConcurrentRequests(1)))
                        .queueTimeout(Duration.ofSeconds(1))
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
        releaseTask.countDown();
        holder.join();
    // 4. Status and utilization
    @DisplayName("getStatus_initiallyHealthy: freshly created bulkhead reports HEALTHY status")
    void getStatus_initiallyHealthy() {
                        .limits(List.of(new ResourceLimit.MaxConcurrentRequests(5)))
        assertThat(bulkhead.getStatus())
                .isEqualTo(BulkheadIsolationEnterprise.BulkheadState.Status.HEALTHY);
    @DisplayName("getUtilizationPercent_initiallyZero: utilization is 0 when no tasks are running")
    void getUtilizationPercent_initiallyZero() {
        assertThat(bulkhead.getUtilizationPercent()).isEqualTo(0);
    // 5. Concurrent requests
            "resourceLimit_maxConcurrentRequests_permits3Concurrent: 3 concurrent tasks all succeed with MaxConcurrentRequests(3)")
    void resourceLimit_maxConcurrentRequests_permits3Concurrent() throws Exception {
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
                                    if (result
                                            instanceof
                                            BulkheadIsolationEnterprise.Result.Success<?>) {
                                        successCount.incrementAndGet();
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
    // 6. ResourceLimit variants
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
    // 7. BulkheadStrategy variants
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
    // 8. Shutdown
    @DisplayName("shutdown_doesNotThrow: calling shutdown on a live bulkhead throws no exception")
    void shutdown_doesNotThrow() {
        assertThatNoException().isThrownBy(bulkhead::shutdown);
}
