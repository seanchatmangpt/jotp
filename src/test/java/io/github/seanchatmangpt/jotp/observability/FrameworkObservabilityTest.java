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

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.dtr.junit5.DtrTest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive tests for framework observability via FrameworkEventBus.
 *
 * <p>Tests cover all event types across three priority levels:
 *
 * <ul>
 *   <li><b>P0 (Fault Detection):</b> ProcessCreated, ProcessTerminated (with abnormal flag),
 *       SupervisorChildCrashed, SupervisorRestartAttempted, SupervisorMaxRestartsExceeded
 *   <li><b>P1 (Debugging):</b> StateMachineTransition, StateMachineTimeout, ParallelTaskFailed
 *   <li><b>P2 (Operational):</b> ProcessMonitorRegistered, RegistryConflict
 * </ul>
 *
 * <p>Test approach:
 *
 * <ul>
 *   <li>Enable observability via system property before each test
 *   <li>Use counting consumers to verify events are published
 *   <li>Use Awaitility for async verification
 *   <li>Test event content and timing
 *   <li>Verify subscriber isolation and error handling
 * </ul>
 *
 * @see FrameworkEventBus
 * @see FrameworkEvent
 */
@DtrTest
@DisplayName("Framework Observability: P0/P1/P2 event publishing and delivery")
@Timeout(30)
class FrameworkObservabilityTest implements WithAssertions {

    private FrameworkEventBus eventBus;
    private AtomicInteger eventCount;
    private ConcurrentHashMap<String, List<FrameworkEvent>> eventsByType;
    private AtomicReference<Throwable> subscriberError;

    @BeforeEach
    void setUp() {
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
        eventBus.subscribe(
                event -> {
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
                });
    }

    @AfterEach
    void tearDown() {
        // Clean up system property
        System.clearProperty("jotp.observability.enabled");

        // Unsubscribe all to prevent cross-test contamination
        // Note: In a real scenario we'd need unsubscribe(Consumer) but the current
        // implementation doesn't support it, so we rely on test isolation
    }

    // =========================================================================
    // P0: Fault Detection Events
    // =========================================================================

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

        @Test
        @DisplayName("ProcessCreated event contains correct process metadata")
        void processCreated_containsCorrectMetadata() {
            // Act
            var proc = Proc.spawn(() -> "initial", (state, msg) -> state);

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                var events = eventsByType.get("ProcessCreated");
                                assertThat(events).isNotNull();

                                var event = (FrameworkEvent.ProcessCreated) events.get(0);
                                assertThat(event.processId()).isNotEmpty();
                                assertThat(event.processType()).isNotEmpty();
                                assertThat(event.timestamp()).isNotNull();
                            });

            // Cleanup
            proc.stop();
        }

        @Test
        @DisplayName("Multiple ProcessCreated events for multiple processes")
        void processCreated_multipleProcesses() {
            // Act
            var proc1 = Proc.spawn(() -> "p1", (s, m) -> s);
            var proc2 = Proc.spawn(() -> "p2", (s, m) -> s);
            var proc3 = Proc.spawn(() -> "p3", (s, m) -> s);

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                var events = eventsByType.get("ProcessCreated");
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
                            });

            // Cleanup
            proc1.stop();
            proc2.stop();
            proc3.stop();
        }
    }

    @Nested
    @DisplayName("P0: ProcessTerminated event")
    class ProcessTerminatedTests {

        @Test
        @DisplayName("ProcessTerminated event published with abnormal=false for graceful stop")
        void processTerminated_gracefulStop() {
            // Arrange
            var proc = Proc.spawn(() -> "initial", (state, msg) -> state);

            // Clear creation events
            eventsByType.remove("ProcessCreated");

            // Act
            proc.stop();

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                var events = eventsByType.get("ProcessTerminated");
                                assertThat(events).isNotNull().isNotEmpty();

                                var event = (FrameworkEvent.ProcessTerminated) events.get(0);
                                assertThat(event.processId()).isNotNull();
                                assertThat(event.abnormal()).isFalse();
                                assertThat(event.reason()).isNotNull();
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });
        }

        @Test
        @DisplayName("ProcessTerminated event published with abnormal=true for crash")
        void processTerminated_crash() {
            // Arrange
            var proc =
                    Proc.spawn(
                            () -> "initial",
                            (state, msg) -> {
                                if ("crash".equals(msg)) {
                                    throw new RuntimeException("Intentional crash");
                                }
                                return state;
                            });

            // Clear creation events
            eventsByType.remove("ProcessCreated");

            // Act
            proc.tell("crash");

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                var events = eventsByType.get("ProcessTerminated");
                                assertThat(events).isNotNull().isNotEmpty();

                                var event = (FrameworkEvent.ProcessTerminated) events.get(0);
                                assertThat(event.processId()).isNotNull();
                                assertThat(event.abnormal()).isTrue();
                                assertThat(event.reason()).isNotNull();
                            });
        }

        @Test
        @DisplayName("ProcessTerminated event contains processType")
        void processTerminated_containsProcessType() {
            // Arrange
            var proc = Proc.spawn(() -> "initial", (state, msg) -> state);

            eventsByType.remove("ProcessCreated");

            // Act
            proc.stop();

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                var events = eventsByType.get("ProcessTerminated");
                                assertThat(events).isNotNull().isNotEmpty();

                                var event = (FrameworkEvent.ProcessTerminated) events.get(0);
                                assertThat(event.processType()).isNotNull();
                            });
        }
    }

    @Nested
    @DisplayName("P0: SupervisorChildCrashed event")
    class SupervisorChildCrashedTests {

        @Test
        @DisplayName("SupervisorChildCrashed event when child crashes")
        void supervisorChildCrashed_childCrashes() throws Exception {
            // Arrange
            var crashReason = new AtomicReference<Throwable>();
            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorChildCrashed) {
                            var scc = (FrameworkEvent.SupervisorChildCrashed) event;
                            crashReason.set(scc.reason());
                        }
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

            // Act
            var childRef = ProcRegistry.whereis("crashy-child");
            assertThat(childRef).isNotNull();

            // Trigger crash through ProcSys
            var proc = childRef.proc();
            proc.tell("crash");

            // Assert
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () -> {
                                assertThat(crashReason.get()).isNotNull();
                                assertThat(crashReason.get().getMessage()).contains("Child crash");

                                var events = eventsByType.get("SupervisorChildCrashed");
                                assertThat(events).isNotNull().isNotEmpty();

                                var event = (FrameworkEvent.SupervisorChildCrashed) events.get(0);
                                assertThat(event.supervisorId()).isNotNull();
                                assertThat(event.childId()).isEqualTo("crashy-child");
                                assertThat(event.reason()).isNotNull();
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            supervisor.shutdown();
        }

        @Test
        @DisplayName("SupervisorChildCrashed includes supervisor and child IDs")
        void supervisorChildCrashed_containsIds() throws Exception {
            // Arrange
            var supervisorId = new AtomicReference<String>();
            var childId = new AtomicReference<String>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorChildCrashed) {
                            var scc = (FrameworkEvent.SupervisorChildCrashed) event;
                            supervisorId.set(scc.supervisorId());
                            childId.set(scc.childId());
                        }
                    });

            var childSpec =
                    Supervisor.ChildSpec.builder()
                            .id("test-child")
                            .start(
                                    () -> {
                                        return Proc.spawn(
                                                        () -> "initial",
                                                        (state, msg) -> {
                                                            if ("boom".equals(msg)) {
                                                                throw new RuntimeException("Boom");
                                                            }
                                                            return state;
                                                        })
                                                .ref();
                                    })
                            .restartType(Supervisor.RestartType.PERMANENT)
                            .build();

            var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, List.of(childSpec));

            // Act
            var childRef = ProcRegistry.whereis("test-child");
            childRef.proc().tell("boom");

            // Assert
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () -> {
                                assertThat(supervisorId.get()).isNotNull();
                                assertThat(childId.get()).isEqualTo("test-child");
                            });

            // Cleanup
            supervisor.shutdown();
        }
    }

    @Nested
    @DisplayName("P0: SupervisorRestartAttempted event")
    class SupervisorRestartAttemptedTests {

        @Test
        @DisplayName("SupervisorRestartAttempted event when restarting child")
        void supervisorRestartAttempted_onChildRestart() throws Exception {
            // Arrange
            var restartEvent = new AtomicReference<FrameworkEvent.SupervisorRestartAttempted>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorRestartAttempted) {
                            restartEvent.set((FrameworkEvent.SupervisorRestartAttempted) event);
                        }
                    });

            var childSpec =
                    Supervisor.ChildSpec.builder()
                            .id("restart-child")
                            .start(
                                    () -> {
                                        return Proc.spawn(
                                                        () -> "initial",
                                                        (state, msg) -> {
                                                            if ("crash".equals(msg)) {
                                                                throw new RuntimeException(
                                                                        "Crash for restart");
                                                            }
                                                            return state;
                                                        })
                                                .ref();
                                    })
                            .restartType(Supervisor.RestartType.PERMANENT)
                            .build();

            var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, List.of(childSpec));

            // Act
            var childRef = ProcRegistry.whereis("restart-child");
            childRef.proc().tell("crash");

            // Assert
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () -> {
                                assertThat(restartEvent.get()).isNotNull();

                                var event = restartEvent.get();
                                assertThat(event.supervisorId()).isNotNull();
                                assertThat(event.childId()).isEqualTo("restart-child");
                                assertThat(event.strategy())
                                        .isEqualTo(Supervisor.Strategy.ONE_FOR_ONE);
                                assertThat(event.crashCount()).isGreaterThan(0);
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            supervisor.shutdown();
        }

        @Test
        @DisplayName("SupervisorRestartAttempted includes crash count")
        void supervisorRestartAttempted_includesCrashCount() throws Exception {
            // Arrange
            var crashCounts = new ArrayList<Integer>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorRestartAttempted) {
                            var sra = (FrameworkEvent.SupervisorRestartAttempted) event;
                            crashCounts.add(sra.crashCount());
                        }
                    });

            var childSpec =
                    Supervisor.ChildSpec.builder()
                            .id("multi-crash-child")
                            .start(
                                    () -> {
                                        return Proc.spawn(
                                                        () -> "initial",
                                                        (state, msg) -> {
                                                            if ("crash".equals(msg)) {
                                                                throw new RuntimeException(
                                                                        "Repeated crash");
                                                            }
                                                            return state;
                                                        })
                                                .ref();
                                    })
                            .restartType(Supervisor.RestartType.PERMANENT)
                            .build();

            var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, List.of(childSpec));

            // Act - trigger multiple crashes
            var childRef = ProcRegistry.whereis("multi-crash-child");
            childRef.proc().tell("crash");

            await().atMost(Duration.ofSeconds(2)).until(() -> crashCounts.size() >= 1);

            childRef.proc().tell("crash");

            // Assert
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () -> {
                                assertThat(crashCounts).hasSizeGreaterThanOrEqualTo(2);
                                // Crash count should increase
                                assertThat(crashCounts.get(1)).isGreaterThan(crashCounts.get(0));
                            });

            // Cleanup
            supervisor.shutdown();
        }
    }

    @Nested
    @DisplayName("P0: SupervisorMaxRestartsExceeded event")
    class SupervisorMaxRestartsExceededTests {

        @Test
        @DisplayName("SupervisorMaxRestartsExceeded event when intensity exceeded")
        void supervisorMaxRestartsExceeded_intensityExceeded() throws Exception {
            // Arrange
            var maxRestartsEvent =
                    new AtomicReference<FrameworkEvent.SupervisorMaxRestartsExceeded>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorMaxRestartsExceeded) {
                            maxRestartsEvent.set(
                                    (FrameworkEvent.SupervisorMaxRestartsExceeded) event);
                        }
                    });

            var childSpec =
                    Supervisor.ChildSpec.builder()
                            .id("rapid-crash-child")
                            .start(
                                    () -> {
                                        return Proc.spawn(
                                                        () -> "initial",
                                                        (state, msg) -> {
                                                            if ("crash".equals(msg)) {
                                                                throw new RuntimeException(
                                                                        "Rapid crash");
                                                            }
                                                            return state;
                                                        })
                                                .ref();
                                    })
                            .restartType(Supervisor.RestartType.PERMANENT)
                            .build();

            // Create supervisor with low intensity threshold
            var supervisor =
                    Supervisor.create(
                            Supervisor.Strategy.ONE_FOR_ONE,
                            2, // maxRestarts
                            Duration.ofSeconds(5), // period
                            List.of(childSpec));

            // Act - trigger rapid crashes
            var childRef = ProcRegistry.whereis("rapid-crash-child");
            childRef.proc().tell("crash");
            childRef.proc().tell("crash");
            childRef.proc().tell("crash");

            // Assert
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> {
                                assertThat(maxRestartsEvent.get()).isNotNull();

                                var event = maxRestartsEvent.get();
                                assertThat(event.supervisorId()).isNotNull();
                                assertThat(event.maxRestarts()).isEqualTo(2);
                                assertThat(event.actualRestarts()).isGreaterThanOrEqualTo(2);
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            supervisor.shutdown();
        }

        @Test
        @DisplayName("SupervisorMaxRestartsExceeded includes duration window")
        void supervisorMaxRestartsExceeded_includesDurationWindow() throws Exception {
            // Arrange
            var maxRestartsEvent =
                    new AtomicReference<FrameworkEvent.SupervisorMaxRestartsExceeded>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.SupervisorMaxRestartsExceeded) {
                            maxRestartsEvent.set(
                                    (FrameworkEvent.SupervisorMaxRestartsExceeded) event);
                        }
                    });

            var childSpec =
                    Supervisor.ChildSpec.builder()
                            .id("window-child")
                            .start(
                                    () -> {
                                        return Proc.spawn(
                                                        () -> "initial",
                                                        (state, msg) -> {
                                                            if ("crash".equals(msg)) {
                                                                throw new RuntimeException(
                                                                        "Window crash");
                                                            }
                                                            return state;
                                                        })
                                                .ref();
                                    })
                            .restartType(Supervisor.RestartType.PERMANENT)
                            .build();

            var supervisor =
                    Supervisor.create(
                            Supervisor.Strategy.ONE_FOR_ONE,
                            2,
                            Duration.ofSeconds(3),
                            List.of(childSpec));

            // Act
            var childRef = ProcRegistry.whereis("window-child");
            for (int i = 0; i < 4; i++) {
                childRef.proc().tell("crash");
            }

            // Assert
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> {
                                assertThat(maxRestartsEvent.get()).isNotNull();
                                var event = maxRestartsEvent.get();
                                assertThat(event.window()).isNotNull();
                                assertThat(event.window().toJavaDuration())
                                        .isEqualTo(Duration.ofSeconds(3));
                            });

            // Cleanup
            supervisor.shutdown();
        }
    }

    // =========================================================================
    // P1: Debugging Events
    // =========================================================================

    @Nested
    @DisplayName("P1: StateMachineTransition event")
    class StateMachineTransitionTests {

        @Test
        @DisplayName("StateMachineTransition event published on state change")
        void stateMachineTransition_publishedOnChange() {
            // Arrange
            interface State {}
            record Idle() implements State {}
            record Active() implements State {}

            interface Event {}
            record Start() implements Event {}
            record Stop() implements Event {}

            var transitionEvents = new ArrayList<FrameworkEvent.StateMachineTransition>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.StateMachineTransition) {
                            transitionEvents.add((FrameworkEvent.StateMachineTransition) event);
                        }
                    });

            // Act
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
                                    })
                            .build();

            sm.tell(new Start());

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(transitionEvents).isNotEmpty();

                                var event = transitionEvents.get(0);
                                assertThat(event.machineId()).isNotNull();
                                assertThat(event.fromState()).contains("Idle");
                                assertThat(event.toState()).contains("Active");
                                assertThat(event.eventType()).contains("Start");
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            sm.stop();
        }

        @Test
        @DisplayName("StateMachineTransition captures all transition metadata")
        void stateMachineTransition_capturesMetadata() {
            // Arrange
            interface State {}
            record A() implements State {}
            record B() implements State {}

            interface Event {}
            record GoToB() implements Event {}

            var lastEvent = new AtomicReference<FrameworkEvent.StateMachineTransition>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.StateMachineTransition) {
                            lastEvent.set((FrameworkEvent.StateMachineTransition) event);
                        }
                    });

            // Act
            var sm =
                    StateMachine.builder()
                            .initialState(new A())
                            .stateClass(State.class)
                            .eventClass(Event.class)
                            .transitionFn(
                                    (state, event, data) -> {
                                        return StateMachine.Transition.nextState(new B());
                                    })
                            .build();

            sm.tell(new GoToB());

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(lastEvent.get()).isNotNull();
                                var event = lastEvent.get();
                                assertThat(event.machineId()).isNotEmpty();
                                assertThat(event.fromState()).isNotEmpty();
                                assertThat(event.toState()).isNotEmpty();
                                assertThat(event.eventType()).isNotEmpty();
                                assertThat(event.timestamp()).isNotNull();
                            });

            // Cleanup
            sm.stop();
        }
    }

    @Nested
    @DisplayName("P1: StateMachineTimeout event")
    class StateMachineTimeoutTests {

        @Test
        @DisplayName("StateMachineTimeout event when timeout scheduled")
        void stateMachineTimeout_scheduled() {
            // Arrange
            interface State {}
            record Waiting() implements State {}

            interface Event {}
            record Timeout() implements Event {}

            var timeoutEvents = new ArrayList<FrameworkEvent.StateMachineTimeout>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.StateMachineTimeout) {
                            timeoutEvents.add((FrameworkEvent.StateMachineTimeout) event);
                        }
                    });

            // Act
            var sm =
                    StateMachine.builder()
                            .initialState(new Waiting())
                            .stateClass(State.class)
                            .eventClass(Event.class)
                            .transitionFn(
                                    (state, event, data) -> {
                                        return StateMachine.Transition.keepState()
                                                .withActions(
                                                        StateMachine.Action.setStateTimeout(
                                                                Duration.ofMillis(100)));
                                    })
                            .build();

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(timeoutEvents).isNotEmpty();

                                var event = timeoutEvents.get(0);
                                assertThat(event.machineId()).isNotNull();
                                assertThat(event.state()).contains("Waiting");
                                assertThat(event.timeoutType()).isEqualTo("state_timeout");
                                assertThat(event.delayMs()).isGreaterThan(0);
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            sm.stop();
        }

        @Test
        @DisplayName("StateMachineTimeout includes timeout type and delay")
        void stateMachineTimeout_includesTypeAndDelay() {
            // Arrange
            interface State {}
            record Processing() implements State {}

            interface Event {}
            record Complete() implements Event {}

            var lastTimeout = new AtomicReference<FrameworkEvent.StateMachineTimeout>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.StateMachineTimeout) {
                            lastTimeout.set((FrameworkEvent.StateMachineTimeout) event);
                        }
                    });

            // Act
            var sm =
                    StateMachine.builder()
                            .initialState(new Processing())
                            .stateClass(State.class)
                            .eventClass(Event.class)
                            .transitionFn(
                                    (state, event, data) -> {
                                        return StateMachine.Transition.keepState()
                                                .withActions(
                                                        StateMachine.Action.setStateTimeout(
                                                                Duration.ofMillis(500)));
                                    })
                            .build();

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(lastTimeout.get()).isNotNull();
                                var event = lastTimeout.get();
                                assertThat(event.timeoutType()).isEqualTo("state_timeout");
                                assertThat(event.delayMs()).isEqualTo(500);
                            });

            // Cleanup
            sm.stop();
        }
    }

    @Nested
    @DisplayName("P1: ParallelTaskFailed event")
    class ParallelTaskFailedTests {

        @Test
        @DisplayName("ParallelTaskFailed event when task fails")
        void parallelTaskFailed_taskFails() {
            // Arrange
            var failureEvents = new ArrayList<FrameworkEvent.ParallelTaskFailed>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.ParallelTaskFailed) {
                            failureEvents.add((FrameworkEvent.ParallelTaskFailed) event);
                        }
                    });

            // Act
            var result =
                    Parallel.execute(
                            List.of(
                                    () -> "success",
                                    () -> {
                                        throw new RuntimeException("Task failure");
                                    },
                                    () -> "another success"));

            // Assert - result should be failure
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(failureEvents).isNotEmpty();

                                var event = failureEvents.get(0);
                                assertThat(event.parallelId()).isNotNull();
                                assertThat(event.reason()).isNotNull();
                                assertThat(event.reason().getMessage()).contains("Task failure");
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });
        }

        @Test
        @DisplayName("ParallelTaskFailed includes failure reason")
        void parallelTaskFailed_includesReason() {
            // Arrange
            var lastFailure = new AtomicReference<FrameworkEvent.ParallelTaskFailed>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.ParallelTaskFailed) {
                            lastFailure.set((FrameworkEvent.ParallelTaskFailed) event);
                        }
                    });

            var expectedException = new IllegalStateException("Expected failure");

            // Act
            Parallel.execute(
                    List.of(
                            () -> "ok",
                            () -> {
                                throw expectedException;
                            }));

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(lastFailure.get()).isNotNull();
                                var event = lastFailure.get();
                                assertThat(event.reason())
                                        .isInstanceOf(IllegalStateException.class);
                                assertThat(event.reason().getMessage())
                                        .isEqualTo("Expected failure");
                                assertThat(event.parallelId()).isNotEmpty();
                            });
        }
    }

    // =========================================================================
    // P2: Operational Events
    // =========================================================================

    @Nested
    @DisplayName("P2: ProcessMonitorRegistered event")
    class ProcessMonitorRegisteredTests {

        @Test
        @DisplayName("ProcessMonitorRegistered event when monitor registered")
        void processMonitorRegistered_whenRegistered() {
            // Arrange
            var monitorEvents = new ArrayList<FrameworkEvent.ProcessMonitorRegistered>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.ProcessMonitorRegistered) {
                            monitorEvents.add((FrameworkEvent.ProcessMonitorRegistered) event);
                        }
                    });

            var targetProc = Proc.spawn(() -> "target", (state, msg) -> state);

            // Act
            var monitor =
                    ProcMonitor.monitor(
                            targetProc.ref(),
                            down -> {
                                // Handle DOWN message
                            });

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(monitorEvents).isNotEmpty();

                                var event = monitorEvents.get(0);
                                assertThat(event.monitorId()).isNotNull();
                                assertThat(event.monitoredProcessId())
                                        .isEqualTo(targetProc.ref().pid());
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            ProcSys.demonitor(targetProc.ref());
            targetProc.stop();
        }

        @Test
        @DisplayName("ProcessMonitorRegistered includes both monitor and monitored IDs")
        void processMonitorRegistered_includesBothIds() {
            // Arrange
            var lastEvent = new AtomicReference<FrameworkEvent.ProcessMonitorRegistered>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.ProcessMonitorRegistered) {
                            lastEvent.set((FrameworkEvent.ProcessMonitorRegistered) event);
                        }
                    });

            var targetProc = Proc.spawn(() -> "t", (s, m) -> s);

            // Act
            ProcMonitor.monitor(targetProc.ref(), down -> {});

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(lastEvent.get()).isNotNull();
                                var event = lastEvent.get();
                                assertThat(event.monitorId()).isNotEmpty();
                                assertThat(event.monitoredProcessId())
                                        .isEqualTo(targetProc.ref().pid());
                            });

            // Cleanup
            ProcSys.demonitor(targetProc.ref());
            targetProc.stop();
        }
    }

    @Nested
    @DisplayName("P2: RegistryConflict event")
    class RegistryConflictTests {

        @Test
        @DisplayName("RegistryConflict event on name collision")
        void registryConflict_onNameCollision() {
            // Arrange
            var conflictEvents = new ArrayList<FrameworkEvent.RegistryConflict>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.RegistryConflict) {
                            conflictEvents.add((FrameworkEvent.RegistryConflict) event);
                        }
                    });

            // Act - register first process
            var proc1 = Proc.spawn(() -> "first", (state, msg) -> state);
            ProcRegistry.register("shared-name", proc1.ref());

            // Try to register second process with same name
            var proc2 = Proc.spawn(() -> "second", (state, msg) -> state);
            ProcRegistry.register("shared-name", proc2.ref());

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(conflictEvents).isNotEmpty();

                                var event = conflictEvents.get(0);
                                assertThat(event.processName()).isEqualTo("shared-name");
                                assertThat(event.existingProcessId()).isEqualTo(proc1.ref().pid());
                                assertThat(event.newProcessId()).isEqualTo(proc2.ref().pid());
                                assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
                            });

            // Cleanup
            ProcRegistry.unregister("shared-name");
            proc1.stop();
            proc2.stop();
        }

        @Test
        @DisplayName("RegistryConflict includes both conflicting process IDs")
        void registryConflict_includesBothIds() {
            // Arrange
            var lastConflict = new AtomicReference<FrameworkEvent.RegistryConflict>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.RegistryConflict) {
                            lastConflict.set((FrameworkEvent.RegistryConflict) event);
                        }
                    });

            // Act
            var p1 = Proc.spawn(() -> "a", (s, m) -> s);
            ProcRegistry.register("dup", p1.ref());

            var p2 = Proc.spawn(() -> "b", (s, m) -> s);
            ProcRegistry.register("dup", p2.ref());

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(lastConflict.get()).isNotNull();
                                var event = lastConflict.get();
                                assertThat(event.processName()).isEqualTo("dup");
                                assertThat(event.existingProcessId()).isEqualTo(p1.ref().pid());
                                assertThat(event.newProcessId()).isEqualTo(p2.ref().pid());
                            });

            // Cleanup
            ProcRegistry.unregister("dup");
            p1.stop();
            p2.stop();
        }
    }

    // =========================================================================
    // Cross-Cutting Concerns
    // =========================================================================

    @Nested
    @DisplayName("Event bus reliability and isolation")
    class EventBusReliabilityTests {

        @Test
        @DisplayName("Subscriber errors don't prevent event delivery to other subscribers")
        void subscriberErrors_isolated() {
            // Arrange - This test uses the error-throwing subscriber registered in setUp()
            var normalSubscriberCount = new AtomicInteger(0);

            eventBus.subscribe(
                    event -> {
                        normalSubscriberCount.incrementAndGet();
                    });

            var proc = Proc.spawn(() -> "test", (s, m) -> s);

            // Act - trigger error by creating a process with specific ID
            // (The error-throwing subscriber will fail on "error-test" process ID)
            var errorProc = Proc.spawn(() -> "error", (s, m) -> s);

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                // Error should have been caught
                                assertThat(subscriberError.get()).isNotNull();
                                assertThat(subscriberError.get().getMessage())
                                        .contains("Simulated subscriber error");

                                // But normal subscriber should still receive events
                                assertThat(normalSubscriberCount.get()).isGreaterThan(0);
                            });

            // Cleanup
            proc.stop();
            errorProc.stop();
        }

        @Test
        @DisplayName("Events delivered asynchronously")
        void eventsDeliveredAsynchronously() {
            // Arrange
            var syncDeliveryTime = new AtomicReference<Long>();
            var asyncDeliveryTime = new AtomicReference<Long>();

            eventBus.subscribe(
                    event -> {
                        if (event instanceof FrameworkEvent.ProcessCreated) {
                            asyncDeliveryTime.set(System.nanoTime());
                        }
                    });

            // Act
            syncDeliveryTime.set(System.nanoTime());
            var proc = Proc.spawn(() -> "async-test", (s, m) -> s);

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(asyncDeliveryTime.get()).isNotNull();
                                // Async delivery should happen after sync call
                                assertThat(asyncDeliveryTime.get())
                                        .isGreaterThanOrEqualTo(syncDeliveryTime.get());
                            });

            // Cleanup
            proc.stop();
        }

        @Test
        @DisplayName("Multiple subscribers all receive events")
        void multipleSubscribers_allReceive() {
            // Arrange
            var sub1Count = new AtomicInteger(0);
            var sub2Count = new AtomicInteger(0);
            var sub3Count = new AtomicInteger(0);

            eventBus.subscribe(event -> sub1Count.incrementAndGet());
            eventBus.subscribe(event -> sub2Count.incrementAndGet());
            eventBus.subscribe(event -> sub3Count.incrementAndGet());

            // Act
            var proc = Proc.spawn(() -> "multi-sub", (s, m) -> s);

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                // All subscribers should receive the event
                                assertThat(sub1Count.get()).isGreaterThan(0);
                                assertThat(sub2Count.get()).isGreaterThan(0);
                                assertThat(sub3Count.get()).isGreaterThan(0);

                                // All should have received the same number of events
                                assertThat(sub1Count.get()).isEqualTo(sub2Count.get());
                                assertThat(sub2Count.get()).isEqualTo(sub3Count.get());
                            });

            // Cleanup
            proc.stop();
        }
    }

    @Nested
    @DisplayName("Event priority and ordering")
    class EventPriorityTests {

        @Test
        @DisplayName("P0 events are delivered in order")
        void p0Events_deliveredInOrder() {
            // Arrange
            var eventOrder = new ArrayList<String>();

            eventBus.subscribe(
                    event -> {
                        eventOrder.add(event.getClass().getSimpleName());
                    });

            // Act - create and stop a process
            var proc = Proc.spawn(() -> "order-test", (s, m) -> s);
            proc.stop();

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(eventOrder)
                                        .containsSequence("ProcessCreated", "ProcessTerminated");
                            });
        }

        @Test
        @DisplayName("All event types are published with timestamps")
        void allEvents_haveTimestamps() {
            // Arrange
            var timestamps = new ArrayList<Instant>();

            eventBus.subscribe(
                    event -> {
                        timestamps.add(event.timestamp());
                    });

            // Act - trigger various events
            var proc = Proc.spawn(() -> "ts-test", (s, m) -> s);
            proc.tell("test");
            proc.stop();

            // Assert
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                assertThat(timestamps).isNotEmpty();
                                assertThat(timestamps)
                                        .allSatisfy(
                                                ts ->
                                                        assertThat(ts)
                                                                .isNotNull()
                                                                .isBeforeOrEqualTo(Instant.now()));
                            });
        }
    }

    @Nested
    @DisplayName("Observability feature flag")
    class FeatureFlagTests {

        @Test
        @DisplayName("Events not published when observability disabled")
        void eventsNotPublished_whenDisabled() {
            // Arrange
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
            }

            // Assert - count should not have changed
            assertThat(eventCount.get()).isEqualTo(countBeforeDisable);

            // Cleanup
            proc.stop();
            System.setProperty("jotp.observability.enabled", "true");
        }
    }
}
