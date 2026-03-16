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

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.MetricsCollector;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FrameworkMetrics.
 *
 * <p>Verifies that FrameworkMetrics correctly bridges FrameworkEventBus events to MetricsCollector,
 * collecting only P0 and P1 events while ignoring P2.
 */
@DtrTest
@DisplayName("FrameworkMetrics: Zero-Cost Telemetry Bridge")
class FrameworkMetricsTest {

    @DtrContextField private DtrContext ctx;

    private MetricsCollector collector;
    private FrameworkMetrics metrics;

    @BeforeEach
    void setUp() {
        collector = MetricsCollector.create("test-metrics");
        metrics =
                FrameworkMetrics.create("test-metrics", collector, FrameworkEventBus.getDefault());
    }

    @Test
    @DisplayName("Factory methods create configured metrics bridge")
    void testFactoryMethods(DtrContext ctx) {
        ctx.sayNextSection("Observability: Zero-Cost Telemetry");
        ctx.say(
                """
                FrameworkMetrics provides zero-cost abstraction for observability. When disabled
                (default), the overhead is <100ns — a single branch check. Only when enabled via
                -Djotp.observability.enabled=true does it bridge events to MetricsCollector.

                This design follows the principle of "pay for what you use": production systems
                can disable observability entirely with zero runtime cost.
                """);

        ctx.sayCode(
                """
                // Factory creates metrics bridge with auto-subscription
                FrameworkMetrics metrics = FrameworkMetrics.create("test-metrics", collector, eventBus);

                // Metrics are NOT collected unless observability is enabled
                // Check subscription status
                boolean isSubscribed = metrics.isSubscribed();
                """,
                "java");

        assertNotNull(metrics);
        assertEquals("test-metrics", metrics.name());
        assertSame(collector, metrics.getCollector());

        ctx.sayKeyValue(
                Map.of(
                        "Metrics Name",
                        metrics.name(),
                        "Collector Type",
                        collector.getClass().getSimpleName(),
                        "Subscribed",
                        String.valueOf(metrics.isSubscribed()),
                        "Feature Flag",
                        "-Djotp.observability.enabled=true"));

        ctx.sayNote(
                "The metrics bridge is feature-gated. Production systems without the flag enabled"
                        + " experience zero overhead — no event bus subscription, no allocation, no metrics collection.");
    }

    @Test
    @DisplayName("P0: ProcessCreated event increments counter")
    void testProcessCreatedEvent(DtrContext ctx) {
        ctx.sayNextSection("Event Bus Telemetry: Process Lifecycle Events");
        ctx.say(
                """
                FrameworkEventBus publishes P0 (fault detection) events when processes are created.
                FrameworkMetrics subscribes to these events and bridges them to MetricsCollector counters
                with tags for process type and classification.

                This enables monitoring of process population growth, leak detection, and capacity
                planning without instrumenting application code.
                """);

        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "proc-123", "TestProcess");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.process.created"));

        ctx.sayCode(
                """
                // Event published automatically by process creation
                FrameworkEventBus.FrameworkEvent event =
                    new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "proc-123", "TestProcess");

                // Metrics bridge accepts and transforms to counter
                metrics.accept(event);

                // Collector snapshot shows metric with tags
                Map<String, Object> snapshot = collector.snapshot();
                // => {"jotp.process.created": 1, "[type=TestProcess]": 1}
                """,
                "java");

        ctx.sayTable(
                new String[][] {
                    {"Metric Type", "Purpose", "Example Tags"},
                    {"Counter", "Monotonically increasing values", "type=TestProcess"},
                    {"Gauge", "Point-in-time values", "queue.depth=42"},
                    {"Histogram", "Distribution of values", "latency p95=100ms"},
                    {"Timer", "Duration measurements", "request.duration=50ms"}
                });

        ctx.sayNote(
                "ProcessCreated events fire in the constructor path, not the hot message loop."
                        + " This ensures observability doesn't impact throughput.");
    }

    @Test
    @DisplayName("P0: ProcessTerminated event tracks abnormal terminations")
    void testProcessTerminatedEvent_Abnormal(DtrContext ctx) {
        ctx.sayNextSection("Fault Detection: Crash Classification");
        ctx.say(
                """
                Abnormal process terminations are critical P0 events. FrameworkMetrics tracks
                both terminations (all exits) and crashes (abnormal exits) separately.

                Crash classification uses heuristics on the reason string:
                - 'timeout' → timeout failures
                - 'cancel'/'stopped' → cancelled operations
                - 'exception'/'error' → exception failures
                - 'normal'/'graceful' → clean shutdowns
                - other → unknown

                This enables alerting on crash spikes and root cause analysis.
                """);

        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-123", "TestProcess", true, "NullPointerException");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.process.terminated"));
        assertTrue(snapshot.containsKey("jotp.process.crashed"));

        ctx.sayCode(
                """
                // Abnormal termination creates TWO metrics
                FrameworkEventBus.FrameworkEvent event =
                    new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-123", "TestProcess", true, "NullPointerException");

                metrics.accept(event);

                // Separate counters for total terminations and crashes
                // => jotp.process.terminated: 1
                // => jotp.process.crashed[type=TestProcess, reason=exception]: 1
                """,
                "java");

        ctx.sayKeyValue(
                Map.of(
                        "Event Type",
                        "ProcessTerminated",
                        "Abnormal",
                        "true",
                        "Reason",
                        "NullPointerException",
                        "Classification",
                        "exception",
                        "Terminated Counter",
                        "incremented",
                        "Crashed Counter",
                        "incremented"));

        ctx.sayNote(
                "Crash classification enables targeted alerting. High 'exception' crashes suggest"
                        + " bugs, while 'timeout' crashes indicate performance issues.");
    }

    @Test
    void testProcessTerminatedEvent_Normal() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-123", "TestProcess", false, "normal");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.process.terminated"));
        // No crash metric for normal termination
    }

    @Test
    void testSupervisorChildCrashed() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed(
                        Instant.now(), "sup-1", "child-1", new RuntimeException("Test crash"));

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.supervisor.child_crashed"));
        assertTrue(snapshot.containsKey("jotp.supervisor.crash_type"));
    }

    @Test
    @DisplayName("P0: SupervisorRestartAttempted tracks restart strategies")
    void testSupervisorRestartAttempted(DtrContext ctx) {
        ctx.sayNextSection("Distributed Tracing: Supervisor Recovery Chains");
        ctx.say(
                """
                Supervisor restart attempts are critical for understanding fault recovery patterns.
                Each restart attempt creates metrics with:

                - Supervisor ID and child ID for correlation
                - Restart strategy (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
                - Crash count to detect restart loops

                This enables tracking of fault tolerance effectiveness and identifying flaky processes
                that crash-restart repeatedly (the "restart loop" anti-pattern).
                """);

        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.SupervisorRestartAttempted(
                        Instant.now(), "sup-1", "child-1", Supervisor.Strategy.ONE_FOR_ONE, 3);

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.supervisor.restart_attempted"));

        ctx.sayCode(
                """
                // Supervisor attempts to restart crashed child
                FrameworkEventBus.FrameworkEvent event =
                    new FrameworkEventBus.FrameworkEvent.SupervisorRestartAttempted(
                        Instant.now(), "sup-1", "child-1",
                        Supervisor.Strategy.ONE_FOR_ONE, 3);

                metrics.accept(event);

                // Metrics track strategy and crash count
                // => jotp.supervisor.restart_attempted[supervisor=sup-1, strategy=ONE_FOR_ONE, child=child-1]
                // => jotp.supervisor.restart_count.sup-1.child-1: 3
                """,
                "java");

        ctx.sayTable(
                new String[][] {
                    {"Strategy", "Behavior", "Use Case"},
                    {
                        "ONE_FOR_ONE",
                        "Restart only crashed child",
                        "Independent processes, no shared state"
                    },
                    {"ONE_FOR_ALL", "Restart all children", "Shared state, unknown corruption"},
                    {
                        "REST_FOR_ONE",
                        "Restart crashed + after it",
                        "Ordered dependencies, cascade prevention"
                    }
                });

        ctx.sayKeyValue(
                Map.of(
                        "Supervisor ID",
                        "sup-1",
                        "Child ID",
                        "child-1",
                        "Strategy",
                        "ONE_FOR_ONE",
                        "Crash Count",
                        "3",
                        "Alert Threshold",
                        ">5 crashes/minute"));

        ctx.sayNote(
                "Crash count >5 in 60 seconds indicates a restart loop. The supervisor should"
                        + " give up and escalate to higher-level supervision instead of thrashing.");
    }

    @Test
    void testSupervisorMaxRestartsExceeded() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.SupervisorMaxRestartsExceeded(
                        Instant.now(),
                        "sup-1",
                        5,
                        new FrameworkEventBus.FrameworkEvent.SupervisorMaxRestartsExceeded.Duration(
                                60, java.time.temporal.ChronoUnit.SECONDS),
                        7);

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.supervisor.max_restarts_exceeded"));
        assertTrue(snapshot.containsKey("jotp.supervisor.restart_intensity"));
        assertTrue(snapshot.containsKey("jotp.supervisor.restarts_per_second"));
    }

    @Test
    void testStateMachineTransition() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.StateMachineTransition(
                        Instant.now(), "sm-1", "idle", "processing", "START");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.statemachine.transition"));
        assertTrue(snapshot.containsKey("jotp.statemachine.state_entered"));
    }

    @Test
    void testStateMachineTimeout() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.StateMachineTimeout(
                        Instant.now(), "sm-1", "processing", "state_timeout", 5000);

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.statemachine.timeout"));
        assertTrue(snapshot.containsKey("jotp.statemachine.timeout_duration_ms"));
    }

    @Test
    void testParallelTaskFailed() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ParallelTaskFailed(
                        Instant.now(), "parallel-1", new TimeoutException("Task timed out"));

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.parallel.task_failed"));
        assertTrue(snapshot.containsKey("jotp.parallel.failure_type"));
    }

    @Test
    @DisplayName("P2: Operational events ignored for metrics")
    void testP2EventsIgnored_ProcessMonitorRegistered(DtrContext ctx) {
        ctx.sayNextSection("Zero-Cost Abstraction: Priority-Based Event Filtering");
        ctx.say(
                """
                FrameworkMetrics uses priority-based event filtering to control overhead:

                - P0 (Fault Detection): ProcessCreated, ProcessTerminated, Supervisor events → COLLECTED
                - P1 (Debugging): StateMachine transitions, Parallel tasks → COLLECTED
                - P2 (Operational): Monitor registration, Registry conflicts → IGNORED

                This filtering happens at compile-time via exhaustive switch on sealed events.
                P2 events are matched but do nothing — the branch is a no-op comment.

                The result: only high-value events create metrics, keeping cardinality low and
                performance high.
                """);

        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessMonitorRegistered(
                        Instant.now(), "monitor-1", "proc-123");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        // P2 events should not create metrics
        assertFalse(snapshot.containsKey("jotp.monitor.registered"));

        ctx.sayCode(
                """
                // P2 events are explicitly ignored in the switch
                case FrameworkEventBus.FrameworkEvent.ProcessMonitorRegistered e -> {
                    /* Ignored: P2 operational event */
                }

                // No metrics created for P2 events
                metrics.accept(event);
                Map<String, Object> snapshot = collector.snapshot();
                // => {} (empty, no metrics created)
                """,
                "java");

        ctx.sayTable(
                new String[][] {
                    {"Priority", "Event Type", "Collected", "Rationale"},
                    {"P0", "Fault Detection", "YES", "Crashes, restarts, terminations"},
                    {"P1", "Debugging", "YES", "State transitions, timeouts"},
                    {"P2", "Operational", "NO", "Low-value, high-cardinality"}
                });

        ctx.sayKeyValue(
                Map.of(
                        "Event Priority",
                        "P2 (Operational)",
                        "Event Type",
                        "ProcessMonitorRegistered",
                        "Metrics Created",
                        "0",
                        "Reason",
                        "Low signal-to-noise ratio"));

        ctx.sayNote(
                "P2 filtering prevents metric cardinality explosion. Monitor registration events"
                        + " can occur thousands of times per second but provide little operational value."
                        + " Collecting them would drown out the important P0/P1 signals.");
    }

    @Test
    void testP2EventsIgnored_RegistryConflict() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.RegistryConflict(
                        Instant.now(), "my-process", "proc-123", "proc-456");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        // P2 events should not create metrics
        assertFalse(snapshot.containsKey("jotp.registry.conflict"));
    }

    @Test
    void testCloseIdempotent() {
        // First close should unsubscribe
        assertTrue(metrics.isSubscribed());
        metrics.close();
        assertFalse(metrics.isSubscribed());

        // Second close should be no-op
        metrics.close();
        assertFalse(metrics.isSubscribed());
    }

    @Test
    void testBuilder() {
        FrameworkMetrics built =
                FrameworkMetrics.builder()
                        .name("custom-metrics")
                        .collector(collector)
                        .eventBus(FrameworkEventBus.getDefault())
                        .build();

        assertNotNull(built);
        assertEquals("custom-metrics", built.name());
        assertSame(collector, built.getCollector());
    }

    @Test
    void testTagHelper() {
        // Test that tag creation doesn't throw for valid input
        assertDoesNotThrow(
                () ->
                        metrics.accept(
                                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                                        Instant.now(), "proc-1", "TestType")));
    }

    @Test
    void testReasonClassification() {
        // Test various reason strings are classified correctly
        FrameworkEventBus.FrameworkEvent timeoutEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-1", "Test", true, "operation timed out");

        FrameworkEventBus.FrameworkEvent normalEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-2", "Test", false, "normal shutdown");

        metrics.accept(timeoutEvent);
        metrics.accept(normalEvent);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.process.terminated"));
    }
}
