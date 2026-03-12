package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for {@link Proc}.
 *
 * <p>Tests cover message delivery (tell/ask), mailbox ordering (FIFO), crash callbacks, termination
 * callbacks, exit signal trapping, process suspension/resumption, and concurrent sender scenarios.
 *
 * <p><strong>Proc Lifecycle:</strong>
 *
 * <ul>
 *   <li><strong>Creation:</strong> {@code new Proc(initial, handler)} starts a virtual thread
 *       immediately.
 *   <li><strong>Message Delivery:</strong> {@link Proc#tell(Object)} (fire-and-forget) and {@link
 *       Proc#ask(Object)} (request-reply).
 *   <li><strong>Crash Callbacks:</strong> Fired only on abnormal termination (unhandled exception).
 *   <li><strong>Termination Callbacks:</strong> Fired on any termination (normal or abnormal).
 *   <li><strong>Exit Signal Trapping:</strong> {@link Proc#trapExits(boolean)} converts incoming
 *       EXIT signals into {@link ExitSignal} mailbox messages.
 * </ul>
 *
 * <p><strong>Testing Approach:</strong>
 *
 * <ul>
 *   <li>Message-based handlers with state updates for deterministic testing
 *   <li>Awaitility for async assertions (wait for callback firing, message processing)
 *   <li>CountDownLatch and atomic counters for callback verification
 *   <li>Virtual thread concurrency tests with 50+ concurrent senders
 *   <li>Timeout assertions using {@link CompletableFuture#orTimeout(long, TimeUnit)}
 * </ul>
 *
 * @see Proc
 * @see ExitSignal
 * @see ProcRef
 * @see Supervisor
 */
@DisplayName("Proc: Lightweight Virtual-Thread Process")
class ProcTest {

    /** Test message types. Use sealed Record hierarchy for pattern matching. */
    sealed interface TestMsg
            permits TestMsg.Increment,
                    TestMsg.Crash,
                    TestMsg.Get,
                    TestMsg.Echo,
                    TestMsg.Noop,
                    TestMsg.Timeout {
        record Increment() implements TestMsg {}

        record Crash() implements TestMsg {}

        record Get() implements TestMsg {}

        record Echo(String value) implements TestMsg {}

        record Noop() implements TestMsg {}

        record Timeout(long delayMs) implements TestMsg {}
    }

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    // ============================================================================
    // MESSAGE DELIVERY TESTS
    // ============================================================================

    @Test
    @DisplayName("tell(): Fire-and-forget message delivery")
    void testTellFireAndForget() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) return state + 1;
                    return state;
                };

        var proc = new Proc<>(0, handler);

        proc.tell(new TestMsg.Increment());
        proc.tell(new TestMsg.Increment());
        proc.tell(new TestMsg.Increment());

        Thread.sleep(100);

        var state = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(3);

        proc.stop();
    }

    @Test
    @DisplayName("ask(): Request-reply with future completion")
    void testAskRequestReply() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) return state + 1;
                    return state;
                };

        var proc = new Proc<>(0, handler);

        proc.tell(new TestMsg.Increment());
        var future = proc.ask(new TestMsg.Get());
        var state = future.get(1, TimeUnit.SECONDS);

        assertThat(state).isEqualTo(1);

        proc.stop();
    }

    @Test
    @DisplayName("ask(msg, timeout): Timed request-reply")
    void testAskWithTimeout() throws Exception {
        var slowHandler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Timeout t) {
                        try {
                            Thread.sleep(t.delayMs());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return state;
                };

        var proc = new Proc<>(0, slowHandler);

        // Ask with sufficient timeout → succeeds
        var future1 = proc.ask(new TestMsg.Timeout(50), Duration.ofMillis(500));
        assertThatNoException().isThrownBy(() -> future1.get(2, TimeUnit.SECONDS));

        // Ask with insufficient timeout → TimeoutException
        var future2 = proc.ask(new TestMsg.Timeout(500), Duration.ofMillis(50));
        assertThatThrownBy(() -> future2.get(1, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);

        proc.stop();
    }

    // ============================================================================
    // MAILBOX ORDERING TESTS
    // ============================================================================

    @Test
    @DisplayName("Mailbox FIFO: 100 messages received in order")
    void testMailboxOrdering() throws Exception {
        var received = new CopyOnWriteArrayList<Integer>();
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Echo e) {
                        try {
                            received.add(Integer.parseInt(e.value()));
                        } catch (NumberFormatException ex) {
                            // ignore
                        }
                    }
                    return state;
                };

        var proc = new Proc<>(0, handler);

        int messageCount = 100;
        for (int i = 0; i < messageCount; i++) {
            proc.tell(new TestMsg.Echo(String.valueOf(i)));
        }

        await().atMost(AWAIT_TIMEOUT).until(() -> received.size() == messageCount);

        // Verify FIFO order
        for (int i = 0; i < messageCount; i++) {
            assertThat(received.get(i)).isEqualTo(i);
        }

        proc.stop();
    }

    @Test
    @DisplayName("Concurrent senders: 50+ virtual threads, no lost messages")
    void testConcurrentSenders() throws Exception {
        var messageCount = new AtomicInteger(0);
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) {
                        messageCount.incrementAndGet();
                        return state + 1;
                    }
                    return state;
                };

        var proc = new Proc<>(0, handler);

        int senderCount = 50;
        int messagesPerSender = 10;
        var threads = new Thread[senderCount];

        for (int i = 0; i < senderCount; i++) {
            threads[i] =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        for (int j = 0; j < messagesPerSender; j++) {
                                            proc.tell(new TestMsg.Increment());
                                        }
                                    });
        }

        for (Thread t : threads) t.join();

        await().atMost(AWAIT_TIMEOUT)
                .until(() -> messageCount.get() == senderCount * messagesPerSender);

        var finalState = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(finalState).isEqualTo(senderCount * messagesPerSender);

        proc.stop();
    }

    // ============================================================================
    // CRASH CALLBACK TESTS
    // ============================================================================

    @Test
    @DisplayName("Crash callback: Fired on abnormal termination")
    void testCrashCallbackFiredOnException() throws Exception {
        var crashFired = new CountDownLatch(1);
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Crash)
                        throw new RuntimeException("intentional crash");
                    return state;
                };

        var proc = new Proc<>(0, handler);
        proc.addCrashCallback(() -> crashFired.countDown());

        proc.tell(new TestMsg.Crash());

        // Wait for crash callback to fire
        var result = crashFired.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();
        assertThat(proc.lastError).isNotNull();

        proc.thread().join();
    }

    @Test
    @DisplayName("Crash callback: Not fired on normal exit")
    void testCrashCallbackNotFiredOnNormalExit() throws Exception {
        var crashFired = new AtomicBoolean(false);
        var handler = (Integer state, TestMsg msg) -> state;

        var proc = new Proc<>(0, handler);
        proc.addCrashCallback(() -> crashFired.set(true));

        proc.stop();

        // Crash callback should NOT fire for normal exit
        await().during(Duration.ofMillis(500)).until(() -> crashFired.get() == false);
    }

    @Test
    @DisplayName("Multiple crash callbacks: All fire on abnormal termination")
    void testMultipleCrashCallbacks() throws Exception {
        var count = new AtomicInteger(0);
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                    return state;
                };

        var proc = new Proc<>(0, handler);
        proc.addCrashCallback(() -> count.incrementAndGet());
        proc.addCrashCallback(() -> count.incrementAndGet());
        proc.addCrashCallback(() -> count.incrementAndGet());

        proc.tell(new TestMsg.Crash());

        await().atMost(AWAIT_TIMEOUT).until(() -> count.get() == 3);

        proc.thread().join();
    }

    // ============================================================================
    // EXIT SIGNAL TRAPPING TESTS
    // ============================================================================

    @Test
    @DisplayName("trapExits(true): EXIT signals become mailbox messages")
    void testExitSignalTrapAsMessage() throws Exception {
        var received = new AtomicBoolean(false);
        var handler =
                (Integer state, Object msg) -> {
                    if (msg instanceof ExitSignal sig) {
                        received.set(true);
                        return state;
                    }
                    return state;
                };

        var proc = new Proc<>(0, handler);
        proc.trapExits(true);

        var reason = new RuntimeException("linked process crashed");
        proc.deliverExitSignal(reason);

        await().atMost(AWAIT_TIMEOUT).until(() -> received.get());

        proc.stop();
    }

    @Test
    @DisplayName("trapExits(false): EXIT signals interrupt immediately")
    void testExitSignalKillsImmediately() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    try {
                        Thread.sleep(1000); // Long operation
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted", e);
                    }
                    return state;
                };

        var proc = new Proc<>(0, handler);
        proc.trapExits(false);

        proc.tell(new TestMsg.Noop());
        var reason = new RuntimeException("linked process crashed");
        proc.deliverExitSignal(reason);

        await().atMost(AWAIT_TIMEOUT).until(() -> proc.lastError != null);

        proc.thread().join();
    }

    @Test
    @DisplayName("trapExits: Toggle on/off")
    void testTrapExitsToggle() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);

        assertThat(proc.isTrappingExits()).isFalse();

        proc.trapExits(true);
        assertThat(proc.isTrappingExits()).isTrue();

        proc.trapExits(false);
        assertThat(proc.isTrappingExits()).isFalse();

        proc.stop();
    }

    // ============================================================================
    // TERMINATION CALLBACK TESTS
    // ============================================================================

    @Test
    @DisplayName("Termination callback: Normal exit (null reason)")
    void testTerminationCallbackNormalExit() throws Exception {
        var callbackFired = new CountDownLatch(1);
        var reason = new AtomicReference<Throwable>();
        var handler = (Integer state, TestMsg msg) -> state;

        var proc = new Proc<>(0, handler);
        proc.addTerminationCallback(
                r -> {
                    reason.set(r);
                    callbackFired.countDown();
                });

        proc.stop();

        var result = callbackFired.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();
        assertThat(reason.get()).isNull(); // Normal exit
    }

    @Test
    @DisplayName("Termination callback: Abnormal exit (non-null reason)")
    void testTerminationCallbackAbnormalExit() throws Exception {
        var callbackFired = new CountDownLatch(1);
        var reason = new AtomicReference<Throwable>();
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
                    return state;
                };

        var proc = new Proc<>(0, handler);
        proc.addTerminationCallback(
                r -> {
                    reason.set(r);
                    callbackFired.countDown();
                });

        proc.tell(new TestMsg.Crash());

        var result = callbackFired.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();
        assertThat(reason.get()).isNotNull(); // Abnormal exit with cause

        proc.thread().join();
    }

    // ============================================================================
    // PROCESS INTROSPECTION TESTS
    // ============================================================================

    @Test
    @DisplayName("lastError: Set before crash callbacks fire")
    void testLastErrorSet() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("test error");
                    return state;
                };

        var proc = new Proc<>(0, handler);
        var crashCalled = new CountDownLatch(1);
        proc.addCrashCallback(
                () -> {
                    // At this point, lastError should be set
                    assertThat(proc.lastError).isNotNull();
                    assertThat(proc.lastError.getMessage()).contains("test error");
                    crashCalled.countDown();
                });

        proc.tell(new TestMsg.Crash());

        var result = crashCalled.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();

        proc.thread().join();
    }

    @Test
    @DisplayName("thread(): Access to underlying virtual thread")
    void testThreadAccess() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        var thread = proc.thread();

        assertThat(thread).isNotNull();
        assertThat(thread.isVirtual()).isTrue();

        proc.stop();
    }

    // ============================================================================
    // MESSAGE STATISTICS TESTS
    // ============================================================================

    @Test
    @DisplayName("Message statistics: messagesIn increments correctly")
    void testMessageStatistics() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) return state + 1;
                    return state;
                };

        var proc = new Proc<>(0, handler);

        proc.tell(new TestMsg.Increment());
        proc.tell(new TestMsg.Increment());
        proc.tell(new TestMsg.Increment());

        await().atMost(AWAIT_TIMEOUT).until(() -> proc.messagesIn.sum() >= 3);

        assertThat(proc.messagesIn.sum()).isGreaterThanOrEqualTo(3);

        proc.stop();
    }

    // ============================================================================
    // EDGE CASE & STRESS TESTS
    // ============================================================================

    @Test
    @DisplayName("Empty mailbox: Process waits for messages")
    void testEmptyMailboxWait() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) return state + 1;
                    return state;
                };

        var proc = new Proc<>(0, handler);

        // Process created with no messages — should wait
        Thread.sleep(50);

        // Now send a message
        proc.tell(new TestMsg.Increment());
        var state = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);

        assertThat(state).isEqualTo(1);

        proc.stop();
    }

    @Test
    @DisplayName("Handler exception: Process terminates abnormally")
    void testHandlerException() throws Exception {
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("handler error");
                    return state;
                };

        var proc = new Proc<>(0, handler);
        var crashFired = new CountDownLatch(1);
        proc.addCrashCallback(() -> crashFired.countDown());

        proc.tell(new TestMsg.Crash());

        var result = crashFired.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();

        proc.thread().join();
    }

    @Test
    @DisplayName("Rapid fire: Many messages in quick succession")
    void testRapidMessageFire() throws Exception {
        var counter = new AtomicInteger(0);
        var handler =
                (Integer state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) {
                        counter.incrementAndGet();
                        return state + 1;
                    }
                    return state;
                };

        var proc = new Proc<>(0, handler);

        int count = 1000;
        for (int i = 0; i < count; i++) {
            proc.tell(new TestMsg.Increment());
        }

        await().atMost(AWAIT_TIMEOUT).until(() -> counter.get() == count);

        var state = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(count);

        proc.stop();
    }

    @Test
    @DisplayName("State immutability: Each message creates new state")
    void testStateImmutability() throws Exception {
        record TestState(int value, String label) {}

        var handler =
                (TestState state, TestMsg msg) -> {
                    if (msg instanceof TestMsg.Increment) {
                        return new TestState(state.value + 1, "incremented");
                    }
                    return state;
                };

        var proc = new Proc<>(new TestState(0, "initial"), handler);

        proc.tell(new TestMsg.Increment());
        proc.tell(new TestMsg.Increment());

        var state = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);

        assertThat(state.value).isEqualTo(2);
        assertThat(state.label).isEqualTo("incremented");

        proc.stop();
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    /**
     * Create a test process with initial state and handler.
     *
     * @param initialState initial process state
     * @param handler message handler
     * @return new Proc instance
     */
    private static <S, M> Proc<S, M> createTestProc(
            S initialState, java.util.function.BiFunction<S, M, S> handler) {
        return new Proc<>(initialState, handler);
    }

    /**
     * Assert that a message was received and processed within timeout.
     *
     * @param proc the process
     * @param message the test message
     * @param timeout wait timeout
     */
    private static void assertMessageReceived(Proc proc, TestMsg message, Duration timeout) {
        await().atMost(timeout)
                .until(
                        () -> {
                            try {
                                proc.ask(message).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        });
    }

    /**
     * Assert that a callback has fired.
     *
     * @param latch the countdown latch
     * @param timeout wait timeout
     */
    private static void assertCallbackFired(CountDownLatch latch, Duration timeout)
            throws Exception {
        var result = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();
    }
}
