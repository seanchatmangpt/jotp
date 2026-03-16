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

import java.util.function.Function;

/**
 * Messaging Mapper - transforms messages from one format to another.
 *
 * <p>The mapper pattern provides type-safe message transformation between different message formats
 * or domains.
 *
 * @param <From> the source message type
 * @param <To> the target message type
 */
public class MessagingMapper<From, To> {

    private final Function<From, To> mapper;

    /**
     * Creates a new messaging mapper.
     *
     * @param mapper the mapping function
     */
    public MessagingMapper(Function<From, To> mapper) {
        this.mapper = mapper;
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
