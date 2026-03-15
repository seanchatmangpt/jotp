package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
/**
 * Comprehensive test suite for {@link Supervisor}.
 *
 * <p>Tests cover the three restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE), max restart
 * throttling, crash isolation, child lifecycle management, and concurrent failure scenarios.
 * <p><strong>Supervisor Restart Strategies:</strong>
 * <ul>
 *   <li><strong>ONE_FOR_ONE:</strong> Only the crashed child is restarted. Siblings are unaffected.
 *       Best for independent workers.
 *   <li><strong>ONE_FOR_ALL:</strong> All children are restarted when any child crashes. Best for
 *       tightly coupled services where partial state is invalid.
 *   <li><strong>REST_FOR_ONE:</strong> The crashed child and all children started <em>after</em> it
 *       are restarted. Best for hierarchical dependencies where child-N depends on child-1 to
 *       child-N-1.
 * </ul>
 * <p><strong>Max Restarts Throttling:</strong> If a single child crashes more than {@code
 * maxRestarts} times within the {@code window} time period, the supervisor gives up and terminates
 * itself (including all children). This prevents infinite restart loops.
 * <p><strong>Testing Approach:</strong>
 *   <li>Simple message-based state for deterministic behavior
 *   <li>Awaitility for async assertions (wait for conditions with timeout)
 *   <li>Counters and latches to verify callback firing and state transitions
 *   <li>Parameterized tests for strategy variations
 *   <li>Concurrent crash scenarios to stress error handling
 * @see Supervisor
 * @see Proc
 * @see CrashRecovery
 */
@DisplayName("Supervisor: Fault-Tolerant Supervision Tree")
class SupervisorTest {
    /**
     * Test message types for Supervisor tests. Use sealed Record hierarchy for type-safe pattern
     * matching.
     */
    sealed interface TestMsg permits TestMsg.Increment, TestMsg.Crash, TestMsg.Get, TestMsg.Noop {
        record Increment() implements TestMsg {}
        record Crash() implements TestMsg {}
        record Get() implements TestMsg {}
        record Noop() implements TestMsg {}
    }
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);
    // ============================================================================
    // ONE_FOR_ONE STRATEGY TESTS
    @Test
    @DisplayName("ONE_FOR_ONE: Child crash restarts only that child")
    void testOneForOneSingleChildCrash() throws Exception {
        var strategy = Supervisor.Strategy.ONE_FOR_ONE;
        var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));
        var child1Counter = new AtomicInteger(0);
        var child2Counter = new AtomicInteger(0);
        var ref1 =
                supervisor.supervise(
                        "child-1",
                        0,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Crash)
                                throw new RuntimeException("child-1 crash");
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });
        var ref2 =
                        "child-2",
        // Increment both counters
        ref1.tell(new TestMsg.Increment());
        ref2.tell(new TestMsg.Increment());
        Thread.sleep(100);
        // Get initial values
        var state1Before = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state2Before = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1Before).isEqualTo(1);
        assertThat(state2Before).isEqualTo(1);
        // Crash child-1
        ref1.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT)
                .until(() -> !supervisor.isRunning() || ref1.proc().lastError() != null);
        // Child-2 should still be running and responsive
        var state2After = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2After).isEqualTo(2);
        // Child-1 should have restarted (fresh state = 0)
        var state1Restarted = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1Restarted).isEqualTo(0);
        supervisor.shutdown();
    @DisplayName("ONE_FOR_ONE: Multiple independent crashes are isolated")
    void testOneForOneMultipleIndependentCrashes() throws Exception {
        var restartCounts = new AtomicInteger[3];
        for (int i = 0; i < 3; i++) restartCounts[i] = new AtomicInteger(0);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            supervisor.supervise(
                    "child-" + i,
                    0,
                    (state, msg) -> {
                        if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                        if (msg instanceof TestMsg.Increment) return state + 1;
                        return state;
                    });
        }
        // Crash all three independently in sequence
        Thread.sleep(50);
            // Note: In real code we'd get the ref, but here we rely on supervisor reconstructing
        assertThat(supervisor.fatalError()).isNull();
    // ONE_FOR_ALL STRATEGY TESTS
    @DisplayName("ONE_FOR_ALL: Child crash restarts ALL children")
    void testOneForAllCrashRestartsAll() throws Exception {
        var strategy = Supervisor.Strategy.ONE_FOR_ALL;
        var child1Restarts = new AtomicInteger(0);
        var child2Restarts = new AtomicInteger(0);
        var child3Restarts = new AtomicInteger(0);
                        100,
        var ref3 =
                        "child-3",
                        200,
        // Increment to establish baseline
        ref3.tell(new TestMsg.Increment());
        var state3Before = ref3.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        // Crash child-1 → ALL children should restart
        await().atMost(AWAIT_TIMEOUT).until(() -> ref1.proc().lastError() != null);
        // After ONE_FOR_ALL restart, all states should be reset to initial
        var state1After = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state3After = ref3.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1After).isEqualTo(0);
        assertThat(state2After).isEqualTo(100);
        assertThat(state3After).isEqualTo(200);
    // REST_FOR_ONE STRATEGY TESTS
    @DisplayName("REST_FOR_ONE: Crash restarts child and later children only")
    void testRestForOneCrashRestartsFromPosition() throws Exception {
        var strategy = Supervisor.Strategy.REST_FOR_ONE;
                                throw new RuntimeException("child-2 crash");
        // Increment all
        // Crash child-2 (middle child)
        // Expected: child-2 and child-3 restart; child-1 unaffected
        ref2.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT).until(() -> ref2.proc().lastError() != null);
        // Child-1 should maintain its state (not restarted)
        assertThat(state1After).isEqualTo(state1Before);
        // Child-2 and child-3 should be reset
    // MAX RESTARTS THROTTLING TESTS
    @DisplayName("Max restarts exceeded: Supervisor terminates itself")
    void testMaxRestartsExceededTerminatesSupervisor() throws Exception {
        var maxRestarts = 2;
        var window = Duration.ofSeconds(10);
        var supervisor = new Supervisor(strategy, maxRestarts, window);
        var ref =
                        "crash-child",
                            throw new RuntimeException("always crash");
        // Trigger crashes
        ref.tell(new TestMsg.Noop());
        // Supervisor should detect max restarts exceeded and terminate
                .until(() -> !supervisor.isRunning() || supervisor.fatalError() != null);
        assertThat(supervisor.isRunning()).isFalse();
        assertThat(supervisor.fatalError()).isNotNull();
    @DisplayName("Restart window: Crashes outside window don't count")
    void testRestartWindowTracking() throws Exception {
        var window = Duration.ofMillis(200); // Short window
        var crashCount = new AtomicInteger(0);
                            crashCount.incrementAndGet();
                            throw new RuntimeException("crash");
        // Crash 1: now
        // Crash 2: within window
        // Crash 3: still within window (should trigger limit)
        Thread.sleep(250); // Wait for window to expire
        // Crash 4: outside window (should not count toward limit)
        // Supervisor should still be running if crash 4 is outside window
        await().during(Duration.ofMillis(100)).until(() -> supervisor.isRunning());
    // CHILD RE-REGISTRATION & LIFECYCLE TESTS
    @DisplayName("After restart: Restarted child accepts new messages")
    void testRestarterChildAcceptsMessages() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
                        "child",
                            if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
        ref.tell(new TestMsg.Increment());
        var state1 = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1).isEqualTo(1);
        // Crash
        ref.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT).until(() -> ref.proc().lastError() != null);
        // Restarted child should accept messages
        var state2 = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2).isEqualTo(1); // Fresh state + 1
    @DisplayName("Shutdown: Children terminated in reverse registration order (LIFO)")
    void testShutdownOrderIsLifo() throws Exception {
        var shutdownOrder = new ArrayList<String>();
        var lock = new Object();
                        if (msg instanceof TestMsg.Noop) {
                            synchronized (lock) {
                                shutdownOrder.add("child-" + idx);
                            }
                        }
        // Verify supervisor is no longer running
    // CONCURRENT CRASH SCENARIOS
    @DisplayName("Concurrent crashes: Multiple children crash simultaneously")
    void testConcurrentChildCrashes() throws Exception {
        var supervisor =
                new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));
        var childCount = 5;
        var refs = new ProcRef[childCount];
        for (int i = 0; i < childCount; i++) {
            refs[i] =
                    supervisor.supervise(
                            "child-" + i,
                            i * 100,
                            (state, msg) -> {
                                if (msg instanceof TestMsg.Crash)
                                    throw new RuntimeException("child-" + idx);
                                if (msg instanceof TestMsg.Increment) return state + 1;
                                return state;
                            });
        // Trigger concurrent crashes via virtual threads
        var threads = new Thread[childCount];
            threads[i] =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            Thread.sleep(idx * 10); // Stagger slightly
                                            refs[idx].tell(new TestMsg.Crash());
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
        for (Thread t : threads) t.join();
        Thread.sleep(200);
        // All children should be restarted and responsive
            var state = refs[i].ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
            assertThat(state).isEqualTo(i * 100);
    // PARAMETERIZED TESTS (All strategies)
    @ParameterizedTest
    @EnumSource(Supervisor.Strategy.class)
    @DisplayName("Strategy: Supervisor starts and supervises children")
    void testAllStrategiesStartChildren(Supervisor.Strategy strategy) throws Exception {
        var ref1 = supervisor.supervise("child-1", 0, (state, msg) -> state);
        var ref2 = supervisor.supervise("child-2", 0, (state, msg) -> state);
        assertThat(supervisor.isRunning()).isTrue();
        assertThat(ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS)).isEqualTo(0);
    @DisplayName("Strategy: Supervisor shutdown completes without errors")
    void testAllStrategiesShutdownCleanly(Supervisor.Strategy strategy) throws Exception {
            supervisor.supervise("child-" + i, 0, (state, msg) -> state);
    // HELPER METHODS
    private static void assertMessageReceived(ProcRef ref, Object message, Duration timeout)
            throws Exception {
        await().atMost(timeout)
                .until(
                        () -> {
                            try {
                                ref.ask(message).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                                return true;
                            } catch (Exception e) {
                                return false;
    private static void crashProc(ProcRef ref) {
}
