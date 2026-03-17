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

package io.github.seanchatmangpt.jotp.distributed;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Append-only distributed message log with replication and replay support.
 */
public sealed interface DistributedLog permits RocksDBLog, ReplicatedLog {

    /**
     * A serializable message wrapper for log entries.
     */
    record LogMessage(String id, Serializable content, long timestamp) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Append a message to the log with atomic sequence number assignment.
     */
    long append(LogMessage msg);

    /**
     * Retrieve a message by its sequence number.
     */
    Optional<LogMessage> get(long seq);

    /**
     * Retrieve all messages in a sequence range (inclusive on both ends).
     */
    List<LogMessage> getRange(long fromSeq, long toSeq);

    /**
     * Subscribe to new messages as they are appended.
     */
    void watch(Consumer<LogMessage> onMessage);

    /**
     * Returns the highest sequence number currently in the log.
     */
    long lastSequence();

    /**
     * Close the log and release resources.
     */
    void close() throws Exception;
}
