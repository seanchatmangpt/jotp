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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Publish-Subscribe Channel - all subscribers receive every message.
 *
 * <p>This pattern implements a one-to-many messaging pattern where each message is delivered to all
 * registered subscribers. It's useful for broadcast scenarios and event notification systems.
 *
 * <p>Subscriber exceptions are isolated: a failing subscriber does not prevent other subscribers
 * from receiving the message.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var channel = PublishSubscribeChannel.create();
 * channel.subscribe(msg -> System.out.println("A: " + msg));
 * channel.subscribe(msg -> System.out.println("B: " + msg));
 *
 * channel.publish(message);        // async fan-out
 * int n = channel.publishSync(m); // sync fan-out, returns subscriber count
 * }</pre>
 *
 * @param <M> the message type
 */
public final class PublishSubscribeChannel<M> {

    private final CopyOnWriteArrayList<Consumer<M>> subscribers = new CopyOnWriteArrayList<>();
    private final Proc<Void, M> proc;

    /** Creates a new publish-subscribe channel. */
    public PublishSubscribeChannel() {
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            for (var subscriber : subscribers) {
                                try {
                                    subscriber.accept(msg);
                                } catch (Exception ignored) {
                                    // Crashing subscribers are isolated per OTP fault-tolerance
                                }
                            }
                            return state;
                        });
    }

    /**
     * Creates a new publish-subscribe channel using the static factory pattern.
     *
     * @param <M> the message type
     * @return a new channel instance
     */
    public static <M> PublishSubscribeChannel<M> create() {
        return new PublishSubscribeChannel<>();
    }

    /**
     * Subscribes a consumer to receive messages from this channel asynchronously.
     *
     * <p>The returned handle is the same consumer reference and can be passed to
     * {@link #unsubscribe} to remove the subscription.
     *
     * @param consumer the consumer to receive messages
     * @return the subscription handle (the consumer itself)
     */
    public Consumer<M> subscribe(Consumer<M> consumer) {
        subscribers.add(consumer);
        return consumer;
    }

    /**
     * Unsubscribes a previously subscribed consumer.
     *
     * @param handle the subscription handle returned by {@link #subscribe}
     */
    public void unsubscribe(Consumer<M> handle) {
        subscribers.remove(handle);
    }

    /**
     * Publishes a message to all subscribers asynchronously via the internal virtual-thread
     * process.
     *
     * @param message the message to publish
     */
    public void publish(M message) {
        proc.tell(message);
    }

    /**
     * Publishes a message to all currently subscribed consumers synchronously on the calling
     * thread. Exceptions thrown by individual subscribers are caught and ignored so that all
     * subscribers receive the message regardless of each other's behaviour.
     *
     * @param message the message to publish
     * @return the number of subscribers that were notified
     */
    public int publishSync(M message) {
        var snapshot = subscribers;
        int notified = 0;
        for (var subscriber : snapshot) {
            try {
                subscriber.accept(message);
                notified++;
            } catch (Exception ignored) {
                // Subscriber exception isolation
            }
        }
        return notified;
    }

    /**
     * Sends a message to this channel asynchronously. Alias for {@link #publish(Object)}.
     *
     * @param message the message to send
     */
    public void send(M message) {
        publish(message);
    }

    /**
     * Sends a message to this channel synchronously. Alias for {@link #publishSync(Object)}.
     *
     * @param message the message to send
     */
    public void sendSync(M message) {
        publishSync(message);
    }

    /**
     * Stops this channel and releases all resources.
     *
     * @throws InterruptedException if interrupted while stopping
     */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
