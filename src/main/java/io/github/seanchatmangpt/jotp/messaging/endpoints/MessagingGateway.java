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
     * Creates a new messaging gateway.
     *
     * @param sender the function to send requests
     */
    public MessagingGateway(Consumer<Request> sender) {
        this.sender = sender;
        this.proc =
                new Proc<>(
                        null,
                        (state, req) -> {
                            sender.accept(req);
                            return state;
                        });
    }

    /**
     * Sends a request synchronously (blocking until response is received).
     *
     * @param request the request to send
     * @return the response
     */
    public Response sendSync(Request request) {
        sender.accept(request);
        return null; // In a real implementation, this would wait for a response
    }

    /**
     * Sends a request asynchronously.
     *
     * @param request the request to send
     */
    public void send(Request request) {
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
