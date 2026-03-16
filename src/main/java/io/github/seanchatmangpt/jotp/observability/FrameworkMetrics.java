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
import io.github.seanchatmangpt.jotp.MetricsCollector;
import java.util.function.Consumer;

/**
 * Framework event metrics bridge for JOTP observability.
 *
 * <p>This component subscribes to {@link FrameworkEventBus} and bridges framework lifecycle events
 * to {@link MetricsCollector} for aggregated metrics collection. It handles P0 (fault detection)
 * and P1 (debugging) events only, filtering out lower-priority operational events.
 *
 * <p><strong>Feature-Gated Behavior:</strong> Like {@link FrameworkEventBus}, this component is
 * disabled by default and only activates when {@code -Djotp.observability.enabled=true}. When
 * disabled, the fast path has <100ns overhead — a single branch check.
 *
 * <p><strong>Event Handling:</strong>
 *
 * <ul>
 *   <li><b>P0 Fault Detection:</b> ProcessCreated, ProcessTerminated, SupervisorChildCrashed,
 *       SupervisorRestartAttempted, SupervisorMaxRestartsExceeded
 *   <li><b>P1 Debugging:</b> StateMachineTransition, StateMachineTimeout, ParallelTaskFailed
 *   <li><b>P2 Operational:</b> ProcessMonitorRegistered, RegistryConflict (ignored)
 * </ul>
 *
 * <p><strong>Metrics Collected:</strong>
 *
 * <ul>
 *   <li><b>Counters:</b> Process lifecycle events, supervisor actions, state transitions
 *   <li><b>Gauges:</b> Active process counts, restart intensities, timeout durations
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Auto-registers with FrameworkEventBus on creation
 * FrameworkMetrics metrics = FrameworkMetrics.create();
 *
 * // Metrics are automatically populated as framework events occur
 * MetricsCollector collector = metrics.getCollector();
 * Map<String, Object> snapshot = collector.snapshot();
 *
 * // On application shutdown
 * metrics.close(); // Unsubscribes from event bus
 * }</pre>
 *
 * @see FrameworkEventBus
 * @see MetricsCollector
 * @see Application.Infrastructure
 */
public final class FrameworkMetrics
        implements Application.Infrastructure,
                Consumer<FrameworkEventBus.FrameworkEvent>,
                AutoCloseable {

    /**
     * Feature flag: set via {@code -Djotp.observability.enabled=true} Default is {@code false} for
     * zero-overhead when unused.
     */
    private static final boolean ENABLED = Boolean.getBoolean("jotp.observability.enabled");

    /**
     * Returns whether observability is enabled via system property.
     *
     * @return {@code true} if {@code -Djotp.observability.enabled=true}, {@code false} otherwise
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    private final String name;
    private final MetricsCollector collector;
    private final FrameworkEventBus eventBus;
    private volatile boolean subscribed;

    private FrameworkMetrics(String name, MetricsCollector collector, FrameworkEventBus eventBus) {
        this.name = name;
        this.collector = collector;
        this.eventBus = eventBus;

        // Auto-subscribe if observability is enabled
        if (ENABLED) {
            eventBus.subscribe(this);
            this.subscribed = true;
        }
    }

    /**
     * Creates a new FrameworkMetrics instance with default configuration.
     *
     * <p>Uses the default {@link MetricsCollector} and {@link FrameworkEventBus}.
     *
     * @return a new FrameworkMetrics instance
     */
    public static FrameworkMetrics create() {
        return new FrameworkMetrics(
                "framework-metrics",
                MetricsCollector.create("framework-metrics"),
                FrameworkEventBus.getDefault());
    }

    /**
     * Creates a new FrameworkMetrics instance with a custom name.
     *
     * @param name the name for this metrics bridge
     * @return a new FrameworkMetrics instance
     */
    public static FrameworkMetrics create(String name) {
        return new FrameworkMetrics(
                name, MetricsCollector.create(name), FrameworkEventBus.getDefault());
    }

    /**
     * Creates a new FrameworkMetrics instance with custom dependencies.
     *
     * @param name the name for this metrics bridge
     * @param collector the metrics collector to use
     * @param eventBus the event bus to subscribe to
     * @return a new FrameworkMetrics instance
     */
    public static FrameworkMetrics create(
            String name, MetricsCollector collector, FrameworkEventBus eventBus) {
        return new FrameworkMetrics(name, collector, eventBus);
    }

    /**
     * Creates a new builder for configuring FrameworkMetrics.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onStop(Application<?> app) {
        close();
    }

    /**
     * Gets the underlying metrics collector.
     *
     * @return the metrics collector
     */
    public MetricsCollector getCollector() {
        return collector;
    }

    /**
     * Checks if this component is currently subscribed to the event bus.
     *
     * @return {@code true} if subscribed, {@code false} otherwise
     */
    public boolean isSubscribed() {
        return subscribed;
    }

    /**
     * Handles framework events by bridging them to metrics collection.
     *
     * <p>This method implements exhaustive pattern matching on the sealed {@link
     * FrameworkEventBus.FrameworkEvent} hierarchy, ensuring all event types are handled at compile
     * time.
     *
     * <p>P0 and P1 events are collected; P2 events are ignored.
     *
     * @param event the framework event to handle
     */
    @Override
    public void accept(FrameworkEventBus.FrameworkEvent event) {
        if (!ENABLED) {
            return; // Zero-cost fast path: single branch check
        }

        // Exhaustive switch on sealed interface — compiler enforces all cases handled
        switch (event) {
            // ── P0: Fault Detection Events ────────────────────────────────────────

            case FrameworkEventBus.FrameworkEvent.ProcessCreated e ->
                    collector
                            .counter("jotp.process.created", tags("type", e.processType()))
                            .increment();

            case FrameworkEventBus.FrameworkEvent.ProcessTerminated e -> {
                collector
                        .counter(
                                "jotp.process.terminated",
                                tags(
                                        "type",
                                        e.processType(),
                                        "abnormal",
                                        String.valueOf(e.abnormal())))
                        .increment();

                // Track abnormal terminations separately for alerting
                if (e.abnormal()) {
                    collector
                            .counter(
                                    "jotp.process.crashed",
                                    tags(
                                            "type",
                                            e.processType(),
                                            "reason",
                                            classifyReason(e.reason())))
                            .increment();
                }
            }

            case FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed e -> {
                collector
                        .counter(
                                "jotp.supervisor.child_crashed",
                                tags("supervisor", e.supervisorId(), "child", e.childId()))
                        .increment();

                // Track crash types for root cause analysis
                String crashType =
                        e.reason() != null ? e.reason().getClass().getSimpleName() : "unknown";
                collector
                        .counter("jotp.supervisor.crash_type", tags("type", crashType))
                        .increment();
            }

            case FrameworkEventBus.FrameworkEvent.SupervisorRestartAttempted e -> {
                collector
                        .counter(
                                "jotp.supervisor.restart_attempted",
                                tags(
                                        "supervisor", e.supervisorId(),
                                        "strategy", e.strategy().name(),
                                        "child", e.childId()))
                        .increment();

                // Track restart intensity (how many times this child has crashed)
                long restartCount = e.crashCount();
                String restartKey =
                        "jotp.supervisor.restart_count." + e.supervisorId() + "." + e.childId();
                collector.gauge(restartKey, () -> (double) restartCount);
            }

            case FrameworkEventBus.FrameworkEvent.SupervisorMaxRestartsExceeded e -> {
                collector
                        .counter(
                                "jotp.supervisor.max_restarts_exceeded",
                                tags("supervisor", e.supervisorId()))
                        .increment();

                // Track the severity of the restart loop
                long actualRestarts = e.actualRestarts();
                String intensityKey = "jotp.supervisor.restart_intensity." + e.supervisorId();
                collector.gauge(intensityKey, () -> (double) actualRestarts);

                // Calculate and record restarts per second
                long windowMs = e.window().toJavaDuration().toMillis();
                double restartsPerSecond = (e.actualRestarts() * 1000.0) / windowMs;
                String rpsKey = "jotp.supervisor.restarts_per_second." + e.supervisorId();
                collector.gauge(rpsKey, () -> restartsPerSecond);
            }

            // ── P1: Debugging Events ───────────────────────────────────────────────

            case FrameworkEventBus.FrameworkEvent.StateMachineTransition e -> {
                collector
                        .counter(
                                "jotp.statemachine.transition",
                                tags(
                                        "machine", e.machineId(),
                                        "from", e.fromState(),
                                        "to", e.toState(),
                                        "event", e.eventType()))
                        .increment();

                // Track state transition patterns for debugging
                collector
                        .counter(
                                "jotp.statemachine.state_entered",
                                tags("machine", e.machineId(), "state", e.toState()))
                        .increment();
            }

            case FrameworkEventBus.FrameworkEvent.StateMachineTimeout e -> {
                collector
                        .counter(
                                "jotp.statemachine.timeout",
                                tags(
                                        "machine", e.machineId(),
                                        "state", e.state(),
                                        "type", e.timeoutType()))
                        .increment();

                // Track timeout durations for performance analysis
                long delayMs = e.delayMs();
                String timeoutKey =
                        "jotp.statemachine.timeout_duration_ms."
                                + e.machineId()
                                + "."
                                + e.timeoutType();
                collector.gauge(timeoutKey, () -> (double) delayMs);
            }

            case FrameworkEventBus.FrameworkEvent.ParallelTaskFailed e -> {
                String reasonMsg = e.reason() != null ? e.reason().getMessage() : null;
                String reasonType = classifyReason(reasonMsg);
                collector
                        .counter(
                                "jotp.parallel.task_failed",
                                tags("parallel", e.parallelId(), "reason", reasonType))
                        .increment();

                // Track failure types for pattern analysis
                String failureType =
                        e.reason() != null ? e.reason().getClass().getSimpleName() : "unknown";
                collector
                        .counter("jotp.parallel.failure_type", tags("type", failureType))
                        .increment();
            }

            // ── P2: Operational Events (Ignored) ───────────────────────────────────
            // These events are low-priority and not collected for metrics

            case FrameworkEventBus.FrameworkEvent.ProcessMonitorRegistered e -> {
                /* Ignored: P2 operational event */
            }

            case FrameworkEventBus.FrameworkEvent.RegistryConflict e -> {
                /* Ignored: P2 operational event */
            }
        }
    }

    /**
     * Unsubscribes from the event bus and releases resources.
     *
     * <p>This method is idempotent — calling it multiple times has no additional effect.
     */
    @Override
    public void close() {
        if (subscribed) {
            eventBus.unsubscribe(this);
            subscribed = false;
        }
    }

    // ── Helper Methods ─────────────────────────────────────────────────────────────

    /**
     * Creates a tag map from key-value pairs.
     *
     * @param pairs alternating key-value pairs (must be even length)
     * @return an immutable tag map
     */
    private static java.util.Map<String, String> tags(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Tags must be provided as key-value pairs");
        }
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            tags.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(tags);
    }

    /**
     * Classifies a termination reason into a high-level category.
     *
     * @param reason the termination reason
     * @return the classified category
     */
    private static String classifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }

        String lower = reason.toLowerCase();
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "timeout";
        } else if (lower.contains("cancel") || lower.contains("stopped")) {
            return "cancelled";
        } else if (lower.contains("exception") || lower.contains("error")) {
            return "exception";
        } else if (lower.contains("normal") || lower.contains("graceful")) {
            return "normal";
        } else {
            return "other";
        }
    }

    /** Builder for creating configured FrameworkMetrics instances. */
    public static final class Builder {
        private String name = "framework-metrics";
        private MetricsCollector collector;
        private FrameworkEventBus eventBus;

        private Builder() {}

        /**
         * Sets the name for this metrics bridge.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the metrics collector to use.
         *
         * <p>If not set, a default collector will be created.
         *
         * @param collector the metrics collector
         * @return this builder
         */
        public Builder collector(MetricsCollector collector) {
            this.collector = collector;
            return this;
        }

        /**
         * Sets the event bus to subscribe to.
         *
         * <p>If not set, the default event bus will be used.
         *
         * @param eventBus the event bus
         * @return this builder
         */
        public Builder eventBus(FrameworkEventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        /**
         * Builds a new FrameworkMetrics instance with the configured settings.
         *
         * @return a new FrameworkMetrics instance
         */
        public FrameworkMetrics build() {
            MetricsCollector actualCollector =
                    collector != null ? collector : MetricsCollector.create(name);
            FrameworkEventBus actualEventBus =
                    eventBus != null ? eventBus : FrameworkEventBus.getDefault();
            return new FrameworkMetrics(name, actualCollector, actualEventBus);
        }
    }
}
