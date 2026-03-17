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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Publish-Subscribe Channel - all subscribers receive every message.
 *
 * <p>This pattern implements a one-to-many messaging pattern where each message is delivered to all
 * registered subscribers. It's useful for broadcast scenarios and event notification systems.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var channel = new PublishSubscribeChannel<String>();
 * channel.subscribe(msg -> System.out.println("A: " + msg));
 * channel.subscribe(msg -> System.out.println("B: " + msg));
 *
 * channel.sendSync("Hello"); // Both subscribers receive this
 * channel.stop();
 * }</pre>
 *
 * @param <M> the message type
 */
public final class PublishSubscribeChannel<M> {

    private final List<Consumer<M>> subscribers = new CopyOnWriteArrayList<>();
    private final Proc<Void, M> proc;

    /** Creates a new publish-subscribe channel. */
    public PublishSubscribeChannel() {
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            var currentSubscribers = new ArrayList<>(subscribers);
                            for (var subscriber : currentSubscribers) {
                                try {
                                    subscriber.accept(msg);
                                } catch (Exception e) {
                                    // Crashing subscribers are removed (OTP fault isolation)
                                    subscribers.remove(subscriber);
                                }
                            }
                            return state;
                        });
    }

    /**
     * Sends a message to this channel asynchronously.
     *
     * @param message the message to send
     */
    public void send(M message) {
        proc.tell(message);
    }

    /**
     * Sends a message to this channel synchronously (blocking until sent).
     *
     * @param message the message to send
     */
    public void sendSync(M message) {
        // Send to each subscriber directly for synchronous delivery
        for (var subscriber : subscribers) {
            try {
                subscriber.accept(message);
            } catch (Exception e) {
                // Crashing subscribers are removed
                subscribers.remove(subscriber);
            }
        }
    }

    /**
     * Subscribes a consumer to receive messages from this channel.
     *
     * @param consumer the consumer to receive messages
     */
    public void subscribe(Consumer<M> consumer) {
        subscribers.add(consumer);
    }

    /**
     * Unsubscribes a consumer from this channel.
     *
     * @param consumer the consumer to unsubscribe
     */
    public void unsubscribe(Consumer<M> consumer) {
        subscribers.remove(consumer);
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
