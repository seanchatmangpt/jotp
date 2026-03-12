package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import io.github.seanchatmangpt.jotp.Proc;

/**
 * Return Address pattern: a message carries an explicit reply-to address.
 *
 * <p>Enterprise Integration Pattern: <em>Return Address</em> (EIP §6.4). In Akka, the implicit
 * {@code sender()} provides the return address. In JOTP, we embed the reply-to {@link Proc}
 * explicitly in the message record — making the routing explicit and type-safe.
 *
 * <p>Erlang analog: {@code {request, From, Payload}} where {@code From} is the caller's Pid,
 * allowing the receiver to send a reply via {@code From ! {reply, Result}}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * record Request(String what, Proc<?, Object> replyTo) {}
 * record Reply(String what) {}
 *
 * var client = new Proc<>(Reply.class.cast(null), (state, msg) -> switch (msg) {
 *     case Reply r -> r;
 *     default -> state;
 * });
 *
 * var server = new Proc<>(Void.class.cast(null), (state, msg) -> {
 *     if (msg instanceof Request r) {
 *         r.replyTo().tell(new Reply("RE: " + r.what()));
 *     }
 *     return state;
 * });
 *
 * server.tell(new Request("Hello", client));
 * }</pre>
 *
 * @param message the original message payload
 * @param replyTo the process to send the reply to
 * @param <M> message type
 * @param <R> reply message type
 */
public record ReturnAddress<M, R>(M message, Proc<?, ? super R> replyTo) {

    /**
     * Send a reply to the return address.
     *
     * <p>Corresponds to Erlang's {@code From ! Reply} pattern.
     *
     * @param reply the reply message
     */
    public void reply(R reply) {
        replyTo.tell(reply);
    }

    /**
     * Creates a return address wrapping a message with the given reply-to process.
     *
     * @param message the payload
     * @param replyTo the reply destination
     * @param <M> message type
     * @param <R> reply type
     * @return a new ReturnAddress
     */
    public static <M, R> ReturnAddress<M, R> of(M message, Proc<?, ? super R> replyTo) {
        return new ReturnAddress<>(message, replyTo);
    }
}
