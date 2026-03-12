package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * Point-to-Point Channel (Vernon: "Point-to-Point Channel")
 *
 * <p>A messaging channel that ensures each message is received by exactly one recipient. Perfect
 * for one-to-one communication between processes where ordering and delivery guarantees matter.
 *
 * <p>JOTP Implementation: Leverages {@code ProcRef<State, Message>} as a stable, persistent message
 * mailbox. Each channel consumer is a lightweight Proc that handles incoming messages with pure
 * state transformation.
 */
public final class PointToPointChannel {

    private PointToPointChannel() {}

    /**
     * Creates a Point-to-Point receiver process with a message handler.
     *
     * @param handler Pure BiFunction: (state, message) -> newState
     * @param initialState Initial state for the receiver
     * @param <State> Application state type
     * @return Proc that can receive messages via tell()
     */
    public static <State> Proc<State, Message> createReceiver(
            BiFunction<State, Message, State> handler, State initialState) {

        return new Proc<>(initialState, handler);
    }

    /**
     * Sends a message to a Point-to-Point channel (receiver). Non-blocking; message is queued in
     * the receiver's mailbox.
     *
     * @param receiver The Proc to send to
     * @param message The message to send
     * @param <State> Receiver's state type
     */
    public static <State> void send(Proc<State, Message> receiver, Message message) {
        receiver.tell(message);
    }

    /**
     * Sends a message to a Point-to-Point channel via ProcRef (receiver). Non-blocking; message is
     * queued in the receiver's mailbox.
     *
     * @param receiver The ProcRef to send to
     * @param message The message to send
     * @param <State> Receiver's state type
     */
    public static <State> void send(ProcRef<State, Message> receiver, Message message) {
        receiver.tell(message);
    }

    /**
     * Sends a message and waits for a reply (blocking with timeout). Uses ask() mechanism for
     * request-reply.
     *
     * @param receiver The Proc to send to
     * @param message The message to send
     * @param timeoutMs Maximum time to wait (milliseconds)
     * @param <State> Receiver's state type
     * @return The updated state after processing
     * @throws TimeoutException if no reply received within timeoutMs
     */
    public static <State> State sendAndWait(
            Proc<State, Message> receiver, Message message, long timeoutMs)
            throws TimeoutException {

        try {
            return receiver.ask(message, java.time.Duration.ofMillis(timeoutMs)).get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("sendAndWait failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("sendAndWait interrupted", e);
        }
    }

    /**
     * Creates a pipeline of Point-to-Point channels, chaining one receiver to the next.
     *
     * @param handlers Array of BiFunction handlers
     * @param initialState Initial state for all stages
     * @param <State> State type
     * @return Array of Procs forming a pipeline
     */
    @SafeVarargs
    public static <State> Proc<State, Message>[] createPipeline(
            State initialState, BiFunction<State, Message, State>... handlers) {

        @SuppressWarnings("unchecked")
        Proc<State, Message>[] pipeline = new Proc[handlers.length];

        for (int i = 0; i < handlers.length; i++) {
            pipeline[i] = new Proc<>(initialState, handlers[i]);
        }

        return pipeline;
    }

    /**
     * Routes a message through a pipeline of Point-to-Point channels.
     *
     * @param pipeline Array of receivers from createPipeline()
     * @param message Message to route
     * @param <State> State type
     */
    public static <State> void routeThrough(Proc<State, Message>[] pipeline, Message message) {

        for (Proc<State, Message> stage : pipeline) {
            send(stage, message);
        }
    }
}
