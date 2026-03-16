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
import java.util.function.Consumer;

/**
 * Point-to-Point Channel - exactly one consumer receives each message.
 *
 * <p>This is the simplest messaging pattern where messages are delivered to a single consumer. It
 * provides a direct one-to-one communication channel between a producer and a consumer.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var channel = new PointToPointChannel<String>(msg -> System.out.println(msg));
 * channel.send("Hello");
 * channel.stop();
 * }</pre>
 *
 * @param <M> the message type
 */
public final class PointToPointChannel<M> {

    private final Proc<Void, M> proc;

    /**
     * Creates a new point-to-point channel with the given consumer.
     *
     * @param consumer the consumer to receive messages
     */
    public PointToPointChannel(Consumer<M> consumer) {
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            if (consumer != null) {
                                consumer.accept(msg);
                            }
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
}
