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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dead Letter Channel - captures undeliverable messages for inspection.
 *
 * <p>This pattern provides a fault tolerance mechanism by capturing messages that cannot be
 * delivered to their intended destination. Messages are stored with their associated reason for
 * failure and can be inspected and retried.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var dead = new DeadLetterChannel<String>();
 *
 * dead.send("msg-1");
 * dead.send("msg-2", "validation-failed");
 *
 * assertThat(dead.size()).isEqualTo(2);
 *
 * var letters = dead.drain();
 * assertThat(letters.get(0).reason()).isEqualTo("undeliverable");
 * }</pre>
 *
 * @param <M> the message type
 */
public final class DeadLetterChannel<M> {

    private final ConcurrentLinkedQueue<DeadLetter<M>> messages = new ConcurrentLinkedQueue<>();

    /** Creates a new dead letter channel. */
    public DeadLetterChannel() {}

    /**
     * Sends a message to this dead letter channel with the default reason.
     *
     * @param message the message to send
     */
    public void send(M message) {
        messages.offer(new DeadLetter<>(message, "undeliverable", Instant.now()));
    }

    /**
     * Sends a message to this dead letter channel with a specific reason.
     *
     * @param message the message to send
     * @param reason the reason for delivery failure
     */
    public void send(M message, String reason) {
        messages.offer(new DeadLetter<>(message, reason, Instant.now()));
    }

    /**
     * Returns the number of undelivered messages in this channel.
     *
     * @return the number of messages
     */
    public int size() {
        return messages.size();
    }

    /**
     * Drains all undelivered messages from this channel.
     *
     * @return a list of all dead letters
     */
    public List<DeadLetter<M>> drain() {
        var result = new ArrayList<DeadLetter<M>>();
        DeadLetter<M> msg;
        while ((msg = messages.poll()) != null) {
            result.add(msg);
        }
        return result;
    }

    /**
     * A dead letter containing the message and the reason for delivery failure.
     *
     * @param message the undelivered message
     * @param reason the reason for delivery failure
     * @param timestamp the timestamp when the message was added
     * @param <M> the message type
     */
    public record DeadLetter<M>(M message, String reason, Instant timestamp) {}
}
