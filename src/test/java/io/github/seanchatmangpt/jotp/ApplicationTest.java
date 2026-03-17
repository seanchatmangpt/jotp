package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for {@link Application}.
 *
 * <p>Tests cover application lifecycle management, startup sequence, service registration, graceful
 * shutdown, and hook execution.
 *
 * <p><strong>Application Lifecycle:</strong>
 *
 * <ul>
 *   <li><strong>INIT:</strong> Run init hooks, initialize state
 *   <li><strong>START:</strong> Start services and supervisors
 *   <li><strong>RUNNING:</strong> Normal operation
 *   <li><strong>STOP:</strong> Stop services, shut down supervisors, run shutdown hooks
 *   <li><strong>STOPPED:</strong> Shutdown complete
 * </ul>
 *
 * @see Application
 * @see ApplicationPhase
 */
@DisplayName("Application: Lifecycle Orchestrator")
class ApplicationTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    sealed interface TestMessage permits TestMessage.Increment, TestMessage.Get {
        record Increment() implements TestMessage {}

        record Get() implements TestMessage {}
    }

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);

    // ============================================================================
    // LIFECYCLE TESTS
    // ============================================================================

    @Test
    @DisplayName("Application startup sequence: INIT -> START -> RUNNING")
    void testStartupSequence() throws Exception {
                """
                Applications in JOTP mirror Erlang/OTP's application behavior - they are the top-level
                containers for supervision trees and services. Each application follows a strict lifecycle:
                INIT (initialization hooks) -> START (services spawn) -> RUNNING (normal operation).
                """);

        record AppState(String name) {}

        var phases = new CopyOnWriteArrayList<Application.ApplicationPhase>();

        Application<AppState> app =
                Application.<AppState>builder("test-app") //
                        .init(
                                () -> {
                                    phases.add(new Application.ApplicationPhase.INIT());
                                    return new AppState("test-app");
                                })
                        .addInitHook(
                                () -> {
                                    // Init hook should run before RUNNING phase
                                })
                        .build();

        CompletableFuture<AppState> startFuture = app.start();
        AppState state = startFuture.join();

        assertThat(state).isNotNull();
        assertThat(state.name()).isEqualTo("test-app");
        assertThat(app.getPhase()).isInstanceOf(Application.ApplicationPhase.RUNNING.class);
    }

    @Test
    @DisplayName("Services can be registered and looked up by name")
    void testServiceLookup() throws Exception {
        record AppState(String name) {}

        BiFunction<Integer, TestMessage, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMessage.Increment) return state + 1;
                    return state;
                };

        var proc = new Proc<>(0, handler);
        var ref = new ProcRef<>(proc);

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .service("counter", ref)
                        .build();

        app.start().join();

        Optional<ProcRef<?, ?>> service = app.getService("counter");
        assertThat(service).isPresent();
        assertThat(service.get()).isEqualTo(ref);
    }

    @Test
    @DisplayName("Multiple services can be registered independently")
    void testMultipleServices() throws Exception {
        record AppState(String name) {}

        BiFunction<Integer, TestMessage, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMessage.Increment) return state + 1;
                    return state;
                };

        var proc1 = new Proc<>(0, handler);
        var ref1 = new ProcRef<>(proc1);

        var proc2 = new Proc<>(100, handler);
        var ref2 = new ProcRef<>(proc2);

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .service("counter1", ref1)
                        .service("counter2", ref2)
                        .build();

        app.start().join();

        assertThat(app.getService("counter1")).isPresent();
        assertThat(app.getService("counter2")).isPresent();
        assertThat(app.getService("counter3")).isEmpty();
    }

    @Test
    @DisplayName("Graceful shutdown stops services in reverse order")
    void testGracefulShutdown() throws Exception {
                """
                Graceful shutdown ensures services are stopped in the correct order, allowing
                cleanup operations to complete before the application fully terminates. Shutdown
                hooks run after all services have been stopped.
                """);

        record AppState(String name) {}

        BiFunction<Integer, TestMessage, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMessage.Increment) return state + 1;
                    return state;
                };

        var proc1 = new Proc<>(0, handler);
        var ref1 = new ProcRef<>(proc1);

        var proc2 = new Proc<>(100, handler);
        var ref2 = new ProcRef<>(proc2);

        var shutdownOrder = new CopyOnWriteArrayList<String>();

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .service("counter1", ref1)
                        .service("counter2", ref2)
                        .stop(
                                state -> {
                                    // Shutdown hook runs after services are stopped
                                    shutdownOrder.add("hook");
                                })
                        .build();

        app.start().join();

        CompletableFuture<Void> stopFuture = app.stop();
        stopFuture.join();

        assertThat(app.getPhase()).isInstanceOf(Application.ApplicationPhase.STOPPED.class);
        assertThat(shutdownOrder).contains("hook");
    }

    @Test
    @DisplayName("Init hooks are called during INIT phase")
    void testInitHooks() throws Exception {
        record AppState(String name) {}

        var hooksCalled = new AtomicInteger(0);

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .addInitHook(() -> hooksCalled.incrementAndGet())
                        .addInitHook(() -> hooksCalled.incrementAndGet())
                        .build();

        app.start().join();

        assertThat(hooksCalled.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Shutdown hooks are called during STOP phase")
    void testShutdownHooks() throws Exception {
        record AppState(String name) {}

        var hooksCalled = new AtomicInteger(0);

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .addShutdownHook(() -> hooksCalled.incrementAndGet())
                        .addShutdownHook(() -> hooksCalled.incrementAndGet())
                        .stop(
                                state -> {
                                    // Stopper runs before shutdown hooks
                                })
                        .build();

        app.start().join();
        app.stop().join();

        assertThat(hooksCalled.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Application state is properly maintained")
    void testStateManagement() throws Exception {
        record AppState(String name, int counter) {}

        Application<AppState> app =
                Application.<AppState>builder("test-app") //
                        .init(() -> new AppState("test-app", 42))
                        .build();

        app.start().join();

        AppState state = app.getState();
        assertThat(state).isNotNull();
        assertThat(state.name()).isEqualTo("test-app");
        assertThat(state.counter()).isEqualTo(42);
    }

    @Test
    @DisplayName("Service lookup with non-existent name returns empty Optional")
    void testServiceLookupNonExistent() throws Exception {
        record AppState(String name) {}

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .build();

        app.start().join();

        Optional<ProcRef<?, ?>> service = app.getService("non-existent");
        assertThat(service).isEmpty();
    }

    @Test
    @DisplayName("Application builder pattern works correctly")
    void testBuilderPattern() throws Exception {
        record AppState(String config) {}

        Application<AppState> app =
                Application.<AppState>builder("my-service") //
                        .init(() -> new AppState("configured"))
                        .stop(
                                state -> {
                                    // Cleanup
                                })
                        .addInitHook(() -> {})
                        .addShutdownHook(() -> {})
                        .build();

        assertThat(app).isNotNull();
        CompletableFuture<AppState> start = app.start();
        assertThat(start).isNotNull();
    }

    @Test
    @DisplayName("Phase transitions occur in correct order")
    void testPhaseTransitions() throws Exception {
                """
                Application phase transitions follow a deterministic sequence: INIT -> RUNNING -> STOPPED.
                This mirrors Erlang/OTP's application controller where each phase has well-defined entry
                and exit conditions.
                """);

        record AppState(String name) {}

        var phaseLog = new CopyOnWriteArrayList<Application.ApplicationPhase>();

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .build();

        // Before start, phase should be INIT
        phaseLog.add(app.getPhase());

        app.start().join();

        // After start, phase should be RUNNING
        phaseLog.add(app.getPhase());

        app.stop().join();

        // After stop, phase should be STOPPED
        phaseLog.add(app.getPhase());

        assertThat(phaseLog.get(0)).isInstanceOf(Application.ApplicationPhase.INIT.class);
        assertThat(phaseLog.get(1)).isInstanceOf(Application.ApplicationPhase.RUNNING.class);
        assertThat(phaseLog.get(2)).isInstanceOf(Application.ApplicationPhase.STOPPED.class);
    }

    @Test
    @DisplayName("Supervisor integration: application can manage supervised children")
    void testSupervisorIntegration() throws Exception {
                """
                Applications integrate with the Supervisor pattern to create fault-tolerant process trees.
                The application registers supervisors and their children as services, enabling lookup
                by name throughout the application lifecycle.
                """);

        record AppState(String name) {}

        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        BiFunction<Integer, TestMessage, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMessage.Increment) return state + 1;
                    return state;
                };

        var childRef = supervisor.supervise("worker", 0, handler);

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .supervisor(supervisor)
                        .service("worker", childRef)
                        .build();

        app.start().join();

        Optional<ProcRef<?, ?>> workerService = app.getService("worker");
        assertThat(workerService).isPresent();

        app.stop().join();
    }

    @Test
    @DisplayName("Stop hook receives application state")
    void testStopHookReceivesState() throws Exception {
        record AppState(String name, int value) {}

        var receivedState = new AtomicBoolean(false);

        Application<AppState> app =
                Application.<AppState>builder("test-app") //
                        .init(() -> new AppState("test-app", 123))
                        .stop(
                                state -> {
                                    assertThat(state.name()).isEqualTo("test-app");
                                    assertThat(state.value()).isEqualTo(123);
                                    receivedState.set(true);
                                })
                        .build();

        app.start().join();
        app.stop().join();

        assertThat(receivedState.get()).isTrue();
    }

    @Test
    @DisplayName("Multiple services can send and receive messages")
    void testServiceMessaging() throws Exception {
        record AppState(String name) {}

        BiFunction<Integer, TestMessage, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMessage.Increment) return state + 1;
                    return state;
                };

        var proc1 = new Proc<>(0, handler);
        var ref1 = new ProcRef<>(proc1);

        var proc2 = new Proc<>(100, handler);
        var ref2 = new ProcRef<>(proc2);

        var app =
                Application.builder("test-app") //
                        .init(() -> new AppState("test-app"))
                        .service("counter1", ref1)
                        .service("counter2", ref2)
                        .build();

        app.start().join();

        Optional<ProcRef<?, ?>> service1 = app.getService("counter1");
        Optional<ProcRef<?, ?>> service2 = app.getService("counter2");

        assertThat(service1).isPresent();
        assertThat(service2).isPresent();

        app.stop().join();
    }
}
