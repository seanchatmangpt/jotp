package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for {@link GenServer}.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li><strong>Fire-and-forget (cast):</strong> Messages delivered asynchronously, no reply
 *       expected.
 *   <li><strong>Request-reply (call):</strong> Caller blocks (with timeout) waiting for response.
 *   <li><strong>Info messages:</strong> Async notifications for monitors/timers.
 *   <li><strong>State maintenance:</strong> State is correctly updated across calls.
 *   <li><strong>Timeout behavior:</strong> Calls timeout correctly when server is slow.
 *   <li><strong>Multiple concurrent callers:</strong> Virtual thread concurrency with 10+ senders.
 *   <li><strong>Graceful shutdown:</strong> Server drains remaining messages before stopping.
 * </ul>
 *
 * <p><strong>Testing Pattern:</strong>
 *
 * <p>Each test defines a sealed message hierarchy and a {@link GenServer.Handler} that updates
 * state deterministically. We use {@link org.awaitility.Awaitility} for async assertions and {@link
 * CountDownLatch} to synchronize callback firing.
 *
 * @see GenServer
 * @see GenServer.Handler
 * @see Proc
 */
@DisplayName("GenServer: OTP-style Request-Reply Server")
class GenServerTest {

    /** Test message types for Counter server. */
    sealed interface CounterMsg
            permits CounterMsg.Increment, CounterMsg.Decrement, CounterMsg.Get, CounterMsg.Reset {
        record Increment() implements CounterMsg {}

        record Decrement() implements CounterMsg {}

        record Get() implements CounterMsg {}

        record Reset() implements CounterMsg {}
    }

    /** Test message types for Echo server. */
    sealed interface EchoMsg permits EchoMsg.Echo, EchoMsg.GetCount {
        record Echo(String value) implements EchoMsg {}

        record GetCount() implements EchoMsg {}
    }

    /** Test message types for StateTracking server. */
    sealed interface StateMsg
            permits StateMsg.Append, StateMsg.GetState, StateMsg.Clear, StateMsg.TimerFired {
        record Append(String value) implements StateMsg {}

        record GetState() implements StateMsg {}

        record Clear() implements StateMsg {}

        record TimerFired(String id) implements StateMsg {}
    }

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);

    // ============================================================================
    // CAST (FIRE-AND-FORGET) TESTS
    // ============================================================================

    @Test
    @DisplayName("cast() delivers messages asynchronously")
    void testCastFireAndForget() throws InterruptedException {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            case CounterMsg.Decrement _ -> state - 1;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Send multiple cast messages
        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Decrement());

        // Verify state after async processing
        var result = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(result).isEqualTo(1);

        server.stop();
    }

    @Test
    @DisplayName("cast() maintains FIFO ordering")
    void testCastFifoOrdering() throws Exception {
        var handler =
                new GenServer.Handler<List<String>, StateMsg>() {
                    @Override
                    public GenServer.CallResult<List<String>> handleCall(
                            StateMsg request, List<String> state) {
                        return switch (request) {
                            case StateMsg.GetState _ ->
                                    new GenServer.CallResult<>(state, new ArrayList<>(state));
                            default -> new GenServer.CallResult<>(state, List.of());
                        };
                    }

                    @Override
                    public List<String> handleCast(StateMsg request, List<String> state) {
                        return switch (request) {
                            case StateMsg.Append append -> {
                                var newState = new ArrayList<>(state);
                                newState.add(append.value());
                                yield newState;
                            }
                            case StateMsg.Clear _ -> new ArrayList<>();
                            default -> state;
                        };
                    }

                    @Override
                    public List<String> handleInfo(Object info, List<String> state) {
                        return state;
                    }
                };

        var server = GenServer.start(new ArrayList<>(), handler);

        // Send multiple casts in order
        server.cast(new StateMsg.Append("first"));
        server.cast(new StateMsg.Append("second"));
        server.cast(new StateMsg.Append("third"));

        // Verify ordering
        var result = server.call(new StateMsg.GetState(), CALL_TIMEOUT).get();
        assertThat(result).containsExactly("first", "second", "third");

        server.stop();
    }

    // ============================================================================
    // CALL (REQUEST-REPLY) TESTS
    // ============================================================================

    @Test
    @DisplayName("call() sends request and waits for reply")
    void testCallRequestReply() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(42, handler);

        var reply = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(reply).isEqualTo(42);

        server.stop();
    }

    @Test
    @DisplayName("call() returns the reply from handler")
    void testCallReplyContent() throws Exception {
        var handler =
                new GenServer.Handler<String, EchoMsg>() {
                    @Override
                    public GenServer.CallResult<String> handleCall(EchoMsg request, String state) {
                        return switch (request) {
                            case EchoMsg.Echo echo -> {
                                var reply = "echo:" + echo.value();
                                yield new GenServer.CallResult<>(state, reply);
                            }
                            default -> new GenServer.CallResult<>(state, "");
                        };
                    }

                    @Override
                    public String handleCast(EchoMsg request, String state) {
                        return state;
                    }

                    @Override
                    public String handleInfo(Object info, String state) {
                        return state;
                    }
                };

        var server = GenServer.start("initial", handler);

        var reply = server.call(new EchoMsg.Echo("hello"), CALL_TIMEOUT).get();
        assertThat(reply).isEqualTo("echo:hello");

        server.stop();
    }

    @Test
    @DisplayName("call() updates state after processing request")
    void testCallStateUpdate() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            case CounterMsg.Decrement _ -> state - 1;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Call increments state
        var reply1 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(reply1).isEqualTo(0);

        // Cast increments asynchronously
        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());

        // Next call sees updated state
        var reply2 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(reply2).isEqualTo(2);

        server.stop();
    }

    // ============================================================================
    // TIMEOUT TESTS
    // ============================================================================

    @Test
    @DisplayName("call() respects timeout duration")
    void testCallTimeout() {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> {
                                // Simulate slow processing
                                try {
                                    Thread.sleep(2000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                yield new GenServer.CallResult<>(state, state);
                            }
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return state;
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Call with short timeout should fail
        var future = server.call(new CounterMsg.Get(), Duration.ofMillis(100));
        assertThatThrownBy(() -> future.get()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("call() with generous timeout completes successfully")
    void testCallWithGenerousTimeout() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                yield new GenServer.CallResult<>(state, state);
                            }
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return state;
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(99, handler);

        var reply = server.call(new CounterMsg.Get(), Duration.ofSeconds(5)).get();
        assertThat(reply).isEqualTo(99);

        server.stop();
    }

    // ============================================================================
    // STATE MAINTENANCE TESTS
    // ============================================================================

    @Test
    @DisplayName("state is maintained across multiple calls and casts")
    void testStateMaintenance() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            case CounterMsg.Decrement _ -> state - 1;
                            case CounterMsg.Reset _ -> 0;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Series of operations
        server.cast(new CounterMsg.Increment());
        var v1 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(v1).isEqualTo(1);

        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());
        var v2 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(v2).isEqualTo(3);

        server.cast(new CounterMsg.Reset());
        var v3 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(v3).isEqualTo(0);

        server.stop();
    }

    @Test
    @DisplayName("state updates are atomic per message")
    void testAtomicStateUpdates() throws Exception {
        var handler =
                new GenServer.Handler<List<Integer>, StateMsg>() {
                    @Override
                    public GenServer.CallResult<List<Integer>> handleCall(
                            StateMsg request, List<Integer> state) {
                        return switch (request) {
                            case StateMsg.GetState _ ->
                                    new GenServer.CallResult<>(state, new ArrayList<>(state));
                            default -> new GenServer.CallResult<>(state, List.of());
                        };
                    }

                    @Override
                    public List<Integer> handleCast(StateMsg request, List<Integer> state) {
                        var newState = new ArrayList<>(state);
                        newState.add(newState.size() + 1);
                        return newState;
                    }

                    @Override
                    public List<Integer> handleInfo(Object info, List<Integer> state) {
                        return state;
                    }
                };

        var server = GenServer.start(new ArrayList<>(), handler);

        // Send 5 casts
        for (int i = 0; i < 5; i++) {
            server.cast(new StateMsg.Append("unused"));
        }

        var result = server.call(new StateMsg.GetState(), CALL_TIMEOUT).get();
        // List should have exactly 5 elements (1, 2, 3, 4, 5)
        assertThat(result).hasSize(5).containsExactly(1, 2, 3, 4, 5);

        server.stop();
    }

    // ============================================================================
    // INFO MESSAGE TESTS
    // ============================================================================

    @Test
    @DisplayName("info() sends async notifications")
    void testInfoMessages() throws Exception {
        var infoCount = new AtomicInteger(0);

        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return state;
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        if (info instanceof String) {
                            infoCount.incrementAndGet();
                        }
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Send info messages
        server.info("info1");
        server.info("info2");
        server.info("info3");

        // Wait for info processing
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> assertThat(infoCount.get()).isEqualTo(3));

        server.stop();
    }

    // ============================================================================
    // CONCURRENT CALLER TESTS
    // ============================================================================

    @Test
    @DisplayName("handles multiple concurrent callers")
    void testConcurrentCallers() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Launch 10 concurrent callers
        var futures = new ArrayList<CompletableFuture<Integer>>();
        for (int i = 0; i < 10; i++) {
            var f =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
            futures.add(f);
        }

        // All should complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

        // All should see the same state
        for (var f : futures) {
            assertThat(f.get()).isEqualTo(0);
        }

        server.stop();
    }

    @Test
    @DisplayName("interleaves casts and calls correctly")
    void testInterleavedCastsAndCalls() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        server.cast(new CounterMsg.Increment());
        var r1 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();

        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());
        var r2 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();

        server.cast(new CounterMsg.Increment());
        var r3 = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();

        assertThat(r1).isEqualTo(1);
        assertThat(r2).isEqualTo(3);
        assertThat(r3).isEqualTo(4);

        server.stop();
    }

    // ============================================================================
    // SHUTDOWN TESTS
    // ============================================================================

    @Test
    @DisplayName("stop() gracefully drains remaining messages")
    void testGracefulShutdown() throws Exception {
        var processedCount = new AtomicInteger(0);

        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        processedCount.incrementAndGet();
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        processedCount.incrementAndGet();
                        return switch (request) {
                            case CounterMsg.Increment _ -> state + 1;
                            default -> state;
                        };
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(0, handler);

        // Queue several messages
        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());

        // Stop and wait for graceful shutdown
        server.stop();

        // All messages should have been processed
        assertThat(processedCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("underlying() provides access to wrapped Proc")
    void testUnderlyingProcAccess() throws Exception {
        var handler =
                new GenServer.Handler<Integer, CounterMsg>() {
                    @Override
                    public GenServer.CallResult<Integer> handleCall(
                            CounterMsg request, Integer state) {
                        return switch (request) {
                            case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                            default -> new GenServer.CallResult<>(state, -1);
                        };
                    }

                    @Override
                    public Integer handleCast(CounterMsg request, Integer state) {
                        return state;
                    }

                    @Override
                    public Integer handleInfo(Object info, Integer state) {
                        return state;
                    }
                };

        var server = GenServer.start(42, handler);

        // Access underlying Proc
        var proc = server.underlying();
        assertThat(proc).isNotNull();

        server.stop();
    }
}
