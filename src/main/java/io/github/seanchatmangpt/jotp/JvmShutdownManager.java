package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton manager for coordinating JVM shutdown hooks with ordered callback execution.
 *
 * <p>Provides a centralized mechanism for registering callbacks that must execute during JVM
 * shutdown, with support for priority levels and configurable timeouts. This enables graceful state
 * persistence even during SIGTERM or SIGKILL scenarios.
 *
 * <p><strong>Priority Levels (executed in order):</strong>
 *
 * <ul>
 *   <li>{@link Priority#GRACEFUL_SAVE} - Full state serialization with validation (first)
 *   <li>{@link Priority#BEST_EFFORT_SAVE} - Quick state dumps without validation
 *   <li>{@link Priority#EMERGENCY_FLUSH} - Last-resort minimal writes (last)
 * </ul>
 *
 * <p><strong>Usage example:</strong>
 *
 * <pre>{@code
 * // Register a callback for graceful shutdown
 * JvmShutdownManager.getInstance().registerCallback(
 *     JvmShutdownManager.Priority.GRACEFUL_SAVE,
 *     () -> {
 *         persistenceBackend.flush();
 *         logger.info("State persisted successfully");
 *     },
 *     Duration.ofSeconds(5)
 * );
 *
 * // Check if shutdown is in progress
 * if (JvmShutdownManager.getInstance().isShuttingDown()) {
 *     // Skip non-essential operations
 * }
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> All registration and execution methods are thread-safe.
 * Callbacks are stored in a {@link CopyOnWriteArrayList} for safe concurrent access.
 *
 * @see DurableState
 * @see PersistenceConfig
 */
public final class JvmShutdownManager {

    /**
     * Priority levels for shutdown callbacks.
     *
     * <p>Callbacks are executed in priority order (lowest ordinal first). Lower ordinal values
     * execute first, allowing critical operations to complete before best-effort or emergency
     * operations.
     */
    public enum Priority {
        /**
         * Graceful save priority - full state serialization with validation.
         *
         * <p>Use for critical state that requires complete serialization with integrity checks.
         * Executes first during shutdown.
         */
        GRACEFUL_SAVE(0),

        /**
         * Best-effort save priority - quick state dumps without validation.
         *
         * <p>Use for important but non-critical state that can tolerate partial writes. Executes
         * after GRACEFUL_SAVE callbacks complete.
         */
        BEST_EFFORT_SAVE(1),

        /**
         * Emergency flush priority - last-resort minimal writes.
         *
         * <p>Use for absolute minimum persistence when time is critical. Executes last, after all
         * other callbacks have attempted.
         */
        EMERGENCY_FLUSH(2);

        private final int ordinalValue;

        Priority(int ordinal) {
            this.ordinalValue = ordinal;
        }

        /** Returns the ordinal value for sorting. */
        public int getOrdinal() {
            return ordinalValue;
        }
    }

    /**
     * Internal record representing a registered shutdown callback.
     *
     * @param priority the execution priority
     * @param callback the callback to execute
     * @param timeout maximum time allowed for execution
     */
    private record ShutdownCallback(Priority priority, Runnable callback, Duration timeout) {}

    private static final JvmShutdownManager INSTANCE = new JvmShutdownManager();

    private final List<ShutdownCallback> callbacks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean hookRegistered = new AtomicBoolean(false);
    private final ReentrantLock registrationLock = new ReentrantLock();

    /** Default timeouts per priority level */
    private volatile Duration gracefulTimeout = Duration.ofSeconds(10);

    private volatile Duration bestEffortTimeout = Duration.ofSeconds(3);
    private volatile Duration emergencyTimeout = Duration.ofSeconds(1);

    private JvmShutdownManager() {
        // Singleton - use getInstance()
    }

    /**
     * Get the singleton instance of JvmShutdownManager.
     *
     * @return the singleton instance
     */
    public static JvmShutdownManager getInstance() {
        return INSTANCE;
    }

    /**
     * Register a shutdown callback with the specified priority.
     *
     * <p>Callbacks are executed in priority order during JVM shutdown. If no shutdown hook has been
     * registered yet, this method will register one with the JVM runtime.
     *
     * <p><strong>Thread Safety:</strong> This method is thread-safe and can be called from any
     * thread.
     *
     * @param priority the execution priority (determines order and default timeout)
     * @param callback the callback to execute during shutdown
     * @throws NullPointerException if priority or callback is null
     */
    public void registerCallback(Priority priority, Runnable callback) {
        Duration timeout =
                switch (priority) {
                    case GRACEFUL_SAVE -> gracefulTimeout;
                    case BEST_EFFORT_SAVE -> bestEffortTimeout;
                    case EMERGENCY_FLUSH -> emergencyTimeout;
                };
        registerCallback(priority, callback, timeout);
    }

    /**
     * Register a shutdown callback with a custom timeout.
     *
     * <p>Use this overload when the default timeout for a priority level is not appropriate for
     * your callback.
     *
     * @param priority the execution priority
     * @param callback the callback to execute during shutdown
     * @param timeout maximum time to allow for callback execution
     * @throws NullPointerException if any parameter is null
     */
    public void registerCallback(Priority priority, Runnable callback, Duration timeout) {
        if (priority == null || callback == null || timeout == null) {
            throw new NullPointerException("priority, callback, and timeout must not be null");
        }

        registrationLock.lock();
        try {
            // Register JVM shutdown hook if not already registered
            if (hookRegistered.compareAndSet(false, true)) {
                Runtime.getRuntime()
                        .addShutdownHook(new Thread(this::executeShutdown, "jotp-shutdown-hook"));
            }

            callbacks.add(new ShutdownCallback(priority, callback, timeout));
        } finally {
            registrationLock.unlock();
        }
    }

    /**
     * Configure default timeouts for each priority level.
     *
     * @param graceful timeout for GRACEFUL_SAVE callbacks
     * @param bestEffort timeout for BEST_EFFORT_SAVE callbacks
     * @param emergency timeout for EMERGENCY_FLUSH callbacks
     */
    public void configureTimeouts(Duration graceful, Duration bestEffort, Duration emergency) {
        if (graceful != null) this.gracefulTimeout = graceful;
        if (bestEffort != null) this.bestEffortTimeout = bestEffort;
        if (emergency != null) this.emergencyTimeout = emergency;
    }

    /**
     * Check if the JVM is currently shutting down.
     *
     * <p>Use this method to skip non-essential operations during shutdown, or to choose faster code
     * paths when time is critical.
     *
     * @return {@code true} if shutdown is in progress, {@code false} otherwise
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Manually trigger graceful shutdown (primarily for testing).
     *
     * <p>This method is intended for testing scenarios where you need to verify shutdown behavior
     * without actually terminating the JVM. In production, shutdown is triggered automatically by
     * the JVM shutdown hook.
     *
     * <p><strong>Warning:</strong> Calling this method sets the shutdown flag permanently.
     * Subsequent calls will be no-ops.
     */
    public void triggerGraceful() {
        if (shuttingDown.compareAndSet(false, true)) {
            executeShutdown();
        }
    }

    /**
     * Execute all registered shutdown callbacks in priority order.
     *
     * <p>Callbacks are sorted by priority (lowest ordinal first) and executed sequentially. Each
     * callback is given its configured timeout; if it exceeds the timeout, execution continues to
     * the next callback.
     *
     * <p><strong>Error Handling:</strong> Exceptions from callbacks are caught and logged, but do
     * not prevent other callbacks from executing.
     */
    private void executeShutdown() {
        shuttingDown.set(true);

        // Sort callbacks by priority
        List<ShutdownCallback> sorted = new ArrayList<>(callbacks);
        sorted.sort(Comparator.comparingInt(c -> c.priority().getOrdinal()));

        for (ShutdownCallback sc : sorted) {
            try {
                executeWithTimeout(sc.callback(), sc.timeout());
            } catch (Exception e) {
                // Log but continue - all callbacks must get a chance
                System.err.println("[JvmShutdownManager] Callback failed: " + e.getMessage());
            }
        }
    }

    /**
     * Execute a callback with a timeout.
     *
     * <p>Uses a virtual thread to execute the callback, with a timeout enforced via {@link
     * Thread#join(long)}.
     */
    private void executeWithTimeout(Runnable callback, Duration timeout) {
        Thread executor = Thread.ofVirtual().name("shutdown-callback").start(callback);

        try {
            executor.join(timeout.toMillis());
            if (executor.isAlive()) {
                executor.interrupt();
                System.err.println("[JvmShutdownManager] Callback timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Clear all registered callbacks and reset shutdown state (primarily for testing).
     *
     * <p>This method is intended for test isolation. Do not use in production. Resets both the
     * callback list and the shutdown flag so that {@link #triggerGraceful()} can be invoked again
     * after a clear.
     */
    public void clearCallbacks() {
        callbacks.clear();
        shuttingDown.set(false);
    }

    /**
     * Get the number of registered callbacks.
     *
     * @return the count of registered callbacks
     */
    public int callbackCount() {
        return callbacks.size();
    }
}
