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
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Comprehensive test suite for {@link Proc}.
 *
 * <p>Tests cover message delivery (tell/ask), mailbox ordering (FIFO), crash callbacks, termination
 * callbacks, exit signal trapping, process suspension/resumption, and concurrent sender scenarios.
 * <p><strong>Proc Lifecycle:</strong>
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
 * <p><strong>Testing Approach:</strong>
 *   <li>Message-based handlers with state updates for deterministic testing
 *   <li>Awaitility for async assertions (wait for callback firing, message processing)
 *   <li>CountDownLatch and atomic counters for callback verification
 *   <li>Virtual thread concurrency tests with 50+ concurrent senders
 *   <li>Timeout assertions using {@link CompletableFuture#orTimeout(long, TimeUnit)}
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
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    // ============================================================================
    // MESSAGE DELIVERY TESTS
    @Test
    @DisplayName("tell(): Fire-and-forget message delivery")
    void testTellFireAndForget() throws Exception {
        BiFunction<Integer, TestMsg, Integer> handler =
                (state, msg) -> {
                    if (msg instanceof TestMsg.Increment) return state + 1;
                    return state;
                };
        var proc = new Proc<>(0, handler);
        proc.tell(new TestMsg.Increment());
        Thread.sleep(100);
        var state = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(3);
        proc.stop();
    @DisplayName("ask(): Request-reply with future completion")
    void testAskRequestReply() throws Exception {
        var future = proc.ask(new TestMsg.Get());
        var state = future.get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1);
    @DisplayName("ask(msg, timeout): Timed request-reply")
    void testAskWithTimeout() throws Exception {
        BiFunction<Integer, TestMsg, Integer> slowHandler =
                    if (msg instanceof TestMsg.Timeout t) {
                        try {
                            Thread.sleep(t.delayMs());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
        var proc = new Proc<>(0, slowHandler);
        // Ask with sufficient timeout → succeeds
        var future1 = proc.ask(new TestMsg.Timeout(50), Duration.ofMillis(500));
        assertThatNoException().isThrownBy(() -> future1.get(2, TimeUnit.SECONDS));
        // Ask with insufficient timeout → TimeoutException
        var future2 = proc.ask(new TestMsg.Timeout(500), Duration.ofMillis(50));
        assertThatThrownBy(() -> future2.get(1, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);
    // MAILBOX ORDERING TESTS
    @DisplayName("Mailbox FIFO: 100 messages received in order")
    void testMailboxOrdering() throws Exception {
        var received = new CopyOnWriteArrayList<Integer>();
                    if (msg instanceof TestMsg.Echo e) {
                            received.add(Integer.parseInt(e.value()));
                        } catch (NumberFormatException ex) {
                            // ignore
        int messageCount = 100;
        for (int i = 0; i < messageCount; i++) {
            proc.tell(new TestMsg.Echo(String.valueOf(i)));
        }
        await().atMost(AWAIT_TIMEOUT).until(() -> received.size() == messageCount);
        // Verify FIFO order
            assertThat(received.get(i)).isEqualTo(i);
    @DisplayName("Concurrent senders: 50+ virtual threads, no lost messages")
    void testConcurrentSenders() throws Exception {
        var messageCount = new AtomicInteger(0);
                    if (msg instanceof TestMsg.Increment) {
                        messageCount.incrementAndGet();
                        return state + 1;
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
        for (Thread t : threads) t.join();
        await().atMost(AWAIT_TIMEOUT)
                .until(() -> messageCount.get() == senderCount * messagesPerSender);
        var finalState = proc.ask(new TestMsg.Get()).get(1, TimeUnit.SECONDS);
        assertThat(finalState).isEqualTo(senderCount * messagesPerSender);
    // CRASH CALLBACK TESTS
    @DisplayName("Crash callback: Fired on abnormal termination")
    void testCrashCallbackFiredOnException() throws Exception {
        var crashFired = new CountDownLatch(1);
                    if (msg instanceof TestMsg.Crash)
                        throw new RuntimeException("intentional crash");
        proc.addCrashCallback(() -> crashFired.countDown());
        proc.tell(new TestMsg.Crash());
        // Wait for crash callback to fire
        var result = crashFired.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result).isTrue();
        assertThat(proc.lastError()).isNotNull();
        proc.thread().join();
    @DisplayName("Crash callback: Not fired on normal exit")
    void testCrashCallbackNotFiredOnNormalExit() throws Exception {
        var crashFired = new AtomicBoolean(false);
        BiFunction<Integer, TestMsg, Integer> handler = (state, msg) -> state;
        proc.addCrashCallback(() -> crashFired.set(true));
        // Crash callback should NOT fire for normal exit
        await().during(Duration.ofMillis(500)).until(() -> crashFired.get() == false);
    @DisplayName("Multiple crash callbacks: All fire on abnormal termination")
    void testMultipleCrashCallbacks() throws Exception {
        var count = new AtomicInteger(0);
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("crash");
        proc.addCrashCallback(() -> count.incrementAndGet());
        await().atMost(AWAIT_TIMEOUT).until(() -> count.get() == 3);
    // EXIT SIGNAL TRAPPING TESTS
    @DisplayName("trapExits(true): EXIT signals become mailbox messages")
    void testExitSignalTrapAsMessage() throws Exception {
        var received = new AtomicBoolean(false);
        BiFunction<Integer, Object, Integer> handler =
                    if (msg instanceof ExitSignal sig) {
                        received.set(true);
                        return state;
        proc.trapExits(true);
        var reason = new RuntimeException("linked process crashed");
        proc.deliverExitSignal(reason);
        await().atMost(AWAIT_TIMEOUT).until(() -> received.get());
    @DisplayName("trapExits(false): EXIT signals interrupt immediately")
    void testExitSignalKillsImmediately() throws Exception {
                    try {
                        Thread.sleep(1000); // Long operation
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted", e);
        proc.trapExits(false);
        proc.tell(new TestMsg.Noop());
        await().atMost(AWAIT_TIMEOUT).until(() -> proc.lastError() != null);
    @DisplayName("trapExits: Toggle on/off")
    void testTrapExitsToggle() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        assertThat(proc.isTrappingExits()).isFalse();
        assertThat(proc.isTrappingExits()).isTrue();
    // TERMINATION CALLBACK TESTS
    @DisplayName("Termination callback: Normal exit (null reason)")
    void testTerminationCallbackNormalExit() throws Exception {
        var callbackFired = new CountDownLatch(1);
        var reason = new AtomicReference<Throwable>();
        proc.addTerminationCallback(
                r -> {
                    reason.set(r);
                    callbackFired.countDown();
                });
        var result = callbackFired.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(reason.get()).isNull(); // Normal exit
    @DisplayName("Termination callback: Abnormal exit (non-null reason)")
    void testTerminationCallbackAbnormalExit() throws Exception {
        assertThat(reason.get()).isNotNull(); // Abnormal exit with cause
    // PROCESS INTROSPECTION TESTS
    @DisplayName("lastError: Set before crash callbacks fire")
    void testLastErrorSet() throws Exception {
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("test error");
        var crashCalled = new CountDownLatch(1);
        proc.addCrashCallback(
                () -> {
                    // At this point, lastError should be set
                    assertThat(proc.lastError()).isNotNull();
                    assertThat(proc.lastError().getMessage()).contains("test error");
                    crashCalled.countDown();
        var result = crashCalled.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    @DisplayName("thread(): Access to underlying virtual thread")
    void testThreadAccess() throws Exception {
        var thread = proc.thread();
        assertThat(thread).isNotNull();
        assertThat(thread.isVirtual()).isTrue();
    // MESSAGE STATISTICS TESTS
    @DisplayName("Message statistics: messagesIn increments correctly")
    void testMessageStatistics() throws Exception {
        await().atMost(AWAIT_TIMEOUT).until(() -> proc.messagesIn.sum() >= 3);
        assertThat(proc.messagesIn.sum()).isGreaterThanOrEqualTo(3);
    // EDGE CASE & STRESS TESTS
    @DisplayName("Empty mailbox: Process waits for messages")
    void testEmptyMailboxWait() throws Exception {
        // Process created with no messages — should wait
        Thread.sleep(50);
        // Now send a message
    @DisplayName("Handler exception: Process terminates abnormally")
    void testHandlerException() throws Exception {
                    if (msg instanceof TestMsg.Crash) throw new RuntimeException("handler error");
    @DisplayName("Rapid fire: Many messages in quick succession")
    void testRapidMessageFire() throws Exception {
        var counter = new AtomicInteger(0);
                        counter.incrementAndGet();
        int count = 1000;
        for (int i = 0; i < count; i++) {
            proc.tell(new TestMsg.Increment());
        await().atMost(AWAIT_TIMEOUT).until(() -> counter.get() == count);
        assertThat(state).isEqualTo(count);
    @DisplayName("State immutability: Each message creates new state")
    void testStateImmutability() throws Exception {
        record TestState(int value, String label) {}
        BiFunction<TestState, TestMsg, TestState> handler =
                        return new TestState(state.value + 1, "incremented");
        var proc = new Proc<>(new TestState(0, "initial"), handler);
        assertThat(state.value).isEqualTo(2);
        assertThat(state.label).isEqualTo("incremented");
    // HELPER METHODS
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
     * Assert that a message was received and processed within timeout.
     * @param proc the process
     * @param message the test message
     * @param timeout wait timeout
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
     * Assert that a callback has fired.
     * @param latch the countdown latch
    private static void assertCallbackFired(CountDownLatch latch, Duration timeout)
            throws Exception {
        var result = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
}
