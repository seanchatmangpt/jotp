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
import java.util.Objects;
import java.util.function.Function;

/**
 * Messaging Mapper - transforms messages from one format to another and routes them downstream.
 *
 * <p>The mapper pattern provides type-safe message transformation between different message formats
 * or domains. When a downstream channel is configured, mapped messages are automatically forwarded.
 * An optional error channel captures mapping failures.
 *
 * @param <From> the source message type
 * @param <To> the target message type
 */
public class MessagingMapper<From, To> {

    private final Function<From, To> mapper;
    private final Channel<To> downstream;
    private final Channel<From> errorChannel;
    private final Proc<Void, From> proc;

    /**
     * Creates a new messaging mapper without a downstream channel.
     *
     * @param mapper the mapping function
     */
    public MessagingMapper(Function<From, To> mapper) {
        this(mapper, null, null);
    }

    /**
     * Creates a new messaging mapper with a downstream channel.
     *
     * @param mapper the mapping function
     * @param downstream the channel to forward mapped messages to
     */
    public MessagingMapper(Function<From, To> mapper, Channel<To> downstream) {
        this(mapper, downstream, null);
    }

    /**
     * Creates a new messaging mapper with downstream and error channels.
     *
     * @param mapper the mapping function
     * @param downstream the channel to forward mapped messages to
     * @param errorChannel the channel to route unmappable messages to
     */
    public MessagingMapper(
            Function<From, To> mapper, Channel<To> downstream, Channel<From> errorChannel) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.downstream = downstream;
        this.errorChannel = errorChannel;
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            try {
                                To mapped = this.mapper.apply(msg);
                                if (this.downstream != null) {
                                    this.downstream.send(mapped);
                                }
                            } catch (Exception e) {
                                if (this.errorChannel != null) {
                                    this.errorChannel.send(msg);
                                }
                            }
                            return state;
                        });
    }

    /**
     * Maps a message from the source type to the target type.
     *
     * @param from the source message
     * @return the mapped message
     */
    public To map(From from) {
        return mapper.apply(from);
    }

    /**
     * Sends a message through the mapper. The message is transformed and forwarded to the
     * downstream channel asynchronously. If the mapping fails and an error channel is configured,
     * the original message is routed there instead.
     *
     * @param message the message to map and forward
     */
    public void send(From message) {
        proc.tell(message);
    }

    /**
     * Creates a composed mapper that applies this mapper first, then the next.
     *
     * @param next the next mapper to apply
     * @param <Final> the final output type
     * @return a composed mapper
     */
    public <Final> MessagingMapper<From, Final> andThen(Function<To, Final> next) {
        return new MessagingMapper<>(from -> next.apply(mapper.apply(from)));
    }
}
