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

package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.messaging.Messaging;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Detour - conditionally routes messages to a detour channel.
 *
 * <p>The detour pattern examines each message and routes it to either a detour channel (when the
 * predicate matches) or a primary channel (when the predicate doesn't match). This is useful for
 * handling special cases, errors, or high-priority messages.
 *
 * @param <M> the message type
 */
public class Detour<M> {

    private final Predicate<M> predicate;
    private final Messaging.MessageChannel<M> detourChannel;
    private final Messaging.MessageChannel<M> primaryChannel;
    private final AtomicLong detourCount = new AtomicLong(0);
    private final AtomicLong primaryCount = new AtomicLong(0);

    /**
     * Creates a new detour.
     *
     * @param predicate the predicate to determine if a message should be detoured
     * @param detourChannel the channel for detoured messages
     * @param primaryChannel the primary channel for non-detoured messages
     */
    public Detour(
            Predicate<M> predicate,
            Messaging.MessageChannel<M> detourChannel,
            Messaging.MessageChannel<M> primaryChannel) {
        this.predicate = predicate;
        this.detourChannel = detourChannel;
        this.primaryChannel = primaryChannel;
    }

    /**
     * Sends a message through the detour.
     *
     * @param message the message to send
     */
    public void send(M message) {
        if (predicate.test(message)) {
            detourCount.incrementAndGet();
            detourChannel.send(message);
        } else {
            primaryCount.incrementAndGet();
            primaryChannel.send(message);
        }
    }

    /**
     * Returns the number of messages that were detoured.
     *
     * @return the detour count
     */
    public long detourCount() {
        return detourCount.get();
    }

    /**
     * Returns the number of messages sent to the primary channel.
     *
     * @return the primary count
     */
    public long primaryCount() {
        return primaryCount.get();
    }
}
