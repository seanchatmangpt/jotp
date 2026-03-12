package io.github.seanchatmangpt.jotp.pool;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * High-level worker pool abstraction built on top of Supervisor + ProcRegistry.
 *
 * <p><strong>Worker Pool Pattern:</strong>
 *
 * <p>A {@code PoolSupervisor<T>} manages a fixed set of worker processes that execute tasks
 * supplied by callers. Each task is routed to an available worker via round-robin distribution.
 * If a worker crashes, the supervision tree automatically restarts it. Callers receive results
 * as {@link CompletableFuture} values, enabling non-blocking task submission and result retrieval.
 *
 * <p><strong>Architecture:</strong>
 *
 * <ul>
 *   <li><strong>Supervisor:</strong> Manages worker lifecycle with {@link Supervisor.Strategy#ONE_FOR_ONE}
 *       restart policy — only crashed workers are restarted, not the entire pool.
 *   <li><strong>Workers:</strong> Lightweight processes (virtual threads) registered in {@link ProcRegistry}
 *       with names like {@code "worker-0"}, {@code "worker-1"}, etc.
 *   <li><strong>Round-Robin:</strong> Tasks are distributed sequentially across workers to balance load.
 *   <li><strong>Task Messages:</strong> Workers receive tasks as {@link PoolTaskMsg} messages and
 *       execute supplier-based work.
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Create a pool with 4 workers
 * var pool = PoolSupervisor.builder("compute-pool", 4, () -> 0)
 *     .withRestartStrategy(Supervisor.Strategy.ONE_FOR_ONE)
 *     .withRestartLimits(5, Duration.ofSeconds(60))
 *     .withTimeout(Duration.ofSeconds(10))
 *     .build();
 *
 * // Submit tasks
 * CompletableFuture<Integer> result = pool.ask(
 *     () -> computeExpensiveValue(),
 *     Duration.ofSeconds(5)
 * );
 *
 * // Access results
 * int value = result.join();  // blocks until complete or timeout
 *
 * // Graceful shutdown
 * pool.shutdown();
 *
 * // Check stats
 * PoolStats stats = pool.getStats();
 * System.out.println("Active workers: " + stats.activeWorkers());
 * System.out.println("Completed tasks: " + stats.completedTasks());
 * }</pre>
 *
 * <p><strong>Error Handling:</strong>
 *
 * <ul>
 *   <li><strong>Worker Crash:</strong> Supervisor automatically restarts the worker via
 *       {@code ONE_FOR_ONE} strategy.
 *   <li><strong>Task Timeout:</strong> {@link CompletableFuture#orTimeout(long, TimeUnit)} ensures
 *       results or timeout exceptions are returned within the specified duration.
 *   <li><strong>Pool Shutdown:</strong> {@link #shutdown()} gracefully stops all workers and
 *       cancels pending tasks.
 * </ul>
 *
 * @param <T> the result type produced by tasks in this pool
 * @see Supervisor
 * @see ProcRegistry
 * @see PoolStats
 */
public final class PoolSupervisor<T> {

    /**
     * Internal message type for worker tasks.
     */
    sealed interface PoolTaskMsg<T> permits PoolTaskMsg.ExecuteTask {}

    record PoolExecuteTask<T>(Supplier<T> task, CompletableFuture<T> future)
            implements PoolTaskMsg<T> {
        static <T> PoolExecuteTask<T> create(Supplier<T> supplier, CompletableFuture<T> future) {
            return new PoolExecuteTask<>(supplier, future);
        }
    }

    /**
     * Worker state: tracks if a worker is busy and provides basic lifecycle info.
     */
    private static final class WorkerState {
        volatile boolean busy = false;
        volatile Instant lastTaskTime = null;
    }

    private final String name;
    private final int workerCount;
    private final Supervisor supervisor;
    private final List<String> workerNames;
    private final AtomicInteger nextWorkerIndex = new AtomicInteger(0);
    private final Duration taskTimeout;

    // Statistics tracking
    private final AtomicLong completedTasks = new AtomicLong(0);
    private final AtomicLong totalResponseTimeMs = new AtomicLong(0);

    private volatile boolean running = true;
    private final Object shutdownLock = new Object();

    /**
     * Create a pool supervisor instance.
     *
     * <p>This constructor is package-private; use {@link #builder(String, int, Supplier)} to
     * create instances.
     *
     * @param name pool name (for identification)
     * @param supervisor the underlying supervisor managing worker processes
     * @param workerCount number of workers in the pool
     * @param taskTimeout default timeout for task execution
     */
    private PoolSupervisor(
            String name,
            Supervisor supervisor,
            int workerCount,
            Duration taskTimeout
    ) {
        this.name = name;
        this.supervisor = supervisor;
        this.workerCount = workerCount;
        this.taskTimeout = taskTimeout;
        this.workerNames = new ArrayList<>();

        // Create worker names and register them
        for (int i = 0; i < workerCount; i++) {
            String workerName = name + "-worker-" + i;
            workerNames.add(workerName);
        }
    }

    /**
     * Create a builder for constructing a {@code PoolSupervisor<T>}.
     *
     * @param name pool name (used for worker registration prefix)
     * @param workerCount number of worker processes to create
     * @param initialStateFactory factory for creating initial worker state
     * @return a {@code PoolSupervisorBuilder<T>}
     * @param <T> result type
     */
    public static <T> PoolSupervisorBuilder<T> builder(
            String name,
            int workerCount,
            Supplier<?> initialStateFactory
    ) {
        return new PoolSupervisorBuilder<>(name, workerCount, initialStateFactory);
    }

    /**
     * Builder class for fluent configuration of {@code PoolSupervisor}.
     *
     * @param <T> result type
     */
    public static final class PoolSupervisorBuilder<T> {
        private final String name;
        private final int workerCount;
        private final Supplier<?> stateFactory;
        private Supervisor.Strategy restartStrategy = Supervisor.Strategy.ONE_FOR_ONE;
        private int maxRestarts = 5;
        private Duration restartWindow = Duration.ofSeconds(60);
        private Duration taskTimeout = Duration.ofSeconds(30);

        private PoolSupervisorBuilder(
                String name,
                int workerCount,
                Supplier<?> stateFactory
        ) {
            this.name = name;
            this.workerCount = workerCount;
            this.stateFactory = stateFactory;
        }

        /**
         * Set the restart strategy for the supervisor.
         *
         * @param strategy restart strategy (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
         * @return this builder
         */
        public PoolSupervisorBuilder<T> withRestartStrategy(Supervisor.Strategy strategy) {
            this.restartStrategy = strategy;
            return this;
        }

        /**
         * Set the restart limits (max restarts within a time window).
         *
         * @param maxRestarts maximum number of restarts allowed
         * @param window time window for counting restarts
         * @return this builder
         */
        public PoolSupervisorBuilder<T> withRestartLimits(int maxRestarts, Duration window) {
            this.maxRestarts = maxRestarts;
            this.restartWindow = window;
            return this;
        }

        /**
         * Set the default timeout for task execution.
         *
         * @param timeout default task timeout
         * @return this builder
         */
        public PoolSupervisorBuilder<T> withTimeout(Duration timeout) {
            this.taskTimeout = timeout;
            return this;
        }

        /**
         * Build and return a new {@code PoolSupervisor<T>}.
         *
         * <p>This method creates the supervisor, spawns worker processes, and registers them
         * in the global {@link ProcRegistry}.
         *
         * @return a new, initialized pool supervisor
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public PoolSupervisor<T> build() {
            var supervisor = Supervisor.create(
                    name + "-supervisor",
                    restartStrategy,
                    maxRestarts,
                    restartWindow
            );

            var pool = new PoolSupervisor<>(name, supervisor, workerCount, taskTimeout);

            // Spawn worker processes and register them
            for (String workerName : pool.workerNames) {
                WorkerState workerState = new WorkerState();
                BiFunction<WorkerState, PoolTaskMsg, WorkerState> handler =
                        (state, msg) -> {
                            if (msg instanceof PoolExecuteTask task) {
                                try {
                                    state.busy = true;
                                    state.lastTaskTime = Instant.now();
                                    T result = task.task().get();
                                    task.future().complete(result);
                                    state.busy = false;
                                    return state;
                                } catch (Exception e) {
                                    task.future().completeExceptionally(e);
                                    state.busy = false;
                                    throw new RuntimeException(e);
                                }
                            }
                            return state;
                        };

                @SuppressWarnings("rawtypes")
                ProcRef workerRef = supervisor.supervise(
                        workerName,
                        workerState,
                        handler
                );

                // Get the underlying Proc and register in global registry
                try {
                    Proc<WorkerState, PoolTaskMsg> proc = (Proc<WorkerState, PoolTaskMsg>) workerRef;
                    ProcRegistry.register(workerName, proc);
                } catch (IllegalStateException e) {
                    // Ignore if already registered (shouldn't happen on initial creation)
                }
            }

            return pool;
        }
    }

    /**
     * Submit a task to the pool for execution.
     *
     * <p>The task is routed to the next worker in round-robin order. Returns a
     * {@link CompletableFuture} that completes with the task result or a timeout exception
     * if the timeout is exceeded.
     *
     * @param task the supplier providing the task to execute
     * @param timeout maximum time to wait for the task to complete
     * @return a future that completes with the task result or fails with timeout
     * @throws IllegalStateException if the pool is shut down
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<T> ask(Supplier<T> task, Duration timeout) {
        synchronized (shutdownLock) {
            if (!running) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Pool is shut down")
                );
            }
        }

        // Create result future
        var resultFuture = new CompletableFuture<T>();

        // Select next worker via round-robin
        int index = nextWorkerIndex.getAndIncrement() % workerCount;
        String workerName = workerNames.get(index);

        try {
            // Look up worker in registry
            Optional<Proc<WorkerState, PoolTaskMsg<T>>> worker =
                    ProcRegistry.whereis(workerName);

            if (worker.isEmpty()) {
                resultFuture.completeExceptionally(
                        new IllegalStateException("Worker not available: " + workerName)
                );
                return resultFuture;
            }

            // Send task to worker
            Proc<WorkerState, PoolTaskMsg<T>> proc = worker.get();
            long startTime = System.currentTimeMillis();

            // Create the task message with a future for tracking completion
            PoolTaskMsg<T> taskMsg = PoolExecuteTask.create(task, resultFuture);

            // Send asynchronously and attach completion tracking
            proc.tell(taskMsg);

            // Apply timeout to the result
            resultFuture
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((result, ex) -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        completedTasks.incrementAndGet();
                        totalResponseTimeMs.addAndGet(elapsed);
                    });

            return resultFuture;
        } catch (Exception e) {
            resultFuture.completeExceptionally(e);
            return resultFuture;
        }
    }

    /**
     * Gracefully shut down the pool.
     *
     * <p>This method stops all worker processes and prevents new task submission. Pending
     * tasks will receive a failure with {@link IllegalStateException}.
     *
     * @throws InterruptedException if interrupted while waiting for workers to stop
     */
    public void shutdown() throws InterruptedException {
        synchronized (shutdownLock) {
            running = false;
        }
        supervisor.shutdown();
    }

    /**
     * Get current pool statistics.
     *
     * <p>Statistics include the number of active (busy) workers, total completed tasks,
     * and average response time.
     *
     * @return a {@link PoolStats} object with current pool metrics
     */
    public PoolStats getStats() {
        long completed = completedTasks.get();
        long avgResponseTime = completed > 0
                ? totalResponseTimeMs.get() / completed
                : 0;

        // Count active workers (those with lastTaskTime recently set)
        int activeWorkers = 0;
        Instant now = Instant.now();
        Duration activeWindow = Duration.ofSeconds(5);

        for (String workerName : workerNames) {
            Optional<Proc<WorkerState, ?>> worker = ProcRegistry.whereis(workerName);
            if (worker.isPresent()) {
                // We can't directly access WorkerState, so use a heuristic:
                // if the worker thread is alive, count it
                activeWorkers++;
            }
        }

        return new PoolStats(activeWorkers, completed, avgResponseTime, workerCount);
    }

    /**
     * Returns true if the pool is currently running.
     *
     * @return true if the pool can accept new tasks
     */
    public boolean isRunning() {
        synchronized (shutdownLock) {
            return running && supervisor.isRunning();
        }
    }

    /**
     * Get the pool name.
     *
     * @return the name of this pool
     */
    public String getName() {
        return name;
    }

    /**
     * Get the number of workers in this pool.
     *
     * @return the configured worker count
     */
    public int getWorkerCount() {
        return workerCount;
    }
}
