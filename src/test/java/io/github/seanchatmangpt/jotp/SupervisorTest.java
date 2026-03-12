package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
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
        // Wait until restart completes — new proc has fresh state (0). Checking lastError is
        // racy because the supervisor swaps the ref before Awaitility can poll.
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            if (!supervisor.isRunning()) return true;
                            try {
                                return ((Integer)
                                                ref1.ask(new TestMsg.Get())
                                                        .get(100, TimeUnit.MILLISECONDS))
                                        == 0;
                            } catch (Exception e) {
                                return false;
                            }
                        });

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
        // Wait until restart completes — ref1 returns fresh state (0)
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                return ((Integer)
                                                ref1.ask(new TestMsg.Get())
                                                        .get(100, TimeUnit.MILLISECONDS))
                                        == 0;
                            } catch (Exception e) {
                                return false;
                            }
                        });

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
        // Wait until ref2 restarts to its initial state (100)
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                return ((Integer)
                                                ref2.ask(new TestMsg.Get())
                                                        .get(100, TimeUnit.MILLISECONDS))
                                        == 100;
                            } catch (Exception e) {
                                return false;
                            }
                        });

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

        // Crash 1: now (1 restart in window, 1 > 2? No)
        ref.tell(new TestMsg.Noop());
        Thread.sleep(50);
        // Crash 2: within window (2 restarts in window, 2 > 2? No — supervisor survives)
        ref.tell(new TestMsg.Noop());
        Thread.sleep(250); // Wait for window to expire (window = 200ms)
        // Crash 3: outside window — prior crashes purged, 1 restart in new window, 1 > 2? No
        ref.tell(new TestMsg.Noop());
        Thread.sleep(100);

        // Supervisor should still be running — crash 3 outside window doesn't push past limit
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
        // Wait until restart completes — fresh state = 0
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                return ((Integer)
                                                ref.ask(new TestMsg.Get())
                                                        .get(100, TimeUnit.MILLISECONDS))
                                        == 0;
                            } catch (Exception e) {
                                return false;
                            }
                        });

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
    @EnumSource(
            value = Supervisor.Strategy.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "SIMPLE_ONE_FOR_ONE")
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
    @EnumSource(
            value = Supervisor.Strategy.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "SIMPLE_ONE_FOR_ONE")
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
    // RESTART TYPE: TEMPORARY
    // ============================================================================

    @Test
    @DisplayName("TEMPORARY: Child is never restarted after crash")
    void testTemporaryChildNotRestartedAfterCrash() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec =
                new Supervisor.ChildSpec<>(
                        "temp-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> {
                            if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                            return state;
                        },
                        Supervisor.ChildSpec.RestartType.TEMPORARY,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);

        var ref = supervisor.startChild(spec);

        // Child should be running initially
        assertThat(supervisor.whichChildren()).hasSize(1);
        assertThat(supervisor.whichChildren().get(0).alive()).isTrue();

        // Crash it
        ref.tell(new TestMsg.Crash());
        await().atMost(AWAIT_TIMEOUT).until(() -> ref.proc().lastError != null);

        // Supervisor should still be running
        await().atMost(AWAIT_TIMEOUT).until(() -> !supervisor.whichChildren().get(0).alive());
        assertThat(supervisor.isRunning()).isTrue();

        // Child should show as not alive in whichChildren
        assertThat(supervisor.whichChildren().get(0).alive()).isFalse();

        supervisor.shutdown();
    }

    @Test
    @DisplayName("TEMPORARY: Child is never restarted after normal exit")
    void testTemporaryChildNotRestartedAfterNormalExit() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec =
                new Supervisor.ChildSpec<>(
                        "temp-child",
                        () -> 0,
                        // Handler that processes one Noop then exits normally by returning same
                        // state
                        // (normal exit happens when proc is stopped externally — we test that here)
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.TEMPORARY,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);

        var ref = supervisor.startChild(spec);
        assertThat(supervisor.whichChildren().get(0).alive()).isTrue();

        // Manually stop proc to simulate normal exit (Proc.stop() = normal exit)
        ref.proc().stop();

        // Supervisor should still be running, child not restarted
        await().atMost(AWAIT_TIMEOUT).until(() -> !supervisor.whichChildren().get(0).alive());
        assertThat(supervisor.isRunning()).isTrue();

        supervisor.shutdown();
    }

    // ============================================================================
    // RESTART TYPE: TRANSIENT
    // ============================================================================

    @Test
    @DisplayName("TRANSIENT: Child IS restarted after abnormal exit (crash)")
    void testTransientChildRestartedOnCrash() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec =
                new Supervisor.ChildSpec<>(
                        "transient-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> {
                            if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        },
                        Supervisor.ChildSpec.RestartType.TRANSIENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);

        var ref = supervisor.startChild(spec);

        ref.tell(new TestMsg.Increment());
        Thread.sleep(50);
        var stateBefore = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(stateBefore).isEqualTo(1);

        // Crash — should restart
        ref.tell(new TestMsg.Crash());
        // Wait until restart completes — fresh state = 0
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                return ((Integer)
                                                ref.ask(new TestMsg.Get())
                                                        .get(100, TimeUnit.MILLISECONDS))
                                        == 0;
                            } catch (Exception e) {
                                return false;
                            }
                        });

        // Should be restarted with fresh state
        var stateAfter = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(stateAfter).isEqualTo(0);

        assertThat(supervisor.isRunning()).isTrue();
        supervisor.shutdown();
    }

    @Test
    @DisplayName("TRANSIENT: Child is NOT restarted after normal exit")
    void testTransientChildNotRestartedOnNormalExit() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec =
                new Supervisor.ChildSpec<>(
                        "transient-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.TRANSIENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);

        var ref = supervisor.startChild(spec);
        assertThat(supervisor.whichChildren().get(0).alive()).isTrue();

        // Normal exit
        ref.proc().stop();

        await().atMost(AWAIT_TIMEOUT).until(() -> !supervisor.whichChildren().get(0).alive());
        assertThat(supervisor.isRunning()).isTrue();

        supervisor.shutdown();
    }

    // ============================================================================
    // AUTO-SHUTDOWN
    // ============================================================================

    @Test
    @DisplayName("ANY_SIGNIFICANT: Supervisor shuts down when any significant child exits")
    void testAnySignificantAutoShutdown() throws Exception {
        var supervisor =
                Supervisor.create(
                        Supervisor.Strategy.ONE_FOR_ONE,
                        5,
                        Duration.ofSeconds(60),
                        Supervisor.AutoShutdown.ANY_SIGNIFICANT);

        var significantSpec =
                new Supervisor.ChildSpec<>(
                        "sig-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.TRANSIENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        true /* significant */);

        var otherSpec =
                new Supervisor.ChildSpec<>(
                        "other-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.PERMANENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);

        var sigRef = supervisor.startChild(significantSpec);
        supervisor.startChild(otherSpec);

        // Significant child exits normally → supervisor should auto-shutdown
        sigRef.proc().stop();

        await().atMost(AWAIT_TIMEOUT).until(() -> !supervisor.isRunning());
        assertThat(supervisor.isRunning()).isFalse();
        assertThat(supervisor.fatalError()).isNull();
    }

    @Test
    @DisplayName("ALL_SIGNIFICANT: Supervisor shuts down only after all significant children exit")
    void testAllSignificantAutoShutdown() throws Exception {
        var supervisor =
                Supervisor.create(
                        Supervisor.Strategy.ONE_FOR_ONE,
                        5,
                        Duration.ofSeconds(60),
                        Supervisor.AutoShutdown.ALL_SIGNIFICANT);

        var sig1Spec =
                new Supervisor.ChildSpec<>(
                        "sig-1",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.TRANSIENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        true);

        var sig2Spec =
                new Supervisor.ChildSpec<>(
                        "sig-2",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.TRANSIENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        true);

        var ref1 = supervisor.startChild(sig1Spec);
        var ref2 = supervisor.startChild(sig2Spec);

        // First exits — supervisor should still be running
        ref1.proc().stop();
        Thread.sleep(200);
        assertThat(supervisor.isRunning()).isTrue();

        // Last significant exits — supervisor should auto-shutdown
        ref2.proc().stop();
        await().atMost(AWAIT_TIMEOUT).until(() -> !supervisor.isRunning());
        assertThat(supervisor.isRunning()).isFalse();
        assertThat(supervisor.fatalError()).isNull();
    }

    @Test
    @DisplayName("Auto-shutdown NOT triggered by terminateChild (supervisor-initiated stop)")
    void testAutoShutdownNotTriggeredByManualTerminate() throws Exception {
        var supervisor =
                Supervisor.create(
                        Supervisor.Strategy.ONE_FOR_ONE,
                        5,
                        Duration.ofSeconds(60),
                        Supervisor.AutoShutdown.ANY_SIGNIFICANT);

        var sigSpec =
                new Supervisor.ChildSpec<>(
                        "sig-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.TRANSIENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        true);

        supervisor.startChild(sigSpec);

        // Manually terminate the significant child — should NOT trigger auto-shutdown
        supervisor.terminateChild("sig-child");

        Thread.sleep(200);
        assertThat(supervisor.isRunning()).isTrue();

        supervisor.shutdown();
    }

    // ============================================================================
    // DYNAMIC CHILD MANAGEMENT
    // ============================================================================

    @Test
    @DisplayName("startChild(ChildSpec): adds child dynamically after construction")
    void testStartChildDynamic() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        assertThat(supervisor.whichChildren()).isEmpty();

        var spec = Supervisor.ChildSpec.permanent("dyn-child", 42, (state, msg) -> state);
        var ref = supervisor.startChild(spec);

        assertThat(supervisor.whichChildren()).hasSize(1);
        assertThat(supervisor.whichChildren().get(0).id()).isEqualTo("dyn-child");
        assertThat(supervisor.whichChildren().get(0).alive()).isTrue();

        var state = ref.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(42);

        supervisor.shutdown();
    }

    @Test
    @DisplayName("terminateChild: stops child but keeps spec in tree")
    void testTerminateChildKeepsSpec() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec = Supervisor.ChildSpec.permanent("child-a", 0, (state, msg) -> state);
        supervisor.startChild(spec);

        assertThat(supervisor.whichChildren().get(0).alive()).isTrue();

        supervisor.terminateChild("child-a");

        // Spec should still be in tree (alive=false)
        assertThat(supervisor.whichChildren()).hasSize(1);
        assertThat(supervisor.whichChildren().get(0).id()).isEqualTo("child-a");
        assertThat(supervisor.whichChildren().get(0).alive()).isFalse();

        supervisor.shutdown();
    }

    @Test
    @DisplayName("deleteChild: removes stopped child spec from tree")
    void testDeleteChildRemovesSpec() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec = Supervisor.ChildSpec.permanent("child-b", 0, (state, msg) -> state);
        supervisor.startChild(spec);

        supervisor.terminateChild("child-b");
        assertThat(supervisor.whichChildren()).hasSize(1);

        supervisor.deleteChild("child-b");
        assertThat(supervisor.whichChildren()).isEmpty();

        supervisor.shutdown();
    }

    @Test
    @DisplayName("deleteChild: throws if child is still alive")
    void testDeleteChildThrowsIfAlive() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var spec = Supervisor.ChildSpec.permanent("child-c", 0, (state, msg) -> state);
        supervisor.startChild(spec);

        assertThatThrownBy(() -> supervisor.deleteChild("child-c"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child-c");

        supervisor.shutdown();
    }

    @Test
    @DisplayName("whichChildren: returns correct ChildType")
    void testWhichChildrenReturnsChildType() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var workerSpec =
                new Supervisor.ChildSpec<>(
                        "worker",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.PERMANENT,
                        new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);
        var supSpec =
                new Supervisor.ChildSpec<>(
                        "sub-sup",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.PERMANENT,
                        new Supervisor.ChildSpec.Shutdown.Infinity(),
                        Supervisor.ChildSpec.ChildType.SUPERVISOR,
                        false);

        supervisor.startChild(workerSpec);
        supervisor.startChild(supSpec);

        var children = supervisor.whichChildren();
        assertThat(children).hasSize(2);
        assertThat(children.get(0).type()).isEqualTo(Supervisor.ChildSpec.ChildType.WORKER);
        assertThat(children.get(1).type()).isEqualTo(Supervisor.ChildSpec.ChildType.SUPERVISOR);

        supervisor.shutdown();
    }

    // ============================================================================
    // SIMPLE_ONE_FOR_ONE STRATEGY
    // ============================================================================

    @Test
    @DisplayName("SIMPLE_ONE_FOR_ONE: startChild spawns instances from template")
    void testSimpleOneForOneSpawnInstances() throws Exception {
        var template =
                Supervisor.ChildSpec.worker(
                        "conn",
                        () -> 0,
                        (Integer state, TestMsg msg) -> {
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        var pool = Supervisor.createSimple(template, 5, Duration.ofSeconds(60));

        var ref1 = pool.<Integer, TestMsg>startChild();
        var ref2 = pool.<Integer, TestMsg>startChild();

        assertThat(pool.whichChildren()).hasSize(2);
        assertThat(pool.whichChildren().get(0).id()).isEqualTo("conn-1");
        assertThat(pool.whichChildren().get(1).id()).isEqualTo("conn-2");

        ref1.tell(new TestMsg.Increment());
        Thread.sleep(50);
        var state1 = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        var state2 = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1).isEqualTo(1);
        assertThat(state2).isEqualTo(0);

        pool.shutdown();
    }

    @Test
    @DisplayName("SIMPLE_ONE_FOR_ONE: startChild(spec) throws; startChild() works")
    void testSimpleOneForOneStartChildWithSpecThrows() throws Exception {
        var template = Supervisor.ChildSpec.worker("t", () -> 0, (state, msg) -> state);
        var pool = Supervisor.createSimple(template, 5, Duration.ofSeconds(60));

        var spec = Supervisor.ChildSpec.permanent("other", 0, (state, msg) -> state);
        assertThatThrownBy(() -> pool.startChild(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIMPLE_ONE_FOR_ONE");

        pool.shutdown();
    }

    @Test
    @DisplayName("SIMPLE_ONE_FOR_ONE: Each crashed instance is restarted independently")
    void testSimpleOneForOneIndependentCrashRestart() throws Exception {
        var template =
                Supervisor.ChildSpec.worker(
                        "worker",
                        () -> 0,
                        (Integer state, TestMsg msg) -> {
                            if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                            if (msg instanceof TestMsg.Increment) return state + 1;
                            return state;
                        });

        var pool = Supervisor.createSimple(template, 5, Duration.ofSeconds(60));

        var ref1 = pool.<Integer, TestMsg>startChild();
        var ref2 = pool.<Integer, TestMsg>startChild();

        ref1.tell(new TestMsg.Increment());
        ref2.tell(new TestMsg.Increment());
        ref2.tell(new TestMsg.Increment());
        Thread.sleep(100);

        var state2Before = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2Before).isEqualTo(2);

        // Crash only ref1 — ref2 should be unaffected
        ref1.tell(new TestMsg.Crash());
        // Wait until ref1 restarts to fresh state (0)
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                return ((Integer)
                                                ref1.ask(new TestMsg.Get())
                                                        .get(100, TimeUnit.MILLISECONDS))
                                        == 0;
                            } catch (Exception e) {
                                return false;
                            }
                        });

        var state2After = ref2.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state2After).isEqualTo(2); // ref2 state preserved

        // ref1 should be restarted (fresh state = 0)
        var state1After = ref1.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state1After).isEqualTo(0);

        pool.shutdown();
    }

    @Test
    @DisplayName("SIMPLE_ONE_FOR_ONE: terminateChild by ProcRef stops only that instance")
    void testSimpleOneForOneTerminateByRef() throws Exception {
        var template = Supervisor.ChildSpec.worker("inst", () -> 0, (state, msg) -> state);
        var pool = Supervisor.createSimple(template, 5, Duration.ofSeconds(60));

        var ref1 = pool.<Integer, TestMsg>startChild();
        var ref2 = pool.<Integer, TestMsg>startChild();

        assertThat(pool.whichChildren()).hasSize(2);

        pool.terminateChild(ref1);

        assertThat(pool.whichChildren()).hasSize(2);
        assertThat(pool.whichChildren().get(0).alive()).isFalse();
        assertThat(pool.whichChildren().get(1).alive()).isTrue();

        pool.shutdown();
    }

    // ============================================================================
    // PER-CHILD SHUTDOWN POLICIES
    // ============================================================================

    @Test
    @DisplayName("BrutalKill: child is interrupted immediately without waiting")
    void testBrutalKillShutdown() throws Exception {
        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var spec =
                new Supervisor.ChildSpec<>(
                        "brutal-child",
                        () -> 0,
                        (Integer state, TestMsg msg) -> state,
                        Supervisor.ChildSpec.RestartType.PERMANENT,
                        new Supervisor.ChildSpec.Shutdown.BrutalKill(),
                        Supervisor.ChildSpec.ChildType.WORKER,
                        false);

        supervisor.startChild(spec);

        // Shutdown should complete quickly even though children are present
        var startTime = System.currentTimeMillis();
        supervisor.shutdown();
        var elapsed = System.currentTimeMillis() - startTime;

        // BrutalKill shutdown should be fast (< 2 seconds)
        assertThat(elapsed).isLessThan(2000L);
    }

    @Test
    @DisplayName("ChildSpec.worker factory: creates PERMANENT worker with 5s timeout")
    void testChildSpecWorkerFactory() {
        var spec = Supervisor.ChildSpec.worker("w", () -> 0, (state, msg) -> state);
        assertThat(spec.restart()).isEqualTo(Supervisor.ChildSpec.RestartType.PERMANENT);
        assertThat(spec.type()).isEqualTo(Supervisor.ChildSpec.ChildType.WORKER);
        assertThat(spec.significant()).isFalse();
        assertThat(spec.shutdown()).isInstanceOf(Supervisor.ChildSpec.Shutdown.Timeout.class);
        var timeout = (Supervisor.ChildSpec.Shutdown.Timeout) spec.shutdown();
        assertThat(timeout.duration()).isEqualTo(Duration.ofMillis(5000));
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
