package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Function;

/**
 * Messaging Bridge pattern: connects two messaging systems by translating between their message
 * formats.
 *
 * <p>Enterprise Integration Pattern: <em>Messaging Bridge</em> (EIP §9.5). Acts as an adapter
 * between two heterogeneous channel types, translating messages from one format to another.
 *
 * <p>Erlang analog: a proxy process that receives messages from one protocol, translates them, and
 * forwards to a process speaking a different protocol.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * RabbitMQBridgeActor} bridges between RabbitMQ binary messages and Akka actor messages.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var bridge = MessagingBridge.create(
 *     byte[].class,              // source type
 *     String.class,              // target type
 *     bytes -> new String(bytes), // translator
 *     msg -> targetActor.tell(msg) // delivery
 * );
 *
 * bridge.forward(new byte[]{72, 101, 108, 108, 111}); // delivers "Hello"
 * }</pre>
 *
 * @param <A> source message type
 * @param <B> target message type
 */
public final class MessagingBridge<A, B> {

    private final Function<A, B> translator;
    private final Proc<Void, A> bridgeProc;

    private MessagingBridge(Function<A, B> translator, Proc<Void, A> bridgeProc) {
        this.translator = translator;
        this.bridgeProc = bridgeProc;
    }

    /**
     * Create a messaging bridge.
     *
     * @param sourceType source message class
     * @param targetType target message class
     * @param translator function converting source to target format
     * @param delivery consumer receiving translated messages
     * @param <A> source type
     * @param <B> target type
     * @return a new messaging bridge
     */
    public static <A, B> MessagingBridge<A, B> create(
            Class<A> sourceType,
            Class<B> targetType,
            Function<A, B> translator,
            java.util.function.Consumer<B> delivery) {
        var proc = new Proc<Void, A>(null, (state, msg) -> {
            B translated = translator.apply(msg);
            delivery.accept(translated);
            return state;
        });
        return new MessagingBridge<>(translator, proc);
    }

    /** Forward a source message through the bridge. */
    public void forward(A message) {
        bridgeProc.tell(message);
    }

    /** Stop the bridge. */
    public void stop() {
        bridgeProc.stop();
    }
}
