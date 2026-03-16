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

package io.github.seanchatmangpt.jotp.messaging.transformation;

import java.util.function.Function;

/**
 * Normalizer - transforms different message formats into a canonical form.
 *
 * <p>The normalizer pattern handles messages that arrive in different formats and transforms them
 * into a common canonical format for consistent processing.
 *
 * @param <Input> the input message type (can be different formats)
 * @param <Output> the canonical output message type
 */
public class Normalizer<Input, Output> {

    private final Function<Input, Output> normalizer;

    /**
     * Creates a new normalizer.
     *
     * @param normalizer the normalization function
     */
    public Normalizer(Function<Input, Output> normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Normalizes an input message into the canonical format.
     *
     * @param input the input message
     * @return the normalized message
     */
    public Output normalize(Input input) {
        return normalizer.apply(input);
    }
}
