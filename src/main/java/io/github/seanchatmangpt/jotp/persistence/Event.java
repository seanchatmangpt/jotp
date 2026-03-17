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

package io.github.seanchatmangpt.jotp.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * An immutable event record for the distributed event log.
 *
 * <p>Events represent significant occurrences in the JOTP system:
 * - Process lifecycle events (started, crashed, restarted)
 * - Message events (sent, received, delivered)
 * - Node events (joined, failed)
 * - Link/Monitor events
 * - Migration events
 *
 * <p>Events are identified by:
 * - {@code id}: UUID for exact deduplication
 * - {@code sequenceNumber}: monotonically increasing log position
 * - {@code timestampNanos}: nanosecond precision from System.nanoTime()
 */
public record Event(
    String id,                  // UUID for idempotency
    long timestampNanos,        // System.nanoTime() — immune to clock skew
    String processId,           // Source process identifier
    String nodeId,              // Node where event occurred
    EventType type,             // Event category (sealed enum)
    Object data,                // Event payload (sealed type hierarchy)
    long sequenceNumber         // Monotonic position in log
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Compact constructor for validation.
     */
    public Event {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(processId, "processId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (timestampNanos < 0) {
            throw new IllegalArgumentException("timestampNanos must be non-negative");
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative");
        }
    }
}
