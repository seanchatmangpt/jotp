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

import io.github.seanchatmangpt.jotp.MetricsCollector;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FrameworkMetrics.
 *
 * <p>Verifies that FrameworkMetrics correctly bridges FrameworkEventBus events to MetricsCollector,
 * collecting only P0 and P1 events while ignoring P2.
 */
class FrameworkMetricsTest {

    private MetricsCollector collector;
    private FrameworkMetrics metrics;

    @BeforeEach
    void setUp() {
        collector = MetricsCollector.create("test-metrics");
        metrics =
                FrameworkMetrics.create("test-metrics", collector, FrameworkEventBus.getDefault());
    }

    @Test
    void testFactoryMethods() {
        assertNotNull(metrics);
        assertEquals("test-metrics", metrics.name());
        assertSame(collector, metrics.getCollector());
    }

    @Test
    void testProcessCreatedEvent() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "proc-123", "TestProcess");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.process.created"));
    }

    @Test
    void testProcessTerminatedEvent_Abnormal() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessTerminated(
                        Instant.now(), "proc-123", "TestProcess", true, "NullPointerException");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.process.terminated"));
        assertTrue(snapshot.containsKey("jotp.process.crashed"));
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
    void testSupervisorRestartAttempted() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.SupervisorRestartAttempted(
                        Instant.now(), "sup-1", "child-1", Supervisor.Strategy.ONE_FOR_ONE, 3);

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        assertTrue(snapshot.containsKey("jotp.supervisor.restart_attempted"));
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
    void testP2EventsIgnored_ProcessMonitorRegistered() {
        FrameworkEventBus.FrameworkEvent event =
                new FrameworkEventBus.FrameworkEvent.ProcessMonitorRegistered(
                        Instant.now(), "monitor-1", "proc-123");

        metrics.accept(event);

        Map<String, Object> snapshot = collector.snapshot();
        // P2 events should not create metrics
        assertFalse(snapshot.containsKey("jotp.monitor.registered"));
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
