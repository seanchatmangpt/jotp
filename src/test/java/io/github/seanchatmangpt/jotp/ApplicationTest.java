package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Comprehensive test suite for {@link Application}.
 *
 * <p>Tests cover application lifecycle management, startup sequence, service registration, graceful
 * shutdown, and hook execution.
 * <p><strong>Application Lifecycle:</strong>
 * <ul>
 *   <li><strong>INIT:</strong> Run init hooks, initialize state
 *   <li><strong>START:</strong> Start services and supervisors
 *   <li><strong>RUNNING:</strong> Normal operation
 *   <li><strong>STOP:</strong> Stop services, shut down supervisors, run shutdown hooks
 *   <li><strong>STOPPED:</strong> Shutdown complete
 * </ul>
 * @see Application
 * @see ApplicationPhase
 */
@DisplayName("Application: Lifecycle Orchestrator")
class ApplicationTest {
    sealed interface TestMessage permits TestMessage.Increment, TestMessage.Get {
        record Increment() implements TestMessage {}
        record Get() implements TestMessage {}
    }
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);
    // ============================================================================
    // LIFECYCLE TESTS
    @Test
    @DisplayName("Application startup sequence: INIT -> START -> RUNNING")
    void testStartupSequence() throws Exception {
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
                                    // Init hook should run before RUNNING phase
                        .build();
        CompletableFuture<AppState> startFuture = app.start();
        AppState state = startFuture.join();
        assertThat(state).isNotNull();
        assertThat(state.name()).isEqualTo("test-app");
        assertThat(app.getPhase()).isInstanceOf(Application.ApplicationPhase.RUNNING.class);
    @DisplayName("Services can be registered and looked up by name")
    void testServiceLookup() throws Exception {
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
        app.start().join();
        Optional<ProcRef<?, ?>> service = app.getService("counter");
        assertThat(service).isPresent();
        assertThat(service.get()).isEqualTo(ref);
    @DisplayName("Multiple services can be registered independently")
    void testMultipleServices() throws Exception {
        var proc1 = new Proc<>(0, handler);
        var ref1 = new ProcRef<>(proc1);
        var proc2 = new Proc<>(100, handler);
        var ref2 = new ProcRef<>(proc2);
                        .service("counter1", ref1)
                        .service("counter2", ref2)
        assertThat(app.getService("counter1")).isPresent();
        assertThat(app.getService("counter2")).isPresent();
        assertThat(app.getService("counter3")).isEmpty();
    @DisplayName("Graceful shutdown stops services in reverse order")
    void testGracefulShutdown() throws Exception {
        var shutdownOrder = new CopyOnWriteArrayList<String>();
                        .stop(
                                state -> {
                                    // Shutdown hook runs after services are stopped
                                    shutdownOrder.add("hook");
        CompletableFuture<Void> stopFuture = app.stop();
        stopFuture.join();
        assertThat(app.getPhase()).isInstanceOf(Application.ApplicationPhase.STOPPED.class);
        assertThat(shutdownOrder).contains("hook");
    @DisplayName("Init hooks are called during INIT phase")
    void testInitHooks() throws Exception {
        var hooksCalled = new AtomicInteger(0);
                        .addInitHook(() -> hooksCalled.incrementAndGet())
        assertThat(hooksCalled.get()).isEqualTo(2);
    @DisplayName("Shutdown hooks are called during STOP phase")
    void testShutdownHooks() throws Exception {
                        .addShutdownHook(() -> hooksCalled.incrementAndGet())
                                    // Stopper runs before shutdown hooks
        app.stop().join();
    @DisplayName("Application state is properly maintained")
    void testStateManagement() throws Exception {
        record AppState(String name, int counter) {}
                        .init(() -> new AppState("test-app", 42))
        AppState state = app.getState();
        assertThat(state.counter()).isEqualTo(42);
    @DisplayName("Service lookup with non-existent name returns empty Optional")
    void testServiceLookupNonExistent() throws Exception {
        Optional<ProcRef<?, ?>> service = app.getService("non-existent");
        assertThat(service).isEmpty();
    @DisplayName("Application builder pattern works correctly")
    void testBuilderPattern() throws Exception {
        record AppState(String config) {}
                Application.<AppState>builder("my-service") //
                        .init(() -> new AppState("configured"))
                                    // Cleanup
                        .addInitHook(() -> {})
                        .addShutdownHook(() -> {})
        assertThat(app).isNotNull();
        CompletableFuture<AppState> start = app.start();
        assertThat(start).isNotNull();
    @DisplayName("Phase transitions occur in correct order")
    void testPhaseTransitions() throws Exception {
        var phaseLog = new CopyOnWriteArrayList<Application.ApplicationPhase>();
        // Before start, phase should be INIT
        phaseLog.add(app.getPhase());
        // After start, phase should be RUNNING
        // After stop, phase should be STOPPED
        assertThat(phaseLog.get(0)).isInstanceOf(Application.ApplicationPhase.INIT.class);
        assertThat(phaseLog.get(1)).isInstanceOf(Application.ApplicationPhase.RUNNING.class);
        assertThat(phaseLog.get(2)).isInstanceOf(Application.ApplicationPhase.STOPPED.class);
    @DisplayName("Supervisor integration: application can manage supervised children")
    void testSupervisorIntegration() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var childRef = supervisor.supervise("worker", 0, handler);
                        .supervisor(supervisor)
                        .service("worker", childRef)
        Optional<ProcRef<?, ?>> workerService = app.getService("worker");
        assertThat(workerService).isPresent();
    @DisplayName("Stop hook receives application state")
    void testStopHookReceivesState() throws Exception {
        record AppState(String name, int value) {}
        var receivedState = new AtomicBoolean(false);
                        .init(() -> new AppState("test-app", 123))
                                    assertThat(state.name()).isEqualTo("test-app");
                                    assertThat(state.value()).isEqualTo(123);
                                    receivedState.set(true);
        assertThat(receivedState.get()).isTrue();
    @DisplayName("Multiple services can send and receive messages")
    void testServiceMessaging() throws Exception {
        Optional<ProcRef<?, ?>> service1 = app.getService("counter1");
        Optional<ProcRef<?, ?>> service2 = app.getService("counter2");
        assertThat(service1).isPresent();
        assertThat(service2).isPresent();
}
