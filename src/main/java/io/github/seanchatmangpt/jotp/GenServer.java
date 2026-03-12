package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * GenServer: A standardized wrapper over {@link Proc} that provides typed Call/Cast message
 * semantics, mirroring OTP's {@code gen_server} behavior.
 *
 * <p>Joe Armstrong: "A generic server is a component that implements the standard protocol by which
 * other components in a system can call one another. The actual service provided by the server is
 * left to the implementation."
 *
 * <p><strong>Overview:</strong>
 *
 * <p>GenServer separates message handling into three categories:
 *
 * <ul>
 *   <li><strong>Call</strong> — synchronous request-reply (blocks caller until response)
 *   <li><strong>Cast</strong> — asynchronous one-way message (fire-and-forget)
 *   <li><strong>Info</strong> — out-of-band messages (e.g., timeouts, external events)
 * </ul>
 *
 * <p>The handler is a sealed interface with three callback methods:
 *
 * <ul>
 *   <li>{@code handleCall(request, state)} → {@code (nextState, response)}
 *   <li>{@code handleCast(request, state)} → {@code nextState}
 *   <li>{@code handleInfo(info, state)} → {@code nextState}
 * </ul>
 *
 * <p><strong>Message Flow:</strong>
 *
 * <p>{@code cast(msg)} fires immediately and returns a CompletableFuture that completes when the
 * message is enqueued (not when it is processed). {@code call(msg, timeout)} sends a request and
 * returns a CompletableFuture that completes with the response after the handler processes the
 * message — supporting timeouts via {@link CompletableFuture#orTimeout(long,
 * java.util.concurrent.TimeUnit)}.
 *
 * <p><strong>State Isolation:</strong>
 *
 * <p>Like OTP gen_server, GenServer holds state privately in the underlying Proc. State is never
 * returned by reference; only the handler's computed next state is persisted. This ensures
 * immutability and prevents accidental side-channel mutations.
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <p>GenServer is created and started immediately by the constructor. To shut it down gracefully,
 * call {@code stop()}, which drains remaining messages and waits for the underlying process to
 * finish.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.Get, CounterMsg.Reset {
 *   record Increment() implements CounterMsg {}
 *   record Get() implements CounterMsg {}
 *   record Reset() implements CounterMsg {}
 * }
 *
 * var handler = new GenServer.Handler<Integer, CounterMsg>() {
 *   @Override
 *   public GenServer.CallResult<Integer> handleCall(
 *       CounterMsg request, Integer state) {
 *     return switch (request) {
 *       case CounterMsg.Get _ ->
 *           new GenServer.CallResult<>(state, state); // (nextState, reply)
 *       case _ -> new GenServer.CallResult<>(state, -1); // unknown request
 *     };
 *   }
 *
 *   @Override
 *   public Integer handleCast(CounterMsg request, Integer state) {
 *     return switch (request) {
 *       case CounterMsg.Increment _ -> state + 1;
 *       case CounterMsg.Reset _ -> 0;
 *       case _ -> state;
 *     };
 *   }
 *
 *   @Override
 *   public Integer handleInfo(Object info, Integer state) {
 *     return state; // ignore info messages
 *   }
 * };
 *
 * var server = GenServer.start(0, handler);
 * server.cast(new CounterMsg.Increment());
 * server.cast(new CounterMsg.Increment());
 * var count = server.call(new CounterMsg.Get(), Duration.ofSeconds(1)).join(); // 2
 * }</pre>
 *
 * @param <S> server state type
 * @param <M> message type — use a sealed interface of records for pattern matching
 */
public final class GenServer<S, M> {

    /**
     * Sealed hierarchy of GenServer message types.
     *
     * <p>Each message carries either a request (Call, Cast) or out-of-band info (Info), and Call
     * messages hold a reply future for the request-reply pattern.
     */
    sealed interface GenServerMessage<M>
            permits GenServerMessage.Call, GenServerMessage.Cast, GenServerMessage.Info {
        /** Synchronous request-reply: {@code (request, replyFuture)} */
        record Call<M>(M request, CompletableFuture<Object> reply) implements GenServerMessage<M> {}

        /** Asynchronous one-way: {@code request} only */
        record Cast<M>(M request) implements GenServerMessage<M> {}

        /** Out-of-band message (timeout, external event, etc.) */
        record Info<M>(Object info) implements GenServerMessage<M> {}
    }

    /**
     * Handler interface for GenServer message processing.
     *
     * <p>Implement this interface to define callback logic for handling Call, Cast, and Info
     * messages. The handler returns new state and (for Call) a response.
     *
     * @param <S> process state type
     * @param <M> message type
     */
    public interface Handler<S, M> {
        /**
         * Handle a synchronous request-reply call.
         *
         * @param request the request sent by the caller
         * @param state the current server state
         * @return a {@link CallResult} containing the updated state and the reply
         */
        CallResult<S> handleCall(M request, S state);

        /**
         * Handle an asynchronous one-way cast message.
         *
         * @param request the message sent by the caller
         * @param state the current server state
         * @return the updated server state
         */
        S handleCast(M request, S state);

        /**
         * Handle an out-of-band info message (e.g., from a monitor or timer).
         *
         * @param info the info object
         * @param state the current server state
         * @return the updated server state
         */
        S handleInfo(Object info, S state);
    }

    /**
     * Pair of (next state, reply) returned by {@link Handler#handleCall}.
     *
     * @param <S> state type
     */
    public record CallResult<S>(S nextState, Object reply) {}

    private final Proc<S, GenServerMessage<M>> proc;

    /**
     * Create and start a GenServer with the given initial state and handler.
     *
     * <p>The handler is invoked sequentially for each message in FIFO order. It is responsible for
     * pattern-matching on the message type (Call, Cast, Info) and returning the next state.
     *
     * @param initial the initial state
     * @param handler the message handler
     */
    private GenServer(S initial, Handler<S, M> handler) {
        this.proc =
                new Proc<>(
                        initial,
                        (state, msg) -> {
                            return switch (msg) {
                                case GenServerMessage.Call<?> call -> {
                                    @SuppressWarnings("unchecked")
                                    var typedCall = (GenServerMessage.Call<M>) call;
                                    var result = handler.handleCall(typedCall.request(), state);
                                    typedCall.reply().complete(result.reply());
                                    yield result.nextState();
                                }
                                case GenServerMessage.Cast<?> cast -> {
                                    @SuppressWarnings("unchecked")
                                    var typedCast = (GenServerMessage.Cast<M>) cast;
                                    yield handler.handleCast(typedCast.request(), state);
                                }
                                case GenServerMessage.Info info ->
                                        handler.handleInfo(info.info(), state);
                            };
                        });
    }

    /**
     * Factory method to create and start a GenServer with the given initial state and handler.
     *
     * @param <S> state type
     * @param <M> message type
     * @param initial initial state
     * @param handler the message handler
     * @return a new GenServer instance
     */
    public static <S, M> GenServer<S, M> start(S initial, Handler<S, M> handler) {
        return new GenServer<>(initial, handler);
    }

    /**
     * Send an asynchronous one-way message (Cast).
     *
     * <p>Returns a CompletableFuture that completes when the message is enqueued, not when it is
     * processed. This is equivalent to OTP's {@code gen_server:cast/2}.
     *
     * @param msg the message to send
     * @return a CompletableFuture that completes when the message is enqueued
     */
    public CompletableFuture<Void> cast(M msg) {
        proc.tell(new GenServerMessage.Cast<>(msg));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Send a synchronous request-reply message (Call) with the given timeout.
     *
     * <p>Returns a CompletableFuture that completes with the response after the handler processes
     * the request. The future completes exceptionally with {@link
     * java.util.concurrent.TimeoutException} if the server does not respond within the timeout.
     * This is equivalent to OTP's {@code gen_server:call(Server, Request, Timeout)}.
     *
     * <p>OTP: "An unbounded call is a latent deadlock. Every call must have a timeout."
     *
     * @param <R> the response type
     * @param msg the request message
     * @param timeout the maximum time to wait for a response
     * @return a CompletableFuture that completes with the response
     */
    @SuppressWarnings("unchecked")
    public <R> CompletableFuture<R> call(M msg, Duration timeout) {
        CompletableFuture<Object> reply = new CompletableFuture<>();
        proc.tell(new GenServerMessage.Call<>(msg, reply));
        return reply.thenApply(r -> (R) r)
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Send an out-of-band message (Info).
     *
     * <p>Used for timeouts, external events, or other asynchronous notifications. The info message
     * is delivered to the process's mailbox and handled like any other message.
     *
     * @param info the info payload
     * @return a CompletableFuture that completes when the message is enqueued
     */
    public CompletableFuture<Void> info(Object info) {
        proc.tell(new GenServerMessage.Info(info));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Gracefully shut down the GenServer.
     *
     * <p>Drains remaining messages from the mailbox and waits for the underlying process to finish.
     * Does not fire crash callbacks. This is equivalent to OTP's {@code gen_server:stop/1}.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void stop() throws InterruptedException {
        proc.stop();
    }

    /**
     * Register a callback to be invoked when this GenServer terminates abnormally.
     *
     * @param cb the callback to register
     */
    public void addCrashCallback(Runnable cb) {
        proc.addCrashCallback(cb);
    }

    /**
     * Returns the underlying {@link Proc} for low-level operations (e.g., monitoring,
     * introspection).
     */
    public Proc<S, GenServerMessage<M>> underlying() {
        return proc;
    }
}
