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

import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.function.Function;

/**
 * Normalizer - transforms different message formats into a canonical form.
 *
 * <p>The normalizer pattern handles messages that arrive in different formats and transforms them
 * into a common canonical format for consistent processing.
 *
 * <p>This class supports both generic normalization via {@link #normalize(Object)} and canonical
 * model operations via {@link #toCanonical(String)} and {@link #fromCanonical(Message, String)}.
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

    /**
     * Converts a raw string (JSON, XML, or plain text) into a canonical {@link Message}.
     *
     * @param raw the raw input string
     * @return a canonical event message wrapping the input
     */
    public Message<?> toCanonical(String raw) {
        String format = detectFormat(raw);
        return Message.event("CANONICAL_" + format, raw);
    }

    /**
     * Converts a canonical {@link Message} back into a target format string.
     *
     * @param message the canonical message
     * @param targetFormat the target format (e.g., "JSON", "XML", "TEXT")
     * @return a string representation in the requested format
     */
    public String fromCanonical(Message<?> message, String targetFormat) {
        Object payload = message.payload();
        String payloadStr = payload != null ? payload.toString() : "";
        return switch (targetFormat.toUpperCase()) {
            case "JSON" -> "{\"type\":\"" + message.type() + "\",\"payload\":\"" + payloadStr + "\"}";
            case "XML" -> "<message><type>" + message.type() + "</type><payload>" + payloadStr + "</payload></message>";
            default -> message.type() + ":" + payloadStr;
        };
    }

    private static String detectFormat(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "JSON";
        } else if (trimmed.startsWith("<")) {
            return "XML";
        } else {
            return "TEXT";
        }
    }
}
