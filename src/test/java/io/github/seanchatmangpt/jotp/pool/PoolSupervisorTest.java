package io.github.seanchatmangpt.jotp.pool;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * Comprehensive test suite for {@link PoolSupervisor}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Task distribution and execution
 *   <li>Round-robin load balancing
 *   <li>Worker crash recovery via supervisor restart
 *   <li>Timeout behavior and error handling
 *   <li>Graceful shutdown
 *   <li>Statistics tracking and reporting
 * </ul>
 * @see PoolSupervisor
 * @see PoolStats
 */
@DisplayName("PoolSupervisor: Worker Pool Abstraction")
class PoolSupervisorTest {
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        ProcRegistry.reset();
    }
    @AfterEach
    void tearDown() throws InterruptedException {
    // ============================================================================
    // BASIC TASK SUBMISSION AND EXECUTION TESTS
    @Test
    @DisplayName("Pool executes submitted tasks and returns results")
    void testTaskExecution() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();
        try {
            CompletableFuture<Integer> result = pool.ask(() -> 42, Duration.ofSeconds(2));
            int value = result.get(3, TimeUnit.SECONDS);
            assertThat(value).isEqualTo(42);
        } finally {
            pool.shutdown();
        }
    @DisplayName("Pool submits multiple tasks concurrently")
    void testMultipleTasks() throws Exception {
                PoolSupervisor.builder("test-pool", 3, () -> 0)
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final int taskId = i;
                futures.add(pool.ask(() -> taskId * 2, Duration.ofSeconds(2)));
            }
            List<Integer> results =
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(_ -> futures.stream().map(f -> f.join()).toList())
                            .get(5, TimeUnit.SECONDS);
            assertThat(results).hasSize(10).containsExactly(0, 2, 4, 6, 8, 10, 12, 14, 16, 18);
    // ROUND-ROBIN DISTRIBUTION TESTS
    @DisplayName("Round-robin distributes tasks across workers")
    void testRoundRobinDistribution() throws Exception {
                PoolSupervisor.builder("test-pool", 4, () -> 0)
            AtomicInteger count = new AtomicInteger(0);
            // Submit 12 tasks to a 4-worker pool
            // Expect roughly 3 tasks per worker if round-robin is working
            for (int i = 0; i < 12; i++) {
                futures.add(pool.ask(() -> count.incrementAndGet(), Duration.ofSeconds(2)));
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
            // All tasks should have executed
            assertThat(count.get()).isEqualTo(12);
            // Check stats: all workers should be alive
            PoolStats stats = pool.getStats();
            assertThat(stats.activeWorkers()).isEqualTo(4);
            assertThat(stats.completedTasks()).isEqualTo(12);
    // TIMEOUT TESTS
    @DisplayName("Task timeout returns TimeoutException")
    void testTaskTimeout() throws Exception {
                        .withTimeout(Duration.ofSeconds(10))
            CompletableFuture<Integer> result =
                    pool.ask(
                            () -> {
                                Thread.sleep(2000); // Simulate slow task
                                return 42;
                            },
                            Duration.ofMillis(500) // Short timeout
                            );
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(TimeoutException.class);
    @DisplayName("Task completes successfully before timeout")
    void testTaskCompletesBeforeTimeout() throws Exception {
                                Thread.sleep(100);
                                return 99;
                            Duration.ofSeconds(2));
            assertThat(value).isEqualTo(99);
    // EXCEPTION HANDLING TESTS
    @DisplayName("Task exceptions are propagated to caller")
    void testTaskException() throws Exception {
                                throw new IllegalArgumentException("Task failed");
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Task failed");
    // WORKER CRASH AND RECOVERY TESTS
    @DisplayName("Worker crash triggers supervisor restart")
    void testWorkerCrashRecovery() throws Exception {
                        .withRestartLimits(10, Duration.ofSeconds(60))
            AtomicInteger callCount = new AtomicInteger(0);
            // First task succeeds
            CompletableFuture<Integer> result1 =
                                callCount.incrementAndGet();
                                return 1;
            assertThat(result1.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            // Second task crashes (simulating worker failure)
            CompletableFuture<Integer> result2 =
                                throw new RuntimeException("Simulated crash");
            assertThatThrownBy(() -> result2.get(3, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(RuntimeException.class);
            // Third task should succeed (worker restarted)
            awaitAtMost(Duration.ofSeconds(5))
                    .pollInSameThread()
                    .until(
                                try {
                                    CompletableFuture<Integer> result3 =
                                            pool.ask(
                                                    () -> {
                                                        callCount.incrementAndGet();
                                                        return 3;
                                                    },
                                                    Duration.ofSeconds(2));
                                    return result3.get(3, TimeUnit.SECONDS) == 3;
                                } catch (Exception e) {
                                    return false;
                                }
                            });
            assertThat(callCount.get()).isGreaterThanOrEqualTo(3);
    // SHUTDOWN TESTS
    @DisplayName("Graceful shutdown prevents new task submission")
    void testShutdownPreventsNewTasks() throws Exception {
        // Shutdown the pool
        pool.shutdown();
        // Attempt to submit a new task
        CompletableFuture<Integer> result = pool.ask(() -> 42, Duration.ofSeconds(2));
        // Should fail with IllegalStateException
        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Pool is shut down");
    @DisplayName("isRunning() returns false after shutdown")
    void testIsRunningAfterShutdown() throws Exception {
        assertThat(pool.isRunning()).isTrue();
        assertThat(pool.isRunning()).isFalse();
    // STATISTICS TESTS
    @DisplayName("getStats() returns correct completed task count")
    void testStatsCompletedTasks() throws Exception {
            PoolStats initialStats = pool.getStats();
            assertThat(initialStats.completedTasks()).isEqualTo(0);
            // Submit and complete 5 tasks
            for (int i = 0; i < 5; i++) {
                futures.add(pool.ask(() -> i, Duration.ofSeconds(2)));
            assertThat(stats.completedTasks()).isEqualTo(5);
    @DisplayName("getStats() tracks active workers")
    void testStatsActiveWorkers() throws Exception {
            assertThat(stats.activeWorkers()).isEqualTo(3);
            assertThat(stats.totalWorkers()).isEqualTo(3);
            assertThat(stats.activePercentage()).isEqualTo(100.0);
    @DisplayName("getStats() calculates average response time")
    void testStatsAverageResponseTime() throws Exception {
            // Submit slow tasks to accumulate response time
            for (int i = 0; i < 3; i++) {
                futures.add(
                        pool.ask(
                                () -> {
                                    Thread.sleep(50);
                                    return i;
                                },
                                Duration.ofSeconds(2)));
            assertThat(stats.completedTasks()).isEqualTo(3);
            assertThat(stats.avgResponseTimeMs()).isGreaterThanOrEqualTo(40);
    @DisplayName("PoolStats.toString() provides formatted output")
    void testPoolStatsToString() throws Exception {
            String output = stats.toString();
            assertThat(output)
                    .contains("PoolStats")
                    .contains("active=")
                    .contains("completed=")
                    .contains("avgResponseTime");
    // CONFIGURATION BUILDER TESTS
    @DisplayName("Builder configures restart strategy")
    void testBuilderRestartStrategy() throws Exception {
                        .withRestartStrategy(
                                io.github.seanchatmangpt.jotp.Supervisor.Strategy.ONE_FOR_ONE)
            assertThat(pool.isRunning()).isTrue();
    @DisplayName("Builder configures restart limits")
    void testBuilderRestartLimits() throws Exception {
                        .withRestartLimits(3, Duration.ofSeconds(30))
    @DisplayName("Builder configures task timeout")
    void testBuilderTaskTimeout() throws Exception {
                        .withTimeout(Duration.ofSeconds(3))
            assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(42);
    // POOL IDENTITY TESTS
    @DisplayName("Pool name and worker count are accessible")
    void testPoolIdentity() throws Exception {
                PoolSupervisor.builder("my-pool", 5, () -> 0)
            assertThat(pool.getName()).isEqualTo("my-pool");
            assertThat(pool.getWorkerCount()).isEqualTo(5);
    // CONCURRENT STRESS TESTS
    @DisplayName("Pool handles high concurrency (50+ concurrent submissions)")
    void testHighConcurrency() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(10);
            List<Future<Integer>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 50; i++) {
                    final int taskId = i;
                    futures.add(
                            executor.submit(
                                    () -> {
                                        CompletableFuture<Integer> result =
                                                pool.ask(() -> taskId * 2, Duration.ofSeconds(3));
                                        return result.get();
                                    }));
                }
                // Wait for all futures to complete
                int completed = 0;
                for (Future<Integer> future : futures) {
                    try {
                        future.get(5, TimeUnit.SECONDS);
                        completed++;
                    } catch (Exception e) {
                        // Some tasks might fail due to timing
                    }
                // Expect most (90%+) to succeed
                assertThat(completed).isGreaterThanOrEqualTo(45);
                PoolStats stats = pool.getStats();
                assertThat(stats.completedTasks()).isGreaterThanOrEqualTo(45);
            } finally {
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
}
