package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
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
 * <p><strong>DTR Documentation:</strong> This test class provides living documentation of OTP
 * gen_server behavior. Run with DTR to see executable examples with actual output values.
 *
 * @see GenServer
 * @see GenServer.Handler
 * @see Proc
 */
@DisplayName("GenServer: OTP-style Request-Reply Server")
class GenServerTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

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
    void testCastFireAndForget()
            throws InterruptedException, java.util.concurrent.ExecutionException {
        ctx.sayNextSection("GenServer: Asynchronous Cast (Fire-and-Forget)");
        ctx.say(
                "cast() sends messages without waiting for a reply. The sender continues immediately; messages are processed asynchronously by the server.");
        ctx.sayCode(
                """
            var handler = new GenServer.Handler<Integer, CounterMsg>() {
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
            // result == 1 (0 + 1 + 1 - 1 = 1)
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Initial State",
                        "0",
                        "Operations",
                        "Increment, Increment, Decrement",
                        "Final State",
                        String.valueOf(result)));
        server.stop();
    }

    @Test
    @DisplayName("cast() maintains FIFO ordering")
    void testCastFifoOrdering() throws Exception {
        ctx.sayNextSection("GenServer: FIFO Ordering Guarantee");
        ctx.say(
                "GenServer processes cast messages in FIFO order. Messages sent earlier are always processed before messages sent later.");
        ctx.sayCode(
                """
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
            // result == ["first", "second", "third"]
            """,
                "java");

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
        var result = server.<List<String>>call(new StateMsg.GetState(), CALL_TIMEOUT).get();
        assertThat(result).containsExactly("first", "second", "third");

        ctx.sayKeyValue(
                Map.of("Messages Sent", "3", "Ordering", "FIFO", "Result", result.toString()));
        server.stop();
    }

    // ============================================================================
    // CALL (REQUEST-REPLY) TESTS
    // ============================================================================

    @Test
    @DisplayName("call() sends request and waits for reply")
    void testCallRequestReply() throws Exception {
        ctx.sayNextSection("GenServer: Synchronous Request-Reply (call)");
        ctx.say(
                "call() sends a synchronous request and blocks until the server replies. The caller receives the response value from handleCall.");
        ctx.sayCode(
                """
            var handler = new GenServer.Handler<Integer, CounterMsg>() {
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
            // reply == 42
            """,
                "java");

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

        ctx.sayKeyValue(Map.of("Initial State", "42", "Reply Received", String.valueOf(reply)));
        server.stop();
    }

    @Test
    @DisplayName("call() returns the reply from handler")
    void testCallReplyContent() throws Exception {
        ctx.sayNextSection("GenServer: Custom Reply Values");
        ctx.say(
                "handleCall returns a CallResult containing (nextState, reply). The reply value is sent back to the caller.");
        ctx.sayCode(
                """
            var handler = new GenServer.Handler<String, EchoMsg>() {
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
            // reply == "echo:hello"
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Input",
                        "hello",
                        "Reply",
                        String.valueOf(reply),
                        "State Unchanged",
                        "true",
                        "Type",
                        "String"));
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
        ctx.sayNextSection("GenServer: Call Timeout Protection");
        ctx.say(
                "call() accepts a timeout duration. If the server doesn't respond within the timeout, the call fails. This prevents indefinite blocking.");
        ctx.sayWarning(
                "Always use timeouts in production to prevent deadlocks. A server that crashes or hangs should not block callers forever.");
        ctx.sayCode(
                """
            var server = GenServer.start(0, slowHandler);
            var future = server.call(new CounterMsg.Get(), Duration.ofMillis(100));
            // Server takes 2000ms, timeout is 100ms -> call fails
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Server Response Time",
                        "2000ms",
                        "Client Timeout",
                        "100ms",
                        "Result",
                        "Timeout Exception"));
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

        var result = server.<List<Integer>>call(new StateMsg.GetState(), CALL_TIMEOUT).get();
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
        ctx.sayNextSection("GenServer: Info Messages (Out-of-Band Notifications)");
        ctx.say(
                "info() sends out-of-band messages that don't expect a reply. Useful for timers, monitors, and external notifications.");
        ctx.sayCode(
                """
            var infoCount = new AtomicInteger(0);

            var handler = new GenServer.Handler<Integer, CounterMsg>() {
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
            // infoCount.get() == 3
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Info Messages Sent",
                        "3",
                        "Info Messages Processed",
                        String.valueOf(infoCount.get())));
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
            CompletableFuture<Integer> f =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return server.<Integer>call(new CounterMsg.Get(), CALL_TIMEOUT)
                                            .get();
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
        ctx.sayNextSection("GenServer: Graceful Shutdown");
        ctx.say(
                "stop() gracefully shuts down the server, draining all remaining messages from the mailbox before terminating.");
        ctx.sayCode(
                """
            var processedCount = new AtomicInteger(0);

            var handler = new GenServer.Handler<Integer, CounterMsg>() {
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
            // processedCount.get() == 3
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Messages Queued",
                        "3",
                        "Messages Processed",
                        String.valueOf(processedCount.get())));
    }

    @Test
    @DisplayName("GenServer message types: call, cast, and info")
    void testAllMessageTypes() throws Exception {
        ctx.sayNextSection("GenServer: Complete Message Type Overview");
        ctx.say(
                "GenServer supports three message types: call (sync request-reply), cast (async fire-and-forget), and info (out-of-band notifications).");
        ctx.sayTable(
                new String[][] {
                    {"Message Type", "Method", "Blocking", "Reply", "Use Case"},
                    {"Call", "call()", "Yes (with timeout)", "Yes", "Request-response pattern"},
                    {"Cast", "cast()", "No", "No", "Fire-and-forget updates"},
                    {"Info", "info()", "No", "No", "Timers, monitors, external events"}
                });
        ctx.sayCode(
                """
            // Counter server demonstrating all three message types
            var handler = new GenServer.Handler<Integer, CounterMsg>() {
                @Override
                public GenServer.CallResult<Integer> handleCall(
                        CounterMsg request, Integer state) {
                    // Synchronous: returns (nextState, reply)
                    return switch (request) {
                        case CounterMsg.Get _ -> new GenServer.CallResult<>(state, state);
                        default -> new GenServer.CallResult<>(state, -1);
                    };
                }

                @Override
                public Integer handleCast(CounterMsg request, Integer state) {
                    // Asynchronous: returns nextState only
                    return switch (request) {
                        case CounterMsg.Increment _ -> state + 1;
                        case CounterMsg.Decrement _ -> state - 1;
                        case CounterMsg.Reset _ -> 0;
                        default -> state;
                    };
                }

                @Override
                public Integer handleInfo(Object info, Integer state) {
                    // Out-of-band: returns nextState only
                    // Log, monitor, or handle external notifications
                    return state;
                }
            };

            var server = GenServer.start(0, handler);

            // Cast: async fire-and-forget
            server.cast(new CounterMsg.Increment());
            server.cast(new CounterMsg.Increment());

            // Call: sync request-reply
            var count = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
            // count == 2

            // Info: out-of-band notification
            server.info("timer fired");
            """,
                "java");

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

        server.cast(new CounterMsg.Increment());
        server.cast(new CounterMsg.Increment());

        var count = server.call(new CounterMsg.Get(), CALL_TIMEOUT).get();
        assertThat(count).isEqualTo(2);

        server.info("timer fired");

        ctx.sayKeyValue(
                Map.of(
                        "Cast Messages",
                        "2 (Increment, Increment)",
                        "Call Messages",
                        "1 (Get)",
                        "Info Messages",
                        "1 (timer fired)",
                        "Final State",
                        String.valueOf(count)));
        server.stop();
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
