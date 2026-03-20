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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sealed message type hierarchy for type-safe messaging in JOTP.
 *
 * <p>This interface defines the base type for all messages in the messaging system. Messages are
 * immutable value types that carry data between processes.
 *
 * <h3>Message Types:</h3>
 *
 * <ul>
 *   <li>{@link CommandMsg} - Commands that request an action to be performed
 *   <li>{@link EventMsg} - Events that notify about something that happened
 *   <li>{@link QueryMsg} - Queries that request information
 *   <li>{@link DocumentMsg} - Document messages carrying binary content
 *   <li>{@link ReplyMsg} - Replies to queries or commands
 * </ul>
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * // Create a command message
 * var cmd = Message.command("UpdateProfile", "John", Map.of("age", 30));
 *
 * // Create an event message
 * var evt = Message.event("UserRegistered", "user@example.com");
 *
 * // Create a query message
 * var qry = Message.query("FindById", 42);
 *
 * // Pattern matching on message type
 * switch (msg) {
 *     case Message.CommandMsg cmd -> handleCommand(cmd.commandType(), cmd.payload());
 *     case Message.EventMsg evt -> handleEvent(evt.eventType(), evt.payload());
 *     case Message.QueryMsg qry -> handleQuery(qry.type(), qry.payload());
 *     case Message.DocumentMsg doc -> handleDoc(doc.documentType(), doc.payload());
 *     case Message.ReplyMsg<?> rep -> handleReply(rep.type(), rep.payload());
 * }
 * }</pre>
 *
 * @param <P> the payload type associated with the message
 */
public sealed interface Message<P>
        permits Message.CommandMsg, Message.EventMsg, Message.QueryMsg, Message.DocumentMsg,
                Message.ReplyMsg {

    /** Returns the unique message ID. */
    UUID id();

    /** Returns the message type/category. */
    String type();

    /** Returns the message payload. */
    P payload();

    /** Returns the timestamp when the message was created. */
    Instant timestamp();

    /** Returns additional metadata associated with the message. */
    Map<String, Object> metadata();

    /**
     * Returns the unique message ID. Alias for {@link #id()} for test convenience.
     *
     * @return the unique message UUID
     */
    default UUID messageId() {
        return id();
    }

    /**
     * Returns the creation time of this message in epoch milliseconds.
     *
     * @return epoch millis of {@link #timestamp()}
     */
    default long createdAt() {
        return timestamp().toEpochMilli();
    }

    /**
     * Command message - requests an action to be performed.
     *
     * <p>Commands are imperative and expect some action to be taken. They typically have a payload
     * that contains the data needed to execute the command.
     *
     * @param payload the command payload data
     */
    record CommandMsg(
            UUID id,
            String type,
            String payload,
            Object expectedReply,
            Instant timestamp,
            Map<String, Object> metadata)
            implements Message<String> {

        public CommandMsg {
            if (id == null) id = UUID.randomUUID();
            if (timestamp == null) timestamp = Instant.now();
            if (metadata == null) metadata = Map.of();
        }

        /**
         * Returns the command type. Convenience alias for {@link #type()}.
         *
         * @return the command type string
         */
        public String commandType() {
            return type();
        }

        /**
         * Creates a command message with the given type and payload.
         *
         * @param type the command type
         * @param payload the command payload
         * @param expectedReply the expected reply type (can be null)
         * @return a new command message
         */
        public static CommandMsg of(String type, String payload, Object expectedReply) {
            return new CommandMsg(null, type, payload, expectedReply, null, null);
        }
    }

    /**
     * Event message - notifies about something that happened.
     *
     * <p>Events are informational and describe a fact that has occurred. They are typically
     * broadcast to multiple subscribers.
     *
     * @param payload the event payload data
     */
    record EventMsg(
            UUID id,
            String type,
            Object payload,
            Instant timestamp,
            Map<String, Object> metadata)
            implements Message<Object> {

        public EventMsg {
            if (id == null) id = UUID.randomUUID();
            if (timestamp == null) timestamp = Instant.now();
            if (metadata == null) metadata = Map.of();
        }

        /**
         * Returns the event type. Convenience alias for {@link #type()}.
         *
         * @return the event type string
         */
        public String eventType() {
            return type();
        }

        /**
         * Creates an event message with the given type and payload.
         *
         * @param type the event type
         * @param payload the event payload
         * @return a new event message
         */
        public static EventMsg of(String type, Object payload) {
            return new EventMsg(null, type, payload, null, null);
        }
    }

    /**
     * Query message - requests information.
     *
     * <p>Queries are request-response patterns where the sender expects a reply.
     *
     * @param payload the query payload data
     */
    record QueryMsg(
            UUID id, String type, Object payload, Instant timestamp, Map<String, Object> metadata)
            implements Message<Object> {

        public QueryMsg {
            if (id == null) id = UUID.randomUUID();
            if (timestamp == null) timestamp = Instant.now();
            if (metadata == null) metadata = Map.of();
        }

        /**
         * Creates a query message with the given type and payload.
         *
         * @param type the query type
         * @param payload the query payload
         * @return a new query message
         */
        public static QueryMsg of(String type, Object payload) {
            return new QueryMsg(null, type, payload, null, null);
        }
    }

    /**
     * Document message - carries binary content with a named document type.
     *
     * <p>Used when messages carry structured binary payloads (PDFs, images, serialized data) rather
     * than text or structured objects.
     *
     * @param content the binary content of the document
     */
    record DocumentMsg(
            UUID id,
            String type,
            byte[] content,
            Instant timestamp,
            Map<String, Object> metadata)
            implements Message<byte[]> {

        public DocumentMsg {
            if (id == null) id = UUID.randomUUID();
            if (timestamp == null) timestamp = Instant.now();
            if (metadata == null) metadata = Map.of();
            if (content == null) content = new byte[0];
        }

        @Override
        public byte[] payload() {
            return content;
        }

        /**
         * Returns the document type. Convenience alias for {@link #type()}.
         *
         * @return the document type string
         */
        public String documentType() {
            return type();
        }

        /**
         * Creates a document message with the given type and binary content.
         *
         * @param type the document type
         * @param content the binary content
         * @return a new document message
         */
        public static DocumentMsg of(String type, byte[] content) {
            return new DocumentMsg(null, type, content, null, null);
        }
    }

    /**
     * Reply message - response to a query or command.
     *
     * @param payload the reply payload data
     * @param <P> the payload type
     */
    record ReplyMsg<P>(
            UUID id,
            UUID inReplyTo,
            String type,
            P payload,
            Instant timestamp,
            Map<String, Object> metadata)
            implements Message<P> {

        public ReplyMsg {
            if (id == null) id = UUID.randomUUID();
            if (timestamp == null) timestamp = Instant.now();
            if (metadata == null) metadata = Map.of();
        }

        /**
         * Creates a reply message.
         *
         * @param inReplyTo the original message ID being replied to
         * @param type the reply type
         * @param payload the reply payload
         * @return a new reply message
         */
        public static <P> ReplyMsg<P> of(UUID inReplyTo, String type, P payload) {
            return new ReplyMsg<>(null, inReplyTo, type, payload, null, null);
        }
    }

    // Factory methods for convenience

    /**
     * Creates a command message.
     *
     * @param type the command type
     * @param payload the command payload
     * @param expectedReply the expected reply type (can be null)
     * @return a new command message
     */
    static CommandMsg command(String type, String payload, Object expectedReply) {
        return CommandMsg.of(type, payload, expectedReply);
    }

    /**
     * Creates an event message.
     *
     * @param type the event type
     * @param payload the event payload (any type)
     * @return a new event message
     */
    static EventMsg event(String type, Object payload) {
        return EventMsg.of(type, payload);
    }

    /**
     * Creates a query message.
     *
     * @param type the query type
     * @param payload the query payload
     * @return a new query message
     */
    static QueryMsg query(String type, Object payload) {
        return QueryMsg.of(type, payload);
    }

    /**
     * Creates a document message.
     *
     * @param type the document type
     * @param content the binary content
     * @return a new document message
     */
    static DocumentMsg document(String type, byte[] content) {
        return DocumentMsg.of(type, content);
    }
}
