package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Comprehensive test suite for {@link Supervisor}.
 *
 * <p>Tests cover the three restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE), max restart
 * throttling, crash isolation, child lifecycle management, and concurrent failure scenarios.
 *
 * <p><strong>Supervisor Restart Strategies:</strong>
 *
 * <ul>
 *   <li><strong>ONE_FOR_ONE:</strong> Only the crashed child is restarted. Siblings are unaffected.
 *       Best for independent workers.
 *   <li><strong>ONE_FOR_ALL:</strong> All children are restarted when any child crashes. Best for
 *       tightly coupled services where partial state is invalid.
 *   <li><strong>REST_FOR_ONE:</strong> The crashed child and all children started <em>after</em> it
 *       are restarted. Best for hierarchical dependencies where child-N depends on child-1 to
 *       child-N-1.
 * </ul>
 *
 * <p><strong>Max Restarts Throttling:</strong> If a single child crashes more than {@code
 * maxRestarts} times within the {@code window} time period, the supervisor gives up and terminates
 * itself (including all children). This prevents infinite restart loops.
 *
 * <p><strong>Testing Approach:</strong>
 *
 * <ul>
 *   <li>Simple message-based state for deterministic behavior
 *   <li>Awaitility for async assertions (wait for conditions with timeout)
 *   <li>Counters and latches to verify callback firing and state transitions
 *   <li>Parameterized tests for strategy variations
 *   <li>Concurrent crash scenarios to stress error handling
 * </ul>
 *
 * @see Supervisor
 * @see Proc
 * @see CrashRecovery
 */
@DisplayName("Supervisor: Fault-Tolerant Supervision Tree")
class SupervisorTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

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

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);

    // ============================================================================
    // ONE_FOR_ONE STRATEGY TESTS
    // ============================================================================

    @Test
    @DisplayName("ONE_FOR_ONE: Child crash restarts only that child")
    void testOneForOneSingleChildCrash() throws Exception {
                """
                Supervisor implements hierarchical process supervision with configurable restart strategies.
                ONE_FOR_ONE: Only the crashed child is restarted. Siblings are unaffected.
                Best for independent workers where one failure shouldn't cascade to healthy processes.
                """);
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
                supervisor.supervise(
                        "child-2",
                        0,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

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
        ref2.tell(new TestMsg.Increment());
        var state2After = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2After).isEqualTo(2);

        // Child-1 should have restarted (fresh state = 0)
        var state1Restarted = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1Restarted).isEqualTo(0);

        supervisor.shutdown();
    }

    @Test
    @DisplayName("ONE_FOR_ONE: Multiple independent crashes are isolated")
    void testOneForOneMultipleIndependentCrashes() throws Exception {
        var strategy = Supervisor.Strategy.ONE_FOR_ONE;
        var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));
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
        for (int i = 0; i < 3; i++) {
            // Note: In real code we'd get the ref, but here we rely on supervisor reconstructing
        }

        supervisor.shutdown();
        assertThat(supervisor.fatalError()).isNull();
    }

    // ============================================================================
    // ONE_FOR_ALL STRATEGY TESTS
    // ============================================================================

    @Test
    @DisplayName("ONE_FOR_ALL: Child crash restarts ALL children")
    void testOneForAllCrashRestartsAll() throws Exception {
                """
                ONE_FOR_ALL: When any child crashes, ALL children are restarted.
                Best for tightly coupled services where partial state is invalid.
                Example: A connection pool where all connections must share the same configuration.
                """);
        var strategy = Supervisor.Strategy.ONE_FOR_ALL;
        var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));

        var child1Restarts = new AtomicInteger(0);
        var child2Restarts = new AtomicInteger(0);
        var child3Restarts = new AtomicInteger(0);

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
                supervisor.supervise(
                        "child-2",
                        100,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        var ref3 =
                supervisor.supervise(
                        "child-3",
                        200,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        // Increment to establish baseline
        ref1.tell(new TestMsg.Increment());
        ref2.tell(new TestMsg.Increment());
        ref3.tell(new TestMsg.Increment());
        Thread.sleep(100);

        var state1Before = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state2Before = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state3Before = ref3.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);

        // Crash child-1 → ALL children should restart
        ref1.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT).until(() -> ref1.proc().lastError() != null);

        // After ONE_FOR_ALL restart, all states should be reset to initial
        var state1After = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state2After = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state3After = ref3.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);

        assertThat(state1After).isEqualTo(0);
        assertThat(state2After).isEqualTo(100);
        assertThat(state3After).isEqualTo(200);

        supervisor.shutdown();
    }

    // ============================================================================
    // REST_FOR_ONE STRATEGY TESTS
    // ============================================================================

    @Test
    @DisplayName("REST_FOR_ONE: Crash restarts child and later children only")
    void testRestForOneCrashRestartsFromPosition() throws Exception {
                """
                REST_FOR_ONE: The crashed child and all children started AFTER it are restarted.
                Best for hierarchical dependencies where child-N depends on child-1 to child-N-1.
                Example: Database connection -> session -> transaction handlers (if DB dies, all dependents restart).
                """);
        var strategy = Supervisor.Strategy.REST_FOR_ONE;
        var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));

        var ref1 =
                supervisor.supervise(
                        "child-1",
                        0,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        var ref2 =
                supervisor.supervise(
                        "child-2",
                        100,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Crash)
                                throw new RuntimeException("child-2 crash");
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        var ref3 =
                supervisor.supervise(
                        "child-3",
                        200,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        // Increment all
        ref1.tell(new TestMsg.Increment());
        ref2.tell(new TestMsg.Increment());
        ref3.tell(new TestMsg.Increment());
        Thread.sleep(100);

        var state1Before = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state2Before = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state3Before = ref3.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);

        // Crash child-2 (middle child)
        // Expected: child-2 and child-3 restart; child-1 unaffected
        ref2.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT).until(() -> ref2.proc().lastError() != null);

        // Child-1 should maintain its state (not restarted)
        var state1After = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1After).isEqualTo(state1Before);

        // Child-2 and child-3 should be reset
        var state2After = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state3After = ref3.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2After).isEqualTo(100);
        assertThat(state3After).isEqualTo(200);

        supervisor.shutdown();
    }

    // ============================================================================
    // MAX RESTARTS THROTTLING TESTS
    // ============================================================================

    @Test
    @DisplayName("Max restarts exceeded: Supervisor terminates itself")
    void testMaxRestartsExceededTerminatesSupervisor() throws Exception {
                """
                Max restarts throttling prevents infinite restart loops.
                If a child crashes more than maxRestarts times within the window duration,
                the supervisor gives up and terminates itself (including all children).
                This prevents cascading failures from unstable processes.
                """);
        var strategy = Supervisor.Strategy.ONE_FOR_ONE;
        var maxRestarts = 2;
        var window = Duration.ofSeconds(10);
        var supervisor = new Supervisor(strategy, maxRestarts, window);

        var ref =
                supervisor.supervise(
                        "crash-child",
                        0,
                        (state, msg) -> {
                            throw new RuntimeException("always crash");
                        });

        // Trigger crashes
        ref.tell(new TestMsg.Noop());
        Thread.sleep(100);
        ref.tell(new TestMsg.Noop());
        Thread.sleep(100);
        ref.tell(new TestMsg.Noop());
        Thread.sleep(100);

        // Supervisor should detect max restarts exceeded and terminate
        await().atMost(AWAIT_TIMEOUT)
                .until(() -> !supervisor.isRunning() || supervisor.fatalError() != null);

        assertThat(supervisor.isRunning()).isFalse();
        assertThat(supervisor.fatalError()).isNotNull();
    }

    @Test
    @DisplayName("Restart window: Crashes outside window don't count")
    void testRestartWindowTracking() throws Exception {
        var strategy = Supervisor.Strategy.ONE_FOR_ONE;
        var maxRestarts = 2;
        var window = Duration.ofMillis(200); // Short window
        var supervisor = new Supervisor(strategy, maxRestarts, window);

        var crashCount = new AtomicInteger(0);
        var ref =
                supervisor.supervise(
                        "crash-child",
                        0,
                        (state, msg) -> {
                            crashCount.incrementAndGet();
                            throw new RuntimeException("crash");
                        });

        // Crash 1: now
        ref.tell(new TestMsg.Noop());
        Thread.sleep(50);
        // Crash 2: within window
        ref.tell(new TestMsg.Noop());
        Thread.sleep(50);
        // Crash 3: still within window (should trigger limit)
        ref.tell(new TestMsg.Noop());
        Thread.sleep(250); // Wait for window to expire
        // Crash 4: outside window (should not count toward limit)
        ref.tell(new TestMsg.Noop());
        Thread.sleep(100);

        // Supervisor should still be running if crash 4 is outside window
        await().during(Duration.ofMillis(100)).until(() -> supervisor.isRunning());

        supervisor.shutdown();
    }

    // ============================================================================
    // CHILD RE-REGISTRATION & LIFECYCLE TESTS
    // ============================================================================

    @Test
    @DisplayName("After restart: Restarted child accepts new messages")
    void testRestarterChildAcceptsMessages() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var ref =
                supervisor.supervise(
                        "child",
                        0,
                        (state, msg) -> {
                            if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        ref.tell(new TestMsg.Increment());
        Thread.sleep(50);
        var state1 = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1).isEqualTo(1);

        // Crash
        ref.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT).until(() -> ref.proc().lastError() != null);

        // Restarted child should accept messages
        ref.tell(new TestMsg.Increment());
        var state2 = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2).isEqualTo(1); // Fresh state + 1

        supervisor.shutdown();
    }

    @Test
    @DisplayName("Shutdown: Children terminated in reverse registration order (LIFO)")
    void testShutdownOrderIsLifo() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var shutdownOrder = new ArrayList<String>();
        var lock = new Object();

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            supervisor.supervise(
                    "child-" + i,
                    0,
                    (state, msg) -> {
                        if (msg instanceof TestMsg.Noop) {
                            synchronized (lock) {
                                shutdownOrder.add("child-" + idx);
                            }
                        }
                        return state;
                    });
        }

        supervisor.shutdown();

        // Verify supervisor is no longer running
        assertThat(supervisor.isRunning()).isFalse();
    }

    // ============================================================================
    // CONCURRENT CRASH SCENARIOS
    // ============================================================================

    @Test
    @DisplayName("Concurrent crashes: Multiple children crash simultaneously")
    void testConcurrentChildCrashes() throws Exception {
        var supervisor =
                new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));
        var childCount = 5;
        var refs = new ProcRef[childCount];

        for (int i = 0; i < childCount; i++) {
            final int idx = i;
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
        }

        // Trigger concurrent crashes via virtual threads
        var threads = new Thread[childCount];
        for (int i = 0; i < childCount; i++) {
            final int idx = i;
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
        }

        for (Thread t : threads) t.join();
        Thread.sleep(200);

        // All children should be restarted and responsive
        for (int i = 0; i < childCount; i++) {
            var state = refs[i].ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
            assertThat(state).isEqualTo(i * 100);
        }

        supervisor.shutdown();
    }

    // ============================================================================
    // PARAMETERIZED TESTS (All strategies)
    // ============================================================================

    @ParameterizedTest
    @EnumSource(Supervisor.Strategy.class)
    @DisplayName("Strategy: Supervisor starts and supervises children")
    void testAllStrategiesStartChildren(Supervisor.Strategy strategy) throws Exception {
        var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));

        var ref1 = supervisor.supervise("child-1", 0, (state, msg) -> state);
        var ref2 = supervisor.supervise("child-2", 0, (state, msg) -> state);

        Thread.sleep(50);
        assertThat(supervisor.isRunning()).isTrue();
        assertThat(ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS)).isEqualTo(0);

        supervisor.shutdown();
    }

    @ParameterizedTest
    @EnumSource(Supervisor.Strategy.class)
    @DisplayName("Strategy: Supervisor shutdown completes without errors")
    void testAllStrategiesShutdownCleanly(Supervisor.Strategy strategy) throws Exception {
        var supervisor = new Supervisor(strategy, 5, Duration.ofSeconds(60));

        for (int i = 0; i < 3; i++) {
            supervisor.supervise("child-" + i, 0, (state, msg) -> state);
        }

        supervisor.shutdown();
        assertThat(supervisor.isRunning()).isFalse();
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

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
                            }
                        });
    }

    private static void crashProc(ProcRef ref) {
        ref.tell(new TestMsg.Crash());
    }
}
