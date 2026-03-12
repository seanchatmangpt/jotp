package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Point-to-Point Channel pattern: exactly one consumer receives each message.
 *
 * <p>Enterprise Integration Pattern: <em>Point-to-Point Channel</em> (EIP §6.1). Erlang analog:
 * direct process-to-process send via {@code Pid ! Message} — the message lands in exactly one
 * process mailbox.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, a
 * single ActorRef receives all messages in FIFO order.
 *
 * <p>In JOTP, this maps directly to a {@link Proc} — each Proc has a single mailbox backed by a
 * virtual thread, guaranteeing ordered, single-consumer delivery.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var channel = PointToPoint.<String>create(msg ->
 *     System.out.println("Received: " + msg));
 *
 * channel.send("Hello");
 * channel.send("World");
 * channel.stop();
 * }</pre>
 *
 * @param <T> message type
 */
public final class PointToPoint<T> {

    private final Proc<Void, T> proc;

    private PointToPoint(Proc<Void, T> proc) {
        this.proc = proc;
    }

    /**
     * Creates a point-to-point channel with a simple consumer.
     *
     * @param consumer the message handler
     * @param <T> message type
     * @return a new point-to-point channel
     */
    public static <T> PointToPoint<T> create(Consumer<T> consumer) {
        var proc =
                new Proc<Void, T>(
                        null,
                        (state, msg) -> {
                            consumer.accept(msg);
                            return state;
                        });
        return new PointToPoint<>(proc);
    }

    /**
     * Creates a point-to-point channel with stateful processing.
     *
     * @param initial initial state
     * @param handler state transition function
     * @param <S> state type
     * @param <T> message type
     * @return a new stateful point-to-point channel
     */
    public static <S, T> Proc<S, T> createStateful(S initial, BiFunction<S, T, S> handler) {
        return new Proc<>(initial, handler);
    }

    /** Send a message to the single consumer. */
    public void send(T message) {
        proc.tell(message);
    }

    /** Stop the channel. */
    public void stop() throws InterruptedException {
        proc.stop();
    }

    /** Returns the underlying Proc. */
    public Proc<Void, T> proc() {
        return proc;
    }
}
