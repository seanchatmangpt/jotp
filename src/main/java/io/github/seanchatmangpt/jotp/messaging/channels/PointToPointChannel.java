/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.function.BiFunction;

/**
 * Point-to-Point Channel - exactly one consumer receives each message.
 *
 * <p>This is the simplest messaging pattern where messages are delivered to a single consumer. It
 * provides a direct one-to-one communication channel between a producer and a consumer.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var receiver = PointToPointChannel.createReceiver(
 *     (state, msg) -> state.addMessage(msg),
 *     new ReceiverState());
 *
 * PointToPointChannel.send(receiver, Message.event("Hello", null));
 * }</pre>
 *
 * @param <M> the message type
 */
public final class PointToPointChannel<M> {

    private final Proc<Void, M> proc;

    /**
     * Creates a new point-to-point channel with the given consumer.
     *
     * @param consumer the consumer to receive messages (may not be null)
     */
    public PointToPointChannel(java.util.function.Consumer<M> consumer) {
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            consumer.accept(msg);
                            return state;
                        });
    }

    /**
     * Sends a message to this channel.
     *
     * @param message the message to send
     */
    public void send(M message) {
        proc.tell(message);
    }

    /**
     * Returns the current queue depth (number of pending messages).
     *
     * @return the queue depth
     */
    public int queueDepth() {
        return proc.mailboxSize();
    }

    /**
     * Stops this channel and releases all resources.
     *
     * @throws InterruptedException if interrupted while stopping
     */
    public void stop() throws InterruptedException {
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // Static factory API
    // -------------------------------------------------------------------------

    /**
     * Creates a new receiver {@link Proc} that processes each incoming {@link Message} using the
     * given handler function.
     *
     * @param handler a pure function {@code (S, Message) -> S} applied for each received message
     * @param initialState the initial state of the receiver process
     * @param <S> the state type
     * @return a running {@link Proc} that accepts {@link Message} instances
     */
    public static <S> Proc<S, Message<?>> createReceiver(
            BiFunction<S, Message<?>, S> handler, S initialState) {
        return new Proc<>(initialState, handler::apply);
    }

    /**
     * Sends a message to a receiver created by {@link #createReceiver}.
     *
     * @param receiver the receiver process
     * @param message the message to send
     * @param <S> the receiver's state type
     */
    public static <S> void send(Proc<S, Message<?>> receiver, Message<?> message) {
        receiver.tell(message);
    }

    /**
     * Creates a pipeline of processing stages. Each stage is an independent {@link Proc} that
     * applies the corresponding handler function to messages routed through the pipeline via
     * {@link #routeThrough}.
     *
     * @param initialState the shared initial state passed to every stage
     * @param handlers the ordered list of handler functions (one per stage)
     * @param <S> the shared state type
     * @return an array of {@link Proc} instances, one per stage, in order
     */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <S> Proc<S, Message<?>>[] createPipeline(
            S initialState, BiFunction<S, Message<?>, S>... handlers) {
        Proc<S, Message<?>>[] stages = new Proc[handlers.length];
        for (int i = 0; i < handlers.length; i++) {
            final BiFunction<S, Message<?>, S> handler = handlers[i];
            stages[i] = new Proc<>(initialState, handler::apply);
        }
        return stages;
    }

    /**
     * Routes a message through every stage of a pipeline created by {@link #createPipeline},
     * delivering the same message to each stage in order.
     *
     * @param pipeline the pipeline stages
     * @param message the message to route
     * @param <S> the shared state type
     */
    public static <S> void routeThrough(Proc<S, Message<?>>[] pipeline, Message<?> message) {
        for (Proc<S, Message<?>> stage : pipeline) {
            stage.tell(message);
        }
    }
}
