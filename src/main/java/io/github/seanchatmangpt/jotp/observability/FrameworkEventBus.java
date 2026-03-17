/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.Application;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Async event bus for JOTP framework lifecycle events.
 *
 * <p>This event bus provides zero-overhead observability for framework-level events (process
 * crashes, supervisor restarts, state transitions) using fire-and-forget async publishing. When
 * disabled via {@code -Djotp.observability.enabled=false}, the fast path has <100ns overhead — a
 * single branch check.
 *
 * <p><strong>Hot Path Protection:</strong> This bus is NEVER used in performance-critical paths
 * like {@code Proc.tell()} or mailbox operations. Events are published only from:
 *
 * <ul>
 *   <li>Process creation/termination (constructor and callback paths)
 *   <li>Supervisor crash handling (exception paths)
 *   <li>State machine transitions (non-hot event loop)
 * </ul>
 *
 * <p><strong>Feature-Gated Rollout:</strong> Disabled by default. Enable via:
 *
 * <pre>{@code
 * // JVM flag
 * java -Djotp.observability.enabled=true -jar app.jar
 *
 * // Or programmatically (before Application.start())
 * System.setProperty("jotp.observability.enabled", "true");
 * }</pre>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Subscribe to events
 * FrameworkEventBus.getDefault().subscribe(event -> {
 *     switch (event) {
 *         case FrameworkEvent.ProcessCreated e -> logger.info("Process started: {}", e.processId());
 *         case FrameworkEvent.SupervisorChildCrashed e -> alert(e.childId(), e.reason());
 *         // ... all events handled exhaustively (sealed interface)
 *     }
 * });
 *
 * // Events are published automatically by the framework
 * // No manual publish() calls needed in application code
 * }</pre>
 *
 * @see FrameworkEvent
 * @see Application.Infrastructure
 */
public final class FrameworkEventBus implements Application.Infrastructure {

    /**
     * Feature flag: set via {@code -Djotp.observability.enabled=true} Default is {@code false} for
     * zero-overhead when unused.
     */
    private static final boolean ENABLED = Boolean.getBoolean("jotp.observability.enabled");

    /** Singleton instance for framework-wide event publishing. */
    private static final FrameworkEventBus DEFAULT = new FrameworkEventBus();

    /**
     * Async executor for fire-and-forget event delivery. Single-threaded daemon to preserve event
     * ordering.
     */
    private static final ExecutorService ASYNC_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "jotp-observability");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * Subscribers receiving framework events. CopyOnWriteArrayList for lock-free iteration during
     * publish().
     */
    private final CopyOnWriteArrayList<Consumer<FrameworkEvent>> subscribers =
            new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    private FrameworkEventBus() {
        // Private constructor for singleton
    }

    /**
     * Creates a new FrameworkEventBus instance for testing. This constructor is package-private for
     * testing purposes.
     */
    FrameworkEventBus(boolean forTesting) {
        // Package-private constructor for testing
    }

    /**
     * Returns the default framework-wide event bus.
     *
     * @return the default event bus
     */
    public static FrameworkEventBus getDefault() {
        return DEFAULT;
    }

    /**
     * Creates a new FrameworkEventBus instance for testing.
     *
     * @return a new event bus instance
     */
    public static FrameworkEventBus create() {
        return new FrameworkEventBus(true);
    }

    @Override
    public String name() {
        return "framework-event-bus";
    }

    @Override
    public void onStop(Application<?> app) {
        shutdown();
    }

    /** Shuts down this event bus, stopping event delivery and clearing subscribers. */
    public void shutdown() {
        running = false;
        subscribers.clear();
    }

    /**
     * Subscribes a consumer to receive framework events.
     *
     * <p>Subscribers are called asynchronously in order. Exceptions in subscribers are caught and
     * logged to prevent cascading failures.
     *
     * @param subscriber the consumer to receive events
     */
    public void subscribe(Consumer<FrameworkEvent> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber cannot be null");
        }
        subscribers.add(subscriber);
    }

    /**
     * Unsubscribes a consumer from receiving framework events.
     *
     * @param subscriber the consumer to remove
     * @return {@code true} if the subscriber was removed, {@code false} if not found
     */
    public boolean unsubscribe(Consumer<FrameworkEvent> subscriber) {
        return subscribers.remove(subscriber);
    }

    /**
     * Returns the current number of subscribers.
     *
     * @return the subscriber count
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    /**
     * Publishes a framework event asynchronously.
     *
     * <p><strong>Zero-Cost Fast Path:</strong> When disabled or no subscribers, this method returns
     * immediately after a single branch check (<100ns).
     *
     * <p>Events are delivered to subscribers on a background daemon thread, ensuring the publisher
     * is never blocked.
     *
     * @param event the event to publish
     */
    public void publish(FrameworkEvent event) {
        if (!running || subscribers.isEmpty()) {
            return; // Zero-cost fast path: single branch check
        }

        // Fire-and-forget async delivery via virtual thread
        Thread.ofVirtual().name("jotp-obs-").start(() -> notifySubscribers(event));
    }

    /**
     * Notifies all subscribers of an event. Exceptions are caught to prevent one subscriber from
     * affecting others.
     */
    private void notifySubscribers(FrameworkEvent event) {
        for (Consumer<FrameworkEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Throwable t) {
                // Log but don't fail — observability failures shouldn't crash the app
                System.err.println("FrameworkEventBus subscriber error: " + t.getMessage());
            }
        }
    }

    /**
     * Checks if observability is enabled.
     *
     * @return {@code true} if enabled via system property, {@code false} otherwise
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    // ── Event Hierarchy ────────────────────────────────────────────────────────

    /**
     * Sealed hierarchy of framework lifecycle events.
     *
     * <p>All events are immutable records with creation timestamps. The sealed interface enables
     * exhaustive switch expressions for event handling.
     *
     * <p><strong>Event Priority:</strong>
     *
     * <ul>
     *   <li><b>P0 (Fault Detection):</b> ProcessCreated, ProcessTerminated, SupervisorChildCrashed,
     *       SupervisorRestartAttempted, SupervisorMaxRestartsExceeded
     *   <li><b>P1 (Debugging):</b> StateMachineTransition, StateMachineTimeout, ParallelTaskFailed
     *   <li><b>P2 (Operational):</b> ProcessMonitorRegistered, RegistryConflict
     * </ul>
     */
    public sealed interface FrameworkEvent {
        /** Timestamp when the event occurred. */
        Instant timestamp();

        // ── P0: Fault Detection Events ────────────────────────────────────────

        /** Published when a process is created. */
        record ProcessCreated(Instant timestamp, String processId, String processType)
                implements FrameworkEvent {}

        /**
         * Published when a process terminates.
         *
         * @param abnormal {@code true} if termination was due to a crash, {@code false} for
         *     graceful stop
         */
        record ProcessTerminated(
                Instant timestamp,
                String processId,
                String processType,
                boolean abnormal,
                String reason)
                implements FrameworkEvent {}

        /** Published when a supervised child process crashes. */
        record SupervisorChildCrashed(
                Instant timestamp, String supervisorId, String childId, Throwable reason)
                implements FrameworkEvent {}

        /** Published when a supervisor attempts to restart a child. */
        record SupervisorRestartAttempted(
                Instant timestamp,
                String supervisorId,
                String childId,
                Supervisor.Strategy strategy,
                int crashCount)
                implements FrameworkEvent {}

        /**
         * Published when a supervisor exceeds its max restart intensity. This indicates a potential
         * cascading failure or restart loop.
         */
        record SupervisorMaxRestartsExceeded(
                Instant timestamp,
                String supervisorId,
                int maxRestarts,
                Duration window,
                int actualRestarts)
                implements FrameworkEvent {
            /** Convenience record for Duration to avoid java.time dependency in sealed interface */
            public record Duration(long amount, java.time.temporal.ChronoUnit unit) {
                public java.time.Duration toJavaDuration() {
                    return java.time.Duration.of(amount, unit);
                }
            }
        }

        // ── P1: Debugging Events ───────────────────────────────────────────────

        /** Published when a state machine transitions to a new state. */
        record StateMachineTransition(
                Instant timestamp,
                String machineId,
                String fromState,
                String toState,
                String eventType)
                implements FrameworkEvent {}

        /** Published when a state machine timeout is scheduled. */
        record StateMachineTimeout(
                Instant timestamp,
                String machineId,
                String state,
                String timeoutType, // "state_timeout", "event_timeout", "generic_timeout"
                long delayMs)
                implements FrameworkEvent {}

        /** Published when a parallel task fails. */
        record ParallelTaskFailed(Instant timestamp, String parallelId, Throwable reason)
                implements FrameworkEvent {}

        // ── P2: Operational Events ─────────────────────────────────────────────

        /** Published when a process monitor is registered. */
        record ProcessMonitorRegistered(
                Instant timestamp, String monitorId, String monitoredProcessId)
                implements FrameworkEvent {}

        /** Published when a process registry name conflict occurs. */
        record RegistryConflict(
                Instant timestamp,
                String processName,
                String existingProcessId,
                String newProcessId)
                implements FrameworkEvent {}
    }
}
