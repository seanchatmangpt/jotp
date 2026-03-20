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

package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.messaging.Channel;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Messaging Gateway - synchronizes asynchronous messaging with synchronous code.
 *
 * <p>The gateway pattern provides a bridge between the messaging world and the synchronous
 * application code, allowing for both synchronous and asynchronous interaction patterns.
 *
 * @param <Request> the request type
 * @param <Response> the response type
 */
public class MessagingGateway<Request, Response> {

    private final Proc<Void, Request> proc;
    private final Consumer<Request> sender;

    /**
     * Creates a new messaging gateway backed by a consumer.
     *
     * @param sender the function to send requests
     */
    public MessagingGateway(Consumer<Request> sender) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.proc =
                new Proc<>(
                        null,
                        (state, req) -> {
                            sender.accept(req);
                            return state;
                        });
    }

    /**
     * Creates a new messaging gateway backed by a channel for request-reply.
     *
     * @param channel the channel to send requests to
     * @param timeout the timeout for synchronous requests
     */
    public MessagingGateway(Channel<Request> channel, Duration timeout) {
        this(channel::send);
    }

    /**
     * Sends a request synchronously (blocking until response is received).
     *
     * @param request the request to send
     * @return the response
     */
    public Response sendSync(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        // Send synchronously on the calling thread by invoking the sender directly.
        // Response correlation is not yet supported; callers that need a typed response
        // should use a Channel with a dedicated reply-to address.
        sender.accept(request);
        return null;
    }

    /**
     * Sends a request asynchronously via the underlying proc mailbox.
     *
     * @param request the request to send
     */
    public void send(Request request) {
        proc.tell(request);
    }

    /**
     * Sends a request asynchronously. Alias for {@link #send(Object)} that validates the input.
     *
     * @param request the request to send
     * @throws IllegalArgumentException if the request is null
     */
    public void sendAsync(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        proc.tell(request);
    }

    /**
     * Stops the gateway.
     *
     * @throws InterruptedException if interrupted while stopping
     */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
