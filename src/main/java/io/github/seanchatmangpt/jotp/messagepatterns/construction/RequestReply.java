package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Request-Reply pattern: a requester sends a message and awaits a response.
 *
 * <p>Enterprise Integration Pattern: <em>Request-Reply</em> (EIP §6.4). Erlang analog: {@code
 * gen_server:call/2} — the caller blocks until the server returns {@code {reply, Reply, NewState}}.
 *
 * <p>In Akka, the receiver uses implicit {@code sender()} to reply. In JOTP, we use {@link
 * Proc#ask(Object)} which returns a {@link CompletableFuture} of the process state after handling.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * sealed interface ServerMsg permits Request, Reply {}
 * record Request(String what) implements ServerMsg {}
 * record Reply(String what) implements ServerMsg {}
 *
 * var server = RequestReply.server(Reply.class, (state, msg) -> switch (msg) {
 *     case Request r -> new Reply("RE: " + r.what());
 *     default -> state;
 * });
 * Reply reply = server.request(new Request("Hello"), Duration.ofSeconds(1));
 * }</pre>
 *
 * @param <S> state / reply type
 * @param <M> message type
 */
public final class RequestReply<S, M> {

    private final Proc<S, M> proc;

    private RequestReply(Proc<S, M> proc) {
        this.proc = proc;
    }

    /**
     * Creates a request-reply server with the given initial state and handler.
     *
     * @param initial the initial state (also the reply type)
     * @param handler state transition function — the returned state is the reply value
     * @param <S> state/reply type
     * @param <M> message type
     * @return a new RequestReply server
     */
    public static <S, M> RequestReply<S, M> server(
            S initial, BiFunction<S, M, S> handler) {
        return new RequestReply<>(new Proc<>(initial, handler));
    }

    /**
     * Send a request and wait for the reply.
     *
     * <p>Corresponds to Akka's ask pattern and Erlang's {@code gen_server:call/3}.
     *
     * @param request the request message
     * @param timeout maximum time to wait
     * @return the reply (server state after handling)
     */
    public S request(M request, Duration timeout) {
        return proc.ask(request, timeout).join();
    }

    /**
     * Send a request asynchronously.
     *
     * @param request the request message
     * @return a future completing with the reply
     */
    public CompletableFuture<S> requestAsync(M request) {
        return proc.ask(request);
    }

    /** Fire-and-forget message send (no reply expected). */
    public void tell(M message) {
        proc.tell(message);
    }

    /** Stop the server process. */
    public void stop() {
        proc.stop();
    }

    /** Returns the underlying Proc. */
    public Proc<S, M> proc() {
        return proc;
    }
}
