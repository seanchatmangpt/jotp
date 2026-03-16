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

package io.github.seanchatmangpt.jotp.messaging;

import java.util.function.Consumer;

/**
 * A channel for passing messages between processes.
 *
 * <p>Channels are the primary abstraction for message passing in JOTP. They provide type-safe,
 * asynchronous communication between processes.
 *
 * <h3>Channel Types:</h3>
 *
 * <ul>
 *   <li>{@code PointToPointChannel} - Exactly one consumer receives each message
 *   <li>{@code PublishSubscribeChannel} - All subscribers receive every message
 *   <li>{@code DataTypeChannel} - Routes messages based on their type
 * </ul>
 *
 * @param <M> the message type
 */
public interface Channel<M> {

    /**
     * Send a message to this channel.
     *
     * @param message the message to send
     */
    void send(M message);

    /**
     * Send a message synchronously (blocking until sent).
     *
     * @param message the message to send
     */
    void sendSync(M message);

    /**
     * Subscribe a consumer to receive messages from this channel.
     *
     * @param consumer the consumer to receive messages
     */
    void subscribe(Consumer<M> consumer);

    /**
     * Unsubscribe a consumer from this channel.
     *
     * @param consumer the consumer to unsubscribe
     */
    void unsubscribe(Consumer<M> consumer);

    /**
     * Stop this channel and release all resources.
     *
     * @throws InterruptedException if interrupted while stopping
     */
    void stop() throws InterruptedException;
}
