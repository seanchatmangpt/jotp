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

import java.util.Collections;
import java.util.Map;

/**
 * A test message with a type name and map of fields.
 *
 * <p>Used by {@link Messaging#testMessage(String, Map)} and related factory methods to create
 * lightweight test data for messaging tests.
 */
public class TestMessage {

    /** The message type name. */
    public final String type;

    /** The message fields (unmodifiable). */
    public final Map<String, Object> fields;

    /**
     * Creates a new test message.
     *
     * @param type the message type name
     * @param fields the message fields
     */
    public TestMessage(String type, Map<String, Object> fields) {
        this.type = type;
        this.fields = Collections.unmodifiableMap(fields);
    }

    /**
     * Gets a field value by name.
     *
     * @param name the field name
     * @return the field value, or null if not present
     */
    public Object getField(String name) {
        return fields.get(name);
    }

    @Override
    public String toString() {
        return type + fields;
    }
}
