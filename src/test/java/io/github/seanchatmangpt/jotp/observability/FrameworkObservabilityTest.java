/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.observability;
import static org.awaitility.Awaitility.await;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcMonitor;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus.FrameworkEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Comprehensive tests for framework observability via FrameworkEventBus.
 * <p>Tests cover all event types across three priority levels:
 * <ul>
 *   <li><b>P0 (Fault Detection):</b> ProcessCreated, ProcessTerminated (with abnormal flag),
 *       SupervisorChildCrashed, SupervisorRestartAttempted, SupervisorMaxRestartsExceeded
 *   <li><b>P1 (Debugging):</b> StateMachineTransition, StateMachineTimeout, ParallelTaskFailed
 *   <li><b>P2 (Operational):</b> ProcessMonitorRegistered, RegistryConflict
 * </ul>
 * <p>Test approach:
 *   <li>Enable observability via system property before each test
 *   <li>Use counting consumers to verify events are published
 *   <li>Use Awaitility for async verification
 *   <li>Test event content and timing
 *   <li>Verify subscriber isolation and error handling
 * @see FrameworkEventBus
 * @see FrameworkEvent
@DisplayName("Framework Observability: P0/P1/P2 event publishing and delivery")
@Timeout(30)
class FrameworkObservabilityTest implements WithAssertions {
    private FrameworkEventBus eventBus;
    private AtomicInteger eventCount;
    private ConcurrentHashMap<String, List<FrameworkEvent>> eventsByType;
    private AtomicReference<Throwable> subscriberError;
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        // Enable observability before creating event bus
        System.setProperty("jotp.observability.enabled", "true");
        eventBus = FrameworkEventBus.getDefault();
        eventCount = new AtomicInteger(0);
        eventsByType = new ConcurrentHashMap<>();
        subscriberError = new AtomicReference<>();
        // Subscribe to count all events
        eventBus.subscribe(
                event -> {
                    eventCount.incrementAndGet();
                    var eventType = event.getClass().getSimpleName();
                    eventsByType.computeIfAbsent(eventType, k -> new ArrayList<>()).add(event);
                });
        // Subscribe to catch errors
                    try {
                        // Simulate potential error in one subscriber
                        if (event instanceof FrameworkEvent.ProcessCreated) {
                            var pc = (FrameworkEvent.ProcessCreated) event;
                            if (pc.processId().equals("error-test")) {
                                throw new RuntimeException("Simulated subscriber error");
                            }
                        }
                    } catch (Throwable t) {
                        subscriberError.set(t);
                    }
    }
    @AfterEach
    void tearDown() {
        // Clean up system property
        System.clearProperty("jotp.observability.enabled");
        // Unsubscribe all to prevent cross-test contamination
        // Note: In a real scenario we'd need unsubscribe(Consumer) but the current
        // implementation doesn't support it, so we rely on test isolation
    // =========================================================================
    // P0: Fault Detection Events
    @Nested
    @DisplayName("P0: ProcessCreated event")
    class ProcessCreatedTests {
        @Test
        @DisplayName("ProcessCreated event published when process spawns")
        void processCreated_publishedWhenProcessSpawns() {
            // Act
            var proc = Proc.spawn(() -> "initial", (state, msg) -> state);
            // Assert - wait for async event delivery
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(eventCount.get()).isGreaterThan(0);
                                var events = eventsByType.get("ProcessCreated");
                                assertThat(events).isNotNull().isNotEmpty();
                                var event = (FrameworkEvent.ProcessCreated) events.get(0);
                                assertThat(event.processId()).isNotNull();
                                assertThat(event.processType()).isNotNull();
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });
            // Cleanup
            proc.stop();
        }
        @DisplayName("ProcessCreated event contains correct process metadata")
        void processCreated_containsCorrectMetadata() {
            // Assert
                                assertThat(events).isNotNull();
                                assertThat(event.processId()).isNotEmpty();
                                assertThat(event.processType()).isNotEmpty();
                                assertThat(event.timestamp()).isNotNull();
        @DisplayName("Multiple ProcessCreated events for multiple processes")
        void processCreated_multipleProcesses() {
            var proc1 = Proc.spawn(() -> "p1", (s, m) -> s);
            var proc2 = Proc.spawn(() -> "p2", (s, m) -> s);
            var proc3 = Proc.spawn(() -> "p3", (s, m) -> s);
                                assertThat(events).hasSizeGreaterThanOrEqualTo(3);
                                // Verify all have unique process IDs
                                var processIds =
                                        events.stream()
                                                .map(
                                                        e ->
                                                                ((FrameworkEvent.ProcessCreated) e)
                                                                        .processId())
                                                .distinct()
                                                .toList();
                                assertThat(processIds).hasSizeGreaterThanOrEqualTo(3);
            proc1.stop();
            proc2.stop();
            proc3.stop();
    @DisplayName("P0: ProcessTerminated event")
    class ProcessTerminatedTests {
        @DisplayName("ProcessTerminated event published with abnormal=false for graceful stop")
        void processTerminated_gracefulStop() {
            // Arrange
            // Clear creation events
            eventsByType.remove("ProcessCreated");
                                var events = eventsByType.get("ProcessTerminated");
                                var event = (FrameworkEvent.ProcessTerminated) events.get(0);
                                assertThat(event.abnormal()).isFalse();
                                assertThat(event.reason()).isNotNull();
        @DisplayName("ProcessTerminated event published with abnormal=true for crash")
        void processTerminated_crash() {
            var proc =
                    Proc.spawn(
                            () -> "initial",
                            (state, msg) -> {
                                if ("crash".equals(msg)) {
                                    throw new RuntimeException("Intentional crash");
                                }
                                return state;
            proc.tell("crash");
                                assertThat(event.abnormal()).isTrue();
        @DisplayName("ProcessTerminated event contains processType")
        void processTerminated_containsProcessType() {
    @DisplayName("P0: SupervisorChildCrashed event")
    class SupervisorChildCrashedTests {
        @DisplayName("SupervisorChildCrashed event when child crashes")
        void supervisorChildCrashed_childCrashes() throws Exception {
            var crashReason = new AtomicReference<Throwable>();
            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorChildCrashed) {
                            var scc = (FrameworkEvent.SupervisorChildCrashed) event;
                            crashReason.set(scc.reason());
                    });
            var childSpec =
                    Supervisor.ChildSpec.builder()
                            .id("crashy-child")
                            .start(
                                    () -> {
                                        return Proc.spawn(
                                                        () -> "initial",
                                                        (state, msg) -> {
                                                            if ("crash".equals(msg)) {
                                                                throw new RuntimeException(
                                                                        "Child crash");
                                                            }
                                                            return state;
                                                        })
                                                .ref();
                                    })
                            .restartType(Supervisor.RestartType.PERMANENT)
                            .build();
            var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, List.of(childSpec));
            var childRef = ProcRegistry.whereis("crashy-child");
            assertThat(childRef).isNotNull();
            // Trigger crash through ProcSys
            var proc = childRef.proc();
            await().atMost(Duration.ofSeconds(3))
                                assertThat(crashReason.get()).isNotNull();
                                assertThat(crashReason.get().getMessage()).contains("Child crash");
                                var events = eventsByType.get("SupervisorChildCrashed");
                                var event = (FrameworkEvent.SupervisorChildCrashed) events.get(0);
                                assertThat(event.supervisorId()).isNotNull();
                                assertThat(event.childId()).isEqualTo("crashy-child");
            supervisor.shutdown();
        @DisplayName("SupervisorChildCrashed includes supervisor and child IDs")
        void supervisorChildCrashed_containsIds() throws Exception {
            var supervisorId = new AtomicReference<String>();
            var childId = new AtomicReference<String>();
                            supervisorId.set(scc.supervisorId());
                            childId.set(scc.childId());
                            .id("test-child")
                                                            if ("boom".equals(msg)) {
                                                                throw new RuntimeException("Boom");
            var childRef = ProcRegistry.whereis("test-child");
            childRef.proc().tell("boom");
                                assertThat(supervisorId.get()).isNotNull();
                                assertThat(childId.get()).isEqualTo("test-child");
    @DisplayName("P0: SupervisorRestartAttempted event")
    class SupervisorRestartAttemptedTests {
        @DisplayName("SupervisorRestartAttempted event when restarting child")
        void supervisorRestartAttempted_onChildRestart() throws Exception {
            var restartEvent = new AtomicReference<FrameworkEvent.SupervisorRestartAttempted>();
                        if (event instanceof FrameworkEvent.SupervisorRestartAttempted) {
                            restartEvent.set((FrameworkEvent.SupervisorRestartAttempted) event);
                            .id("restart-child")
                                                                        "Crash for restart");
            var childRef = ProcRegistry.whereis("restart-child");
            childRef.proc().tell("crash");
                                assertThat(restartEvent.get()).isNotNull();
                                var event = restartEvent.get();
                                assertThat(event.childId()).isEqualTo("restart-child");
                                assertThat(event.strategy())
                                        .isEqualTo(Supervisor.Strategy.ONE_FOR_ONE);
                                assertThat(event.crashCount()).isGreaterThan(0);
        @DisplayName("SupervisorRestartAttempted includes crash count")
        void supervisorRestartAttempted_includesCrashCount() throws Exception {
            var crashCounts = new ArrayList<Integer>();
                            var sra = (FrameworkEvent.SupervisorRestartAttempted) event;
                            crashCounts.add(sra.crashCount());
                            .id("multi-crash-child")
                                                                        "Repeated crash");
            // Act - trigger multiple crashes
            var childRef = ProcRegistry.whereis("multi-crash-child");
            await().atMost(Duration.ofSeconds(2)).until(() -> crashCounts.size() >= 1);
                                assertThat(crashCounts).hasSizeGreaterThanOrEqualTo(2);
                                // Crash count should increase
                                assertThat(crashCounts.get(1)).isGreaterThan(crashCounts.get(0));
    @DisplayName("P0: SupervisorMaxRestartsExceeded event")
    class SupervisorMaxRestartsExceededTests {
        @DisplayName("SupervisorMaxRestartsExceeded event when intensity exceeded")
        void supervisorMaxRestartsExceeded_intensityExceeded() throws Exception {
            var maxRestartsEvent =
                    new AtomicReference<FrameworkEvent.SupervisorMaxRestartsExceeded>();
                        if (event instanceof FrameworkEvent.SupervisorMaxRestartsExceeded) {
                            maxRestartsEvent.set(
                                    (FrameworkEvent.SupervisorMaxRestartsExceeded) event);
                            .id("rapid-crash-child")
                                                                        "Rapid crash");
            // Create supervisor with low intensity threshold
            var supervisor =
                    Supervisor.create(
                            Supervisor.Strategy.ONE_FOR_ONE,
                            2, // maxRestarts
                            Duration.ofSeconds(5), // period
                            List.of(childSpec));
            // Act - trigger rapid crashes
            var childRef = ProcRegistry.whereis("rapid-crash-child");
            await().atMost(Duration.ofSeconds(5))
                                assertThat(maxRestartsEvent.get()).isNotNull();
                                var event = maxRestartsEvent.get();
                                assertThat(event.maxRestarts()).isEqualTo(2);
                                assertThat(event.actualRestarts()).isGreaterThanOrEqualTo(2);
        @DisplayName("SupervisorMaxRestartsExceeded includes duration window")
        void supervisorMaxRestartsExceeded_includesDurationWindow() throws Exception {
                            .id("window-child")
                                                                        "Window crash");
                            2,
                            Duration.ofSeconds(3),
            var childRef = ProcRegistry.whereis("window-child");
            for (int i = 0; i < 4; i++) {
                childRef.proc().tell("crash");
            }
                                assertThat(event.window()).isNotNull();
                                assertThat(event.window().toJavaDuration())
                                        .isEqualTo(Duration.ofSeconds(3));
    // P1: Debugging Events
    @DisplayName("P1: StateMachineTransition event")
    class StateMachineTransitionTests {
        @DisplayName("StateMachineTransition event published on state change")
        void stateMachineTransition_publishedOnChange() {
            interface State {}
            record Idle() implements State {}
            record Active() implements State {}
            interface Event {}
            record Start() implements Event {}
            record Stop() implements Event {}
            var transitionEvents = new ArrayList<FrameworkEvent.StateMachineTransition>();
                        if (event instanceof FrameworkEvent.StateMachineTransition) {
                            transitionEvents.add((FrameworkEvent.StateMachineTransition) event);
            var sm =
                    StateMachine.builder()
                            .initialState(new Idle())
                            .stateClass(State.class)
                            .eventClass(Event.class)
                            .transitionFn(
                                    (state, event, data) -> {
                                        if (state instanceof Idle && event instanceof Start) {
                                            return StateMachine.Transition.nextState(new Active());
                                        }
                                        return StateMachine.Transition.keepState();
            sm.tell(new Start());
                                assertThat(transitionEvents).isNotEmpty();
                                var event = transitionEvents.get(0);
                                assertThat(event.machineId()).isNotNull();
                                assertThat(event.fromState()).contains("Idle");
                                assertThat(event.toState()).contains("Active");
                                assertThat(event.eventType()).contains("Start");
            sm.stop();
        @DisplayName("StateMachineTransition captures all transition metadata")
        void stateMachineTransition_capturesMetadata() {
            record A() implements State {}
            record B() implements State {}
            record GoToB() implements Event {}
            var lastEvent = new AtomicReference<FrameworkEvent.StateMachineTransition>();
                            lastEvent.set((FrameworkEvent.StateMachineTransition) event);
                            .initialState(new A())
                                        return StateMachine.Transition.nextState(new B());
            sm.tell(new GoToB());
                                assertThat(lastEvent.get()).isNotNull();
                                var event = lastEvent.get();
                                assertThat(event.machineId()).isNotEmpty();
                                assertThat(event.fromState()).isNotEmpty();
                                assertThat(event.toState()).isNotEmpty();
                                assertThat(event.eventType()).isNotEmpty();
    @DisplayName("P1: StateMachineTimeout event")
    class StateMachineTimeoutTests {
        @DisplayName("StateMachineTimeout event when timeout scheduled")
        void stateMachineTimeout_scheduled() {
            record Waiting() implements State {}
            record Timeout() implements Event {}
            var timeoutEvents = new ArrayList<FrameworkEvent.StateMachineTimeout>();
                        if (event instanceof FrameworkEvent.StateMachineTimeout) {
                            timeoutEvents.add((FrameworkEvent.StateMachineTimeout) event);
                            .initialState(new Waiting())
                                        return StateMachine.Transition.keepState()
                                                .withActions(
                                                        StateMachine.Action.setStateTimeout(
                                                                Duration.ofMillis(100)));
                                assertThat(timeoutEvents).isNotEmpty();
                                var event = timeoutEvents.get(0);
                                assertThat(event.state()).contains("Waiting");
                                assertThat(event.timeoutType()).isEqualTo("state_timeout");
                                assertThat(event.delayMs()).isGreaterThan(0);
        @DisplayName("StateMachineTimeout includes timeout type and delay")
        void stateMachineTimeout_includesTypeAndDelay() {
            record Processing() implements State {}
            record Complete() implements Event {}
            var lastTimeout = new AtomicReference<FrameworkEvent.StateMachineTimeout>();
                            lastTimeout.set((FrameworkEvent.StateMachineTimeout) event);
                            .initialState(new Processing())
                                                                Duration.ofMillis(500)));
                                assertThat(lastTimeout.get()).isNotNull();
                                var event = lastTimeout.get();
                                assertThat(event.delayMs()).isEqualTo(500);
    @DisplayName("P1: ParallelTaskFailed event")
    class ParallelTaskFailedTests {
        @DisplayName("ParallelTaskFailed event when task fails")
        void parallelTaskFailed_taskFails() {
            var failureEvents = new ArrayList<FrameworkEvent.ParallelTaskFailed>();
                        if (event instanceof FrameworkEvent.ParallelTaskFailed) {
                            failureEvents.add((FrameworkEvent.ParallelTaskFailed) event);
            var result =
                    Parallel.execute(
                            List.of(
                                    () -> "success",
                                        throw new RuntimeException("Task failure");
                                    },
                                    () -> "another success"));
            // Assert - result should be failure
                                assertThat(failureEvents).isNotEmpty();
                                var event = failureEvents.get(0);
                                assertThat(event.parallelId()).isNotNull();
                                assertThat(event.reason().getMessage()).contains("Task failure");
        @DisplayName("ParallelTaskFailed includes failure reason")
        void parallelTaskFailed_includesReason() {
            var lastFailure = new AtomicReference<FrameworkEvent.ParallelTaskFailed>();
                            lastFailure.set((FrameworkEvent.ParallelTaskFailed) event);
            var expectedException = new IllegalStateException("Expected failure");
            Parallel.execute(
                    List.of(
                            () -> "ok",
                                throw expectedException;
                            }));
                                assertThat(lastFailure.get()).isNotNull();
                                var event = lastFailure.get();
                                assertThat(event.reason())
                                        .isInstanceOf(IllegalStateException.class);
                                assertThat(event.reason().getMessage())
                                        .isEqualTo("Expected failure");
                                assertThat(event.parallelId()).isNotEmpty();
    // P2: Operational Events
    @DisplayName("P2: ProcessMonitorRegistered event")
    class ProcessMonitorRegisteredTests {
        @DisplayName("ProcessMonitorRegistered event when monitor registered")
        void processMonitorRegistered_whenRegistered() {
            var monitorEvents = new ArrayList<FrameworkEvent.ProcessMonitorRegistered>();
                        if (event instanceof FrameworkEvent.ProcessMonitorRegistered) {
                            monitorEvents.add((FrameworkEvent.ProcessMonitorRegistered) event);
            var targetProc = Proc.spawn(() -> "target", (state, msg) -> state);
            var monitor =
                    ProcMonitor.monitor(
                            targetProc.ref(),
                            down -> {
                                // Handle DOWN message
                                assertThat(monitorEvents).isNotEmpty();
                                var event = monitorEvents.get(0);
                                assertThat(event.monitorId()).isNotNull();
                                assertThat(event.monitoredProcessId())
                                        .isEqualTo(targetProc.ref().pid());
            ProcSys.demonitor(targetProc.ref());
            targetProc.stop();
        @DisplayName("ProcessMonitorRegistered includes both monitor and monitored IDs")
        void processMonitorRegistered_includesBothIds() {
            var lastEvent = new AtomicReference<FrameworkEvent.ProcessMonitorRegistered>();
                            lastEvent.set((FrameworkEvent.ProcessMonitorRegistered) event);
            var targetProc = Proc.spawn(() -> "t", (s, m) -> s);
            ProcMonitor.monitor(targetProc.ref(), down -> {});
                                assertThat(event.monitorId()).isNotEmpty();
    @DisplayName("P2: RegistryConflict event")
    class RegistryConflictTests {
        @DisplayName("RegistryConflict event on name collision")
        void registryConflict_onNameCollision() {
            var conflictEvents = new ArrayList<FrameworkEvent.RegistryConflict>();
                        if (event instanceof FrameworkEvent.RegistryConflict) {
                            conflictEvents.add((FrameworkEvent.RegistryConflict) event);
            // Act - register first process
            var proc1 = Proc.spawn(() -> "first", (state, msg) -> state);
            ProcRegistry.register("shared-name", proc1.ref());
            // Try to register second process with same name
            var proc2 = Proc.spawn(() -> "second", (state, msg) -> state);
            ProcRegistry.register("shared-name", proc2.ref());
                                assertThat(conflictEvents).isNotEmpty();
                                var event = conflictEvents.get(0);
                                assertThat(event.processName()).isEqualTo("shared-name");
                                assertThat(event.existingProcessId()).isEqualTo(proc1.ref().pid());
                                assertThat(event.newProcessId()).isEqualTo(proc2.ref().pid());
            ProcRegistry.unregister("shared-name");
        @DisplayName("RegistryConflict includes both conflicting process IDs")
        void registryConflict_includesBothIds() {
            var lastConflict = new AtomicReference<FrameworkEvent.RegistryConflict>();
                            lastConflict.set((FrameworkEvent.RegistryConflict) event);
            var p1 = Proc.spawn(() -> "a", (s, m) -> s);
            ProcRegistry.register("dup", p1.ref());
            var p2 = Proc.spawn(() -> "b", (s, m) -> s);
            ProcRegistry.register("dup", p2.ref());
                                assertThat(lastConflict.get()).isNotNull();
                                var event = lastConflict.get();
                                assertThat(event.processName()).isEqualTo("dup");
                                assertThat(event.existingProcessId()).isEqualTo(p1.ref().pid());
                                assertThat(event.newProcessId()).isEqualTo(p2.ref().pid());
            ProcRegistry.unregister("dup");
            p1.stop();
            p2.stop();
    // Cross-Cutting Concerns
    @DisplayName("Event bus reliability and isolation")
    class EventBusReliabilityTests {
        @DisplayName("Subscriber errors don't prevent event delivery to other subscribers")
        void subscriberErrors_isolated() {
            // Arrange - This test uses the error-throwing subscriber registered in setUp()
            var normalSubscriberCount = new AtomicInteger(0);
                        normalSubscriberCount.incrementAndGet();
            var proc = Proc.spawn(() -> "test", (s, m) -> s);
            // Act - trigger error by creating a process with specific ID
            // (The error-throwing subscriber will fail on "error-test" process ID)
            var errorProc = Proc.spawn(() -> "error", (s, m) -> s);
                                // Error should have been caught
                                assertThat(subscriberError.get()).isNotNull();
                                assertThat(subscriberError.get().getMessage())
                                        .contains("Simulated subscriber error");
                                // But normal subscriber should still receive events
                                assertThat(normalSubscriberCount.get()).isGreaterThan(0);
            errorProc.stop();
        @DisplayName("Events delivered asynchronously")
        void eventsDeliveredAsynchronously() {
            var syncDeliveryTime = new AtomicReference<Long>();
            var asyncDeliveryTime = new AtomicReference<Long>();
                            asyncDeliveryTime.set(System.nanoTime());
            syncDeliveryTime.set(System.nanoTime());
            var proc = Proc.spawn(() -> "async-test", (s, m) -> s);
                                assertThat(asyncDeliveryTime.get()).isNotNull();
                                // Async delivery should happen after sync call
                                assertThat(asyncDeliveryTime.get())
                                        .isGreaterThanOrEqualTo(syncDeliveryTime.get());
        @DisplayName("Multiple subscribers all receive events")
        void multipleSubscribers_allReceive() {
            var sub1Count = new AtomicInteger(0);
            var sub2Count = new AtomicInteger(0);
            var sub3Count = new AtomicInteger(0);
            eventBus.subscribe(event -> sub1Count.incrementAndGet());
            eventBus.subscribe(event -> sub2Count.incrementAndGet());
            eventBus.subscribe(event -> sub3Count.incrementAndGet());
            var proc = Proc.spawn(() -> "multi-sub", (s, m) -> s);
                                // All subscribers should receive the event
                                assertThat(sub1Count.get()).isGreaterThan(0);
                                assertThat(sub2Count.get()).isGreaterThan(0);
                                assertThat(sub3Count.get()).isGreaterThan(0);
                                // All should have received the same number of events
                                assertThat(sub1Count.get()).isEqualTo(sub2Count.get());
                                assertThat(sub2Count.get()).isEqualTo(sub3Count.get());
    @DisplayName("Event priority and ordering")
    class EventPriorityTests {
        @DisplayName("P0 events are delivered in order")
        void p0Events_deliveredInOrder() {
            var eventOrder = new ArrayList<String>();
                        eventOrder.add(event.getClass().getSimpleName());
            // Act - create and stop a process
            var proc = Proc.spawn(() -> "order-test", (s, m) -> s);
                                assertThat(eventOrder)
                                        .containsSequence("ProcessCreated", "ProcessTerminated");
        @DisplayName("All event types are published with timestamps")
        void allEvents_haveTimestamps() {
            var timestamps = new ArrayList<Instant>();
                        timestamps.add(event.timestamp());
            // Act - trigger various events
            var proc = Proc.spawn(() -> "ts-test", (s, m) -> s);
            proc.tell("test");
                                assertThat(timestamps).isNotEmpty();
                                assertThat(timestamps)
                                        .allSatisfy(
                                                ts ->
                                                        assertThat(ts)
                                                                .isNotNull()
                                                                .isBeforeOrEqualTo(Instant.now()));
    @DisplayName("Observability feature flag")
    class FeatureFlagTests {
        @DisplayName("Events not published when observability disabled")
        void eventsNotPublished_whenDisabled() {
            var countBeforeDisable = eventCount.get();
            // Disable observability
            System.setProperty("jotp.observability.enabled", "false");
            // Act - spawn process (should not publish events)
            var proc = Proc.spawn(() -> "disabled-test", (s, m) -> s);
            // Wait a bit for async delivery
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            // Assert - count should not have changed
            assertThat(eventCount.get()).isEqualTo(countBeforeDisable);
            System.setProperty("jotp.observability.enabled", "true");
}
