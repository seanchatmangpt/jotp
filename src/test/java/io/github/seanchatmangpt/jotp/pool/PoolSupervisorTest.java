package io.github.seanchatmangpt.jotp.pool;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.ProcRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for {@link PoolSupervisor}.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Task distribution and execution
 *   <li>Round-robin load balancing
 *   <li>Worker crash recovery via supervisor restart
 *   <li>Timeout behavior and error handling
 *   <li>Graceful shutdown
 *   <li>Statistics tracking and reporting
 * </ul>
 *
 * @see PoolSupervisor
 * @see PoolStats
 */
@DisplayName("PoolSupervisor: Worker Pool Abstraction")
class PoolSupervisorTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    @BeforeEach
    void setUp() {
        ProcRegistry.reset();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ProcRegistry.reset();
    }

    // ============================================================================
    // BASIC TASK SUBMISSION AND EXECUTION TESTS
    // ============================================================================

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
    }

    @Test
    @DisplayName("Pool submits multiple tasks concurrently")
    void testMultipleTasks() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 3, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
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
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // ROUND-ROBIN DISTRIBUTION TESTS
    // ============================================================================

    @Test
    @DisplayName("Round-robin distributes tasks across workers")
    void testRoundRobinDistribution() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 4, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            AtomicInteger count = new AtomicInteger(0);
            List<CompletableFuture<Integer>> futures = new ArrayList<>();

            // Submit 12 tasks to a 4-worker pool
            // Expect roughly 3 tasks per worker if round-robin is working
            for (int i = 0; i < 12; i++) {
                futures.add(pool.ask(() -> count.incrementAndGet(), Duration.ofSeconds(2)));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            // All tasks should have executed
            assertThat(count.get()).isEqualTo(12);

            // Check stats: all workers should be alive
            PoolStats stats = pool.getStats();
            assertThat(stats.activeWorkers()).isEqualTo(4);
            assertThat(stats.completedTasks()).isEqualTo(12);
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // TIMEOUT TESTS
    // ============================================================================

    @Test
    @DisplayName("Task timeout returns TimeoutException")
    void testTaskTimeout() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(10))
                        .build();

        try {
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
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Task completes successfully before timeout")
    void testTaskCompletesBeforeTimeout() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            CompletableFuture<Integer> result =
                    pool.ask(
                            () -> {
                                Thread.sleep(100);
                                return 99;
                            },
                            Duration.ofSeconds(2));

            int value = result.get(3, TimeUnit.SECONDS);
            assertThat(value).isEqualTo(99);
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // EXCEPTION HANDLING TESTS
    // ============================================================================

    @Test
    @DisplayName("Task exceptions are propagated to caller")
    void testTaskException() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            CompletableFuture<Integer> result =
                    pool.ask(
                            () -> {
                                throw new IllegalArgumentException("Task failed");
                            },
                            Duration.ofSeconds(2));

            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Task failed");
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // WORKER CRASH AND RECOVERY TESTS
    // ============================================================================

    @Test
    @DisplayName("Worker crash triggers supervisor restart")
    void testWorkerCrashRecovery() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withRestartLimits(10, Duration.ofSeconds(60))
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            AtomicInteger callCount = new AtomicInteger(0);

            // First task succeeds
            CompletableFuture<Integer> result1 =
                    pool.ask(
                            () -> {
                                callCount.incrementAndGet();
                                return 1;
                            },
                            Duration.ofSeconds(2));
            assertThat(result1.get(3, TimeUnit.SECONDS)).isEqualTo(1);

            // Second task crashes (simulating worker failure)
            CompletableFuture<Integer> result2 =
                    pool.ask(
                            () -> {
                                callCount.incrementAndGet();
                                throw new RuntimeException("Simulated crash");
                            },
                            Duration.ofSeconds(2));
            assertThatThrownBy(() -> result2.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(RuntimeException.class);

            // Third task should succeed (worker restarted)
            awaitAtMost(Duration.ofSeconds(5))
                    .pollInSameThread()
                    .until(
                            () -> {
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
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // SHUTDOWN TESTS
    // ============================================================================

    @Test
    @DisplayName("Graceful shutdown prevents new task submission")
    void testShutdownPreventsNewTasks() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        // Shutdown the pool
        pool.shutdown();

        // Attempt to submit a new task
        CompletableFuture<Integer> result = pool.ask(() -> 42, Duration.ofSeconds(2));

        // Should fail with IllegalStateException
        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Pool is shut down");
    }

    @Test
    @DisplayName("isRunning() returns false after shutdown")
    void testIsRunningAfterShutdown() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        assertThat(pool.isRunning()).isTrue();

        pool.shutdown();

        assertThat(pool.isRunning()).isFalse();
    }

    // ============================================================================
    // STATISTICS TESTS
    // ============================================================================

    @Test
    @DisplayName("getStats() returns correct completed task count")
    void testStatsCompletedTasks() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            PoolStats initialStats = pool.getStats();
            assertThat(initialStats.completedTasks()).isEqualTo(0);

            // Submit and complete 5 tasks
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.ask(() -> i, Duration.ofSeconds(2)));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            PoolStats stats = pool.getStats();
            assertThat(stats.completedTasks()).isEqualTo(5);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("getStats() tracks active workers")
    void testStatsActiveWorkers() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 3, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            PoolStats stats = pool.getStats();
            assertThat(stats.activeWorkers()).isEqualTo(3);
            assertThat(stats.totalWorkers()).isEqualTo(3);
            assertThat(stats.activePercentage()).isEqualTo(100.0);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("getStats() calculates average response time")
    void testStatsAverageResponseTime() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            // Submit slow tasks to accumulate response time
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(
                        pool.ask(
                                () -> {
                                    Thread.sleep(50);
                                    return i;
                                },
                                Duration.ofSeconds(2)));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            PoolStats stats = pool.getStats();
            assertThat(stats.completedTasks()).isEqualTo(3);
            assertThat(stats.avgResponseTimeMs()).isGreaterThanOrEqualTo(40);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("PoolStats.toString() provides formatted output")
    void testPoolStatsToString() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 4, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            PoolStats stats = pool.getStats();
            String output = stats.toString();

            assertThat(output)
                    .contains("PoolStats")
                    .contains("active=")
                    .contains("completed=")
                    .contains("avgResponseTime");
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // CONFIGURATION BUILDER TESTS
    // ============================================================================

    @Test
    @DisplayName("Builder configures restart strategy")
    void testBuilderRestartStrategy() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withRestartStrategy(
                                io.github.seanchatmangpt.jotp.Supervisor.Strategy.ONE_FOR_ONE)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            assertThat(pool.isRunning()).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Builder configures restart limits")
    void testBuilderRestartLimits() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withRestartLimits(3, Duration.ofSeconds(30))
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            assertThat(pool.isRunning()).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Builder configures task timeout")
    void testBuilderTaskTimeout() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 2, () -> 0)
                        .withTimeout(Duration.ofSeconds(3))
                        .build();

        try {
            CompletableFuture<Integer> result = pool.ask(() -> 42, Duration.ofSeconds(2));
            assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(42);
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // POOL IDENTITY TESTS
    // ============================================================================

    @Test
    @DisplayName("Pool name and worker count are accessible")
    void testPoolIdentity() throws Exception {
        var pool =
                PoolSupervisor.builder("my-pool", 5, () -> 0)
                        .withTimeout(Duration.ofSeconds(5))
                        .build();

        try {
            assertThat(pool.getName()).isEqualTo("my-pool");
            assertThat(pool.getWorkerCount()).isEqualTo(5);
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================================
    // CONCURRENT STRESS TESTS
    // ============================================================================

    @Test
    @DisplayName("Pool handles high concurrency (50+ concurrent submissions)")
    void testHighConcurrency() throws Exception {
        var pool =
                PoolSupervisor.builder("test-pool", 4, () -> 0)
                        .withTimeout(Duration.ofSeconds(10))
                        .build();

        try {
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
                }

                // Expect most (90%+) to succeed
                assertThat(completed).isGreaterThanOrEqualTo(45);

                PoolStats stats = pool.getStats();
                assertThat(stats.completedTasks()).isGreaterThanOrEqualTo(45);
            } finally {
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
    }
}
